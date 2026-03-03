@echo off
chcp 936 >nul
setlocal EnableExtensions

REM 当前脚本目录（带结尾反斜杠，支持中文/空格路径）
set "BASE=%~dp0"

REM 程序路径
set "ZLM_EXE=%BASE%zlm\MediaServer.exe"
set "ZLM_DIR=%BASE%zlm"
set "VIDEO_EXE=%BASE%video\video.exe"
set "VIDEO_DIR=%BASE%video"

REM 检查文件是否存在
if not exist "%ZLM_EXE%" (
    echo [ERROR] 未找到: "%ZLM_EXE%"
    pause
    exit /b 1
)

if not exist "%VIDEO_EXE%" (
    echo [ERROR] 未找到: "%VIDEO_EXE%"
    pause
    exit /b 1
)

echo [INFO] 当前目录: "%BASE%"

REM 通过环境变量传递给 PowerShell（避免中文路径/引号问题）
set "ZLM_EXE_PS=%ZLM_EXE%"
set "ZLM_DIR_PS=%ZLM_DIR%"
set "VIDEO_EXE_PS=%VIDEO_EXE%"
set "VIDEO_DIR_PS=%VIDEO_DIR%"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "& {" ^
  "  $ErrorActionPreference='Stop';" ^
  "  function Get-NormalizedPath([string]$p) {" ^
  "    try { return (Resolve-Path -LiteralPath $p).Path } catch { return $p }" ^
  "  }" ^
  "  function Is-RunningByPath([string]$target) {" ^
  "    $t = Get-NormalizedPath $target;" ^
  "    $procs = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath };" ^
  "    foreach($p in $procs) {" ^
  "      if([string]::Equals($p.ExecutablePath, $t, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }" ^
  "    }" ^
  "    return $false" ^
  "  }" ^
  "" ^
  "  $zlmExe = $env:ZLM_EXE_PS;" ^
  "  $zlmDir = $env:ZLM_DIR_PS;" ^
  "  $videoExe = $env:VIDEO_EXE_PS;" ^
  "  $videoDir = $env:VIDEO_DIR_PS;" ^
  "" ^
  "  if (Is-RunningByPath $zlmExe) {" ^
  "    Write-Host '[SKIP] ZLM 已在运行（同目录实例），跳过启动';" ^
  "  } else {" ^
  "    Write-Host '[START] 启动 ZLM（隐藏窗口）...';" ^
  "    Start-Process -FilePath $zlmExe -WorkingDirectory $zlmDir -WindowStyle Hidden | Out-Null;" ^
  "    Start-Sleep -Milliseconds 300;" ^
  "  }" ^
  "" ^
  "  if (Is-RunningByPath $videoExe) {" ^
  "    Write-Host '[SKIP] video.exe 已在运行（同目录实例），跳过启动';" ^
  "  } else {" ^
  "    Write-Host '[START] 启动 video.exe ...';" ^
  "    Start-Process -FilePath $videoExe -WorkingDirectory $videoDir | Out-Null;" ^
  "    Start-Sleep -Milliseconds 300;" ^
  "  }" ^
  "" ^
  "  Write-Host '[OK] 启动脚本执行完成';" ^
  "}"

if errorlevel 1 (
    echo [ERROR] 启动过程中出现错误
    pause
    exit /b 1
)

exit /b 0