package com.workbuddy2api.panel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SchoolVoucher(
    @SerialName("grant_id") val grantId: Long = 0,
    @SerialName("draw_uuid") val drawUuid: String = "",
    @SerialName("sku_code") val skuCode: String = "",
    @SerialName("prize_name") val prizeName: String = "",
    val code: String = "",
    @SerialName("valid_from") val validFrom: String = "",
    @SerialName("valid_to") val validTo: String = "",
    @SerialName("granted_at") val grantedAt: String = "",
)

@Serializable
data class SchoolVoucherAccount(
    val uid: String = "",
    val nickname: String = "",
    val realm: String = "cn",
    val vouchers: List<SchoolVoucher>? = null,
    val error: String = "",
)

@Serializable
data class SchoolVouchersPage(val accounts: List<SchoolVoucherAccount> = emptyList())

@Serializable
data class ConfigSaveResult(
    val ok: Boolean = false,
    val error: String = "",
    @SerialName("restart_required") val restartRequired: List<String>? = null,
)

@Serializable
data class QueueStartResult(
    val ok: Boolean = false,
    val started: Boolean = false,
    val total: Int = 0,
    val seq: Long = 0,
    val message: String = "",
    val error: String = "",
)

/** 单账号动作。path 与服务端路由一致。 */
enum class AccountAction(val path: String, val label: String) {
    CHECKIN("checkin", "签到"),
    BALANCE("balance", "刷新余额"),
    REVIVE("revive", "解冻"),
    DISABLE("disable", "禁用"),
    PAUSE("pause", "暂停选号"),
    RESUME("resume", "恢复选号"),
    REMOVE("remove", "移除"),
}

/** 批量动作。前四个异步返回 started，balance_all 同步返回账号列表。 */
enum class BulkAction(val path: String, val label: String, val async: Boolean) {
    CHECKIN_ALL("checkin_all", "全部签到", true),
    TRAVEL_ALL("travel_all", "全部旅行", true),
    ACTIVITY_ALL("activity_all", "全部活动", true),
    KEEPALIVE_ALL("keepalive_all", "全部保活", true),
    BALANCE_ALL("balance_all", "全部刷新余额", false),
}
