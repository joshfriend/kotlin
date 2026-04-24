@echo off

setlocal
set _BIN_DIR=%~dp0
set _KOTLIN_HOME=%_BIN_DIR%..
set _RESOURCES_DIR=%_KOTLIN_HOME%\resources

if "%JAVA_HOME%"=="" (
  echo error: JAVA_HOME is not set; kotlincni requires a JDK for java.home 1>&2
  exit /b 1
)

"%_BIN_DIR%kotlincni.exe" ^
  "-Djava.home=%JAVA_HOME%" ^
  "-Dkotlin.home=%_KOTLIN_HOME%\" ^
  "-Xintellij-plugin-root=%_RESOURCES_DIR%\" ^
  %*
