# PowerShell Script to Build and Package the Project with jpackage (Java 25)
# 生成带精简 JDK 运行时的自包含 Windows 应用，用户无需安装 Java

param(
    [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot"
)

$ErrorActionPreference = "Stop"

# ── 验证 JDK 路径 ──────────────────────────────────────────────────────
if (-not (Test-Path "$JavaHome\bin\java.exe")) {
    Write-Host "ERROR: 找不到 JDK，请确认路径: $JavaHome" -ForegroundColor Red
    exit 1
}
Write-Host "Using JDK: $JavaHome" -ForegroundColor Cyan

$JDEPS    = "$JavaHome\bin\jdeps.exe"
$JLINK    = "$JavaHome\bin\jlink.exe"
$JPACKAGE = "$JavaHome\bin\jpackage.exe"

# ── Step 1: 构建前端 ──────────────────────────────────────────────────
Write-Host "`n[1/5] Building frontend..." -ForegroundColor Yellow
Push-Location "frontend"
npm install
npm run build
Pop-Location

# ── Step 2: 构建后端 ──────────────────────────────────────────────────
Write-Host "`n[2/5] Building backend..." -ForegroundColor Yellow
$env:JAVA_HOME = $JavaHome
./mvnw.cmd clean package -DskipTests

# 查找生成的 JAR
$jarFile = Get-ChildItem -Path "target" -Filter "video-*.jar" |
           Where-Object { $_.Name -notlike "*-plain*" -and $_.Name -notlike "*.original" } |
           Select-Object -First 1
if (-not $jarFile) {
    Write-Host "ERROR: Maven 构建失败，未找到 JAR 文件" -ForegroundColor Red
    exit 1
}
Write-Host "Found JAR: $($jarFile.FullName)" -ForegroundColor Green

# ── Step 3: 用 jdeps 分析所需模块 ─────────────────────────────────────
Write-Host "`n[3/5] Analyzing required Java modules with jdeps..." -ForegroundColor Yellow

# jdeps 分析 Spring Boot fat-jar（忽略缺失依赖的警告）
$jdepsOutput = & $JDEPS `
    --ignore-missing-deps `
    --print-module-deps `
    --multi-release 25 `
    $jarFile.FullName 2>&1

# 提取模块列表（jdeps 输出的最后一行是逗号分隔的模块名）
$detectedModules = ($jdepsOutput | Where-Object { $_ -match "^[a-z]" } | Select-Object -Last 1).Trim()

if (-not $detectedModules) {
    Write-Host "WARNING: jdeps 未检测到模块，使用默认模块列表" -ForegroundColor Yellow
    $detectedModules = "java.base,java.logging,java.sql,java.naming,java.management,java.instrument,java.desktop,java.xml"
}

# 补充常用模块，确保 Spring Boot 运行时完整
# jdeps 无法检测通过反射加载的模块，需要手动补充
$extraModules = @(
    "java.desktop",        # java.beans（Spring 核心依赖，必需）
    "java.instrument",     # Java Agent / Spring instrumentation
    "java.management",     # JMX（Spring Actuator）
    "java.naming",         # JNDI
    "java.net.http",       # HTTP Client
    "java.compiler",       # javax.annotation.processing
    "java.prefs",          # java.util.prefs
    "java.rmi",            # RMI（JMX 远程）
    "java.security.jgss",  # Kerberos / GSSAPI
    "java.sql.rowset",     # SQL RowSet
    "java.datatransfer",   # java.awt.datatransfer
    "jdk.crypto.ec",       # HTTPS / TLS (EC)
    "jdk.crypto.cryptoki", # PKCS#11
    "jdk.unsupported",     # sun.misc.Unsafe（Netty 等需要）
    "jdk.naming.dns",      # DNS 解析
    "jdk.management",      # JMX 扩展
    "jdk.httpserver",      # 内嵌 HTTP 服务器
    "jdk.jfr"              # Java Flight Recorder
)

# 合并去重
$allModulesSet = [System.Collections.Generic.HashSet[string]]::new()
foreach ($m in $detectedModules.Split(',')) { [void]$allModulesSet.Add($m.Trim()) }
foreach ($m in $extraModules) { [void]$allModulesSet.Add($m) }
$moduleList = ($allModulesSet | Sort-Object) -join ","

Write-Host "Modules: $moduleList" -ForegroundColor Cyan

# ── Step 4: 用 jlink 生成精简运行时 ──────────────────────────────────
Write-Host "`n[4/5] Creating custom JDK runtime with jlink..." -ForegroundColor Yellow
$runtimeDir = "target\java-runtime"
if (Test-Path $runtimeDir) {
    Remove-Item -Recurse -Force $runtimeDir
}

& $JLINK `
    --module-path "$JavaHome\jmods" `
    --add-modules $moduleList `
    --output $runtimeDir `
    --strip-debug `
    --compress zip-6 `
    --no-header-files `
    --no-man-pages

if (-not (Test-Path "$runtimeDir\bin\java.exe")) {
    Write-Host "ERROR: jlink 生成运行时失败" -ForegroundColor Red
    exit 1
}
Write-Host "Custom runtime created at: $runtimeDir" -ForegroundColor Green

# ── Step 5: 用 jpackage 打包 ─────────────────────────────────────────
Write-Host "`n[5/5] Packaging with jpackage..." -ForegroundColor Yellow

# 创建staging目录，只放入需要的JAR
$stagingDir = "target\staging"
if (Test-Path $stagingDir) {
    Remove-Item -Recurse -Force $stagingDir
}
New-Item -ItemType Directory -Path $stagingDir | Out-Null
Copy-Item -Path $jarFile.FullName -Destination "$stagingDir\video.jar"

$outputDir = "dist"
if (Test-Path $outputDir) {
    Remove-Item -Recurse -Force $outputDir
}

& $JPACKAGE `
    --type app-image `
    --name video `
    --input $stagingDir `
    --main-jar "video.jar" `
    --main-class org.springframework.boot.loader.launch.JarLauncher `
    --runtime-image $runtimeDir `
    --dest $outputDir `
    --app-version "1.0.0" `
    --vendor "OwnDing" `
    --java-options "-Xms256m" `
    --java-options "-Xmx1024m"

if (-not (Test-Path "$outputDir\video")) {
    Write-Host "ERROR: jpackage 打包失败" -ForegroundColor Red
    exit 1
}

# 创建便捷启动脚本
$runScript = @"
@echo off
cd /d "%~dp0"
video.exe
"@
Set-Content -Path "$outputDir\video\run.bat" -Value $runScript

Write-Host "`nPackaging complete!" -ForegroundColor Green
Write-Host "Output directory: $outputDir\video" -ForegroundColor Green
Write-Host "Run the application: $outputDir\video\video.exe" -ForegroundColor Cyan