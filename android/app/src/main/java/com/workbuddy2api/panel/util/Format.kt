package com.workbuddy2api.panel.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/*
 * 面板所有「时间 / 数字 / 容量 / 密钥」展示的统一格式化入口。
 *
 * 纯 Kotlin 实现（只依赖 java.time），不引用任何 Android API，可直接单元测试。
 * 数字一律用 Locale.US 分组与小数点，避免跟随系统区域出现「1.234,5」这类差异。
 */

/** Go 的零值时间（`0001-01-01T00:00:00Z`）在 UI 上等价于「没有值」。 */
private const val ZERO_DATE_PREFIX = "0001-01-01"

/** 超过纳秒精度的小数位（Go / .NET 偶尔输出 10–12 位）需要截断后再解析。 */
private val FRACTION_TOO_LONG = Regex("""(\.\d{9})\d+""")

/** 绝对时间兜底格式：超过 30 天只显示日期。 */
private val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

/**
 * 解析 ISO-8601 时间字符串。
 *
 * 支持 `2024-01-01T00:00:00Z`、带 `+08:00` 偏移、无时区（按 UTC 处理）、纯日期，
 * 以及 7–9 位（甚至更多，超出部分截断）小数秒。
 * 空白、`null`、以及 `0001-01-01...` 这类 Go 零值统一返回 `null`。
 */
fun parseIso(s: String?): Instant? {
    val raw = s?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (raw.startsWith(ZERO_DATE_PREFIX)) return null
    // 部分服务端把空格当分隔符；Go 的 RFC3339 偶尔带 7–9 位小数。
    val text = FRACTION_TOO_LONG.replace(raw.replace(' ', 'T')) { it.groupValues[1] }
    return runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: runCatching { LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
}

/**
 * 相对时间：`刚刚` / `3 分钟前` / `2 小时前` / `5 天前`，超过 30 天回落成 `yyyy-MM-dd`。
 * 无法解析或为空时返回 `—`。
 */
fun agoText(s: String?): String {
    val instant = parseIso(s) ?: return "—"
    val diff = Instant.now().epochSecond - instant.epochSecond
    return when {
        diff < 60L -> "刚刚"
        diff < 3_600L -> "${diff / 60} 分钟前"
        diff < 86_400L -> "${diff / 3_600} 小时前"
        diff < 30L * 86_400L -> "${diff / 86_400} 天前"
        else -> DATE_ONLY.withZone(ZoneOffset.systemDefault()).format(instant)
    }
}

/**
 * 剩余时长：`7 秒` / `12 分钟` / `1 小时 5 分` / `2 天 3 小时`。
 * 已到期（<= 0 秒）返回 `已到期`。
 */
fun remainText(seconds: Long): String {
    if (seconds <= 0L) return "已到期"
    if (seconds < 60L) return "$seconds 秒"
    if (seconds < 3_600L) return "${seconds / 60} 分钟"
    if (seconds < 86_400L) {
        val h = seconds / 3_600
        val m = seconds % 3_600 / 60
        return if (m == 0L) "$h 小时" else "$h 小时 $m 分"
    }
    val d = seconds / 86_400
    val h = seconds % 86_400 / 3_600
    return if (h == 0L) "$d 天" else "$d 天 $h 小时"
}

/** 运行时长：`12 分钟` / `2 小时 3 分` / `2 天 3 小时`；负数按 0 处理。 */
fun uptimeText(sec: Long): String {
    val s = if (sec < 0L) 0L else sec
    if (s < 60L) return "$s 秒"
    if (s < 3_600L) return "${s / 60} 分钟"
    if (s < 86_400L) {
        val h = s / 3_600
        val m = s % 3_600 / 60
        return if (m == 0L) "$h 小时" else "$h 小时 $m 分"
    }
    val d = s / 86_400
    val h = s % 86_400 / 3_600
    return if (h == 0L) "$d 天" else "$d 天 $h 小时"
}

/**
 * 整数压缩：`1234 -> 1,234`、`1234567 -> 1.23M`、`1500000000 -> 1.5B`。
 * 百万以下保留完整千分位，方便核对精确数量。
 */
fun compactNum(n: Long): String {
    val a = if (n == Long.MIN_VALUE) Long.MAX_VALUE.toDouble() else abs(n).toDouble()
    val sign = if (n < 0L) "-" else ""
    return when {
        a >= 1_000_000_000.0 -> sign + trimZeros(String.format(Locale.US, "%.2f", a / 1_000_000_000.0)) + "B"
        a >= 1_000_000.0 -> sign + trimZeros(String.format(Locale.US, "%.2f", a / 1_000_000.0)) + "M"
        else -> String.format(Locale.US, "%,d", n)
    }
}

/** 小数压缩：整数值走 [compactNum] 的整数路径，其余最多保留两位小数。 */
fun compactNum(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return "—"
    if (d == floor(d) && abs(d) < 1e15) return compactNum(d.toLong())
    val a = abs(d)
    val sign = if (d < 0.0) "-" else ""
    return when {
        a >= 1e9 -> sign + trimZeros(String.format(Locale.US, "%.2f", a / 1e9)) + "B"
        a >= 1e6 -> sign + trimZeros(String.format(Locale.US, "%.2f", a / 1e6)) + "M"
        else -> sign + trimZeros(String.format(Locale.US, "%,.2f", a))
    }
}

/**
 * 额度：整数不带小数点（`1200 -> 1,200`），负数表示企业版不限额度（`-1 -> 不限`）。
 */
fun creditsText(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return "—"
    if (d < 0.0) return "不限"
    if (d == floor(d)) return String.format(Locale.US, "%,d", d.toLong())
    return trimZeros(String.format(Locale.US, "%,.2f", d))
}

/**
 * 百分比：入参在 0..1 之间时按比例换算（`0.942 -> 94.2%`），
 * 已经大于 1 的值视为百分数直接使用（`94.2 -> 94.2%`）。整数值去掉 `.0`。
 */
fun percentText(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return "—"
    val pct = if (d >= -1.0 && d <= 1.0) d * 100.0 else d
    return trimZeros(String.format(Locale.US, "%.1f", pct)) + "%"
}

/** 字节容量（1024 进制）：`1782579 -> 1.7 MB`、`512 -> 512 B`。 */
fun bytesText(b: Long): String {
    if (b < 0L) return "—"
    if (b < 1024L) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var value = b.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}

/** 耗时：`820 ms` / `3.3 s` / `2.5 min`；负数返回 `—`。 */
fun msText(ms: Long): String {
    if (ms < 0L) return "—"
    if (ms < 1_000L) return "$ms ms"
    if (ms < 60_000L) return trimZeros(String.format(Locale.US, "%.1f", ms / 1_000.0)) + " s"
    return trimZeros(String.format(Locale.US, "%.1f", ms / 60_000.0)) + " min"
}

/** UID 简写：取前 8 位；空串返回 `—`。 */
fun shortUid(uid: String): String {
    val v = uid.trim()
    if (v.isEmpty()) return "—"
    return if (v.length <= 8) v else v.substring(0, 8)
}

/**
 * API Key 打码：保留 `sk-` 这类前缀与末 4 位，中间固定 4 个星号。
 * 例：`sk-1234567890abcd -> sk-****abcd`；空串返回 `—`。
 */
fun maskKey(key: String): String {
    val k = key.trim()
    if (k.isEmpty()) return "—"
    val prefix = if (k.length > 8 && k.contains('-')) k.substringBefore('-') + "-" else ""
    return prefix + "****" + k.takeLast(4)
}

/** 去掉小数尾部的 0（`1.20 -> 1.2`、`1.00 -> 1`），无小数点时原样返回。 */
private fun trimZeros(s: String): String {
    if (!s.contains('.')) return s
    return s.trimEnd('0').trimEnd('.')
}
