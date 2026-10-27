# 把 Go 网关（仓库根目录的 cmd/server）交叉编译成 Android 可执行文件，输出到 app/src/main/jniLibs。
#
# 为什么要 cgo：DNS 走 Android bionic 的 getaddrinfo、系统证书走 /system/etc/security/cacerts；
# 为什么叫 lib*.so：Android 10+ 只允许从 nativeLibraryDir 执行二进制，jniLibs 是唯一落点。
# -tags timetzdata：内嵌时区库，保证手机上的签到排程使用本地时区。
#
# 用法：pwsh android/build_gateway.ps1 [-NdkPath <ndk 目录>] [-SourceRoot <panel 仓库根>]
param(
    [string]$NdkPath = "",
    [string]$SourceRoot = ""
)

$ErrorActionPreference = "Stop"

# Go 网关源码根（含 cmd/server）。本仓库**不存放** Go 源码：libwb2api.so 是构建产物，
# 由 .gitignore 排除。默认按「工作区同级仓库」推断 <父目录>\workbuddy2api-panel，
# 也可用 -SourceRoot 显式指定。找不到就 fail fast——旧版静默回落到本仓库根，
# 报错发生在 go build 阶段且信息晦涩（root 下根本没有 cmd/server）。
if (-not $SourceRoot) {
    foreach ($cand in @(
        (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) "workbuddy2api-panel"),  # 工作区同级仓库（默认）
        (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),              # 历史行为：本仓库根含 cmd/server
        (Split-Path -Parent $PSScriptRoot)                                    # 兜底：android 的父目录
    )) {
        if ($cand -and (Test-Path (Join-Path $cand "cmd\server"))) { $SourceRoot = $cand; break }
    }
}
if (-not $SourceRoot -or -not (Test-Path (Join-Path $SourceRoot "cmd\server"))) {
    throw "未找到 Go 网关源码（cmd/server）。请用 -SourceRoot 指定 workbuddy2api-panel 仓库根，例如：pwsh android/build_gateway.ps1 -SourceRoot ..\workbuddy2api-panel"
}
$root = $SourceRoot

# 溯源：把 Go 源码仓库的提交号打进日志。libwb2api.so 是 .gitignore 的构建产物，
# 出来后没法和面板仓库的版本对上，排障时"这个 APK 用的是哪版网关"要能一眼看出来。
$srcRev = ""
try { $srcRev = (& git -C $root rev-parse --short HEAD 2>$null) } catch { }
Write-Host "Go 源码：$root (rev=$srcRev)"

$jni = Join-Path $PSScriptRoot "app\src\main\jniLibs"

if (-not $NdkPath) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if ($sdk -and (Test-Path (Join-Path $sdk "ndk"))) {
        $NdkPath = (Get-ChildItem (Join-Path $sdk "ndk") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
    }
}
if (-not $NdkPath -or -not (Test-Path $NdkPath)) {
    throw "未找到 Android NDK，请设置 ANDROID_NDK_HOME 或用 -NdkPath 指定。"
}

$toolchain = Join-Path $NdkPath "toolchains\llvm\prebuilt"
$prebuilt = Get-ChildItem $toolchain -Directory | Select-Object -First 1
$bin = Join-Path $prebuilt.FullName "bin"

$targets = @(
    @{ Abi = "arm64-v8a"; GoArch = "arm64"; Triple = "aarch64-linux-android24" },
    @{ Abi = "x86_64";    GoArch = "amd64"; Triple = "x86_64-linux-android24" }
)

$env:CGO_ENABLED = "1"
$env:GOOS = "android"

foreach ($t in $targets) {
    $env:GOARCH = $t.GoArch
    $env:CC = Join-Path $bin "$($t.Triple)-clang.cmd"
    if (-not (Test-Path $env:CC)) { $env:CC = Join-Path $bin "$($t.Triple)-clang.exe" }
    $env:CXX = $env:CC -replace 'clang\.(cmd|exe)$', 'clang++.$1'
    $outDir = Join-Path $jni $t.Abi
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $out = Join-Path $outDir "libwb2api.so"
    Write-Host "==> $($t.Abi): GOARCH=$($t.GoArch) CC=$env:CC"
    # 必须 cd 进 Go 源码根再 build：go build 以**当前目录**定位 module，
    # 传绝对包路径也不会去包所在目录找 go.mod（从 APK 仓库根执行会报 cannot find main module）。
    Push-Location $root
    & go build -trimpath -tags timetzdata -ldflags "-s -w" -o $out ".\cmd\server"
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { throw "构建失败：$($t.Abi)" }
    $size = [math]::Round((Get-Item $out).Length / 1MB, 2)
    Write-Host "    -> $out ($size MB)"
}

Write-Host "完成（Go 源码 rev=$srcRev）。重新构建 APK：gradle -p android assembleDebug"
