package com.workbuddy2api.panel.dsh

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 用内置 npm（payload 里的 9 MB 精简 npm）+ 内核 node 运行时安装/卸载插件与命令行工具。
 *
 * 安装位置：
 * - 社区插件 → `kernel/dshroot/lib`（npm 装到其 node_modules，DSH 可直接 import）
 * - 扩展中心 CLI → `home/tools`（其 .bin 已加入内核 PATH）
 */
object PluginInstaller {

    private const val REGISTRY = "https://registry.npmmirror.com"
    private val SAFE_PKG = Regex("^@?[a-zA-Z0-9][a-zA-Z0-9._-]*(/[a-zA-Z0-9][a-zA-Z0-9._-]*)?(@[a-zA-Z0-9._-]+)?$")

    data class Result(val ok: Boolean, val output: String)

    private fun node(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libdshnode.so")
    private fun npmCli(homeDir: File) = File(homeDir, "npm/bin/npm-cli.js")

    fun npmAvailable(context: Context, homeDir: File): Boolean =
        node(context).isFile && npmCli(homeDir).isFile

    suspend fun installPlugin(context: Context, kernelDir: File, homeDir: File, pkg: String): Result =
        run(context, kernelDir, homeDir, pkg, target = File(kernelDir, "dshroot/lib/node_modules/@deepseek-ai/dsh"), action = "install")

    suspend fun uninstallPlugin(context: Context, kernelDir: File, homeDir: File, pkg: String): Result =
        run(context, kernelDir, homeDir, pkg, target = File(kernelDir, "dshroot/lib/node_modules/@deepseek-ai/dsh"), action = "uninstall")

    /** 扩展中心：把 CLI 工具装到 home/tools（PATH 里已包含 node_modules/.bin）。 */
    suspend fun installTool(context: Context, kernelDir: File, homeDir: File, pkg: String): Result =
        run(context, kernelDir, homeDir, pkg, target = File(homeDir, "tools"), action = "install")

    suspend fun uninstallTool(context: Context, kernelDir: File, homeDir: File, pkg: String): Result =
        run(context, kernelDir, homeDir, pkg, target = File(homeDir, "tools"), action = "uninstall")

    fun toolInstalled(homeDir: File, pkg: String): Boolean {
        val dir = File(homeDir, "tools/node_modules/" + pkg.replace("/", File.separator))
        return dir.isDirectory
    }

    fun pluginInstalled(kernelDir: File, pkg: String): Boolean {
        val dir = File(kernelDir, "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/" + pkg.replace("/", File.separator))
        return dir.isDirectory
    }

    private suspend fun run(
        context: Context,
        kernelDir: File,
        homeDir: File,
        pkg: String,
        target: File,
        action: String,
    ): Result = withContext(Dispatchers.IO) {
        if (!SAFE_PKG.matches(pkg)) return@withContext Result(false, "包名不合法：$pkg")
        if (!npmAvailable(context, homeDir)) {
            return@withContext Result(false, "内核未内置 npm（请升级到 3.6.0+ 并重新解压内核）")
        }
        // npm 需要目标目录里有 package.json（或至少可写）
        target.mkdirs()
        val pkgJson = File(target, "package.json")
        if (!pkgJson.isFile) {
            pkgJson.writeText(
                """{"name":"wb-${if (target.name == "tools") "tools" else "kernel"}","private":true}""",
            )
        }
        val cmd = buildList {
            add(node(context).absolutePath)
            add(npmCli(homeDir).absolutePath)
            add(action)
            add(pkg)
            add("--prefix"); add(target.absolutePath)
            add("--registry=$REGISTRY")
            add("--no-audit"); add("--no-fund"); add("--omit=dev"); add("--loglevel=error")
        }
        val env = HashMap<String, String>()
        env["PATH"] = listOf(
            File(kernelDir, "bin").absolutePath,
            File(homeDir, "tools/node_modules/.bin").absolutePath,
            context.applicationInfo.nativeLibraryDir,
            "/system/bin", "/system/xbin",
        ).joinToString(":")
        env["HOME"] = context.filesDir.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["npm_config_cache"] = File(context.filesDir, "npm-cache").absolutePath
        env["npm_config_update_notifier"] = "false"
        env["SSL_CERT_FILE"] = File(homeDir, "runtime/etc/cacert-system.pem")
            .takeIf { it.isFile }?.absolutePath ?: File(homeDir, "runtime/etc/cacert.pem").absolutePath
        runCatching {
            val pb = ProcessBuilder(cmd)
            pb.directory(target)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline && p.isAliveCompat()) Thread.sleep(150)
            val code = runCatching { p.exitValue() }.getOrDefault(-1)
            if (code != 0) p.destroy()
            Result(code == 0, out.takeLast(3000).ifBlank { "exit=$code" })
        }.getOrElse { Result(false, it.message ?: "安装失败") }
    }

    private fun Process.isAliveCompat(): Boolean = try {
        exitValue(); false
    } catch (e: IllegalThreadStateException) {
        true
    }
}
