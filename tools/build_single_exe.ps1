<#
.SYNOPSIS
    把 Windows 版打成一个单文件 exe（自解压引导器 + 内嵌完整应用）。

.DESCRIPTION
    WinUI 3 不支持 PublishSingleFile，所以流程是：
      1. dotnet publish 出完整应用（含 ffmpeg、resources.pri、Windows App SDK）
      2. 压成 payload.zip
      3. 内嵌进 TransMusic.Launcher，用 PublishSingleFile 输出单个 exe
      4. 首次运行时解压到 %LOCALAPPDATA%\TransMusic\app\<版本>，再启动真正的主程序

.PARAMETER Version
    输出文件名里的版本号，默认 1.2.0。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\build_single_exe.ps1
#>
param(
    [string]$Version = "1.2.0"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$dotnet = $null
$cmd = Get-Command dotnet -ErrorAction SilentlyContinue
if ($cmd) { $dotnet = $cmd.Source }
if (-not $dotnet) {
    $portable = Join-Path $env:TEMP "dotnet-sdk\dotnet.exe"
    if (Test-Path $portable) { $dotnet = $portable }
    else { throw "找不到 dotnet SDK" }
}

$appProj = "windows\TransMusic.App\TransMusic.App.csproj"
$launcherDir = "windows\TransMusic.Launcher"
$publishDir = "windows\publish"
$payloadZip = Join-Path $launcherDir "payload.zip"

# ---------- 1. 完整应用 ----------
Write-Host "[1/4] 发布完整应用…" -ForegroundColor Cyan
if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }
& $dotnet publish $appProj -c Release -r win-x64 --self-contained false -o $publishDir -v q -nologo
if ($LASTEXITCODE -ne 0) { throw "应用发布失败" }

$ffmpeg = Join-Path $publishDir "ffmpeg\ffmpeg.exe"
if (-not (Test-Path $ffmpeg)) {
    Write-Host "  注意：publish 目录里没有 ffmpeg\ffmpeg.exe（单文件版会缺少转码引擎）" -ForegroundColor Yellow
}

# ---------- 2. payload.zip ----------
Write-Host "[2/4] 打包内嵌载荷…" -ForegroundColor Cyan
if (Test-Path $payloadZip) { Remove-Item $payloadZip -Force }
Compress-Archive -Path "$publishDir\*" -DestinationPath $payloadZip -CompressionLevel Optimal
$payloadMB = [math]::Round((Get-Item $payloadZip).Length / 1MB, 1)
Write-Host "  payload.zip = $payloadMB MB"

# ---------- 3. 单文件 exe ----------
Write-Host "[3/4] 编译单文件启动器…" -ForegroundColor Cyan
$outDir = "windows\publish-single"
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
& $dotnet publish (Join-Path $launcherDir "TransMusic.Launcher.csproj") `
    -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true -p:PublishTrimmed=true `
    -p:Version=$Version -o $outDir -v q -nologo
if ($LASTEXITCODE -ne 0) { throw "启动器编译失败" }

$exe = Join-Path $outDir "TransMusic.exe"
if (-not (Test-Path $exe)) { throw "没有生成 TransMusic.exe" }
$exeMB = [math]::Round((Get-Item $exe).Length / 1MB, 1)
Write-Host "  TransMusic.exe = $exeMB MB"

# ---------- 4. 归档 ----------
Write-Host "[4/4] 输出到 release…" -ForegroundColor Cyan
$releaseDir = Join-Path $root "release"
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$target = Join-Path $releaseDir "TransMusic-$Version-standalone.exe"
Copy-Item $exe $target -Force
Remove-Item $payloadZip -Force

Write-Host ""
Write-Host "完成：$target（$exeMB MB，单文件，免安装，内置 ffmpeg）" -ForegroundColor Green
Write-Host "首次运行会解压到 %LOCALAPPDATA%\TransMusic\app\<版本>，之后直接启动。"
