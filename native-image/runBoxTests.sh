#!/bin/bash

NATIVE_IMAGE_BIN="./dist/kotlincni/bin/kotlincni.sh"
DEFAULT_KOTLINC="dist/kotlinc/bin/kotlinc"
TESTS_DIR="compiler/testData/codegen/box"
NATIVE_IMAGE_ARGS=""
STOP_ON_FAILURE=false

for arg in "$@"; do
  case $arg in
    --nativeImage=*)
      NATIVE_IMAGE_BIN="${arg#*=}"
      ;;
    --defaultKotlinc=*)
      DEFAULT_KOTLINC="${arg#*=}"
      ;;
    --testsDir=*)
      TESTS_DIR="${arg#*=}"
      ;;
    --nativeImageArgs=*)
      NATIVE_IMAGE_ARGS="${arg#*=}"
      ;;
    --stopOnFailure)
      STOP_ON_FAILURE=true
      ;;
    *)
      echo "Unknown argument: $arg"
      echo "Usage: $0 [--nativeImage=PATH] [--defaultKotlinc=PATH] [--testsDir=PATH] [--nativeImageArgs=ARGS] [--stopOnFailure]"
      exit 1
      ;;
  esac
done

TMP_DIR=$(mktemp -d)
COMPILER_LOG=$(mktemp)
OUT_DIR_DEFAULT=$(mktemp -d)
OUT_DIR_NI=$(mktemp -d)
trap 'rm -rf $TMP_DIR $COMPILER_LOG $OUT_DIR_DEFAULT $OUT_DIR_NI' EXIT

KOTLIN_TEST_JAR="dist/kotlinc/lib/kotlin-test.jar"

PASSED=0
FAILED=0
SKIPPED=0
SKIPPED_DEFAULT_COMPILE=0
SKIPPED_DEFAULT_RUNTIME=0

for file in $(find $TESTS_DIR -name "*.kt"); do
  # --- Skip non-JVM tests ---
  if grep -qE '// TARGET_BACKEND: (JS|JS_IR|JS_IR_ES6|WASM|NATIVE)$' "$file"; then
    continue
  fi
  if grep -q '// DONT_TARGET_EXACT_BACKEND: JVM_IR$' "$file"; then
    continue
  fi

  # --- Skip tests requiring expect/actual (multiplatform) ---
  if grep -q "^// LANGUAGE:.*+MultiPlatformProjects" "$file"; then
    continue
  fi

  # --- Skip tests that import helpers (test infra dependency not available) ---
  if grep -q '^import helpers\.' "$file" || grep -q '^import helpers\.\*' "$file"; then
    continue
  fi

  # --- Skip multi-file tests (// FILE: markers) ---
  if grep -q '^// FILE:' "$file"; then
    continue
  fi

  echo "--- Running $file ---"

  # --- Parse LANGUAGE directives into -XXLanguage: flags ---
  LANGUAGE_FLAGS=""
  LANGUAGE_LINE=$(grep '^// LANGUAGE:' "$file" | head -1)
  if [ -n "$LANGUAGE_LINE" ]; then
    # Extract +Feature and -Feature tokens
    LANGUAGE_FLAGS=$(echo "$LANGUAGE_LINE" | sed 's|// LANGUAGE:||' | tr ',' '\n' | sed 's/^ *//;s/ *$//' | while read feature; do
      if [ -n "$feature" ]; then
        echo "-XXLanguage:$feature"
      fi
    done | tr '\n' ' ')
  fi

  # --- Build extra classpath for compilation ---
  EXTRA_CP=""
  if grep -q 'import kotlin\.test' "$file"; then
    EXTRA_CP="-cp $KOTLIN_TEST_JAR"
  fi

  # --- Single-file test ---
  TMP_FILE="$TMP_DIR/blackBox.kt"
  cat "$file" > "$TMP_FILE"
  printf '\n fun main() { println(box()) } \n' >> "$TMP_FILE"

  # --- Detect package and class name to build qualified main class ---
  BOX_SOURCE="$TMP_FILE"

  PACKAGE=$(grep '^package ' "$BOX_SOURCE" | head -1 | sed 's/package //;s/[[:space:]]//g')
  # Class name is derived from the source filename: foo.kt -> FooKt, 1.kt -> _1Kt
  BOX_BASENAME=$(basename "$BOX_SOURCE" .kt)
  FIRST_CHAR="${BOX_BASENAME:0:1}"
  if echo "$FIRST_CHAR" | grep -q '[0-9]'; then
    CLASS_NAME="_${BOX_BASENAME}Kt"
  else
    CLASS_NAME="$(echo "$FIRST_CHAR" | tr '[:lower:]' '[:upper:]')${BOX_BASENAME:1}Kt"
  fi
  if [ -n "$PACKAGE" ]; then
    MAIN_CLASS="${PACKAGE}.${CLASS_NAME}"
  else
    MAIN_CLASS="$CLASS_NAME"
  fi

  # --- Default kotlinc pass (oracle) ---
  $DEFAULT_KOTLINC $EXTRA_CP $LANGUAGE_FLAGS -d "$OUT_DIR_DEFAULT" "$TMP_FILE" &> $COMPILER_LOG
  if [ $? -ne 0 ]; then
    echo "SKIPPED: $file (default kotlinc compilation failed)"
    cat $COMPILER_LOG
    SKIPPED=$((SKIPPED + 1))
    SKIPPED_DEFAULT_COMPILE=$((SKIPPED_DEFAULT_COMPILE + 1))
    rm -rf "${OUT_DIR_DEFAULT:?}"/* "${OUT_DIR_NI:?}"/*
    continue
  fi

  DEFAULT_RESULT=$(java -cp "$OUT_DIR_DEFAULT:dist/kotlinc/lib/*" "$MAIN_CLASS" 2>&1)
  if [ "$DEFAULT_RESULT" != "OK" ]; then
    echo "SKIPPED: $file (default kotlinc runtime != 'OK', got '$DEFAULT_RESULT')"
    SKIPPED=$((SKIPPED + 1))
    SKIPPED_DEFAULT_RUNTIME=$((SKIPPED_DEFAULT_RUNTIME + 1))
    rm -rf "${OUT_DIR_DEFAULT:?}"/* "${OUT_DIR_NI:?}"/*
    continue
  fi

  # --- NI kotlinc pass (compared against oracle) ---
  $NATIVE_IMAGE_BIN $NATIVE_IMAGE_ARGS $EXTRA_CP $LANGUAGE_FLAGS -d "$OUT_DIR_NI" "$TMP_FILE" &> $COMPILER_LOG
  if [ $? -ne 0 ]; then
    echo "FAILED: $file (NI compilation failed, default compiled OK)"
    cat $COMPILER_LOG
    FAILED=$((FAILED + 1))
    if [ "$STOP_ON_FAILURE" = true ]; then break; fi
    rm -rf "${OUT_DIR_DEFAULT:?}"/* "${OUT_DIR_NI:?}"/*
    continue
  fi

  NI_RESULT=$(java -cp "$OUT_DIR_NI:dist/kotlinc/lib/*" "$MAIN_CLASS" 2>&1)
  if [ "$NI_RESULT" = "$DEFAULT_RESULT" ]; then
    echo "PASSED: $file"
    PASSED=$((PASSED + 1))
  else
    echo "FAILED: $file (NI runtime differs, expected '$DEFAULT_RESULT', got '$NI_RESULT')"
    FAILED=$((FAILED + 1))
    if [ "$STOP_ON_FAILURE" = true ]; then break; fi
  fi

  rm -rf "${OUT_DIR_DEFAULT:?}"/* "${OUT_DIR_NI:?}"/*
done

echo ""
echo "=== Summary: $PASSED passed, $FAILED failed, $SKIPPED skipped ==="
echo "    (skipped breakdown: $SKIPPED_DEFAULT_COMPILE default compile, $SKIPPED_DEFAULT_RUNTIME default runtime != OK)"
if [ $FAILED -gt 0 ] || [ $SKIPPED -gt 0 ]; then
  exit 1
fi
