package com.workbuddy2api.panel.dsh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DSH 插件开关。
 *
 * 内核通过 `dshhome/cordis.patch.yml` 决定哪些插件被禁用；本 App 托管该文件
 * （内置策略 + 用户在设置里额外禁用的插件 id）。
 *
 * 插件 id 的经验映射：npm 包 `@deepseek-ai/dsh-foo-bar` → 插件 id `foo-bar`。
 */
object DshPlugins {

    private const val PREFS = "wb_dsh_plugins"
    private const val KEY_DISABLED = "disabled_ids"

    /** 内置策略：这些插件在手机上禁用（详见 pack_dsh.ps1 的托管补丁）。 */
    val BUILTIN_DISABLED = listOf(
        "bash-sandbox",
        "pwsh-sandbox",
        "session-log-download",
        "session-telemetry-otel",
        "office-to-pdf",
    )

    data class Plugin(
        val id: String,
        val packageName: String,
        val version: String,
        val description: String,
        val builtinDisabled: Boolean,
        val userDisabled: Boolean,
    ) {
        val disabled: Boolean get() = builtinDisabled || userDisabled
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun userDisabledIds(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_DISABLED, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
        }.getOrDefault(emptySet())
    }

    fun setUserDisabled(context: Context, id: String, disabled: Boolean) {
        val next = userDisabledIds(context).toMutableSet()
        if (disabled) next.add(id) else next.remove(id)
        val arr = JSONArray()
        next.sorted().forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_DISABLED, arr.toString()).apply()
    }

    /** npm 包名 → 插件 id：`@deepseek-ai/dsh-office-to-pdf` → `office-to-pdf`。 */
    fun idForPackage(name: String): String = name.removePrefix("@deepseek-ai/dsh-")

    /** 扫描内核树里的 `@deepseek-ai/dsh-*` 包，列出可管理的插件。 */
    fun scan(context: Context, kernelDir: File, homeDir: File): List<Plugin> {
        val userSet = userDisabledIds(context)
        val roots = listOf(
            File(kernelDir, "dshroot/lib/node_modules/@deepseek-ai"),
            File(kernelDir, "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"),
        )
        val out = LinkedHashMap<String, Plugin>()
        for (root in roots) {
            val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("dsh-") } ?: continue
            for (dir in dirs) {
                val pkg = File(dir, "package.json")
                if (!pkg.isFile) continue
                val json = runCatching { JSONObject(pkg.readText()) }.getOrNull() ?: continue
                val name = json.optString("name").ifBlank { "@deepseek-ai/${dir.name}" }
                val id = idForPackage(name).ifBlank { dir.name.removePrefix("dsh-") }
                if (out.containsKey(id)) continue
                out[id] = Plugin(
                    id = id,
                    packageName = name,
                    version = json.optString("version"),
                    description = json.optString("description"),
                    builtinDisabled = id in BUILTIN_DISABLED,
                    userDisabled = id in userSet,
                )
            }
        }
        // 只保留「看起来像可插拔插件」的包：排除纯工具库（tool/service 名单也可展示，这里按名称全量保留）
        return out.values.sortedBy { it.id }
    }

    /** 生成托管补丁文件（内置策略 + 用户禁用项）。 */
    fun writePatch(context: Context, homeDir: File): Int {
        val disabled = (BUILTIN_DISABLED + userDisabledIds(context)).distinct()
        val sb = StringBuilder()
        sb.appendLine("# WorkBuddy-managed-patch: v3")
        sb.appendLine("# 由 App 自动生成（设置 → DSH 工具 → 插件开关），手改会在下次启动时被覆盖")
        for (id in disabled) {
            sb.appendLine("- id: $id")
            sb.appendLine("  disabled: true")
        }
        sb.appendLine("- id: permission")
        sb.appendLine("  config:")
        sb.appendLine("    presets:")
        sb.appendLine("      read-only:")
        sb.appendLine("        sandbox: read-only")
        sb.appendLine("        approval: ask")
        sb.appendLine("      workspace-write:")
        sb.appendLine("        sandbox: workspace-write")
        sb.appendLine("        approval: ask")
        sb.appendLine("      danger-full-access:")
        sb.appendLine("        sandbox: danger-full-access")
        sb.appendLine("        approval: never")
        sb.appendLine("    defaultPreset: danger-full-access")
        sb.appendLine("- insert:")
        sb.appendLine("    - id: bash-local")
        sb.appendLine("      name: '@deepseek-ai/dsh-bash-local'")
        sb.appendLine("      config:")
        sb.appendLine("        cwd: !!js (process.env.DSH_WORKSPACE || process.cwd())")
        sb.appendLine("        timeoutMs: 60000")

        val target = File(homeDir, "dshhome/cordis.patch.yml")
        target.parentFile?.mkdirs()
        runCatching { target.writeText(sb.toString()) }
        return disabled.size
    }
}
