# 将 DSH（DeepSeek Harness）内核打包进本 App：
#   1) 从官方 payload.zip 抽取 node + 运行库，改写 ELF DT_NEEDED（版本号名 -> jniLibs 合法名），
#      输出到 app/src/main/jniLibs/arm64-v8a/（Android 10+ 只允许从 nativeLibraryDir exec）；
#   2) 抽取 dshroot + dshhome + bin + runtime/etc 为精简 payload 资产（assets/payload-dsh.zip），
#      首次启动由 App 解压到应用目录（纯 JS/数据，无需 exec）。
# 用法：pwsh android/dsh/pack_dsh.ps1 [-PayloadZip <path>]
param(
    [string]$PayloadZip = ""
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jniArm = Join-Path $repoRoot "android\app\src\main\jniLibs\arm64-v8a"
$assets = Join-Path $repoRoot "android\app\src\main\assets"
New-Item -ItemType Directory -Force $jniArm, $assets | Out-Null

if (-not $PayloadZip -or -not (Test-Path $PayloadZip)) {
    $PayloadZip = Join-Path $env:TEMP "dsh-payload.zip"
    if (-not (Test-Path $PayloadZip)) {
        $url = "https://github.com/woaiys3/deepseek-harness-android-app/releases/download/v1.20.1/DeepSeekHarnessLite.apk"
        Write-Host "==> 下载官方运行时（154MB，仅构建期需要一次）"
        $tmpApk = "$PayloadZip.apk"
        Invoke-WebRequest $url -OutFile $tmpApk -TimeoutSec 900 -UseBasicParsing
        $apk = [IO.Compression.ZipFile]::OpenRead($tmpApk)
        $e = $apk.Entries | Where-Object FullName -eq "assets/payload.zip"
        [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $PayloadZip, $true)
        $apk.Dispose()
    }
}
Write-Host "==> payload: $PayloadZip"

$RewriteMap = [ordered]@{
    "libz.so.1"        = "libz_1.so"
    "libcrypto.so.3"   = "libcrypto_3.so"
    "libssl.so.3"      = "libssl_3.so"
    "libicui18n.so.78" = "libicui18n_78.so"
    "libicuuc.so.78"   = "libicuuc_78.so"
    "libicudata.so.78" = "libicudata_78.so"
}
$Libs = @("libz.so.1","libcares.so","libsqlite3.so","libffi.so","libcrypto.so.3","libssl.so.3",
          "libicui18n.so.78","libicuuc.so.78","libicudata.so.78","libc++_shared.so")

function V2F([UInt64]$v, $loads) {
    foreach ($l in $loads) {
        if ($v -ge $l.vaddr -and $v -lt ($l.vaddr + $l.size)) { return [int]($l.off + ($v - $l.vaddr)) }
    }
    throw ("vaddr 0x{0:x} not mapped" -f $v)
}

function Patch-Elf([byte[]]$bytes) {
    $phoff = [BitConverter]::ToUInt64($bytes, 0x20)
    $phentsize = [BitConverter]::ToUInt16($bytes, 0x36)
    $phnum = [BitConverter]::ToUInt16($bytes, 0x38)
    $loads = @(); $dyn = $null
    for ($i = 0; $i -lt $phnum; $i++) {
        $o = [int]($phoff + $i * $phentsize)
        $type = [BitConverter]::ToUInt32($bytes, $o)
        $off  = [BitConverter]::ToUInt64($bytes, $o + 0x08)
        $vaddr= [BitConverter]::ToUInt64($bytes, $o + 0x10)
        $fsz  = [BitConverter]::ToUInt64($bytes, $o + 0x20)
        if ($type -eq 1) { $loads += @{ off = $off; vaddr = $vaddr; size = $fsz } }
        if ($type -eq 2) { $dyn = @{ off = $off; vaddr = $vaddr; size = $fsz } }
    }
    if (-not $dyn) { throw "no PT_DYNAMIC" }
    $dynOff = [int](V2F $dyn.vaddr $loads)
    $strTabV = [UInt64]0
    for ($o = $dynOff; $o -lt ($dynOff + $dyn.size); $o += 16) {
        $tag = [BitConverter]::ToInt64($bytes, $o)
        if ($tag -eq 0) { break }
        if ($tag -eq 5) { $strTabV = [BitConverter]::ToUInt64($bytes, $o + 8) }
    }
    if ($strTabV -eq 0) { throw "no DT_STRTAB" }
    $strOff = [int](V2F $strTabV $loads)
    $patched = 0
    # DT_SONAME（tag 14）：bionic 用 soname 做 verneed 匹配，必须与改写后的文件名一致。
    $sonameOff = [UInt64]0
    for ($o = $dynOff; $o -lt ($dynOff + $dyn.size); $o += 16) {
        $tag = [BitConverter]::ToInt64($bytes, $o)
        if ($tag -eq 0) { break }
        if ($tag -eq 14) { $sonameOff = [BitConverter]::ToUInt64($bytes, $o + 8) }
    }
    if ($sonameOff -ne 0) {
        $nameOff = [int]($strOff + $sonameOff)
        $end = $nameOff; while ($bytes[$end] -ne 0) { $end++ }
        $name = [Text.Encoding]::ASCII.GetString($bytes, $nameOff, $end - $nameOff)
        if ($RewriteMap.Contains($name)) {
            $new = [Text.Encoding]::ASCII.GetBytes($RewriteMap[$name])
            for ($i = 0; $i -lt $new.Length; $i++) { $bytes[$nameOff + $i] = $new[$i] }
            for ($i = $new.Length; $i -lt ($end - $nameOff); $i++) { $bytes[$nameOff + $i] = 0 }
            $patched++
        }
    }
    # DT_VERNEED（tag 0x6ffffffe）：Verneed 结构
    #   struct Verneed { half vn_version; half vn_cnt; word vn_file; word vn_aux; word vn_next; }
    # vn_file 是 dynstr 偏移，指向依赖库名 —— 与 DT_NEEDED 一样要改写。
    $verNeedV = [UInt64]0
    for ($o = $dynOff; $o -lt ($dynOff + $dyn.size); $o += 16) {
        $tag = [BitConverter]::ToInt64($bytes, $o)
        if ($tag -eq 0) { break }
        if ($tag -eq 0x6ffffffe) { $verNeedV = [BitConverter]::ToUInt64($bytes, $o + 8) }
    }
    if ($verNeedV -ne 0) {
        $vnOff = [int](V2F $verNeedV $loads)
        $vnCount = [BitConverter]::ToUInt16($bytes, $vnOff + 2)
        $cur = $vnOff
        for ($v = 0; $v -lt $vnCount; $v++) {
            $vnFile = [BitConverter]::ToUInt32($bytes, $cur + 4)
            $vnNext = [BitConverter]::ToUInt16($bytes, $cur + 14)
            $nameOff = [int]($strOff + $vnFile)
            $end = $nameOff; while ($bytes[$end] -ne 0) { $end++ }
            $name = [Text.Encoding]::ASCII.GetString($bytes, $nameOff, $end - $nameOff)
            if ($RewriteMap.Contains($name)) {
                $new = [Text.Encoding]::ASCII.GetBytes($RewriteMap[$name])
                for ($i = 0; $i -lt $new.Length; $i++) { $bytes[$nameOff + $i] = $new[$i] }
                for ($i = $new.Length; $i -lt ($end - $nameOff); $i++) { $bytes[$nameOff + $i] = 0 }
                $patched++
            }
            if ($vnNext -eq 0) { break }
            $cur += $vnNext
        }
    }
    for ($o = $dynOff; $o -lt ($dynOff + $dyn.size); $o += 16) {
        $tag = [BitConverter]::ToInt64($bytes, $o)
        if ($tag -eq 0) { break }
        if ($tag -ne 1) { continue }
        $nameOff = [int]($strOff + [BitConverter]::ToUInt64($bytes, $o + 8))
        $end = $nameOff; while ($bytes[$end] -ne 0) { $end++ }
        $name = [Text.Encoding]::ASCII.GetString($bytes, $nameOff, $end - $nameOff)
        if ($RewriteMap.Contains($name)) {
            $new = [Text.Encoding]::ASCII.GetBytes($RewriteMap[$name])
            if ($new.Length -gt ($end - $nameOff)) { throw "new name longer: $name" }
            for ($i = 0; $i -lt $new.Length; $i++) { $bytes[$nameOff + $i] = $new[$i] }
            for ($i = $new.Length; $i -lt ($end - $nameOff); $i++) { $bytes[$nameOff + $i] = 0 }
            $patched++
        }
    }
    return $bytes, $patched
}

function Extract-Entry($zip, [string]$name, [string]$outFile) {
    $e = $zip.Entries | Where-Object FullName -eq $name
    if (-not $e) { throw "payload 缺少条目：$name" }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $outFile, $true)
}

$pz = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $PayloadZip))
try {
    $stage = Join-Path $env:TEMP "dsh-pack"
    Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $stage | Out-Null
    Extract-Entry $pz "runtime/bin/node" "$stage\node"
    foreach ($l in $Libs) { Extract-Entry $pz "runtime/lib/$l" "$stage\$l" }

    $bin = [IO.File]::ReadAllBytes("$stage\node")
    $bin, $n1 = Patch-Elf $bin
    [IO.File]::WriteAllBytes("$jniArm\libdshnode.so", $bin)
    Write-Host "    libdshnode.so  patched=$n1"
    foreach ($l in $Libs) {
        $bytes = [IO.File]::ReadAllBytes("$stage\$l")
        $bytes, $n = Patch-Elf $bytes
        $newName = if ($RewriteMap.Contains($l)) { $RewriteMap[$l] } else { $l }
        [IO.File]::WriteAllBytes((Join-Path $jniArm $newName), $bytes)
        Write-Host ("    {0,-20} patched={1}" -f $newName, $n)
    }

    $outZip = Join-Path $assets "payload-dsh.zip"
    if (Test-Path $outZip) { Remove-Item $outZip -Force }
    $out = [IO.Compression.ZipFile]::Open($outZip, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $count = 0
        $cleanPatchLines = @(
            '# WorkBuddy-managed-patch: v2',
            '# 一体化 APK 的 DSH 兼容补丁：禁用移动端不需要/缺依赖的插件，模型提供方走本地网关',
            '- id: bash-sandbox', '  disabled: true',
            '- id: pwsh-sandbox', '  disabled: true',
            '- id: session-log-download', '  disabled: true',
            '- id: session-telemetry-otel', '  disabled: true',
            '- id: office-to-pdf', '  disabled: true',
            '- id: permission', '  config:', '    presets:',
            '      read-only:', '        sandbox: read-only', '        approval: ask',
            '      workspace-write:', '        sandbox: workspace-write', '        approval: ask',
            '      danger-full-access:', '        sandbox: danger-full-access', '        approval: never',
            '    defaultPreset: danger-full-access',
            '- insert:',
            '    - id: bash-local',
            "      name: '@deepseek-ai/dsh-bash-local'",
            '      config:',
            '        cwd: !!js (process.env.DSH_WORKSPACE || process.cwd())',
            '        timeoutMs: 60000'
        )
        $cleanPatch = $cleanPatchLines -join "
"
        foreach ($e in $pz.Entries) {
            if ($e.FullName.EndsWith("/")) { continue }
            $inc = ($e.FullName -like "dshroot/*") -or ($e.FullName -like "dshhome/*") -or ($e.FullName -like "npm/*") -or ($e.FullName -like "pnpm/*") -or
                   ($e.FullName -like "bin/*") -or ($e.FullName -like "runtime/etc/*")
            if (-not $inc) { continue }
            if ($e.FullName -eq "dshhome/cordis.patch.yml") { continue }
            $ne = $out.CreateEntry($e.FullName, [IO.Compression.CompressionLevel]::Optimal)
            $es = $e.Open(); $ns = $ne.Open()
            $es.CopyTo($ns); $ns.Close(); $es.Close()
            $count++
        }
        $ne = $out.CreateEntry("dshhome/cordis.patch.yml", [IO.Compression.CompressionLevel]::Optimal)
        $bytes = [Text.Encoding]::UTF8.GetBytes($cleanPatch)
        $s = $ne.Open(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
        $count++
        $manifest = [ordered]@{
            kernel = "0.2.1-rc.2"
            revision = "20261018085302"
            source = "woaiys3/deepseek-harness-android-app v1.20.1 (DeepSeekHarnessLite)"
            entries = $count
            nodeInJniLibs = $true
        }
        $json = [Text.Encoding]::UTF8.GetBytes(($manifest | ConvertTo-Json -Depth 4))

        # 内置纯 JS 的 pnpm（应用进程不能执行原生 @pnpm/exe，且不依赖首次启动联网安装）
        $pnpmExtra = (Resolve-Path (Join-Path $PSScriptRoot "..\.payload-extra\pnpm10")).Path
        if (Test-Path $pnpmExtra) {
            $pnpmFiles = Get-ChildItem $pnpmExtra -Recurse -File
            foreach ($f in $pnpmFiles) {
                $rel = $f.FullName.Substring($pnpmExtra.Length).TrimStart("\", "/") -replace "\\", "/"
                $entry = $out.CreateEntry("pnpm10/$rel", [IO.Compression.CompressionLevel]::Optimal)
                $s = $f.OpenRead(); $d = $entry.Open(); $s.CopyTo($d); $d.Dispose(); $s.Dispose()
            }
            Write-Host ("    pnpm10 files={0}" -f $pnpmFiles.Count)
        } else {
            Write-Host "    [warn] 缺少 .payload-extra\pnpm10（社区插件安装会退化为离线不可用）"
        }

        $me = $out.CreateEntry("PAYLOAD-MANIFEST.json", [IO.Compression.CompressionLevel]::Optimal)
        $ms = $me.Open(); $ms.Write($json, 0, $json.Length); $ms.Close()
        Write-Host "    payload-dsh.zip entries=$count"
    } finally { $out.Dispose() }
    Write-Host ("==> assets/payload-dsh.zip {0} MB" -f [math]::Round((Get-Item $outZip).Length / 1MB, 1))
} finally { $pz.Dispose() }
Write-Host "完成。构建 APK：gradle -p android assembleRelease"
