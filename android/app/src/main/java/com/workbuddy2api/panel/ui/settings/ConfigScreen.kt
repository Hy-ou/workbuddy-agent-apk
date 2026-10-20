package com.workbuddy2api.panel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbuddy2api.panel.data.ConfigEnvelope
import com.workbuddy2api.panel.data.ConfigSaveResult
import com.workbuddy2api.panel.data.WbHttpException
import com.workbuddy2api.panel.data.WbJson
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.components.ErrorBox
import com.workbuddy2api.panel.ui.components.LoadingBox
import com.workbuddy2api.panel.ui.components.LocalSnackbar
import com.workbuddy2api.panel.ui.components.Mono
import com.workbuddy2api.panel.ui.components.SectionHeader
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbTextField
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.rememberLoader
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.ui.theme.WbDimens
import com.workbuddy2api.panel.util.maskKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/** 美化输出用：只在「高级 JSON」里生成人类可读的缩进文本，不参与网络请求。 */
private val PRETTY_JSON = Json { prettyPrint = true }

/** 表单里某个字段填错时抛出，message 直接展示在保存按钮上方。 */
private class ConfigInputError(message: String) : Exception(message)

// ---------------------------------------------------------------------------
// JSON 小工具：配置是任意深度的对象，这里只做最小必要的读写
// ---------------------------------------------------------------------------

/** 按 `a.b.c` 路径取值；中间任何一层不是对象都返回 null。 */
private fun JsonObject.at(path: String): JsonElement? {
    var current: JsonElement = this
    for (segment in path.split('.')) {
        val obj = current as? JsonObject ?: return null
        current = obj[segment] ?: return null
    }
    return current
}

/** 取字符串值（数字 / 布尔也会转成字面量文本）；缺失或 null 返回 [def]。 */
private fun JsonObject.strAt(path: String, def: String = ""): String =
    (at(path) as? JsonPrimitive)?.contentOrNull ?: def

/** 取布尔值；服务端偶尔返回 `"true"` 字符串，[booleanOrNull] 一并兼容。 */
private fun JsonObject.boolAt(path: String, def: Boolean): Boolean =
    (at(path) as? JsonPrimitive)?.booleanOrNull ?: def

/** 取整型数组（定时小时），转成「9,21」这种可直接编辑的文本。 */
private fun JsonObject.hoursAt(path: String): String =
    (at(path) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        ?.joinToString(",")
        .orEmpty()

/** 深写：`putPath("pool.max_in_flight", x)` 生成 `{pool:{max_in_flight:x}}`，保留其他键。 */
private fun JsonObject.putPath(path: String, value: JsonElement): JsonObject {
    val head = path.substringBefore('.')
    val rest = path.substringAfter('.', "")
    val existing = this[head]
    val child = if (rest.isEmpty()) {
        value
    } else {
        (existing as? JsonObject ?: JsonObject(emptyMap())).putPath(rest, value)
    }
    return JsonObject(this + (head to child))
}

/** 深合并：保存前用「已加载配置 + 表单补丁」生成高级编辑器里的完整预览。 */
private fun deepMerge(base: JsonObject, patch: JsonObject): JsonObject {
    val out = base.toMutableMap()
    for ((k, v) in patch) {
        val old = out[k]
        out[k] = if (old is JsonObject && v is JsonObject) deepMerge(old, v) else v
    }
    return JsonObject(out)
}

private fun prettyJson(element: JsonElement): String =
    PRETTY_JSON.encodeToString(JsonElement.serializer(), element)

/** 解析高级 JSON；非对象 / 语法错误都转成可展示的中文。 */
private fun parseConfigJson(text: String): JsonObject {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) throw ConfigInputError("高级 JSON 不能为空")
    val parsed = runCatching { WbJson.parseToJsonElement(trimmed) }.getOrElse {
        throw ConfigInputError("JSON 解析失败：${it.message?.takeIf { m -> m.isNotBlank() } ?: "格式不正确"}")
    }
    return parsed as? JsonObject ?: throw ConfigInputError("JSON 顶层必须是对象（以 { 开头）")
}

/** 整数字段：并发数这类只接受整数，小数直接提示。 */
private fun intPrimitive(text: String, label: String): JsonPrimitive {
    val t = text.trim()
    if (t.isEmpty()) throw ConfigInputError("$label 不能为空")
    val v = t.toIntOrNull() ?: throw ConfigInputError("$label 必须是整数：$t")
    if (v < 0) throw ConfigInputError("$label 不能为负数")
    return JsonPrimitive(v)
}

/** 数值字段：上限 / 阈值既可能是整数也可能是小数，整数优先保持整数形态。 */
private fun numPrimitive(text: String, label: String): JsonPrimitive {
    val t = text.trim()
    if (t.isEmpty()) throw ConfigInputError("$label 不能为空")
    t.toLongOrNull()?.let { return JsonPrimitive(it) }
    val d = t.toDoubleOrNull() ?: throw ConfigInputError("$label 必须是数字：$t")
    return JsonPrimitive(d)
}

/** 文本字段：时长（如 600s / 2h）原样回传，不做单位换算。 */
private fun textPrimitive(text: String, label: String): JsonPrimitive {
    val t = text.trim()
    if (t.isEmpty()) throw ConfigInputError("$label 不能为空")
    return JsonPrimitive(t)
}

/** 小时列表：逗号 / 顿号 / 空格分隔，每个值必须在 0–23。 */
private fun hoursPrimitive(text: String, label: String): JsonArray {
    val parts = text.split(',', '，', '、', ';', '；', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) throw ConfigInputError("$label 至少填一个小时（0–23）")
    val values = parts.map { part ->
        val v = part.toIntOrNull() ?: throw ConfigInputError("$label 含非法小时：$part")
        if (v < 0 || v > 23) throw ConfigInputError("$label 的小时需在 0–23 之间：$part")
        JsonPrimitive(v)
    }
    return JsonArray(values)
}

/** 把异常转成可直接进 snackbar 的中文文案。 */
private fun configErrorText(e: Throwable): String = when (e) {
    is WbHttpException -> e.message
    is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "无法连接服务器"
    else -> e.message?.takeIf { it.isNotBlank() } ?: "操作失败"
}

// ---------------------------------------------------------------------------
// 屏幕
// ---------------------------------------------------------------------------

/**
 * 服务器配置页：用表单覆盖最常用的字段，用「高级 JSON」兜住长尾。
 *
 * GET 回来的配置是完整快照，POST 是深合并补丁；因此表单只提交自己负责的字段，
 * 高级模式则整份提交（用户显式选择后以 JSON 为准）。
 */
@Composable
fun ConfigScreen(repo: WbRepository, onBack: () -> Unit) {
    val colors = LocalWbColors.current
    val loader = rememberLoader(repo) { repo.config() }
    // 作用域放在外层：保存成功后表单会被重新 key 掉，内层的手势作用域会随之取消，
    // 而「重载 + 弹 snackbar」必须在表单重建后继续执行。
    val scope = rememberCoroutineScope()
    val state = loader.state
    val envelope = (state as? Loadable.Ready<ConfigEnvelope>)?.data

    Column(modifier = Modifier.fillMaxSize()) {
        WbTopBar(
            title = "服务器配置",
            subtitle = envelope?.path?.takeIf { it.isNotBlank() } ?: repo.serverLabel,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = { if (!loader.refreshing) loader.refresh() },
                    enabled = !loader.refreshing,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "重新加载配置",
                        tint = colors.ink2,
                    )
                }
            },
        )

        when {
            state is Loadable.Failed -> ErrorBox(message = state.message, onRetry = { loader.refresh() })
            envelope == null -> LoadingBox()
            envelope.config == null -> ErrorBox(
                message = envelope.error.ifBlank { "服务端返回的配置为空" },
                onRetry = { loader.refresh() },
            )
            // 每次重新加载都会产生新的 ConfigEnvelope 实例，换 key 让表单重置为最新配置。
            else -> key(System.identityHashCode(envelope)) {
                ConfigForm(
                    repo = repo,
                    envelope = envelope,
                    busy = loader.refreshing,
                    scope = scope,
                    onReload = { loader.refresh() },
                )
            }
        }
    }
}

/**
 * 配置表单本体：所有字段都是本地草稿，点「保存配置」才写回服务器。
 *
 * [busy] 为重新加载中；[onReload] 由外层提供，保存成功后触发一次重载以拿到最新配置。
 */
@Composable
private fun ConfigForm(
    repo: WbRepository,
    envelope: ConfigEnvelope,
    busy: Boolean,
    scope: CoroutineScope,
    onReload: () -> Unit,
) {
    val colors = LocalWbColors.current
    val snackbar = LocalSnackbar.current

    val base = remember { envelope.config ?: JsonObject(emptyMap()) }

    // ── 基础 ────────────────────────────────────────────────
    var apiKey by remember { mutableStateOf(base.strAt("api_key")) }

    // ── 任务开关 ────────────────────────────────────────────
    var checkinEnabled by remember { mutableStateOf(base.boolAt("schedule.checkin_enabled", true)) }
    var travelEnabled by remember { mutableStateOf(base.boolAt("schedule.travel_enabled", true)) }
    var activityEnabled by remember { mutableStateOf(base.boolAt("schedule.activity_enabled", true)) }
    var keepaliveEnabled by remember { mutableStateOf(base.boolAt("schedule.keepalive_enabled", true)) }
    var growthEnabled by remember { mutableStateOf(base.boolAt("schedule.growth_enabled", true)) }

    // ── 执行时刻 ────────────────────────────────────────────
    var checkinHours by remember { mutableStateOf(base.hoursAt("schedule.checkin_hours")) }
    var travelHours by remember { mutableStateOf(base.hoursAt("schedule.travel_hours")) }
    var activityHours by remember { mutableStateOf(base.hoursAt("schedule.activity_hours")) }
    var keepaliveHours by remember { mutableStateOf(base.hoursAt("schedule.keepalive_hours")) }
    var growthHours by remember { mutableStateOf(base.hoursAt("schedule.growth_hours")) }

    // ── 调度与冷却 ──────────────────────────────────────────
    var maxInFlight by remember { mutableStateOf(base.strAt("pool.max_in_flight")) }
    var maxInFlightGlobal by remember { mutableStateOf(base.strAt("pool.max_in_flight_global")) }
    var creditFloor by remember { mutableStateOf(base.strAt("pool.credit_floor")) }
    var expiringSoon by remember { mutableStateOf(base.strAt("pool.expiring_soon")) }
    var softRate by remember { mutableStateOf(base.strAt("cooldown.soft_rate")) }
    var softRateMax by remember { mutableStateOf(base.strAt("cooldown.soft_rate_max")) }

    // ── 其他 ────────────────────────────────────────────────
    var stickyEnabled by remember { mutableStateOf(base.boolAt("session_sticky.enabled", true)) }
    var clientInfo by remember { mutableStateOf(base.boolAt("logging.request_client_info", false)) }
    var sanitizeFingerprints by remember {
        mutableStateOf(base.boolAt("features.sanitize_blacklist_fingerprints", false))
    }

    // ── 高级 JSON ───────────────────────────────────────────
    var advancedText by remember { mutableStateOf(prettyJson(base)) }
    var advancedEdited by remember { mutableStateOf(false) }

    var inlineError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val disabled = saving || busy

    /** 把表单字段组装成一份「只含本页字段」的深合并补丁。 */
    fun buildFormPatch(): JsonObject {
        var patch = JsonObject(emptyMap())
        patch = patch.putPath("api_key", JsonPrimitive(apiKey.trim()))

        patch = patch.putPath("schedule.checkin_enabled", JsonPrimitive(checkinEnabled))
        patch = patch.putPath("schedule.travel_enabled", JsonPrimitive(travelEnabled))
        patch = patch.putPath("schedule.activity_enabled", JsonPrimitive(activityEnabled))
        patch = patch.putPath("schedule.keepalive_enabled", JsonPrimitive(keepaliveEnabled))
        patch = patch.putPath("schedule.growth_enabled", JsonPrimitive(growthEnabled))

        patch = patch.putPath("schedule.checkin_hours", hoursPrimitive(checkinHours, "签到时刻"))
        patch = patch.putPath("schedule.travel_hours", hoursPrimitive(travelHours, "旅行时刻"))
        patch = patch.putPath("schedule.activity_hours", hoursPrimitive(activityHours, "活动时刻"))
        patch = patch.putPath("schedule.keepalive_hours", hoursPrimitive(keepaliveHours, "保活时刻"))
        patch = patch.putPath("schedule.growth_hours", hoursPrimitive(growthHours, "成长时刻"))

        patch = patch.putPath("pool.max_in_flight", intPrimitive(maxInFlight, "单账号并发上限"))
        patch = patch.putPath("pool.max_in_flight_global", intPrimitive(maxInFlightGlobal, "全局并发上限"))
        patch = patch.putPath("pool.credit_floor", numPrimitive(creditFloor, "积分保底线"))
        patch = patch.putPath("pool.expiring_soon", textPrimitive(expiringSoon, "即将过期阈值"))
        patch = patch.putPath("cooldown.soft_rate", textPrimitive(softRate, "软限流冷却"))
        patch = patch.putPath("cooldown.soft_rate_max", textPrimitive(softRateMax, "软限流冷却上限"))

        patch = patch.putPath("session_sticky.enabled", JsonPrimitive(stickyEnabled))
        patch = patch.putPath("logging.request_client_info", JsonPrimitive(clientInfo))
        patch = patch.putPath(
            "features.sanitize_blacklist_fingerprints",
            JsonPrimitive(sanitizeFingerprints),
        )
        return patch
    }

    /** 保存：高级模式以 JSON 文本为准，否则用表单补丁。 */
    fun save() {
        // 直接读最新状态，避免快速连点两次都通过旧 composition 的 enabled 判断。
        if (saving || busy) return
        val prepared = runCatching {
            if (advancedEdited) parseConfigJson(advancedText) else buildFormPatch()
        }
        val failed = prepared.exceptionOrNull()
        if (failed != null) {
            inlineError = failed.message ?: "配置格式有误"
            return
        }
        val patch = prepared.getOrThrow()
        inlineError = null
        saving = true
        scope.launch {
            var result: ConfigSaveResult? = null
            var failure: String? = null
            try {
                result = repo.saveConfig(patch)
            } catch (e: Exception) {
                failure = configErrorText(e)
            } finally {
                // 先解除按钮的 loading 态，snackbar 展示（会挂起到自动消失）不阻塞按钮。
                saving = false
            }
            val saved = result
            if (saved == null) {
                snackbar.showSnackbar(failure ?: "保存失败")
                return@launch
            }
            if (!saved.ok) {
                snackbar.showSnackbar(saved.error.ifBlank { "保存失败，请检查配置内容" })
                return@launch
            }
            val restart = saved.restartRequired.orEmpty().filter { it.isNotBlank() }
            // 先重载拿到落盘后的最新配置，再提示结果。
            onReload()
            snackbar.showSnackbar(
                if (restart.isEmpty()) "已保存" else "已保存；重启后生效：" + restart.joinToString("、"),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WbDimens.screenPadding),
    ) {
        Spacer(Modifier.height(4.dp))

        // 安全提示：配置里有明文密钥，必须让用户知道。
        WbCard {
            Text(
                text = "这份配置包含明文密钥",
                style = MaterialTheme.typography.titleSmall,
                color = colors.warn,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "服务器的 api_key、各类 token 都以明文保存在配置里，本页会原样显示（输入框已打码）。" +
                    "请勿截图外传；跨网络访问时务必使用 HTTPS 或内网环境。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )
        }

        Spacer(Modifier.height(WbDimens.gap))

        // ── 基础 ────────────────────────────────────────────────
        SectionHeader(title = "基础")
        WbCard {
            WbTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = "API Key",
                password = true,
                placeholder = "留空表示服务端免鉴权",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Mono(
                text = "当前打码：${maskKey(apiKey)}",
                size = 11.5.sp,
                color = colors.ink3,
            )
        }

        Spacer(Modifier.height(WbDimens.gap))

        // ── 任务开关 ────────────────────────────────────────────
        SectionHeader(title = "任务开关", subtitle = "关闭后调度器不再执行对应任务")
        WbCard {
            SwitchRow(
                title = "每日签到",
                subtitle = "schedule.checkin_enabled",
                checked = checkinEnabled,
                enabled = !saving,
                onChange = { checkinEnabled = it },
            )
            SwitchRow(
                title = "每日旅行",
                subtitle = "schedule.travel_enabled",
                checked = travelEnabled,
                enabled = !saving,
                onChange = { travelEnabled = it },
            )
            SwitchRow(
                title = "活动领取",
                subtitle = "schedule.activity_enabled",
                checked = activityEnabled,
                enabled = !saving,
                onChange = { activityEnabled = it },
            )
            SwitchRow(
                title = "账号保活",
                subtitle = "schedule.keepalive_enabled",
                checked = keepaliveEnabled,
                enabled = !saving,
                onChange = { keepaliveEnabled = it },
            )
            SwitchRow(
                title = "成长任务",
                subtitle = "schedule.growth_enabled",
                checked = growthEnabled,
                enabled = !saving,
                onChange = { growthEnabled = it },
            )
        }

        Spacer(Modifier.height(WbDimens.gap))

        // ── 执行时刻 ────────────────────────────────────────────
        SectionHeader(
            title = "执行时刻",
            subtitle = "24 小时制，多个小时用逗号分隔，例如 9,21",
        )
        WbCard {
            WbTextField(
                value = checkinHours,
                onValueChange = { checkinHours = it },
                label = "签到时刻",
                placeholder = "9,21",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            WbTextField(
                value = travelHours,
                onValueChange = { travelHours = it },
                label = "旅行时刻",
                placeholder = "9,21",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            WbTextField(
                value = activityHours,
                onValueChange = { activityHours = it },
                label = "活动时刻",
                placeholder = "10",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            WbTextField(
                value = keepaliveHours,
                onValueChange = { keepaliveHours = it },
                label = "保活时刻",
                placeholder = "22",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            WbTextField(
                value = growthHours,
                onValueChange = { growthHours = it },
                label = "成长时刻",
                placeholder = "1",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(WbDimens.gap))

        // ── 调度与冷却 ──────────────────────────────────────────
        SectionHeader(title = "调度与冷却", subtitle = "并发、积分保底与限流冷却")
        WbCard {
            WbTextField(
                value = maxInFlight,
                onValueChange = { maxInFlight = it },
                label = "单账号并发上限",
                placeholder = "3",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            WbTextField(
                value = maxInFlightGlobal,
                onValueChange = { maxInFlightGlobal = it },
                label = "全局并发上限",
                placeholder = "2",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            WbTextField(
                value = creditFloor,
                onValueChange = { creditFloor = it },
                label = "积分保底线",
                placeholder = "100",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            WbTextField(
                value = expiringSoon,
                onValueChange = { expiringSoon = it },
                label = "即将过期阈值",
                placeholder = "168h",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            WbTextField(
                value = softRate,
                onValueChange = { softRate = it },
                label = "软限流冷却",
                placeholder = "600s",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            WbTextField(
                value = softRateMax,
                onValueChange = { softRateMax = it },
                label = "软限流冷却上限",
                placeholder = "2h",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(WbDimens.gap))

        // ── 其他 ────────────────────────────────────────────────
        SectionHeader(title = "其他")
        WbCard {
            SwitchRow(
                title = "会话粘滞",
                subtitle = "session_sticky.enabled",
                checked = stickyEnabled,
                enabled = !saving,
                onChange = { stickyEnabled = it },
            )
            SwitchRow(
                title = "记录客户端信息",
                subtitle = "logging.request_client_info",
                checked = clientInfo,
                enabled = !saving,
                onChange = { clientInfo = it },
            )
            SwitchRow(
                title = "清理指纹头",
                subtitle = "features.sanitize_blacklist_fingerprints",
                checked = sanitizeFingerprints,
                enabled = !saving,
                onChange = { sanitizeFingerprints = it },
            )
        }

        Spacer(Modifier.height(WbDimens.gap))

        // ── 高级 JSON ───────────────────────────────────────────
        SectionHeader(title = "高级", subtitle = "直接编辑完整配置（深合并补丁）")
        WbCard {
            Text(
                text = "只改表单里没有的长尾字段时才用这里。一旦手动编辑过，保存时会整份提交这段 JSON，" +
                    "表单里的修改不再生效；改完可点「按表单重新生成」回到表单驱动。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )
            Spacer(Modifier.height(8.dp))
            WbTextField(
                value = advancedText,
                onValueChange = {
                    advancedText = it
                    advancedEdited = true
                },
                label = "完整配置 JSON",
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 340.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (advancedEdited) "已切换为 JSON 优先" else "当前由表单生成补丁",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (advancedEdited) colors.warn else colors.ink3,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WbButton(
                    text = "按表单重新生成",
                    onClick = {
                        val patch = runCatching { buildFormPatch() }.getOrNull()
                        advancedText = prettyJson(if (patch != null) deepMerge(base, patch) else base)
                        advancedEdited = false
                        inlineError = null
                    },
                    enabled = !disabled,
                    modifier = Modifier.weight(1f),
                )
                WbButton(
                    text = "恢复已加载",
                    onClick = {
                        advancedText = prettyJson(base)
                        advancedEdited = false
                        inlineError = null
                    },
                    enabled = !disabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (inlineError != null) {
            Spacer(Modifier.height(WbDimens.gap))
            Text(
                text = inlineError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.bad,
            )
        }

        Spacer(Modifier.height(WbDimens.gap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WbButton(
                text = if (saving) "保存中…" else "保存配置",
                onClick = { save() },
                enabled = !disabled,
                primary = true,
                modifier = Modifier.weight(1f),
            )
            WbButton(
                text = "重新加载",
                onClick = onReload,
                enabled = !disabled,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 开关行：左侧标题 + 等宽键名，右侧 Material3 Switch（颜色取自面板语义色）。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalWbColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = colors.accent,
                checkedBorderColor = colors.accent,
                uncheckedThumbColor = colors.ink3,
                uncheckedTrackColor = colors.surface2,
                uncheckedBorderColor = colors.line,
            ),
        )
    }
}
