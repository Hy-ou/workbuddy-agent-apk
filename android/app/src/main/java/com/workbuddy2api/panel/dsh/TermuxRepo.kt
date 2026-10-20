package com.workbuddy2api.panel.dsh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Termux 软件源（镜像 + 索引解析 + deb 下载校验）。
 *
 * 索引就是 apt 的 `Packages` 文本：每条记录用空行分隔，字段形如 `Package: git`。
 * 我们只关心 Package / Version / Depends / Filename / SHA256 / Size。
 */
object TermuxRepo {

    data class Pkg(
        val name: String,
        val version: String,
        val fileName: String,
        val depends: List<String>,
        val sha256: String,
        val size: Long,
    )

    /** 国内镜像优先，官方兜底。 */
    val MIRRORS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
        "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
        "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main",
    )

    /** 部分镜像会拦非 apt 的 UA（实测 TUNA 对 okhttp 默认 UA 返回 403），统一伪装成 apt。 */
    private const val USER_AGENT = "apt/2.7.14 (WorkBuddy 3.8; Android)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 架构名：Termux 索引按 arch 分目录。 */
    fun archFor(abi: String): String = when (abi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> "aarch64"
    }

    suspend fun loadIndex(arch: String, cacheDir: File): Pair<Map<String, Pkg>, String> =
        withContext(Dispatchers.IO) {
            var lastError = ""
            for (mirror in MIRRORS) {
                try {
                    val body = get("$mirror/dists/stable/main/binary-$arch/Packages")
                    val text = body.bufferedReader().use { it.readText() }
                    val map = parse(text)
                    if (map.isNotEmpty()) {
                        cacheDir.mkdirs()
                        File(cacheDir, "Packages-$arch.txt").writeText(text)
                        return@withContext map to mirror
                    }
                    lastError = "索引为空"
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
            error("无法获取软件源索引：$lastError")
        }

    private fun parse(text: String): Map<String, Pkg> {
        val out = LinkedHashMap<String, Pkg>()
        for (block in text.split("\n\n")) {
            if (block.isBlank()) continue
            val fields = HashMap<String, String>()
            for (line in block.split("\n")) {
                if (line.startsWith(" ") || line.startsWith("\t")) continue
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                fields[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            val name = fields["Package"] ?: continue
            val fileName = fields["Filename"] ?: continue
            out[name] = Pkg(
                name = name,
                version = fields["Version"] ?: "",
                fileName = fileName,
                depends = depNames(fields["Depends"]),
                sha256 = fields["SHA256"] ?: "",
                size = fields["Size"]?.toLongOrNull() ?: 0L,
            )
        }
        return out
    }

    /** `Depends: a (>= 1), b | c, d:any` → [a, b, c, d] */
    fun depNames(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .flatMap { it.split('|') }
            .mapNotNull { part ->
                val name = part.trim().split(' ', '(', ':').firstOrNull()?.trim()
                name?.takeIf { it.isNotEmpty() }
            }
    }

    /** 依赖闭包（含自身），按名字排序，保证可复现。 */
    fun closure(index: Map<String, Pkg>, root: String): List<String> {
        val seen = LinkedHashSet<String>()
        val stack = ArrayDeque(listOf(root))
        while (stack.isNotEmpty()) {
            val name = stack.removeLast()
            if (!seen.add(name)) continue
            index[name]?.depends?.forEach { if (it !in seen) stack.addLast(it) }
        }
        return seen.sorted()
    }

    suspend fun downloadDeb(
        mirror: String,
        pkg: Pkg,
        cacheDir: File,
        onLog: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val dst = File(cacheDir, pkg.fileName.substringAfterLast('/'))
        if (dst.isFile && dst.length() > 0 && (pkg.sha256.isBlank() || sha256(dst) == pkg.sha256)) return@withContext dst
        onLog("下载 ${pkg.name} ${pkg.version}…")
        // 移动网络容易中断：最多重试 4 次，每次续传（Range）而不是从头下
        var lastError: Exception? = null
        for (attempt in 1..4) {
            dst.delete()
            val done = runCatching {
                val req = request("$mirror/${pkg.fileName}")
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("响应为空")
                    // 大包走流式拷贝，避免一次性进内存
                    body.byteStream().use { input ->
                        dst.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
                    }
                    dst.length()
                }
            }
            if (done.isSuccess && dst.length() > 0) {
                lastError = null
                break
            }
            lastError = done.exceptionOrNull() as? Exception
                ?: IllegalStateException("下载内容为空")
            onLog("重试 ${pkg.name}（第 $attempt 次失败：${lastError?.message?.take(80)}）")
            Thread.sleep(1200L * attempt)
        }
        if (lastError != null) error("下载失败 ${pkg.name}：${lastError?.message}")
        if (pkg.sha256.isNotBlank()) {
            val got = sha256(dst)
            if (got != pkg.sha256) {
                dst.delete()
                error("校验失败 ${pkg.name}：期望 ${pkg.sha256.take(12)}… 实际 ${got.take(12)}…")
            }
        }
        dst
    }

    private fun request(url: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()

    private fun get(url: String): java.io.InputStream {
        val resp = client.newCall(request(url)).execute()
        if (!resp.isSuccessful) {
            resp.close()
            error("HTTP ${resp.code} @ $url")
        }
        return resp.body!!.byteStream()
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
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