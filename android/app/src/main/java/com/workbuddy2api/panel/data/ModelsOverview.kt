package com.workbuddy2api.panel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 全局 JSON 解析器：服务端字段只增不减，忽略未知字段；缺省值由数据类兜底。 */
val WbJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
data class Overview(
    val version: String = "",
    @SerialName("uptime_sec") val uptimeSec: Long = 0,
    @SerialName("auth_required") val authRequired: Boolean = false,
    @SerialName("redis_mode") val redisMode: String = "noop",
    @SerialName("sticky_sessions") val stickySessions: Int = 0,
    val total: Int = 0,
    val healthy: Int = 0,
    val cooling: Int = 0,
    val disabled: Int = 0,
    val paused: Int = 0,
    @SerialName("in_flight_full") val inFlightFull: Int = 0,
    val accounts: List<Account> = emptyList(),
    @SerialName("model_locks") val modelLocks: List<ModelLock>? = null,
)

@Serializable
data class Account(
    val uid: String = "",
    val nickname: String = "",
    val credits: Double = 0.0,
    @SerialName("credits_total") val creditsTotal: Double = 0.0,
    @SerialName("credits_expiring") val creditsExpiring: Double = 0.0,
    @SerialName("credits_earliest_expiry") val creditsEarliestExpiry: String = "",
    @SerialName("credits_earliest_remaining") val creditsEarliestRemaining: Long = 0,
    val cooling: Boolean = false,
    @SerialName("cool_kind") val coolKind: String = "",
    @SerialName("cool_remaining_sec") val coolRemainingSec: Long = 0,
    val until: String = "",
    val reason: String = "",
    val realm: String = "cn",
    val disabled: Boolean = false,
    val paused: Boolean = false,
    @SerialName("success_count") val successCount: Long = 0,
    @SerialName("last_success") val lastSuccess: String = "",
    @SerialName("last_err") val lastErr: String = "",
    @SerialName("checkin_done") val checkinDone: Boolean = false,
    @SerialName("token_usage") val tokenUsage: TokenUsage? = null,
    @SerialName("model_costs") val modelCosts: List<ModelCost> = emptyList(),
    @SerialName("consecutive_fails") val consecutiveFails: Int = 0,
    @SerialName("degrade_until") val degradeUntil: String = "",
    @SerialName("in_flight") val inFlight: Int = 0,
    @SerialName("breaker_fails") val breakerFails: Int = 0,
    @SerialName("breaker_until") val breakerUntil: String = "",
    val enterprise: Boolean = false,
)

@Serializable
data class TokenUsage(
    @SerialName("request_count") val requestCount: Long = 0,
    @SerialName("usage_count") val usageCount: Long = 0,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("last_latency_ms") val lastLatencyMs: Long = 0,
    @SerialName("last_tokens_per_second") val lastTokensPerSecond: Double = 0.0,
    @SerialName("last_used_at") val lastUsedAt: String = "",
    @SerialName("last_model") val lastModel: String = "",
)

@Serializable
data class ModelCost(
    val model: String = "",
    @SerialName("cost_per_1k") val costPer1k: Double = 0.0,
    @SerialName("last_seen") val lastSeen: String = "",
    val samples: Long = 0,
)

@Serializable
data class ModelLock(
    val model: String = "",
    val realm: String = "cn",
    val total: Int = 0,
    val servable: Int = 0,
    val locked: Int = 0,
    val state: String = "",
    val reason: String = "",
    @SerialName("unlock_at") val unlockAt: String = "",
    @SerialName("fully_unlock_at") val fullyUnlockAt: String = "",
)
