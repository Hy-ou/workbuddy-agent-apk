package com.workbuddy2api.panel.dsh

import android.content.Context
import java.io.File

/**
 * 宿主内置的 DSH 插件：`@workbuddy/dsh-mobile`。
 *
 * 为什么需要它：
 * - 内核里的官方 bash 工具执行的是 `spawn("bash", "-c", ...)`，而 Android 没有 bash
 *   （只有 /system/bin/sh = mksh），于是手机上每次调用都失败；
 * - 模型不知道这台手机的真实能力（所有文件访问、Shizuku、npm、工具链前缀），
 *   只能靠猜，猜错就再报错一次。
 *
 * 这个插件给内核补上两样东西：
 * 1. 工具：`wb_sh`（/system/bin/sh，稳定可用）、`wb_env`（环境体检）、
 *    `wb_shizuku`（经回环桥以 uid 2000 执行）、`wb_notify`（系统通知）；
 * 2. 提示词段：一段「Android 环境手册」，把文件权限、POSIX-only、npm/pnpm、
 *    工具链位置、提权边界与排错约定写进 system prompt。
 *
 * 安装位置必须是 `dshhome/profiles/web/node_modules/@workbuddy/dsh-mobile`：
 * DSH 的 loader 用 profile 目录作为 baseUrl 解析 insert 里的插件名，装到内核树里会
 * `ERR_MODULE_NOT_FOUND`（3.6.0 的 notify 插件就是这么失败的）。
 * 插件本身零依赖，只 import node: 内置模块，所以不需要 pnpm 安装、也不需要联网。
 */
object MobilePlugin {

    /** cordis.patch.yml 里的行 id。 */
    const val ID = "wb-mobile"

    /** npm 包名（profile/node_modules 下的目录名）。 */
    const val PACKAGE = "@workbuddy/dsh-mobile"

    private const val ASSET_DIR = "dsh-plugins/wb-mobile"

    /** 随插件一起落地的文件（README 供用户在手机上查看，缺失不致命）。 */
    private val FILES = listOf("package.json", "index.js", "README.md")

    /** 插件包目录（profile 的 node_modules 下）。 */
    fun packageDir(homeDir: File): File =
        File(PluginInstaller.profileDir(homeDir), "node_modules/" + PACKAGE.replace("/", File.separator))

    /**
     * 把插件写入 profile（内容有变化才写），返回是否就绪。
     * 每次启动内核前调用，覆盖安装后自动更新插件代码。
     */
    fun ensure(context: Context, homeDir: File): Boolean = runCatching {
        val dir = packageDir(homeDir).apply { mkdirs() }
        var ready = 0
        for (name in FILES) {
            val bytes = runCatching {
                context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
            }.getOrNull() ?: continue
            val target = File(dir, name)
            val current = if (target.isFile) runCatching { target.readBytes() }.getOrNull() else null
            if (current == null || !current.contentEquals(bytes)) target.writeBytes(bytes)
            ready++
        }
        // index.js 是插件本体，缺了就不要登记（否则 patch 会指向一个空包）
        ready > 0 && File(dir, "index.js").isFile
    }.getOrDefault(false)
}
