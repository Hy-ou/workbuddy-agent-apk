package com.workbuddy2api.panel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ── 账号动作 / 登录 ───────────────────────────────────────────────────
@Serializable
data class ActionResult(
    val ok: Boolean = false,
    val error: String = "",
    val message: String = "",
    val uid: String = "",
    val nickname: String = "",
    val realm: String = "",
    val credits: Double? = null,
    @SerialName("credits_total") val creditsTotal: Double? = null,
    @SerialName("checkin_done") val checkinDone: Boolean? = null,
    @SerialName("checkin_message") val checkinMessage: String = "",
    @SerialName("balance_error") val balanceError: String = "",
    @SerialName("file_error") val fileError: String = "",
    val enterprise: Boolean = false,
    val started: Boolean = false,
    val accepted: Int = 0,
    val failed: List<String>? = null,
    @SerialName("already_claimed") val alreadyClaimed: Boolean = false,
    val credit: Double? = null,
    val energy: Double? = null,
    val claimed: Boolean? = null,
    @SerialName("claim_error") val claimError: String = "",
    val skipped: Boolean = false,
    @SerialName("progress_before") val progressBefore: String = "",
    @SerialName("progress_after") val progressAfter: String = "",
    @SerialName("claimable") val claimable: Boolean = false,
    @SerialName("task_code") val taskCode: String = "",
    val results: JsonElement? = null,
)

@Serializable
data class Region(val code: String = "", val name: String = "")

@Serializable
data class LoginRegions(val ok: Boolean = false, val regions: List<Region> = emptyList())

@Serializable
data class LoginStart(
    val ok: Boolean = false,
    val url: String = "",
    val state: String = "",
    val realm: String = "",
    val error: String = "",
)

@Serializable
data class LoginPoll(
    val ok: Boolean = false,
    val done: Boolean = false,
    val uid: String = "",
    val nickname: String = "",
    val realm: String = "",
    val credits: Double = 0.0,
    @SerialName("credits_total") val creditsTotal: Double = 0.0,
    @SerialName("checkin_message") val checkinMessage: String = "",
    val error: String = "",
    val message: String = "",
)

// ── 套餐 ──────────────────────────────────────────────────────────────
@Serializable
data class CreditPackage(
    val name: String = "",
    val remain: Double = 0.0,
    val used: Double = 0.0,
    val size: Double = 0.0,
    @SerialName("end_time") val endTime: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("package_code") val packageCode: String = "",
    @SerialName("sub_product_code") val subProductCode: String = "",
    @SerialName("sub_product_name") val subProductName: String = "",
    val cycle: Boolean = false,
)

@Serializable
data class PackageAccount(
    val uid: String = "",
    val nickname: String = "",
    val realm: String = "cn",
    val remain: Double = 0.0,
    val size: Double = 0.0,
    val packages: List<CreditPackage>? = null,
    val error: String = "",
)

@Serializable
data class PackagesPage(val accounts: List<PackageAccount> = emptyList())

// ── 用量 ──────────────────────────────────────────────────────────────
@Serializable
data class UsageTotals(
    val requests: Long = 0,
    val errors: Long = 0,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    val credits: Double = 0.0,
    @SerialName("credits_per_1m_tokens") val creditsPer1mTokens: Double = 0.0,
    @SerialName("cache_hit_tokens") val cacheHitTokens: Long = 0,
    @SerialName("cache_miss_tokens") val cacheMissTokens: Long = 0,
    @SerialName("cache_hit_rate") val cacheHitRate: Double = 0.0,
    @SerialName("avg_latency_ms") val avgLatencyMs: Double = 0.0,
    @SerialName("avg_tokens_per_second") val avgTokensPerSecond: Double = 0.0,
)

@Serializable
data class UsageAgg(
    val key: String = "",
    val realm: String = "",
    val extra: String = "",
    val requests: Long = 0,
    val errors: Long = 0,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    val credits: Double = 0.0,
    @SerialName("cache_hit_tokens") val cacheHitTokens: Long = 0,
    @SerialName("cache_miss_tokens") val cacheMissTokens: Long = 0,
    @SerialName("cache_hit_rate") val cacheHitRate: Double = 0.0,
    @SerialName("avg_latency_ms") val avgLatencyMs: Double = 0.0,
    @SerialName("avg_tokens_per_second") val avgTokensPerSecond: Double = 0.0,
)

@Serializable
data class UsageSnapshot(
    val totals: UsageTotals = UsageTotals(),
    @SerialName("by_realm") val byRealm: List<UsageAgg>? = null,
    @SerialName("by_account") val byAccount: List<UsageAgg>? = null,
    @SerialName("by_model") val byModel: List<UsageAgg>? = null,
    val series: JsonElement? = null,
    @SerialName("credit_by_account") val creditByAccount: List<UsageAgg>? = null,
    @SerialName("credit_by_model") val creditByModel: List<UsageAgg>? = null,
    val buckets: Int = 0,
    @SerialName("file_bytes") val fileBytes: Long = 0,
    val since: String = "",
    @SerialName("window_from") val windowFrom: String = "",
    @SerialName("window_to") val windowTo: String = "",
    val generated: String = "",
)

// ── 模型 / 探测 / 配置 ────────────────────────────────────────────────
@Serializable
data class ModelInfo(
    val id: String = "",
    val name: String = "",
    val vendor: String = "",
    val description: String = "",
    @SerialName("default_effort") val defaultEffort: String = "",
    @SerialName("supported_efforts") val supportedEfforts: List<String>? = null,
    @SerialName("can_disable_thinking") val canDisableThinking: Boolean = false,
    @SerialName("supports_reasoning") val supportsReasoning: Boolean = false,
    @SerialName("supports_images") val supportsImages: Boolean = false,
    @SerialName("supports_tool_call") val supportsToolCall: Boolean = false,
    val credits: String = "",
    @SerialName("promo_credits") val promoCredits: String = "",
    @SerialName("promo_label") val promoLabel: String = "",
    val tags: List<String>? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("context_length") val contextLength: Long = 0,
    @SerialName("max_output_tokens") val maxOutputTokens: Long = 0,
)

@Serializable
data class ModelProbes(
    val exists: Boolean = false,
    val probes: JsonElement? = null,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class ConfigEnvelope(
    val ok: Boolean = false,
    val path: String = "",
    val config: JsonObject? = null,
    val error: String = "",
    @SerialName("restart_required") val restartRequired: List<String>? = null,
)

@Serializable
data class ModelsPage(
    val ok: Boolean = false,
    val models: List<ModelInfo>? = null,
    val error: String = "",
)
