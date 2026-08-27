package com.susumonitor.util

import java.util.Locale

/**
 * 指标数值/单位格式化。
 */
object ValueFormatter {

    private val percentFormatter = java.text.DecimalFormat("0.0")
    private val byteFormatter = java.text.DecimalFormat("0.00")

    /** 字节 → 人类可读（B/KB/MB/GB/TB）。 */
    fun bytes(value: Double?): String {
        if (value == null) return "-"
        var v = value
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var unitIndex = 0
        while (v >= 1024 && unitIndex < units.size - 1) {
            v /= 1024
            unitIndex++
        }
        return "${byteFormatter.format(v)} ${units[unitIndex]}"
    }

    /** 百分比显示（保留 1 位）。 */
    fun percent(value: Double?): String {
        if (value == null) return "-"
        return "${percentFormatter.format(value)}%"
    }

    /** 告警指标显示标签（中文）。 */
    fun metricLabel(metric: String): String = when (metric) {
        "cpu" -> "CPU 使用率"
        "memory" -> "内存使用率"
        "disk" -> "磁盘使用率"
        "temperature" -> "温度"
        "load" -> "系统负载"
        else -> metric
    }

    /** 按指标类型格式化数值（百分比类加 %，其他原值）。 */
    fun formatMetric(metric: String, value: Double): String = when (metric) {
        "cpu", "memory", "disk" -> percent(value)
        else -> percentFormatter.format(value)
    }

    /** 最新指标数值卡展示：百分比指标用百分号，其他用原始数值。 */
    fun metricValue(metric: String, value: Double?): String = when (metric) {
        "cpu", "memory", "disk" -> percent(value)
        "temperature" -> if (value == null) "-" else String.format(Locale.US, "%.1f °C", value)
        "load" -> if (value == null) "-" else percentFormatter.format(value)
        else -> value?.toString() ?: "-"
    }
}
