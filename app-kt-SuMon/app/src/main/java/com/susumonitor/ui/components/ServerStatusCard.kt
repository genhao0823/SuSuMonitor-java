package com.susumonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.susumonitor.data.model.Metrics
import com.susumonitor.ui.theme.StatusCritical
import com.susumonitor.ui.theme.StatusOffline
import com.susumonitor.ui.theme.StatusOnline
import com.susumonitor.ui.theme.StatusWarning
import com.susumonitor.util.TimeFormatter
import com.susumonitor.util.ValueFormatter

/**
 * 服务器状态卡片：名称/host/在线状态 + 最新 CPU/内存。
 * @param serverName 服务器名称
 * @param host 主机地址
 * @param status 服务器状态（online/offline/unknown）
 * @param agentStatus Agent 状态（online/offline）
 * @param lastHeartbeatAt 最近心跳（UTC ISO-8601）
 * @param latest 最新指标（可为空，来自 REST 或 WS 推送）
 */
@Composable
fun ServerStatusCard(
    serverName: String,
    host: String,
    status: String,
    agentStatus: String,
    lastHeartbeatAt: String?,
    latest: Metrics?,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status = status, agentStatus = agentStatus)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = serverName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricCell(label = "CPU", value = ValueFormatter.percent(latest?.cpuPercent), modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(12.dp))
                MetricCell(label = "内存", value = ValueFormatter.percent(latest?.memoryPercent), modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(12.dp))
                MetricCell(label = "磁盘", value = ValueFormatter.percent(latest?.diskPercent), modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "心跳 ${TimeFormatter.relative(lastHeartbeatAt)} · Agent ${agentStatusText(agentStatus)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 状态圆点：绿色在线 / 灰色离线。 */
@Composable
private fun StatusDot(status: String, agentStatus: String) {
    val color = when {
        status == "online" && agentStatus == "online" -> StatusOnline
        status == "offline" || agentStatus == "offline" -> StatusOffline
        else -> StatusWarning
    }
    Box(
        modifier = Modifier
            .width(10.dp)
            .height(10.dp)
            .background(color = color, shape = RoundedCornerShape(50)),
    )
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleSmall)
    }
}

private fun agentStatusText(agentStatus: String): String = when (agentStatus) {
    "online" -> "在线"
    "offline" -> "离线"
    else -> agentStatus
}
