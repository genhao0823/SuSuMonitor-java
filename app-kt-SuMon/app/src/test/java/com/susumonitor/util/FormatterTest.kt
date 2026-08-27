package com.susumonitor.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 指标数值与时间格式化测试。
 */
class FormatterTest {

    @Test
    fun `bytes formats units`() {
        assertEquals("1.00 KB", ValueFormatter.bytes(1024.0))
        assertEquals("1.00 MB", ValueFormatter.bytes(1024.0 * 1024))
        assertEquals("512.00 B", ValueFormatter.bytes(512.0))
        assertEquals("-", ValueFormatter.bytes(null))
    }

    @Test
    fun `percent formats one decimal`() {
        assertEquals("90.5%", ValueFormatter.percent(90.5))
        assertEquals("-", ValueFormatter.percent(null))
    }

    @Test
    fun `metric label maps known metrics`() {
        assertEquals("CPU 使用率", ValueFormatter.metricLabel("cpu"))
        assertEquals("温度", ValueFormatter.metricLabel("temperature"))
        assertEquals("unknown", ValueFormatter.metricLabel("unknown"))
    }

    @Test
    fun `relative time formats`() {
        val now = System.currentTimeMillis()
        assertEquals("刚刚", TimeFormatter.relative(java.time.Instant.ofEpochMilli(now - 30_000).toString()))
        assertEquals("5 分钟前", TimeFormatter.relative(java.time.Instant.ofEpochMilli(now - 300_000).toString()))
        assertEquals("-", TimeFormatter.relative(null))
    }
}
