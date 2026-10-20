# 编译 jniLibs 里的 ELF 启动器（node/npm/pnpm 分发 + 16 KB 对齐）
# 用法：pwsh android/dsh/build-shim.ps1
param([string]$Ndk = "D:\Environment\android-sdk\ndk\27.3.13750724")
$ErrorActionPreference = 'stop'
$bin = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$out = Join-Path $PSScriptRoot '..\app\src\main\jniLibs'
$src = Join-Path $PSScriptRoot 'shim.c'
$link = '-Wl,-z,max-page-size=16384'
foreach ($abi in @(@('arm64-v8a', 'aarch64-linux-android24-clang.cmd'), @('x86_64', 'x86_64-linux-android24-clang.cmd'))) {
    & (Join-Path $bin $abi[1]) -O2 -fPIE -pie $link -o (Join-Path $out "$($abi[0])\libwbnode_shim.so") $src
    Write-Host "built $($abi[0])"
}