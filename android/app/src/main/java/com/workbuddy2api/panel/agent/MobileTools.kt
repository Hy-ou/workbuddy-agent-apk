package com.workbuddy2api.panel.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.workbuddy2api.panel.data.WbJson
import com.workbuddy2api.panel.vdisplay.VirtualScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Agent 的「手机操作」工具集合。
 *
 * 模型不需要 Function Calling 能力：它的回复里如果出现一段 json 围栏，
 * 形如 `{"tool":"tap","text":"登录"}`，界面就把整段 JSON 交给 [execute] 执行，
 * 再把返回的 `{"ok":true,"result":"..."}` 作为 role=tool 的消息喂回模型。
 * [TOOL_PROMPT_ZH] 就是注入到 system prompt 里的协议说明。
 *
 * 约定：除协程取消外，[execute] 永远不会抛异常；所有失败都返回
 * `{"ok":false,"error":"中文原因"}`，界面可以直接展示。
 */
object MobileTools {

    /** 手机操作协议说明；开启手机操作时追加到 system prompt 末尾。 */
    const val TOOL_PROMPT_ZH: String = """
你现在可以使用「手机操作」工具，代替用户操作这台 Android 手机的界面。

【何时使用】只有用户明确要求「帮我点 / 帮我打开 / 帮我在手机上操作」时才使用；普通问答不要调用工具。

【调用格式】需要操作时，回复里只输出一个 json 代码块，不要附加任何解释文字：
```json
{"tool":"screen"}
```

【可用工具】
1. {"tool":"screen"} —— 读取当前界面：前台应用包名 + 可见控件文本（带中心坐标）。操作前先看一眼界面。
2. {"tool":"tap","text":"登录"} —— 按文字点击控件；找不到文字时用坐标点击 {"tool":"tap","x":540,"y":1200}。
3. {"tool":"type","text":"你好"} —— 在已聚焦的输入框里输入文字（先 tap 输入框，再 type）。
4. {"tool":"scroll","dir":"down"} —— 滚动当前界面，dir 取 down 或 up。
5. {"tool":"back"} —— 返回上一步；{"tool":"home"} —— 回到桌面。
6. {"tool":"notify","title":"任务完成","text":"已为你整理好 3 个文件"} —— 发一条通知栏提醒（任务完成或需要用户关注时使用）。
7. {"tool":"shell","cmd":"pm list packages | head"} —— 以 shell 权限执行系统命令（需用户安装并授权 Shizuku；可装应用、截屏、模拟输入、改设置）。

【虚拟屏（不打扰用户当前屏幕）】
需要点击/输入 App 界面时，默认先开虚拟屏在后台操作，真机留给用户；只有用户明确说「操作我现在这个屏幕」才用真机工具。
8. {"tool":"vdisplay","action":"start"} —— 创建虚拟屏（可带 width/height/dpi，默认 720x1280/320）；action 取 start / stop / status。
9. {"tool":"vlaunch","package":"com.tencent.mm"} —— 在虚拟屏启动一个已安装的 App（包名可先用 shell 的 pm list packages 查）。
10. {"tool":"vscreen"} —— 截取虚拟屏当前画面，返回 PNG 路径与分辨率。
11. {"tool":"vtap","x":360,"y":640} —— 在虚拟屏点击坐标（坐标以虚拟屏分辨率为准）。
12. {"tool":"vtype","text":"你好"} —— 在虚拟屏当前输入框输入文字（中文走剪贴板粘贴，个别 App 可能不生效）。
13. {"tool":"vswipe","x1":360,"y1":1000,"x2":360,"y2":400} / {"tool":"vkey","code":"back"} —— 滑动 / 按键（back/home/enter/recents/menu）。
虚拟屏与真机共用同一套应用数据；单实例 App 可能把窗口从真机移到虚拟屏，操作前先告诉用户。
虚拟屏不可用时（Shizuku 未授权 / Android 10 以下）改用真机工具（screen/tap/…），不要卡住。
任务结束后询问用户是否 {"tool":"vdisplay","action":"stop"} 关闭虚拟屏。

【规则】
- 每次只输出一个工具调用，等系统返回 {"ok":true,"result":"..."} 或 {"ok":false,"error":"..."} 后再决定下一步。
- 失败时先 screen 重新确认界面，不要盲目重复同样的调用；连续失败两次就停下来询问用户。
- 涉及支付、删除、发送消息等不可逆操作，必须先向用户确认并得到同意。
- 任务完成后用中文简要说明做了哪些操作，不要再输出工具调用。
"""

    /** 无障碍服务是否已连接（未授权时为 false）。 */
    fun isReady(context: Context): Boolean = WbAccessibilityService.instance != null

    /**
     * 工具模式是否有任一可用能力：
     * 无障碍（真机界面操作）或 Shizuku（虚拟屏、系统命令）。
     */
    fun toolsAvailable(context: Context): Boolean = isReady(context) || VirtualScreen.ready()

    /** 跳转系统「无障碍」设置页，让用户手动开启本应用的服务（失败时退到系统设置首页）。 */
    fun openSettings(context: Context) {
        val app = context.applicationContext
        val direct = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { app.startActivity(direct) }.isSuccess) return
        // 个别定制 ROM 没有无障碍直链，退回系统设置首页
        runCatching {
            app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * 解析并执行一条工具指令。
     *
     * 输入可以是裸 JSON，也可以是从模型回复里直接截出来的 ```json 围栏（会自动截取首尾大括号）。
     * 返回紧凑 JSON 字符串：`{"ok":true,"result":"..."}` 或 `{"ok":false,"error":"..."}`。
     */
    suspend fun execute(context: Context, json: String): String = try {
        runCommand(context, json)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        failure(e.message?.takeIf { it.isNotBlank() } ?: "执行失败（${e.javaClass.simpleName}）")
    }

    // ── 内部实现 ──────────────────────────────────────────────────────

    private suspend fun runCommand(context: Context, json: String): String = withContext(Dispatchers.IO) {
        val command = parseCommand(json)
            ?: return@withContext failure("无法解析工具指令，请只输出 {\"tool\":\"...\"} 形式的 JSON")
        val tool = command.string("tool")?.lowercase()
            ?: return@withContext failure("指令缺少 tool 字段")
        // 不依赖无障碍的工具先处理
        when (tool) {
            "notify", "通知" -> return@withContext notify(context, command)
            "shell", "命令" -> return@withContext shell(command)
            "vdisplay", "虚拟屏" -> return@withContext vdisplay(context, command)
            "vlaunch", "虚拟启动" -> return@withContext vlaunch(context, command)
            "vscreen", "虚拟截图" -> return@withContext vscreen(context)
            "vtap", "虚拟点击" -> return@withContext vtap(context, command)
            "vswipe", "虚拟滑动" -> return@withContext vswipe(context, command)
            "vtype", "虚拟输入" -> return@withContext vtype(context, command)
            "vkey", "虚拟按键" -> return@withContext vkey(context, command)
        }
        if (!isReady(context)) {
            return@withContext failure("无障碍服务未连接：请到「设置 → 无障碍」里开启 WorkBuddy 助手")
        }
        val service = WbAccessibilityService.instance
            ?: return@withContext failure("无障碍服务未连接：请到「设置 → 无障碍」里开启 WorkBuddy 助手")
        when (tool) {
            "screen", "截图", "界面" -> success(service.dumpScreen())
            "tap", "click", "点击" -> tap(service, command)
            "type", "input", "输入" -> type(service, command)
            "scroll", "滚动" -> scroll(service, command)
            "back", "返回" -> if (service.pressBack()) success("已返回上一页") else failure("返回操作失败")
            "home", "桌面" -> if (service.pressHome()) success("已回到桌面") else failure("回到桌面失败")
            "notify", "通知" -> notify(context, command)
            "shell", "命令" -> shell(command)
            else -> failure("未知工具：$tool（可用：screen / tap / type / scroll / back / home / notify / shell）")
        }
    }

    /** 发通知：不依赖无障碍，也不需要 Shizuku。 */
    private fun notify(context: Context, command: JsonObject): String {
        val title = command.string("title") ?: "WorkBuddy"
        val text = command.string("text") ?: command.string("body") ?: ""
        if (text.isBlank()) return failure("notify 需要 text 字段（通知正文）")
        val ok = com.workbuddy2api.panel.util.NotificationCenter.post(context, title, text)
        return if (ok) success("已发送通知：$title") else failure("通知发送失败：请检查通知权限是否已开启")
    }

    /** shell 工具：经 Shizuku 以系统 shell 身份执行命令。 */
    private suspend fun shell(command: JsonObject): String {
        val cmd = command.string("cmd") ?: command.string("command")
        if (cmd.isNullOrBlank()) return failure("shell 需要 cmd 字段（要执行的命令）")
        if (cmd.length > 4000) return failure("命令过长（上限 4000 字符）")
        return try {
            val r = com.workbuddy2api.panel.util.ShizukuShell.exec(cmd)
            if (r.code == 0) success(r.output.ifBlank { "（无输出）" })
            else failure("命令退出码 ${r.code}：${r.output.take(2000)}")
        } catch (e: Exception) {
            failure(e.message ?: "Shizuku 执行失败")
        }
    }

    // ── 虚拟屏工具（不依赖无障碍，只要求 Shizuku） ─────────────────────

    private suspend fun vdisplay(context: Context, command: JsonObject): String {
        val action = command.string("action")?.lowercase() ?: "status"
        return when (action) {
            "start", "开启", "创建" -> {
                val w = (command.int("width") ?: command.int("w") ?: 720).coerceIn(240, 2560)
                val h = (command.int("height") ?: command.int("h") ?: 1280).coerceIn(240, 2560)
                val dpi = (command.int("dpi") ?: 320).coerceIn(120, 640)
                when (val st = VirtualScreen.start(context, w, h, dpi)) {
                    is VirtualScreen.State.On -> success(
                        "虚拟屏已开启：displayId=${st.info.displayId}，" +
                            "${st.info.width}x${st.info.height}@${st.info.densityDpi}dpi",
                    )
                    is VirtualScreen.State.Failed -> failure(st.message)
                    is VirtualScreen.State.Unsupported -> failure("这台设备不支持虚拟屏（需要 Android 10+）")
                    else -> failure("虚拟屏启动失败")
                }
            }
            "stop", "关闭" -> when (val st = VirtualScreen.stop(context)) {
                is VirtualScreen.State.Off -> success("虚拟屏已关闭，系统显示设置已恢复")
                is VirtualScreen.State.Failed -> failure(st.message)
                else -> failure("关闭虚拟屏失败")
            }
            else -> when (val st = VirtualScreen.refresh(context)) {
                is VirtualScreen.State.On -> success(
                    "虚拟屏运行中：displayId=${st.info.displayId}，" +
                        "${st.info.width}x${st.info.height}@${st.info.densityDpi}dpi",
                )
                is VirtualScreen.State.Failed -> failure(st.message)
                is VirtualScreen.State.Unsupported -> failure("这台设备不支持虚拟屏（需要 Android 10+）")
                else -> failure("虚拟屏未开启：先调用 {\"tool\":\"vdisplay\",\"action\":\"start\"}")
            }
        }
    }

    private suspend fun vlaunch(context: Context, command: JsonObject): String {
        val pkg = command.string("package") ?: command.string("pkg") ?: command.string("app")
            ?: return failure("vlaunch 需要 package（应用包名）")
        VirtualScreen.refresh(context)
        return VirtualScreen.launch(context, pkg).fold(
            { success(it) },
            { failure(it.message ?: "启动失败") },
        )
    }

    private suspend fun vscreen(context: Context): String {
        VirtualScreen.refresh(context)
        val shot = VirtualScreen.shot(context)
            ?: return failure("虚拟屏截图失败：确认虚拟屏已开启且 Shizuku 已授权")
        return success("虚拟屏截图：${shot.path}（${shot.width}x${shot.height}）")
    }

    private suspend fun vtap(context: Context, command: JsonObject): String {
        val x = command.int("x") ?: return failure("vtap 需要 x 坐标")
        val y = command.int("y") ?: return failure("vtap 需要 y 坐标")
        VirtualScreen.refresh(context)
        return VirtualScreen.tap(context, x, y).fold(
            { success("已在虚拟屏点击 ($x, $y)") },
            { failure(it.message ?: "点击失败") },
        )
    }

    private suspend fun vswipe(context: Context, command: JsonObject): String {
        val x1 = command.int("x1") ?: return failure("vswipe 需要 x1")
        val y1 = command.int("y1") ?: return failure("vswipe 需要 y1")
        val x2 = command.int("x2") ?: return failure("vswipe 需要 x2")
        val y2 = command.int("y2") ?: return failure("vswipe 需要 y2")
        val ms = command.int("durationMs") ?: 300
        VirtualScreen.refresh(context)
        return VirtualScreen.swipe(context, x1, y1, x2, y2, ms).fold(
            { success("已在虚拟屏滑动 ($x1,$y1) → ($x2,$y2)") },
            { failure(it.message ?: "滑动失败") },
        )
    }

    private suspend fun vtype(context: Context, command: JsonObject): String {
        val text = command.string("text") ?: return failure("vtype 需要 text")
        VirtualScreen.refresh(context)
        return VirtualScreen.text(context, text).fold(
            { success("已在虚拟屏输入：$text") },
            { failure(it.message ?: "输入失败") },
        )
    }

    private suspend fun vkey(context: Context, command: JsonObject): String {
        val code = command.int("code") ?: when (command.string("code")?.lowercase()) {
            "back", "返回" -> 4
            "home", "桌面" -> 3
            "enter", "回车" -> 66
            "recents", "最近任务" -> 187
            "menu" -> 82
            else -> null
        } ?: return failure("vkey 需要 code（4/3/66/187/82 或 back/home/enter/recents/menu）")
        VirtualScreen.refresh(context)
        return VirtualScreen.key(context, code).fold(
            { success("已发送按键 $code") },
            { failure(it.message ?: "按键失败") },
        )
    }

    private fun tap(service: WbAccessibilityService, command: JsonObject): String {
        val text = command.string("text")
        if (!text.isNullOrBlank()) {
            return if (service.clickText(text)) success("已点击「$text」")
            else failure("当前界面上没有找到「$text」，请先 screen 确认")
        }
        val x = command.int("x")
        val y = command.int("y")
        if (x != null && y != null) {
            return if (service.clickPoint(x, y)) success("已点击坐标 ($x, $y)")
            else failure("坐标 ($x, $y) 处没有可点击的控件")
        }
        return failure("tap 需要 text，或者 x 与 y 坐标")
    }

    private fun type(service: WbAccessibilityService, command: JsonObject): String {
        val text = command.string("text").orEmpty()
        if (text.isEmpty()) return failure("type 需要 text 字段")
        return if (service.typeText(text)) success("已输入：$text")
        else failure("没有找到可输入的输入框，请先 tap 输入框再 type")
    }

    private fun scroll(service: WbAccessibilityService, command: JsonObject): String {
        val dir = command.string("dir")?.lowercase().orEmpty()
        val down = dir.isEmpty() || dir == "down" || dir == "forward" || dir == "下" || dir == "向下"
        return if (down) {
            if (service.scrollForward()) success("已向下滚动") else failure("当前界面无法向下滚动")
        } else {
            if (service.scrollBackward()) success("已向上滚动") else failure("当前界面无法向上滚动")
        }
    }

    /** 从模型输出里截取第一段 JSON 对象（容忍代码围栏与前后解释文字）。 */
    private fun parseCommand(raw: String): JsonObject? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { WbJson.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(name: String): String? {
        val value = this[name] as? JsonPrimitive ?: return null
        return value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 坐标允许写成 540 或 540.0，统一收成 Int。 */
    private fun JsonObject.int(name: String): Int? {
        val value = this[name] as? JsonPrimitive ?: return null
        val raw = value.contentOrNull?.trim() ?: return null
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
    }

    private fun success(result: String): String = buildJsonObject {
        put("ok", true)
        put("result", result)
    }.toString()

    private fun failure(error: String): String = buildJsonObject {
        put("ok", false)
        put("error", error)
    }.toString()
}
