package com.workbuddy2api.panel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskItem(
    @SerialName("task_code") val taskCode: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("task_desc") val taskDesc: String = "",
    val credit: Double = 0.0,
    val energy: Double = 0.0,
    @SerialName("has_reward") val hasReward: Boolean = false,
    @SerialName("reward_buddy") val rewardBuddy: Boolean = false,
    @SerialName("task_type") val taskType: String = "",
    val tag: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
    val locked: Boolean = false,
    val target: Int = 0,
    val current: Int = 0,
    val status: String = "",
    val claimable: Boolean = false,
    @SerialName("accept_status") val acceptStatus: String = "",
    val claimed: Boolean = false,
)

@Serializable
data class AccountTasks(
    val ok: Boolean = false,
    val tasks: List<TaskItem>? = null,
    val error: String = "",
)

@Serializable
data class TaskQueueItem(
    val uid: String = "",
    val nickname: String = "",
    val kind: String = "growth",
    val code: String = "",
    val status: String = "",
    val message: String = "",
)

@Serializable
data class QueueStatus(
    val conc: Int = 0,
    val items: List<TaskQueueItem>? = null,
    val running: Boolean = false,
    val seq: Long = 0,
    val started: Boolean = false,
    @SerialName("started_at") val startedAt: String = "",
    val total: Int = 0,
)

@Serializable
data class ScanAccountItem(
    val uid: String = "",
    val nickname: String = "",
    val growth: List<TaskItem>? = null,
    @SerialName("growth_error") val growthError: String = "",
)

@Serializable
data class ScanResult(
    val ok: Boolean = false,
    val accounts: List<ScanAccountItem>? = null,
    @SerialName("pending_count") val pendingCount: Int = 0,
    val error: String = "",
)
