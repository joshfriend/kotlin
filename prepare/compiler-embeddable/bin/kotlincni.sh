#!/usr/bin/env bash

KOTLINC_BINARY_NAME=kotlincni

# Resolve ${BASH_SOURCE[0]} through any chain of symlinks, so that a symlink
# on PATH (e.g. /usr/local/bin/kotlincni.sh) still resolves to the real
# distribution layout. Mirrors findKotlinHome() from compiler/cli/bin/kotlinc.
findKotlincHome() {
    local source="${BASH_SOURCE[0]}"
    while [ -h "$source" ]; do
        local linked
        linked="$(readlink "$source")"
        local dir
        dir="$(cd -P "$(dirname "$source")" && cd -P "$(dirname "$linked")" && pwd)"
        source="$dir/$(basename "$linked")"
    done
    (cd -P "$(dirname "$source")/.." && pwd)
}

KOTLINC_HOME_DIR="$(findKotlincHome)"
KOTLINC_BINARY_DIR="${KOTLINC_HOME_DIR}/bin"
KOTLINC_RESOURCES_DIR="${KOTLINC_HOME_DIR}/resources"

if [ -z "$JAVA_HOME" ]; then
  echo "error: JAVA_HOME is not set; ${KOTLINC_BINARY_NAME} requires a JDK for java.home" >&2
  exit 1
fi

exec "${KOTLINC_BINARY_DIR}/${KOTLINC_BINARY_NAME}" \
  -Djava.home="${JAVA_HOME}" \
  -Dkotlin.home="${KOTLINC_HOME_DIR}/" \
  -Xintellij-plugin-root="${KOTLINC_RESOURCES_DIR}/" \
  "$@"
