@echo off
chcp 936 >nul
setlocal EnableExtensions

REM 当前脚本目录（带结尾反斜杠）
set "BASE=%~dp0"

REM 程序路径（仅停止该目录下的目标程序）
set "ZLM_EXE=%BASE%zlm\MediaServer.exe"
set "VIDEO_EXE=%BASE%video\video.exe"

echo [INFO] 当前目录: "%BASE%"
echo [INFO] 停止同目录下的 MediaServer.exe 和 video.exe ...

set "ZLM_EXE_PS=%ZLM_EXE%"
set "VIDEO_EXE_PS=%VIDEO_EXE%"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "& {" ^
  "  $targets = @();" ^
  "  foreach($p in @($env:ZLM_EXE_PS,$env:VIDEO_EXE_PS)) {" ^
  "    try { $targets += (Resolve-Path -LiteralPath $p).Path } catch { $targets += $p }" ^
  "  }" ^
  "  $killed = 0;" ^
  "  Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and ($targets -contains $_.ExecutablePath) } | ForEach-Object {" ^
  "    try {" ^
  "      Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop;" ^
  "      Write-Host ('[STOPPED] PID=' + $_.ProcessId + ' Name=' + $_.Name);" ^
  "      $script:killed++;" ^
  "    } catch {" ^
  "      Write-Host ('[FAILED] PID=' + $_.ProcessId + ' Name=' + $_.Name + ' ' + $_.Exception.Message);" ^
  "    }" ^
  "  };" ^
  "  if($killed -eq 0){ Write-Host '[INFO] 未找到需要停止的目标进程（或已停止）' }" ^
  "  Write-Host '[OK] 停止脚本执行完成'" ^
  "}"

exit /b 0