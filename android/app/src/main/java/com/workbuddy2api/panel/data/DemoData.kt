package com.workbuddy2api.panel.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 演示模式数据：无需服务器即可完整浏览手机版界面。 */
object DemoData {

    private fun iso(secondsAgo: Long = 0): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now().minusSeconds(secondsAgo))

    fun accounts(): MutableList<Account> = mutableListOf(
        Account(
            uid = "90985b0b-8960-4a1e-9801-7864eaf91d86",
            nickname = "紫川",
            credits = 1532.0,
            creditsTotal = 2000.0,
            creditsExpiring = 300.0,
            creditsEarliestExpiry = iso(-3 * 86400),
            creditsEarliestRemaining = 3,
            cooling = false,
            realm = "cn",
            lastSuccess = iso(120),
            successCount = 412,
            checkinDone = true,
            tokenUsage = TokenUsage(
                requestCount = 1203, usageCount = 1198,
                promptTokens = 5_120_000, completionTokens = 880_000, totalTokens = 6_000_000,
                lastLatencyMs = 1830, lastTokensPerSecond = 42.5,
                lastUsedAt = iso(120), lastModel = "glm-5.2",
            ),
            modelCosts = listOf(
                ModelCost("glm-5.2", 0.42, iso(120), 37),
                ModelCost("deepseek-v4.1-flash", 0.0, iso(3600), 120),
            ),
            inFlight = 1,
        ),
        Account(
            uid = "ba84e00b-c2c7-40f4-9551-3ee2ffa86bb6",
            nickname = "xiaofeifei199810@gmail.com",
            credits = 350.0,
            creditsTotal = 350.0,
            cooling = true,
            coolKind = "soft_rate",
            coolRemainingSec = 7,
            until = iso(-7),
            reason = "429 rate limit",
            realm = "global",
            lastSuccess = iso(9),
            successCount = 225,
            checkinDone = true,
            tokenUsage = TokenUsage(
                requestCount = 323, usageCount = 225,
                promptTokens = 11_268_248, completionTokens = 79_250, totalTokens = 11_347_498,
                lastLatencyMs = 2771, lastTokensPerSecond = 165.1,
                lastUsedAt = iso(9), lastModel = "deepseek-v4.1-flash",
            ),
            inFlight = 2,
        ),
        Account(
            uid = "0f3c9a1e-7b2d-4c55-9f1a-2b3c4d5e6f70",
            nickname = "云端漫步",
            credits = 88.0,
            creditsTotal = 500.0,
            creditsExpiring = 88.0,
            creditsEarliestExpiry = iso(-6 * 86400),
            creditsEarliestRemaining = 6,
            realm = "cn",
            lastSuccess = iso(540),
            successCount = 96,
            tokenUsage = TokenUsage(
                requestCount = 102, usageCount = 96,
                promptTokens = 820_000, completionTokens = 46_000, totalTokens = 866_000,
                lastLatencyMs = 6100, lastTokensPerSecond = 36.2,
                lastUsedAt = iso(540), lastModel = "glm-5.2",
            ),
            inFlight = 0,
        ),
        Account(
            uid = "e1a7c3d2-1b4f-4e6a-9c8d-5f2b7a9e0c31",
            nickname = "企业工作台",
            credits = 0.0,
            creditsTotal = -1.0,
            realm = "cn",
            enterprise = true,
            lastSuccess = iso(4200),
            successCount = 40,
            checkinDone = false,
        ),
        Account(
            uid = "5b2e9f60-8d31-4a72-b0c4-6e1f8a2d9b57",
            nickname = "备用号 · 小蓝",
            credits = 420.0,
            creditsTotal = 600.0,
            realm = "cn",
            paused = true,
            lastSuccess = iso(18 * 3600),
            successCount = 12,
            checkinDone = true,
        ),
        Account(
            uid = "c7d4a918-2e5b-4f36-8a0d-1b9e6c3f7a24",
            nickname = "旧账号",
            credits = 0.0,
            creditsTotal = 0.0,
            realm = "cn",
            disabled = true,
            reason = "manual disable (panel)",
            lastErr = iso(2 * 86400),
            lastSuccess = iso(3 * 86400),
            successCount = 3,
        ),
    )

    fun modelLocks(): List<ModelLock> = listOf(
        ModelLock(
            model = "glm-5.2", realm = "cn", total = 3, servable = 1, locked = 2,
            state = "partial", reason = "6004 rate limit", unlockAt = iso(-420), fullyUnlockAt = iso(-90),
        ),
        ModelLock(
            model = "deepseek-v4.1-flash", realm = "global", total = 1, servable = 0, locked = 1,
            state = "locked", reason = "6004 global", unlockAt = iso(-7), fullyUnlockAt = iso(-7),
        ),
    )

    fun logs(): List<LogEntry> {
        val out = mutableListOf<LogEntry>()
        val seed = listOf(
            "task" to "checkin acct=紫川(90985b0b) credits=1532 total=2000",
            "task" to "checkin acct=云端漫步(0f3c9a1e) credits=88 total=500",
            "sys" to "WARN: [upstream] chat_stream acct=xiaofeifei199810@gmail.com(ba84e00b): upstream 429 soft_rate",
            "chat" to "chat model=global:deepseek-v4.1-flash acct=xiaofeifei199810@gmail.com status=200 dur=3.3s tokens=27115",
            "task" to "balance acct=紫川(90985b0b) credits=1532 total=2000",
            "sys" to "INFO: scheduler checkin 已执行，覆盖 3 个账号",
            "chat" to "chat model=cn:glm-5.2 acct=紫川 status=200 dur=6.1s tokens=1520",
            "task" to "activity acct=云端漫步(0f3c9a1e) streak=6",
            "sys" to "WARN: account xiaofeifei199810@gmail.com 进入冷却（soft_rate 7s）",
            "task" to "travel acct=紫川(90985b0b) adopted=1",
        )
        repeat(40) { i ->
            val (ch, text) = seed[i % seed.size]
            out += LogEntry(ts = iso((40 - i) * 37L), ch = ch, text = text)
        }
        return out
    }

    fun requestLogs(): List<RequestLogEntry> {
        val models = listOf("cn:glm-5.2", "global:deepseek-v4.1-flash", "cn:step-5-preview")
        val accounts = listOf("紫川(90985b0b)", "xiaofeifei199810@gmail.com(ba84e00b)", "云端漫步(0f3c9a1e)")
        return (0 until 24).map { i ->
            val ok = i % 5 != 0
            RequestLogEntry(
                time = iso(i * 47L),
                requestId = "req-demo${1000 + i}",
                path = "/v1/chat/completions",
                account = accounts[i % accounts.size],
                model = models[i % models.size],
                status = if (ok) 200 else 429,
                ok = ok,
                outcome = if (ok) "success" else "http_error",
                durationMs = 1200L + i * 130,
                ttfbMs = 600L + i * 40,
                attempts = if (ok) 1 else 2,
                promptTokens = 8000L + i * 700,
                completionTokens = 200L + i * 30,
                totalTokens = 8200L + i * 730,
                creditKnown = true,
                cacheHitTokens = 6000L,
                cacheMissTokens = 2200L,
                clientIp = "192.168.1.10",
                userAgent = "deepseek-harness/0.2.1",
            )
        }
    }

    fun tasks(): List<TaskItem> = listOf(
        TaskItem("chat_5", "和 AI 对话 5 次", target = 5, current = 5, credit = 100.0, energy = 5.0,
            acceptStatus = "accepted", claimable = true, tag = "PC",
            description = "与 WorkBuddy 完成 5 次对话", taskDesc = "完成 5 次对话"),
        TaskItem("expert_5", "使用专家模式 5 次", target = 5, current = 3, credit = 100.0, energy = 5.0,
            acceptStatus = "accepted", tag = "PC", description = "在专家模式下完成 5 次对话"),
        TaskItem("first_buddy", "首次召唤 Buddy", target = 1, current = 1, credit = 300.0, energy = 5.0,
            acceptStatus = "claimed", claimed = true, hasReward = true, tag = "新人"),
        TaskItem("create_canvas", "体验「设计创意模式」", target = 1, current = 1, credit = 300.0,
            energy = 5.0, acceptStatus = "claimed", claimed = true, tag = "PC"),
        TaskItem("playbook_prompt", "探索优秀灵感", target = 1, current = 1, credit = 100.0,
            energy = 5.0, acceptStatus = "claimed", claimed = true),
        TaskItem("RichMeow_Chat", "桌面端对话 1 次", target = 1, current = 0, credit = 100.0,
            energy = 5.0, acceptStatus = "", rewardBuddy = true, tag = "限定"),
        TaskItem("Library_read", "体验「资料库」", target = 1, current = 1, credit = 100.0,
            energy = 5.0, acceptStatus = "completed", claimable = true),
        TaskItem("Sequential_Tasks_3", "连续任务 3", target = 3, current = 1, credit = 50.0,
            energy = 2.0, acceptStatus = "accepted", locked = true, tag = "小程序"),
    )

    fun vouchers(): SchoolVouchersPage = SchoolVouchersPage(
        accounts = listOf(
            SchoolVoucherAccount(
                uid = "90985b0b-8960-4a1e-9801-7864eaf91d86", nickname = "紫川", realm = "cn",
                vouchers = listOf(
                    SchoolVoucher(1, "draw-1", "kfc_ice_cream", "肯德基冰淇淋", "KFC-8F2A-9C41",
                        validTo = "2026-10-24", grantedAt = iso(86400)),
                    SchoolVoucher(2, "draw-2", "coffee_1", "瑞幸咖啡券", "LK-77C1-2B90",
                        validTo = "2026-09-01", grantedAt = iso(6 * 86400)),
                ),
            ),
            SchoolVoucherAccount(
                uid = "ba84e00b-c2c7-40f4-9551-3ee2ffa86bb6",
                nickname = "xiaofeifei199810@gmail.com", realm = "global",
                error = "global realm（无开学季活动）",
            ),
        ),
    )

    fun packages(): List<PackageAccount> = listOf(
        PackageAccount(
            uid = "90985b0b-8960-4a1e-9801-7864eaf91d86", nickname = "紫川", realm = "cn",
            remain = 1532.0, size = 2000.0,
            packages = listOf(
                CreditPackage("CodeBuddy 个人体验版", 500.0, 0.0, 500.0,
                    endTime = "2034-10-11 20:45:19", expiresAt = 2_044_183_519_000, subProductName = "腾讯云代码助手 (IDE)"),
                CreditPackage("国内运营裂变包", 732.0, 268.0, 1000.0,
                    endTime = "2026-11-19 01:25:43", expiresAt = 1_795_022_743_000, subProductName = "代码助手 - 赠送包"),
                CreditPackage("个人版月度包", 300.0, 200.0, 500.0,
                    endTime = "2026-11-01 23:59:59", expiresAt = 1_793_491_199_000, cycle = true),
            ),
        ),
        PackageAccount(
            uid = "0f3c9a1e-7b2d-4c55-9f1a-2b3c4d5e6f70", nickname = "云端漫步", realm = "cn",
            remain = 88.0, size = 500.0,
            packages = listOf(
                CreditPackage("新手礼包", 88.0, 412.0, 500.0, endTime = "2026-10-25 23:59:59"),
            ),
        ),
        PackageAccount(
            uid = "ba84e00b-c2c7-40f4-9551-3ee2ffa86bb6", nickname = "xiaofeifei199810@gmail.com",
            realm = "global", remain = 0.0, size = 0.0, error = "account not loaded",
        ),
    )

    fun usage(): UsageSnapshot = UsageSnapshot(
        totals = UsageTotals(
            requests = 501, errors = 100, promptTokens = 16_213_218,
            completionTokens = 109_856, totalTokens = 16_323_074, credits = 16.71,
            creditsPer1mTokens = 1.024, cacheHitTokens = 15_267_712, cacheMissTokens = 945_506,
            cacheHitRate = 94.2, avgLatencyMs = 3895.1, avgTokensPerSecond = 120.6,
        ),
        byRealm = listOf(
            UsageAgg("global", requests = 333, errors = 100, totalTokens = 12_048_061, credits = 0.0, avgLatencyMs = 2518.1),
            UsageAgg("cn", requests = 168, totalTokens = 4_275_013, credits = 16.71, avgLatencyMs = 6624.4),
        ),
        byAccount = listOf(
            UsageAgg("90985b0b-8960-4a1e-9801-7864eaf91d86", extra = "紫川", requests = 168,
                totalTokens = 4_275_013, credits = 16.71, avgLatencyMs = 6624.4),
            UsageAgg("ba84e00b-c2c7-40f4-9551-3ee2ffa86bb6", extra = "xiaofeifei199810@gmail.com",
                requests = 333, errors = 100, totalTokens = 12_048_061, avgLatencyMs = 2518.1),
        ),
        byModel = listOf(
            UsageAgg("glm-5.2", requests = 168, totalTokens = 4_275_013, credits = 16.71, avgLatencyMs = 6624.4),
            UsageAgg("deepseek-v4.1-flash", requests = 333, totalTokens = 12_048_061, avgLatencyMs = 2518.1),
        ),
        buckets = 72,
        since = "2026-10-16T13",
        generated = iso(0),
    )

    fun models(): List<ModelInfo> = listOf(
        ModelInfo(id = "cn:glm-5.2", name = "GLM-5.2", vendor = "zhipu", defaultEffort = "high",
            supportedEfforts = listOf("low", "medium", "high"), credits = "x0.05",
            contextLength = 128_000, maxOutputTokens = 16_384, supportsReasoning = true,
            supportsToolCall = true, isDefault = true, tags = listOf("badge:限时免费")),
        ModelInfo(id = "global:deepseek-v4.1-flash", name = "DeepSeek V4.1 Flash", vendor = "deepseek",
            defaultEffort = "medium", supportedEfforts = listOf("low", "medium", "high"),
            credits = "x0.01", contextLength = 256_000, maxOutputTokens = 32_768,
            supportsReasoning = true, supportsToolCall = true),
        ModelInfo(id = "cn:step-5-preview", name = "Step-5 Preview", vendor = "stepfun",
            credits = "x0", contextLength = 64_000, maxOutputTokens = 8_192, supportsImages = true),
    )

    fun probes(): ModelProbes = ModelProbes(
        exists = true,
        updatedAt = iso(3600),
        probes = buildJsonObject {
            put("cn:glm-5.2", buildJsonObject {
                put("claimed", 16384); put("measured", 8192)
                put("verdict", "clamped"); put("note", "实测在 8k 输出处被截断")
                put("tested_at", "2026-10-01 12:00:00")
            })
            put("global:deepseek-v4.1-flash", buildJsonObject {
                put("claimed", 32768); put("measured", 32768)
                put("verdict", "at_least"); put("note", "至少 32k 未触顶")
                put("tested_at", "2026-10-02 09:11:03")
            })
        },
    )

    fun config(): ConfigEnvelope = ConfigEnvelope(
        ok = true,
        path = "config.json（演示）",
        config = buildJsonObject {
            put("listen", ":7863")
            put("api_key", "sk-demo-1234567890")
            putJsonObject("schedule") {
                put("checkin_enabled", true)
                put("travel_enabled", true)
                put("activity_enabled", true)
                put("keepalive_enabled", true)
                put("growth_enabled", true)
                put("balance_refresh_enabled", true)
                put("balance_refresh_minutes", 5)
                put("include_disabled_in_tasks", false)
                put("checkin_hours", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive(9),
                    kotlinx.serialization.json.JsonPrimitive(21),
                )))
            }
            putJsonObject("pool") {
                put("max_in_flight", 3)
                put("max_in_flight_global", 2)
                put("credit_floor", 0)
                put("expiring_soon", "168h")
            }
            putJsonObject("features") { put("sanitize_blacklist_fingerprints", true) }
            putJsonObject("session_sticky") { put("enabled", true); put("ttl", "30m") }
            putJsonObject("logging") { put("request_client_info", true) }
        },
    )

    fun configPatchResult(): ConfigSaveResult =
        ConfigSaveResult(ok = true, restartRequired = listOf("listen", "auth_dir", "state_file", "upstream.timeout_seconds"))
}
