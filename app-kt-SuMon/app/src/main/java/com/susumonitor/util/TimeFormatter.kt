package com.susumonitor.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间格式化：后端时间标准为 UTC ISO-8601，转换为设备本地时区展示。
 */
object TimeFormatter {

    private val localFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private val dateOnlyFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    /**
     * UTC ISO-8601 → 本地 "MM-dd HH:mm:ss"。
     * 解析失败原样返回（契约漂移容忍）。
     */
    fun formatLocal(utcIso: String?): String {
        if (utcIso.isNullOrBlank()) return "-"
        return runCatching {
            localFormatter.format(Instant.parse(utcIso))
        }.getOrDefault(utcIso)
    }

    /** UTC ISO-8601 → 本地日期 "yyyy-MM-dd"。 */
    fun formatDate(utcIso: String?): String {
        if (utcIso.isNullOrBlank()) return "-"
        return runCatching {
            dateOnlyFormatter.format(Instant.parse(utcIso))
        }.getOrDefault(utcIso)
    }

    /** 距今相对时间："刚刚 / N 分钟前 / N 小时前 / N 天前"。 */
    fun relative(utcIso: String?): String {
        if (utcIso.isNullOrBlank()) return "-"
        val instant = runCatching { Instant.parse(utcIso) }.getOrNull() ?: return utcIso
        val diffSeconds = (System.currentTimeMillis() - instant.toEpochMilli()) / 1000
        return when {
            diffSeconds < 60 -> "刚刚"
            diffSeconds < 3600 -> "${diffSeconds / 60} 分钟前"
            diffSeconds < 86400 -> "${diffSeconds / 3600} 小时前"
            else -> "${diffSeconds / 86400} 天前"
        }
    }
}
