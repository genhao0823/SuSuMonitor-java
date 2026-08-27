package com.susumonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.susumonitor.data.model.Metrics
import com.susumonitor.data.model.AlertRule
import com.susumonitor.util.TimeFormatter

/**
 * 自绘 Canvas 折线图（无第三方依赖）。
 * 支持多指标多线 + 告警阈值横线。
 *
 * @param data 历史指标（按 collected_at 升序）
 * @param lines 需要绘制的指标列表（(键, 颜色, 中文名)）
 * @param rules 告警规则（按 metric+server 匹配画阈值线）
 * @param fixedMax 固定 y 轴最大值（百分比类 100）；null 则自适应
 */
@Composable
fun MetricsLineChart(
    data: List<Metrics>,
    lines: List<LineSpec>,
    rules: List<AlertRule> = emptyList(),
    fixedMax: Double? = null,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(200.dp)) {
        if (data.isEmpty()) return@Canvas
        val chartTop = 12f
        val chartBottom = size.height - 20f
        val chartLeft = 8f
        val chartRight = size.width - 8f
        val chartHeight = chartBottom - chartTop

        // 收集所有数值确定 y 范围
        val allValues = lines.flatMap { line ->
            data.mapNotNull { extractMetric(it, line.key) }
        }
        if (allValues.isEmpty()) return@Canvas
        val maxValue = fixedMax ?: (allValues.maxOrNull()?.times(1.2) ?: 100.0)
        val minValue = 0.0

        // x 轴：按时间均匀分布
        val xStep = if (data.size > 1) (chartRight - chartLeft) / (data.size - 1) else 0f

        fun yOf(value: Double): Float {
            val ratio = ((value - minValue) / (maxValue - minValue)).toFloat()
            return chartBottom - (ratio * chartHeight).coerceIn(0f, chartHeight)
        }

        // 网格线
        val gridColor = Color(0x22000000)
        for (i in 0..4) {
            val y = chartTop + chartHeight * i / 4f
            drawLine(gridColor, Offset(chartLeft, y), Offset(chartRight, y), strokeWidth = 1f)
        }

        // 阈值线（告警规则）
        rules.forEach { rule ->
            val metricKey = when (rule.metric) {
                "cpu" -> "cpu_percent"
                "memory" -> "memory_percent"
                "disk" -> "disk_percent"
                else -> null
            }
            if (metricKey == null) return@forEach
            if (lines.none { it.key == metricKey }) return@forEach
            val thresholdY = yOf(rule.thresholdValue)
            val color = if (rule.level == "critical") Color(0xFFE5484D) else Color(0xFFF5A623)
            drawLine(
                color = color,
                start = Offset(chartLeft, thresholdY),
                end = Offset(chartRight, thresholdY),
                strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            )
        }

        // 各指标折线
        lines.forEach { line ->
            val points = data.mapNotNull { m ->
                val v = extractMetric(m, line.key) ?: return@mapNotNull null
                Offset(xPoint(m, data, chartLeft, chartRight), yOf(v))
            }
            if (points.size < 2) return@forEach
            val path = Path()
            path.moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { path.lineTo(it.x, it.y) }
            drawPath(path, color = line.color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

            // 最近点圆点
            val last = points.last()
            drawCircle(line.color, radius = 4f, center = last)
        }
    }
}

/** 折线规格。 */
data class LineSpec(
    val key: String,
    val color: Color,
    val label: String,
)

private fun extractMetric(m: Metrics, key: String): Double? = when (key) {
    "cpu_percent" -> m.cpuPercent
    "memory_percent" -> m.memoryPercent
    "disk_percent" -> m.diskPercent
    "net_rx" -> m.netRx
    "net_tx" -> m.netTx
    "load_avg" -> m.loadAvg
    "temperature" -> m.temperature
    else -> null
}

private fun xPoint(m: Metrics, all: List<Metrics>, left: Float, right: Float): Float {
    val index = all.indexOfFirst { it.collectedAt == m.collectedAt }
    if (index < 0) return left
    return if (all.size > 1) left + (right - left) * index / (all.size - 1) else left
}
