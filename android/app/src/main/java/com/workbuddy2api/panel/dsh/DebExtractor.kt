package com.workbuddy2api.panel.dsh

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File



/**
 * 把 Termux 的 `.deb`（ar 包 → data.tar.xz → tar）解到应用的工具链前缀。
 *
 * Termux 包内路径固定为 `data/data/com.termux/files/usr/...`，这里剥掉该前缀，
 * 落到 `<home>/toolchain/usr/...`；符号链接与可执行位都会保留。
 */
object DebExtractor {

    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_REL = "data/data/com.termux/files/usr/"

    data class Stats(val files: Int, val links: Int, val skipped: Int)

    fun extract(deb: File, usrDir: File, onLog: (String) -> Unit): Stats {
        var files = 0
        var links = 0
        var skipped = 0
        ArArchiveInputStream(BufferedInputStream(deb.inputStream())).use { ar ->
            while (true) {
                val entry = ar.nextEntry ?: break
                val name = entry.name
                if (!name.startsWith("data.tar")) continue
                val stream = when {
                    name.endsWith(".xz") -> XZCompressorInputStream(BufferedInputStream(ar))
                    name.endsWith(".gz") -> GzipCompressorInputStream(BufferedInputStream(ar))
                    else -> BufferedInputStream(ar)
                }
                TarArchiveInputStream(stream).use { tar ->
                    while (true) {
                        val te = tar.nextEntry ?: break
                        var rel = te.name
                        while (rel.startsWith("./")) rel = rel.substring(2)
                        if (rel.isEmpty() || rel == ".") continue
                        if (!rel.startsWith(TERMUX_REL)) {
                            // usr/share/doc 等仍在 TERMUX_REL 下；其余（如 data/data/com.termux/files/home）跳过
                            skipped++
                            continue
                        }
                        val tail = rel.removePrefix(TERMUX_REL)
                        if (tail.isEmpty()) continue
                        val target = File(usrDir, tail)
                        when {
                            te.isDirectory -> target.mkdirs()
                            te.isSymbolicLink -> {
                                target.parentFile?.mkdirs()
                                runCatching { target.delete() }
                                val ok = runCatching {
                                    android.system.Os.symlink(te.linkName, target.absolutePath)
                                    true
                                }.getOrDefault(false)
                                if (ok) links++ else onLog("无法创建符号链接：$tail")
                            }
                            te.isFile -> {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out -> tar.copyTo(out, 64 * 1024) }
                                val mode = te.mode
                                if (mode and 0b001_000_000 != 0) target.setExecutable(true, false)
                                files++
                            }
                            else -> skipped++
                        }
                    }
                }
                break
            }
        }
        return Stats(files, links, skipped)
    }
}