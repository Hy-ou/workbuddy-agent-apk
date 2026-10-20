# 让 gh 登录 GitHub（绕开 gh 自带网络栈在部分网络下连不上 github.com 的问题）。
#
# 做法：用 .NET 走 OAuth 设备码流程拿 token（github.com 可达即可），
# 再把 token 交给 gh 保存（gh 只在保存时需要 read:org 权限）。
#
# 用法：
#   pwsh tools/gh-login.ps1                 # 交互登录（推荐）
#   pwsh tools/gh-login.ps1 -NoWait         # 只生成设备码并复制到剪贴板，不轮询
param(
    [switch]$NoWait,
    [string]$ClientId = "178c6fc778ccc68e1d6a",   # GitHub CLI 的公开 OAuth client id
    [string]$Scope = "repo read:org"
)

$ErrorActionPreference = "Stop"

$code = Invoke-RestMethod 'https://github.com/login/device/code' -Method Post `
    -Headers @{ Accept = 'application/json' } `
    -Body @{ client_id = $ClientId; scope = $Scope } -TimeoutSec 30

Set-Clipboard -Value $code.user_code
Write-Host ""
Write-Host "一次性代码（已复制到剪贴板）： $($code.user_code)" -ForegroundColor Cyan
Write-Host "打开授权页： $($code.verification_uri)" -ForegroundColor Cyan
Write-Host "有效期约 $([math]::Round($code.expires_in/60)) 分钟。" -ForegroundColor DarkGray
if (-not $NoWait) {
    try { Start-Process $code.verification_uri } catch { }
}

if ($NoWait) { return }

$deadline = (Get-Date).AddSeconds([int]$code.expires_in)
$token = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds ([int]$code.interval)
    try {
        $res = Invoke-RestMethod 'https://github.com/login/oauth/access_token' -Method Post `
            -Headers @{ Accept = 'application/json' } `
            -Body @{
                client_id   = $ClientId
                device_code = $code.device_code
                grant_type  = 'urn:ietf:params:oauth:grant-type:device_code'
            } -TimeoutSec 20
    } catch { continue }

    if ($res.access_token) { $token = $res.access_token; break }
    if ($res.error -eq 'authorization_pending') { continue }
    if ($res.error -eq 'slow_down') { Start-Sleep -Seconds 5; continue }
    throw "授权失败：$($res.error) $($res.error_description)"
}

if (-not $token) { throw "设备码已过期，请重新运行本脚本" }

$gh = (Get-Command gh -ErrorAction SilentlyContinue)
if (-not $gh) {
    $fallback = Join-Path $env:LOCALAPPDATA 'GitHubCLI\bin\gh.exe'
    if (Test-Path $fallback) { $gh = Get-Item $fallback } else { throw "找不到 gh，请先安装 GitHub CLI" }
}

$token | & $gh.Source auth login --with-token
if ($LASTEXITCODE -ne 0) { throw "gh auth login 失败" }

Write-Host ""
Write-Host "登录成功：" -ForegroundColor Green
& $gh.Source auth status