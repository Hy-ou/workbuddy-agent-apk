package com.workbuddy2api.panel.data

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 演示模式仓库：完整走一遍 UI，但不访问网络。
 *
 * 账号动作会真实修改内存里的状态（暂停/禁用/签到/刷新余额），
 * 方便演示交互效果；重启 App 后恢复初始演示数据。
 */
class DemoWbRepository(private val delayMs: Long = 120) : WbRepository {
    override val serverLabel: String = "演示模式"
    override val isDemo: Boolean = true

    private suspend fun tick() {
        if (delayMs > 0) delay(delayMs)
    }

    private val accounts = DemoData.accounts()
    private val demoLogs = DemoData.logs().toMutableList()

    override suspend fun overview(): Overview {
        tick()
        val disabled = accounts.count { it.disabled }
        val paused = accounts.count { it.paused && !it.disabled }
        val cooling = accounts.count { it.cooling && !it.disabled && !it.paused }
        val healthy = (accounts.size - disabled - paused - cooling).coerceAtLeast(0)
        return Overview(
            version = "1.4.2-demo",
            uptimeSec = 86_412,
            authRequired = false,
            redisMode = "noop",
            stickySessions = 3,
            total = accounts.size,
            healthy = healthy,
            cooling = cooling,
            disabled = disabled,
            paused = paused,
            inFlightFull = 0,
            accounts = accounts.toList(),
            modelLocks = DemoData.modelLocks(),
        )
    }

    override suspend fun logs(): List<LogEntry> {
        tick()
        return demoLogs.toList()
    }

    override suspend fun requestMetrics(): RequestMetrics {
        tick()
        return RequestMetrics(
            startedAt = DemoData.logs().first().ts,
            completed = 501,
            inFlight = 0,
            succeeded = 401,
            failed = 100,
            successRate = 80.0,
            httpSuccessRate = 80.0,
            avgDurationMs = 4009.3,
            recent = DemoData.requestLogs(),
            archive = ArchiveInfo(enabled = true, dir = "data/request-logs", files = 1, bytes = 178_348),
        )
    }

    override suspend fun requestLogs(limit: Int, outcome: String?, from: Long?, to: Long?): RequestLogsPage {
        tick()
        val rows = DemoData.requestLogs().let { list ->
            if (outcome.isNullOrBlank()) list else list.filter { it.outcome == outcome }
        }.take(limit)
        return RequestLogsPage(entries = rows, limit = limit)
    }

    override suspend fun models(): List<ModelInfo> {
        tick()
        return DemoData.models()
    }

    override suspend fun accountTasks(uid: String): AccountTasks {
        tick()
        return AccountTasks(ok = true, tasks = DemoData.tasks())
    }

    override suspend fun acceptTasks(uid: String, codes: List<String>): ActionResult {
        tick()
        return ActionResult(ok = true, message = "已接受 ${codes.size} 个任务（演示）")
    }

    override suspend fun acceptAllTasks(uid: String): JsonObject {
        tick()
        return buildJsonObject { put("ok", true); put("accepted", 3) }
    }

    override suspend fun claimTask(uid: String, code: String): ActionResult {
        tick()
        return ActionResult(ok = true, message = "已领取奖励 +100 分 +5 能（演示）", credit = 100.0, energy = 5.0)
    }

    override suspend fun autoTask(uid: String, code: String): ActionResult {
        tick()
        return ActionResult(
            ok = true,
            message = "演示模式：已模拟完成任务",
            progressBefore = "3/5",
            progressAfter = "5/5",
            claimed = true,
            credit = 100.0,
            energy = 5.0,
        )
    }

    override suspend fun autoAllTasks(uid: String): JsonObject {
        tick()
        return buildJsonObject {
            put("ok", true)
            put("results", JsonArray(emptyList()))
        }
    }

    override suspend fun scanAll(): ScanResult {
        tick()
        val items = accounts.filter { !it.disabled }.map {
            ScanAccountItem(uid = it.uid, nickname = it.nickname, growth = DemoData.tasks().take(3))
        }
        return ScanResult(ok = true, accounts = items, pendingCount = items.size * 3)
    }

    override suspend fun runQueue(concurrency: Int): QueueStartResult {
        tick()
        return QueueStartResult(ok = true, started = false, message = "演示模式不执行真实任务队列")
    }

    override suspend fun taskQueue(): QueueStatus {
        tick()
        return QueueStatus(running = false, started = false, total = 0, seq = 0, items = emptyList())
    }

    override suspend fun schoolVouchers(): SchoolVouchersPage {
        tick()
        return DemoData.vouchers()
    }

    override suspend fun packages(): List<PackageAccount> {
        tick()
        return DemoData.packages()
    }

    override suspend fun usage(hours: Int?): UsageSnapshot {
        tick()
        return DemoData.usage()
    }

    override suspend fun modelProbes(): ModelProbes {
        tick()
        return DemoData.probes()
    }

    override suspend fun config(): ConfigEnvelope {
        tick()
        return DemoData.config()
    }

    override suspend fun saveConfig(patch: JsonObject): ConfigSaveResult {
        tick()
        return DemoData.configPatchResult()
    }

    override suspend fun loginStart(realm: String): LoginStart {
        tick()
        return LoginStart(ok = true, url = "https://example.com/demo-login", state = "demo-state", realm = realm)
    }

    override suspend fun loginPoll(state: String): LoginPoll {
        tick()
        return LoginPoll(
            ok = true, done = true, uid = "demo-new-account", nickname = "新账号（演示）",
            realm = "cn", credits = 500.0, creditsTotal = 500.0, checkinMessage = "签到成功 +100",
        )
    }

    override suspend fun accountAction(uid: String, action: AccountAction): ActionResult {
        tick()
        val index = accounts.indexOfFirst { it.uid == uid }
        if (index < 0) return ActionResult(ok = false, error = "账号不存在（演示）")
        when (action) {
            AccountAction.REVIVE -> accounts[index] = accounts[index].copy(
                disabled = false, paused = false, cooling = false, coolKind = "",
                coolRemainingSec = 0, reason = "", until = "", breakerFails = 0,
            )
            AccountAction.DISABLE -> accounts[index] = accounts[index].copy(
                disabled = true, reason = "manual disable (panel)",
            )
            AccountAction.PAUSE -> accounts[index] = accounts[index].copy(paused = true)
            AccountAction.RESUME -> accounts[index] = accounts[index].copy(paused = false)
            AccountAction.BALANCE -> {
                val a = accounts[index]
                val credits = if (a.creditsTotal > 0) minOf(a.creditsTotal, a.credits + 10) else a.credits + 10
                accounts[index] = a.copy(credits = credits)
            }
            AccountAction.CHECKIN -> accounts[index] = accounts[index].copy(checkinDone = true)
            AccountAction.REMOVE -> accounts.removeAt(index)
        }
        demoLogs += LogEntry(ts = java.time.OffsetDateTime.now().toString(), ch = "task", text = "${action.label} uid=$uid（演示）")
        val message = when (action) {
            AccountAction.CHECKIN -> "签到成功（演示）"
            AccountAction.BALANCE -> "余额已刷新（演示）"
            AccountAction.REMOVE -> "账号已移除（演示）"
            AccountAction.REVIVE -> "已解冻（演示）"
            else -> "${action.label}成功（演示）"
        }
        return ActionResult(ok = true, message = message)
    }

    override suspend fun bulkAction(action: BulkAction): ActionResult {
        delay(if (action.async) 300 else 1200)
        demoLogs += LogEntry(
            ts = java.time.OffsetDateTime.now().toString(),
            ch = "task",
            text = "${action.label} 已开始（演示）",
        )
        return ActionResult(ok = true, started = action.async, message = "${action.label}已开始（演示）")
    }
}
