package com.workbuddy2api.panel.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.workbuddy2api.panel.data.WbJson
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

【规则】
- 每次只输出一个工具调用，等系统返回 {"ok":true,"result":"..."} 或 {"ok":false,"error":"..."} 后再决定下一步。
- 失败时先 screen 重新确认界面，不要盲目重复同样的调用；连续失败两次就停下来询问用户。
- 涉及支付、删除、发送消息等不可逆操作，必须先向用户确认并得到同意。
- 任务完成后用中文简要说明做了哪些操作，不要再输出工具调用。
"""

    /** 无障碍服务是否已连接（未授权时为 false）。 */
    fun isReady(context: Context): Boolean = WbAccessibilityService.instance != null

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
        if (!isReady(context)) {
            return@withContext failure("无障碍服务未连接：请到「设置 → 无障碍」里开启 WorkBuddy 助手")
        }
        val command = parseCommand(json)
            ?: return@withContext failure("无法解析工具指令，请只输出 {\"tool\":\"...\"} 形式的 JSON")
        val tool = command.string("tool")?.lowercase()
            ?: return@withContext failure("指令缺少 tool 字段")
        val service = WbAccessibilityService.instance
            ?: return@withContext failure("无障碍服务未连接：请到「设置 → 无障碍」里开启 WorkBuddy 助手")
        when (tool) {
            "screen", "截图", "界面" -> success(service.dumpScreen())
            "tap", "click", "点击" -> tap(service, command)
            "type", "input", "输入" -> type(service, command)
            "scroll", "滚动" -> scroll(service, command)
            "back", "返回" -> if (service.pressBack()) success("已返回上一页") else failure("返回操作失败")
            "home", "桌面" -> if (service.pressHome()) success("已回到桌面") else failure("回到桌面失败")
            else -> failure("未知工具：$tool（可用：screen / tap / type / scroll / back / home）")
        }
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
