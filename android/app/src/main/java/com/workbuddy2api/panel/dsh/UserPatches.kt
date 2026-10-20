package com.workbuddy2api.panel.dsh

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 「我的补丁」：用户对内核树的修改在重装/重新解压后不丢。
 *
 * - 基线：首次解压后记录 `dshroot` 下每个文件的 sha256（`home/.baseline.tsv`）；
 * - 保存：把与基线不同的文件（文本/配置/图片，单个 ≤8 MB）复制到 `home/user-patch/`；
 * - 回放：每次启动前把这些文件盖回内核树；
 * - 清空：删除 user-patch，并清掉内核树标记，下次启动重新解压出厂文件。
 */
object UserPatches {

    const val DIR = "user-patch"
    private const val MANIFEST = "manifest.json"
    private const val BASELINE = ".baseline.tsv"
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private val ALLOWED_EXT = setOf(
        "js", "mjs", "cjs", "ts", "json", "yml", "yaml", "md", "txt", "html", "css",
        "svg", "png", "jpg", "jpeg", "webp", "gif",
    )
    private val SKIP_DIRS = setOf("node_modules/.bin")

    fun patchDir(homeDir: File): File = File(homeDir, DIR)

    fun hasPatches(homeDir: File): Boolean =
        File(patchDir(homeDir), MANIFEST).isFile

    fun patchCount(homeDir: File): Int {
        val manifest = File(patchDir(homeDir), MANIFEST)
        if (!manifest.isFile) return 0
        return runCatching { JSONObject(manifest.readText()).optInt("count", 0) }.getOrDefault(0)
    }

    /** 已经记录过基线就跳过（避免每次启动重算 400 MB）。 */
    fun ensureBaseline(kernelDir: File, homeDir: File) {
        val baseline = File(homeDir, BASELINE)
        if (baseline.isFile) return
        val root = File(kernelDir, "dshroot")
        if (!root.isDirectory) return
        runCatching {
            val sb = StringBuilder()
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { f ->
                    sb.append(f.relativeTo(root).path.replace('\\', '/'))
                        .append('\t').append(sha256(f)).append('\n')
                }
            baseline.writeText(sb.toString())
        }
    }

    /** 把用户改动保存为补丁；返回保存的文件数。 */
    fun capture(kernelDir: File, homeDir: File): Int {
        val root = File(kernelDir, "dshroot")
        if (!root.isDirectory) return 0
        val baseline = readBaseline(homeDir)
        val out = patchDir(homeDir)
        out.deleteRecursively()
        out.mkdirs()
        val items = JSONArray()
        var count = 0
        root.walkTopDown()
            .filter { it.isFile }
            .forEach { f ->
                if (count >= 5000) return@forEach
                val rel = f.relativeTo(root).path.replace('\\', '/')
                if (rel in SKIP_DIRS) return@forEach
                val ext = rel.substringAfterLast('.', "").lowercase()
                if (ext !in ALLOWED_EXT) return@forEach
                if (f.length() > MAX_FILE_BYTES) return@forEach
                val old = baseline[rel]
                val now = runCatching { sha256(f) }.getOrNull() ?: return@forEach
                if (old == now) return@forEach
                val dst = File(out, "files/$rel")
                dst.parentFile?.mkdirs()
                runCatching { f.copyTo(dst, overwrite = true) }
                items.put(JSONObject().put("path", rel).put("size", f.length()))
                count++
            }
        File(out, MANIFEST).writeText(
            JSONObject()
                .put("count", count)
                .put("savedAt", System.currentTimeMillis())
                .put("files", items)
                .toString(2),
        )
        return count
    }

    /** 启动前回放补丁；返回回放的文件数。 */
    fun replay(kernelDir: File, homeDir: File): Int {
        val out = patchDir(homeDir)
        val manifest = File(out, MANIFEST)
        if (!manifest.isFile) return 0
        val root = File(kernelDir, "dshroot")
        if (!root.isDirectory) return 0
        var n = 0
        runCatching {
            val arr = JSONObject(manifest.readText()).optJSONArray("files") ?: return 0
            for (i in 0 until arr.length()) {
                val rel = arr.getJSONObject(i).optString("path")
                if (rel.isBlank() || rel.contains("..")) continue
                val src = File(out, "files/$rel")
                if (!src.isFile) continue
                val dst = File(root, rel)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                n++
            }
        }
        return n
    }

    /** 清空补丁，并让内核树在下次启动重新解压（回到出厂）。 */
    fun clear(homeDir: File, kernelDir: File) {
        patchDir(homeDir).deleteRecursively()
        File(homeDir, BASELINE).delete()
        File(kernelDir, "dshroot").deleteRecursively()
        File(kernelDir, ".extracted").delete()
    }

    private fun readBaseline(homeDir: File): Map<String, String> {
        val f = File(homeDir, BASELINE)
        if (!f.isFile) return emptyMap()
        return runCatching {
            f.readLines().mapNotNull { line ->
                val idx = line.indexOf('\t')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
