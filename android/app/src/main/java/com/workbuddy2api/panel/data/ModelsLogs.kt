package com.workbuddy2api.panel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LogEntry(
    val ts: String = "",
    val ch: String = "sys",
    val text: String = "",
)

@Serializable
data class LogsPage(val entries: List<LogEntry> = emptyList())

@Serializable
data class RequestMetrics(
    @SerialName("started_at") val startedAt: String = "",
    val completed: Long = 0,
    @SerialName("in_flight") val inFlight: Long = 0,
    val succeeded: Long = 0,
    val failed: Long = 0,
    @SerialName("success_rate") val successRate: Double = 0.0,
    @SerialName("http_success_rate") val httpSuccessRate: Double = 0.0,
    @SerialName("avg_duration_ms") val avgDurationMs: Double = 0.0,
    val recent: List<RequestLogEntry>? = null,
    val archive: ArchiveInfo? = null,
)

@Serializable
data class ArchiveInfo(
    val enabled: Boolean = false,
    val dir: String = "",
    val files: Long = 0,
    val bytes: Long = 0,
    @SerialName("dropped_writes") val droppedWrites: Long = 0,
)

@Serializable
data class RequestLogEntry(
    val time: String = "",
    @SerialName("request_id") val requestId: String = "",
    val path: String = "",
    val account: String = "",
    val model: String = "",
    val status: Int = 0,
    val ok: Boolean = false,
    val outcome: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("ttfb_ms") val ttfbMs: Long = 0,
    val attempts: Int = 0,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("credit_known") val creditKnown: Boolean = false,
    @SerialName("cache_hit_tokens") val cacheHitTokens: Long = 0,
    @SerialName("cache_miss_tokens") val cacheMissTokens: Long = 0,
    @SerialName("client_ip") val clientIp: String = "",
    @SerialName("user_agent") val userAgent: String = "",
)

@Serializable
data class RequestLogsPage(
    val entries: List<RequestLogEntry> = emptyList(),
    val limit: Int = 0,
)
