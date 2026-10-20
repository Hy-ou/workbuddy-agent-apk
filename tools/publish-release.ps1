# 把 release/ 下的 APK 作为 GitHub Release 附件发布。
#
# 用法：
#   pwsh tools/publish-release.ps1 -Tag v3.2.0 -Repo owner/repo
#   pwsh tools/publish-release.ps1 -Tag v3.2.0 -Repo owner/repo -DryRun   # 只打印命令
#
# 说明：APK 不入库（体积原因），本脚本先校验 SHA256SUMS，再调用 gh CLI 创建/更新 Release。
# 没有安装 gh 时，脚本会打印等价的 curl 命令（需要一个有 repo 权限的 GITHUB_TOKEN）。
param(
    [string]$Tag = "v3.2.0",
    [string]$Repo = "",
    [string]$ApkDir = "",
    [string]$NotesFile = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $ApkDir) { $ApkDir = Join-Path $root "release" }
if (-not $NotesFile) {
    # 支持 RELEASE_NOTES-v3.7.1.md 与 RELEASE_NOTES-3.7.1.md 两种命名
    $bare = $Tag -replace '^v', ''
    foreach ($name in @("RELEASE_NOTES-$Tag.md", "RELEASE_NOTES-$bare.md")) {
        $candidate = Join-Path $ApkDir $name
        if (Test-Path $candidate) { $NotesFile = $candidate; break }
    }
    if (-not $NotesFile) {
        # 找不到就用仓库 release/ 目录下的同名说明，绝不静默使用 README
        foreach ($name in @("RELEASE_NOTES-$Tag.md", "RELEASE_NOTES-$bare.md")) {
            $candidate = Join-Path (Split-Path $PSScriptRoot -Parent) "release\$name"
            if (Test-Path $candidate) { $NotesFile = $candidate; break }
        }
    }
    if (-not $NotesFile) {
        throw "找不到 $Tag 的发布说明：请创建 release/RELEASE_NOTES-$bare.md"
    }
}

function Resolve-RepoFromRemote {
    $url = (& git -C $root remote get-url origin 2>$null)
    if (-not $url) { return "" }
    $url = $url.Trim()
    if ($url -match 'github\.com[:/](?<o>[^/]+)/(?<r>[^/.]+?)(\.git)?$') {
        return "$($Matches.o)/$($Matches.r)"
    }
    return ""
}

if (-not $Repo) { $Repo = Resolve-RepoFromRemote }
if (-not $Repo) {
    throw "缺少 -Repo owner/repo（或先 git remote add origin <github-url>）"
}

# ---- 1) 校验附件 ----
$sumsFile = Join-Path $ApkDir "SHA256SUMS"
if (-not (Test-Path $sumsFile)) { throw "找不到 $sumsFile" }
$apks = @()
foreach ($line in Get-Content $sumsFile) {
    $line = $line.Trim()
    if (-not $line) { continue }
    $parts = $line -split '\s+', 2
    $expected = $parts[0].ToLower()
    $name = $parts[1].Trim()
    $file = Join-Path $ApkDir $name
    if (-not (Test-Path $file)) { throw "缺少附件：$file" }
    $actual = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $expected) { throw "校验失败：$name`n  期望 $expected`n  实际 $actual" }
    Write-Host ("校验通过  {0}  ({1:N1} MB)" -f $name, ((Get-Item $file).Length / 1MB))
    $apks += $file
}

# ---- 2) 发布 ----
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) {
    Write-Host ""
    Write-Host "未检测到 gh CLI，可用 curl 上传（需要 repo 权限的 token）：" -ForegroundColor Yellow
    Write-Host "  1) 创建 Release："
    Write-Host "     curl -X POST -H `"Authorization: Bearer `$env:GITHUB_TOKEN`" \\"
    Write-Host "       https://api.github.com/repos/$Repo/releases \\"
    Write-Host "       -d '{`"tag_name`":`"$Tag`",`"name`":`"WorkBuddy $Tag`",`"body`":`"见 release/RELEASE_NOTES.md`"}'"
    Write-Host "  2) 上传附件（<ID> 为上一步返回的 id）："
    foreach ($apk in $apks) {
        $n = Split-Path -Leaf $apk
        Write-Host "     curl -X POST -H `"Authorization: Bearer `$env:GITHUB_TOKEN`" -H `"Content-Type: application/octet-stream`" \\"
        Write-Host "       --data-binary `"@$apk`" `"https://uploads.github.com/repos/$Repo/releases/<ID>/assets?name=$n`""
    }
    exit 0
}

if ($DryRun) {
    Write-Host "[dry-run] gh release create $Tag -R $Repo --notes-file $NotesFile"
    foreach ($apk in $apks) { Write-Host "[dry-run] gh release upload $Tag $apk -R $Repo --clobber" }
    exit 0
}

$exists = $false
try {
    & gh release view $Tag -R $Repo *> $null
    if ($LASTEXITCODE -eq 0) { $exists = $true }
} catch { }

if ($exists) {
    Write-Host "Release $Tag 已存在，更新附件…"
} else {
    Write-Host "创建 Release $Tag …"
    & gh release create $Tag -R $Repo --title "WorkBuddy $Tag" --notes-file $NotesFile
    if ($LASTEXITCODE -ne 0) { throw "gh release create 失败" }
}
& gh release upload $Tag @apks -R $Repo --clobber
if ($LASTEXITCODE -ne 0) { throw "gh release upload 失败" }
Write-Host "完成：https://github.com/$Repo/releases/tag/$Tag"