# 把 Go 网关（仓库根目录的 cmd/server）交叉编译成 Android 可执行文件，输出到 app/src/main/jniLibs。
#
# 为什么要 cgo：DNS 走 Android bionic 的 getaddrinfo、系统证书走 /system/etc/security/cacerts；
# 为什么叫 lib*.so：Android 10+ 只允许从 nativeLibraryDir 执行二进制，jniLibs 是唯一落点。
# -tags timetzdata：内嵌时区库，保证手机上的签到排程使用本地时区。
#
# 用法：pwsh android/build_gateway.ps1 [-NdkPath <ndk 目录>]
param(
    [string]$NdkPath = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)  # 仓库根
if (-not (Test-Path (Join-Path $root "cmd\server"))) {
    $root = Split-Path -Parent $PSScriptRoot
}
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
    & go build -trimpath -tags timetzdata -ldflags "-s -w" -o $out "$root\cmd\server"
    if ($LASTEXITCODE -ne 0) { throw "构建失败：$($t.Abi)" }
    $size = [math]::Round((Get-Item $out).Length / 1MB, 2)
    Write-Host "    -> $out ($size MB)"
}

Write-Host "完成。重新构建 APK：gradle -p android assembleDebug"
