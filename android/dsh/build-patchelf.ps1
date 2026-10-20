# 交叉编译 patchelf（工具链重定位用），产物 libwbpatchelf.so 放入 jniLibs
# 用法：pwsh android/dsh/build-patchelf.ps1 -SourceDir <patchelf-0.18.0 解压目录>
param(
    [Parameter(Mandatory = $true)][string]$SourceDir,
    [string]$Ndk = "D:\Environment\android-sdk\ndk\27.3.13750724"
)
$ErrorActionPreference = 'stop'
$bin = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$out = Join-Path $PSScriptRoot '..\app\src\main\jniLibs'
$src = Join-Path $SourceDir 'src\patchelf.cc'
if (-not (Test-Path $src)) { throw "找不到 patchelf.cc：$src" }
$link = '-Wl,-z,max-page-size=16384'
foreach ($abi in @(@('arm64-v8a', 'aarch64-linux-android24-clang++.cmd'), @('x86_64', 'x86_64-linux-android24-clang++.cmd'))) {
    & (Join-Path $bin $abi[1]) -std=c++17 -O2 -fPIE -pie $link -I(Join-Path $SourceDir 'src') `
        -o (Join-Path $out "$($abi[0])\libwbpatchelf.so") $src
    Write-Host "built $($abi[0])"
}