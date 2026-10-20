package com.workbuddy2api.panel.dsh

import android.content.Context
import java.io.File

/**
 * 内核里的通知脚本：让 DSH 的 Agent 通过 bash 工具发手机通知。
 *
 * 为什么不是「可执行命令」也不是「DSH 插件」：
 * - App 数据目录里的文件在 Android 10+ 无法 exec（SELinux），所以不能放 shell 脚本；
 * - insert 的插件名由 DSH 解析器从 profile 目录解析，第三方包会报 package not found。
 * 因此提供 node 脚本 + 环境变量：Agent 直接 `node "$WB_NOTIFY" "标题" "正文"`。
 *
 * token 从内核环境 `WB_BRIDGE_TOKEN` 读取（DshManager 启动内核时注入）。
 */
object NotifyCli {

    const val SCRIPT = "notify.js"
    const val ENV_PATH = "WB_NOTIFY"

    private const val JS = """
const args = process.argv.slice(2);
const title = args[0] || "WorkBuddy";
const text = args.slice(1).join(" ") || title;
const token = process.env.WB_BRIDGE_TOKEN || "";
if (!token) {
  console.log(JSON.stringify({ ok: false, error: "WB_BRIDGE_TOKEN missing" }));
  process.exit(1);
}
fetch("http://127.0.0.1:7865/notify", {
  method: "POST",
  headers: { "content-type": "application/json", "x-wb-token": token },
  body: JSON.stringify({ title, text }),
}).then(async (r) => {
  const body = await r.text();
  console.log(body);
  process.exit(r.ok ? 0 : 1);
}).catch((e) => {
  console.log(JSON.stringify({ ok: false, error: String((e && e.message) || e) }));
  process.exit(1);
});
"""

    /** 写入/刷新脚本，返回脚本绝对路径（供注入 WB_NOTIFY）。 */
    fun ensure(context: Context, homeDir: File): File {
        val bin = File(homeDir, "bin").apply { mkdirs() }
        val js = File(bin, SCRIPT)
        runCatching {
            val text = JS.trimIndent() + "\n"
            if (!js.isFile || js.readText() != text) js.writeText(text)
            // 清掉旧版本尝试过的 shell 包装（无法 exec）
            File(bin, "notify").delete()
            File(bin, ".wb-bridge-token").delete()
        }
        return js
    }
}