<#
.SYNOPSIS
    把 TransMusic 发布到 GitHub：创建仓库 → 推送 main → 建 Release → 上传三个产物。

.DESCRIPTION
    前置：gh CLI 已安装并登录（gh auth status 显示已登录）。
    若未登录，先执行：gh auth login --hostname github.com --git-protocol https --web

.PARAMETER Repository
    仓库名，默认 TransMusic（会建在当前登录账号下）。

.PARAMETER Visibility
    public 或 private，默认 public。

.PARAMETER Tag
    Release 标签，默认 v1.0.0。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\publish_github.ps1
    powershell -ExecutionPolicy Bypass -File tools\publish_github.ps1 -Repository TransMusic-WinUI -Visibility private
#>
param(
    [string]$Repository = "TransMusic",
    [ValidateSet("public", "private")]
    [string]$Visibility = "public",
    [string]$Tag = "v1.0.0"
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

# ---------- 0. 前置检查 ----------
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) {
    $candidate = "C:\Program Files\GitHub CLI\gh.exe"
    if (Test-Path $candidate) { $gh = Get-Item $candidate } else {
        throw "未找到 gh CLI。安装：winget install --id GitHub.cli"
    }
}
$ghExe = if ($gh.Source) { $gh.Source } else { $gh.FullName }

& $ghExe auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "gh 未登录。先执行：gh auth login --hostname github.com --git-protocol https --web"
}

# ---------- 1. 待上传产物 ----------
$assets = @(
    "release\TransMusic-windows-x64.zip",
    "release\TransMusic-android-arm-v1.0.0.apk",
    "release\TransMusic-src-v1.0.0.zip",
    "release\SHA256SUMS.txt"
)
foreach ($a in $assets) {
    if (-not (Test-Path $a)) { throw "缺少产物：$a（先跑构建，见 BUILD-WINDOWS.md / BUILD-ANDROID.md）" }
}

# ---------- 2. 仓库 ----------
$owner = (& $ghExe api user --jq .login).Trim()
Write-Host "GitHub 账号：$owner" -ForegroundColor Cyan

$exists = $false
$ErrorActionPreference = "Continue"
& $ghExe repo view "$owner/$Repository" --json name 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) { $exists = $true }
$ErrorActionPreference = "Stop"

if ($exists) {
    Write-Host "仓库 $owner/$Repository 已存在，直接推送" -ForegroundColor Yellow
    $remotes = git remote
    if ($remotes -notcontains "origin") {
        git remote add origin "https://github.com/$owner/$Repository.git"
    }
} else {
    Write-Host "创建仓库 $owner/$Repository（$Visibility）…" -ForegroundColor Cyan
    & $ghExe repo create "$owner/$Repository" "--$Visibility" --source=. --remote=origin --description "Windows (WinUI 3) + Android (MD3E) 音频格式转换器：15 种格式互转" | Out-Null
}

# ---------- 3. 推送 ----------
git add -A
$pending = git status --porcelain
if ($pending) {
    git -c core.autocrlf=false commit -q -m "docs: 更新 README（下载安装与发布说明）"
}
git branch -M main
git push -u origin main 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) {
    Write-Host "推送失败，尝试 gh auth setup-git 后重试…" -ForegroundColor Yellow
    & $ghExe auth setup-git 2>$null
    git push -u origin main 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "推送失败：请检查 gh auth status 与网络" }
}
Write-Host "源码已推送到 https://github.com/$owner/$Repository" -ForegroundColor Green

# ---------- 4. Release ----------
$notes = @"
## TransMusic 1.0.0

**Windows（WinUI 3）+ Android（Material 3 Expressive）** 双端音频格式转换器，15 种格式互转。

### 下载

| 文件 | 适用 |
| --- | --- |
| ``TransMusic-windows-x64.zip`` | Windows 10 1809+ / 11 (x64)，解压双击 ``TransMusic.exe``，内置 ffmpeg，免安装 |
| ``TransMusic-android-arm-v1.0.0.apk`` | Android 7.0+（arm64-v8a / armeabi-v7a），自签名 |
| ``TransMusic-src-v1.0.0.zip`` | 完整源码（不含构建产物） |

校验值见 ``SHA256SUMS.txt``。使用说明见仓库 README。

### 亮点

- 三端（WinUI 3 / Android / Python CLI）共用同一份格式定义，ffmpeg 参数经 236 用例逐条对齐
- MP3 / AAC / ALAC / FLAC / WAV / AIFF / OGG / Opus / WMA / WavPack / TTA / AC3 / E-AC3 / MP2 / Speex
- 批量队列、并发、随时取消、保留标签与封面、文件名模板、重名策略
"@

$notesFile = Join-Path $env:TEMP "transmusic-release-notes.md"
$notes | Set-Content -Path $notesFile -Encoding UTF8

$ErrorActionPreference = "Continue"
& $ghExe release view $Tag 2>$null | Out-Null
$releaseExists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = "Stop"
if ($releaseExists) {
    Write-Host "Release $Tag 已存在，改为上传/覆盖资产" -ForegroundColor Yellow
} else {
    & $ghExe release create $Tag --title "TransMusic $Tag" --notes-file $notesFile | Out-Null
}

& $ghExe release upload $Tag @assets --clobber

Write-Host ""
Write-Host "发布完成：" -ForegroundColor Green
& $ghExe release view $Tag --json url --jq .url
