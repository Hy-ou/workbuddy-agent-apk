# 校验 APK 内所有 .so 的 ELF LOAD 对齐是否满足 16 KB 页要求（>= 0x4000）。
# 用法：pwsh android/dsh/check-16kb.ps1 -Apk <path-to.apk>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Abi = 'arm64-v8a'
)

Add-Type -AssemblyName System.IO.Compression.FileSystem

$ndkRoot = $env:ANDROID_NDK_HOME
if (-not $ndkRoot) {
    $candidates = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\ndk", 'D:\Environment\android-sdk\ndk' -Directory -ErrorAction SilentlyContinue
    if ($candidates) { $ndkRoot = ($candidates | Sort-Object Name -Descending | Select-Object -First 1).FullName }
}
if (-not $ndkRoot) { Write-Error 'NDK not found (set ANDROID_NDK_HOME)'; exit 2 }

$readelf = Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
if (-not (Test-Path $readelf)) { Write-Error "llvm-readelf not found: $readelf"; exit 2 }

$tmp = Join-Path $env:TEMP ('wb-16kb-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $tmp | Out-Null
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $Apk))
$entries = $zip.Entries | Where-Object { $_.FullName -like "lib/$Abi/*.so" }
$fail = 0
foreach ($e in $entries) {
    $dst = Join-Path $tmp (Split-Path $e.FullName -Leaf)
    [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dst, $true)
    $out = (& $readelf -l $dst 2>$null) -join "`n"
    $aligns = [regex]::Matches($out, '(?m)^\s+LOAD\s+.*?\s(0x[0-9a-fA-F]+)\s*$') | ForEach-Object { $_.Groups[1].Value }
    $bad = @($aligns | Where-Object { [Convert]::ToInt64($_, 16) -lt 0x4000 })
    if ($bad.Count -gt 0 -or $aligns.Count -eq 0) {
        $fail++
        Write-Host ("FAIL  {0}  aligns={1}" -f (Split-Path $e.FullName -Leaf), ($aligns -join ',')) -ForegroundColor Red
    } else {
        Write-Host ("OK    {0}" -f (Split-Path $e.FullName -Leaf)) -ForegroundColor DarkGray
    }
}
$zip.Dispose()
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

if ($entries.Count -eq 0) { Write-Error "no .so found for $Abi in $Apk"; exit 2 }
if ($fail -gt 0) { Write-Host "$fail library(ies) are not 16 KB aligned" -ForegroundColor Red; exit 1 }
Write-Host ('all {0} libraries are 16 KB aligned' -f $entries.Count) -ForegroundColor Green