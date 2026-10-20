package com.workbuddy2api.panel.dsh

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 扩展中心：安装 Termux 系统工具链（Python/Go/Rust/Clang/JDK/Git/…）。
 *
 * 重定位原理（实测得出）：
 * - Termux 二进制的 INTERP 是 `/system/bin/linker64`（Bionic），本身可运行；
 * - 但 DT_RUNPATH 写死 `/data/data/com.termux/files/usr/lib`，且若干脚本/配置里也有该前缀；
 * - 因此安装时对每个 ELF 跑 `patchelf --set-rpath <我们的 lib>`，文本文件做前缀替换。
 *
 * 所有包解到 `<home>/toolchain/usr`，其 `bin` 已在内核 PATH 中。
 */
object Toolchain {

    data class Item(val id: String, val label: String, val pkg: String, val desc: String)

    /** 参考项目的 19 项扩展（Termux 包名）。 */
    val CATALOG: List<Item> = listOf(
        Item("python", "Python 3", "python", "脚本与数据处理"),
        Item("golang", "Go", "golang", "编译 Go 程序"),
        Item("rust", "Rust", "rust", "cargo 工具链"),
        Item("clang", "Clang", "clang", "C/C++ 编译器"),
        Item("openjdk", "OpenJDK 17", "openjdk-17", "Java 运行时与 javac"),
        Item("git", "Git", "git", "版本控制"),
        Item("ruby", "Ruby", "ruby", "Ruby 解释器"),
        Item("php", "PHP", "php", "PHP 解释器"),
        Item("perl", "Perl", "perl", "Perl 解释器"),
        Item("lua", "Lua 5.4", "lua54", "轻量脚本语言"),
        Item("sqlite", "SQLite", "sqlite", "数据库命令行"),
        Item("ffmpeg", "FFmpeg", "ffmpeg", "音视频处理"),
        Item("imagemagick", "ImageMagick", "imagemagick", "图像处理"),
        Item("openssh", "OpenSSH", "openssh", "ssh / scp / sshd"),
        Item("adb", "ADB", "android-tools", "adb / fastboot"),
        Item("vim", "Vim", "vim", "终端编辑器"),
        Item("nano", "nano", "nano", "轻量编辑器"),
        Item("tmux", "tmux", "tmux", "终端复用"),
        Item("make", "make", "make", "构建工具"),
    )

    fun rootDir(homeDir: File): File = File(homeDir, "toolchain")
    fun usrDir(homeDir: File): File = File(rootDir(homeDir), "usr")
    fun libDir(homeDir: File): File = File(usrDir(homeDir), "lib")
    fun binDir(homeDir: File): File = File(usrDir(homeDir), "bin")

    private fun marker(homeDir: File, id: String) = File(rootDir(homeDir), ".installed-$id")

    fun installed(homeDir: File, id: String): Boolean = marker(homeDir, id).isFile

    fun installedIds(homeDir: File): List<String> =
        rootDir(homeDir).listFiles()
            ?.filter { it.name.startsWith(".installed-") }
            ?.map { it.name.removePrefix(".installed-") }
            ?: emptyList()

    data class Result(val ok: Boolean, val message: String)

    suspend fun install(
        context: Context,
        homeDir: File,
        id: String,
        cacheDir: File,
        onLog: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val item = CATALOG.firstOrNull { it.id == id } ?: return@withContext Result(false, "未知扩展：$id")
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val arch = TermuxRepo.archFor(abi)
        val usr = usrDir(homeDir).apply { mkdirs() }
        try {
            onLog("获取软件源索引（$arch）…")
            val (index, mirror) = TermuxRepo.loadIndex(arch, cacheDir)
            onLog("软件源：$mirror")
            val root = item.pkg
            if (root !in index) return@withContext Result(false, "软件源里没有 $root（架构 $arch）")
            val pkgs = TermuxRepo.closure(index, root)
            val missing = pkgs.filter { it !in index }
            if (missing.isNotEmpty()) return@withContext Result(false, "缺少包：${missing.joinToString()}")

            val debCache = File(cacheDir, "debs-$arch")
            val patchelf = File(context.applicationInfo.nativeLibraryDir, "libwbpatchelf.so")
            var elfCount = 0
            var textCount = 0

            for (name in pkgs) {
                val pkg = index.getValue(name)
                val deb = TermuxRepo.downloadDeb(mirror, pkg, debCache) { onLog(it) }
                val stats = DebExtractor.extract(deb, usr) { onLog(it) }
                onLog("解包 $name：${stats.files} 文件 / ${stats.links} 链接")
            }

            onLog("重定位（patchelf）…")
            val newPrefix = usr.absolutePath
            usr.walkTopDown().filter { it.isFile }.forEach { f ->
                when {
                    isElf(f) -> {
                        if (patchelf.isFile) {
                            runCatching { setRpath(patchelf, f, libDir(homeDir).absolutePath) }
                            elfCount++
                        }
                    }
                    isText(f) -> {
                        val text = f.readText()
                        if (text.contains(DebExtractor.TERMUX_PREFIX)) {
                            f.writeText(text.replace(DebExtractor.TERMUX_PREFIX, newPrefix))
                            textCount++
                        }
                    }
                }
            }
            onLog("完成：$elfCount 个 ELF 改 RUNPATH，$textCount 个文本改前缀")

            marker(homeDir, id).apply { parentFile?.mkdirs() }.writeText(
                "${item.pkg} ${index.getValue(root).version}\n",
            )
            Result(true, "${item.label} 安装完成（${pkgs.size} 个包）")
        } catch (e: Exception) {
            Result(false, e.message ?: "安装失败（${e.javaClass.simpleName}）")
        }
    }

    fun uninstall(homeDir: File, id: String): Result {
        val m = marker(homeDir, id)
        if (!m.isFile) return Result(false, "未安装：$id")
        m.delete()
        return Result(true, "已标记卸载（共享库保留，供其它扩展使用）")
    }

    private fun isElf(f: File): Boolean = runCatching {
        if (f.length() < 4) false
        else f.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
                b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun isText(f: File): Boolean = runCatching {
        if (f.length() > 2L * 1024 * 1024) return false
        f.inputStream().use { s ->
            val b = ByteArray(4096)
            val n = s.read(b)
            if (n <= 0) return false
            for (i in 0 until n) {
                val v = b[i].toInt()
                if (v == 0) return false
            }
            true
        }
    }.getOrDefault(false)

    private fun setRpath(patchelf: File, target: File, libPath: String) {
        val p = ProcessBuilder(
            patchelf.absolutePath, "--set-rpath", libPath, target.absolutePath,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0) error("patchelf 失败 ${target.name}: ${out.take(200)}")
    }
}