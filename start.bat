@echo off
setlocal EnableExtensions

set "BASE=%~dp0"
set "ZLM_EXE=%BASE%zlm\MediaServer.exe"
set "ZLM_DIR=%BASE%zlm"
set "VIDEO_EXE=%BASE%video\video.exe"
set "VIDEO_DIR=%BASE%video"
set "BOOTSTRAP_PS1=%BASE%start.ps1"

if not exist "%ZLM_EXE%" (
    echo [ERROR] Not found: "%ZLM_EXE%"
    pause
    exit /b 1
)

if not exist "%VIDEO_EXE%" (
    echo [ERROR] Not found: "%VIDEO_EXE%"
    pause
    exit /b 1
)

if not exist "%BOOTSTRAP_PS1%" (
    echo [ERROR] Not found: "%BOOTSTRAP_PS1%"
    pause
    exit /b 1
)

echo [INFO] Base dir: "%BASE%"

powershell -NoProfile -ExecutionPolicy Bypass -File "%BOOTSTRAP_PS1%" ^
  -ZlmExe "%ZLM_EXE%" ^
  -ZlmDir "%ZLM_DIR%" ^
  -VideoExe "%VIDEO_EXE%" ^
  -VideoDir "%VIDEO_DIR%"

if errorlevel 1 (
    echo [ERROR] Startup failed.
    pause
    exit /b 1
)

exit /b 0
