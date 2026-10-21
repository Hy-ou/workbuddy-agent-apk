package com.workbuddy2api.panel.dsh

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 社区插件安装：与 DSH 官方行为一致 —— 在 profile 目录里用 pnpm 安装。
 *
 * 两个坑（都是真机踩出来的）：
 * 1. 不能装到内核树（`/sdcard/WorkBuddy/dsh/...`）：npm/pnpm 的原子重命名会被 FUSE 拒绝
 *    （`The operation was rejected by your operating system`）。
 * 2. DSH 只从 profile 目录（`dshhome/profiles/web`）解析插件包名，装到别处会
 *    `ERR_MODULE_NOT_FOUND ... imported from profiles/web`。
 *
 * 命令行工具（扩展中心）仍装到 `home/tools`（应用私有目录，npm 可正常写入）。
 */
object PluginInstaller {

    private const val REGISTRY = "https://registry.npmmirror.com"
    private val SAFE_PKG = Regex("^@?[a-zA-Z0-9][a-zA-Z0-9._-]*(/[a-zA-Z0-9][a-zA-Z0-9._-]*)?(@[a-zA-Z0-9._-]+)?$")

    data class Result(val ok: Boolean, val output: String)

    fun profileDir(homeDir: File): File = File(homeDir, "dshhome/profiles/web")

    private fun node(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libdshnode.so")
    /** 优先纯 JS 入口：pnpm>=9 自带的 @pnpm/exe 原生二进制无法在应用数据目录执行。 */
    private fun pnpmEntry(homeDir: File): File {
        val bundled = File(homeDir, "pnpm10/bin/pnpm.cjs")
        if (bundled.isFile) return bundled
        return File(homeDir, "pnpmjs/node_modules/pnpm/bin/pnpm.cjs")
    }
    private fun npmCli(homeDir: File) = File(homeDir, "npm/bin/npm-cli.js")

    fun npmAvailable(context: Context, homeDir: File): Boolean =
        node(context).isFile && (pnpmEntry(homeDir).isFile || npmCli(homeDir).isFile)

    fun pluginInstalled(homeDir: File, pkg: String): Boolean =
        File(profileDir(homeDir), "node_modules/" + pkg.replace("/", File.separator)).isDirectory

    fun toolInstalled(homeDir: File, pkg: String): Boolean =
        File(homeDir, "tools/node_modules/" + pkg.replace("/", File.separator)).isDirectory

    /** 社区插件：`pnpm add|remove <pkg>`，工作目录是 profile。 */
    suspend fun plugin(
        context: Context,
        homeDir: File,
        pkg: String,
        action: String,
    ): Result = withContext(Dispatchers.IO) {
        if (!SAFE_PKG.matches(pkg)) return@withContext Result(false, "包名不合法：$pkg")
        val dir = profileDir(homeDir)
        if (!dir.isDirectory) return@withContext Result(false, "profile 目录不存在：${dir.name}（先启动一次内核）")
        val entry = pnpmEntry(homeDir)
        val usePnpm = entry.isFile
        if (usePnpm) alignStoreDir(context, homeDir)
        val cmd = buildList {
            add(node(context).absolutePath)
            if (usePnpm) {
                add(entry.absolutePath)
                add(if (action == "uninstall") "remove" else "add")
                add(pkg)
                // profile 是 pnpm workspace 根：必须显式 -w，否则 pnpm 拒绝
                add("--workspace-root")
                add("--reporter=append-only")
            } else {
                add(npmCli(homeDir).absolutePath)
                add(if (action == "uninstall") "uninstall" else "install")
                add(pkg)
                add("--no-audit"); add("--no-fund"); add("--loglevel=error")
            }
            // pnpm remove 不接受 --registry（Unknown option），只有安装时需要指定源
            if (action != "uninstall") add("--registry=$REGISTRY")
        }
        runCommand(context, homeDir, dir, cmd, 240_000)
    }

    /** 扩展中心 CLI 工具：装到 home/tools（私有目录）。 */
    suspend fun tool(
        context: Context,
        homeDir: File,
        pkg: String,
        action: String,
    ): Result = withContext(Dispatchers.IO) {
        if (!SAFE_PKG.matches(pkg)) return@withContext Result(false, "包名不合法：$pkg")
        val target = File(homeDir, "tools").apply { mkdirs() }
        val pkgJson = File(target, "package.json")
        if (!pkgJson.isFile) pkgJson.writeText("""{"name":"wb-tools","private":true}""")
        val cmd = listOf(
            node(context).absolutePath,
            npmCli(homeDir).absolutePath,
            if (action == "uninstall") "uninstall" else "install",
            pkg,
            "--prefix", target.absolutePath,
            "--registry=$REGISTRY",
            "--no-audit", "--no-fund", "--omit=dev", "--loglevel=error",
        )
        runCommand(context, homeDir, target, cmd, 240_000)
    }

    /**
     * pnpm 会检查 node_modules/.modules.yaml 里记录的 storeDir 是否与当前版本一致，
     * 不一致直接报 ERR_PNPM_UNEXPECTED_STORE 并拒绝安装。profile 最初由别的 pnpm 版本装过，
     * 这里把记录改成当前 pnpm 期望的位置（并建好目录），让 add/remove 能跑。
     */
    internal fun alignStoreDir(context: Context, homeDir: File) {
        val modules = File(profileDir(homeDir), "node_modules/.modules.yaml")
        if (!modules.isFile) return
        val store = File(context.filesDir, ".local/share/pnpm/store/v10")
        runCatching { store.mkdirs() }
        runCatching {
            val text = modules.readText()
            val updated = text.replace(
                Regex("\"storeDir\":\\s*\"[^\"]*\""),
                "\"storeDir\": \"" + store.absolutePath + "\"",
            )
            if (updated != text) modules.writeText(updated)
        }
    }

    private fun runCommand(
        context: Context,
        homeDir: File,
        workDir: File,
        cmd: List<String>,
        timeoutMs: Long,
    ): Result {
        val env = HashMap<String, String>()
        env["PATH"] = listOf(
            File(homeDir, "bin").absolutePath,
            context.applicationInfo.nativeLibraryDir,
            "/system/bin", "/system/xbin",
        ).joinToString(":")
        env["HOME"] = context.filesDir.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        env["WB_NODE"] = File(context.applicationInfo.nativeLibraryDir, "libdshnode.so").absolutePath
        env["WB_DSH_HOME"] = homeDir.absolutePath
        env["npm_config_cache"] = File(homeDir, "npm-cache").absolutePath
        env["npm_config_update_notifier"] = "false"
        env["npm_config_fund"] = "false"
        env["npm_config_audit"] = "false"
        return runCatching {
            val pb = ProcessBuilder(cmd)
            pb.directory(workDir)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            }.apply { isDaemon = true; start() }
            val deadline = System.currentTimeMillis() + timeoutMs
            var code = -1
            while (System.currentTimeMillis() < deadline) {
                code = runCatching { p.exitValue() }.getOrDefault(-1)
                if (code >= 0) break
                Thread.sleep(150)
            }
            if (code < 0) runCatching { p.destroy() }
            reader.join(1000)
            Result(code == 0, out.toString().trim().takeLast(2000).ifBlank { "exit=$code" })
        }.getOrElse { Result(false, it.message ?: "执行失败") }
    }
}
