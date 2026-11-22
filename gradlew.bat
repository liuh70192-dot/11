@echo off
setlocal
set GRADLE_VERSION=8.11.1
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set INSTALL_DIR=%GRADLE_USER_HOME%\turrit-bootstrap\gradle-%GRADLE_VERSION%
if exist "%INSTALL_DIR%\bin\gradle.bat" (
  call "%INSTALL_DIR%\bin\gradle.bat" %*
  exit /b %ERRORLEVEL%
)
echo Gradle %GRADLE_VERSION% is not installed in the bootstrap cache.
echo Run this project on a machine with network access, or use GitHub Actions to build it.
exit /b 1
