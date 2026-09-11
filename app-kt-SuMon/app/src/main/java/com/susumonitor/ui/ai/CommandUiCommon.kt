package com.susumonitor.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.susumonitor.data.model.CommandApprovalModeValues
import com.susumonitor.data.model.CommandRiskValues
import com.susumonitor.data.model.CommandStatusValues
import com.susumonitor.ui.theme.StatusCritical
import com.susumonitor.ui.theme.StatusOffline
import com.susumonitor.ui.theme.StatusOnline
import com.susumonitor.ui.theme.StatusWarning
import com.susumonitor.ui.theme.SusuPurple

/** 命令状态中文标签。 */
fun commandStatusLabel(status: String): String = when (status) {
    CommandStatusValues.PENDING_APPROVAL -> "待审批"
    CommandStatusValues.APPROVED -> "已批准"
    CommandStatusValues.EXECUTING -> "执行中"
    CommandStatusValues.SUCCEEDED -> "成功"
    CommandStatusValues.FAILED -> "失败"
    CommandStatusValues.REJECTED -> "已驳回"
    CommandStatusValues.EXPIRED -> "已过期"
    CommandStatusValues.TIMEOUT -> "超时"
    else -> status
}

/** 命令状态语义色。 */
fun commandStatusColor(status: String): Color = when (status) {
    CommandStatusValues.PENDING_APPROVAL -> StatusWarning
    CommandStatusValues.APPROVED -> SusuPurple
    CommandStatusValues.EXECUTING -> SusuPurple
    CommandStatusValues.SUCCEEDED -> StatusOnline
    CommandStatusValues.FAILED, CommandStatusValues.TIMEOUT -> StatusCritical
    CommandStatusValues.REJECTED, CommandStatusValues.EXPIRED -> StatusOffline
    else -> StatusOffline
}

/** 命令来源标签。 */
fun commandSourceLabel(source: String): String = when (source) {
    CommandSourceAi -> "AI 建议"
    CommandSourceManual -> "手动"
    else -> source
}

private const val CommandSourceAi = "ai"
private const val CommandSourceManual = "manual"

/** 风险等级中文标签。 */
fun commandRiskLabel(risk: String?): String = when (risk) {
    CommandRiskValues.LOW -> "低风险"
    CommandRiskValues.MEDIUM -> "中风险"
    CommandRiskValues.HIGH -> "高风险"
    else -> "低风险"
}

/** 风险等级语义色。 */
fun commandRiskColor(risk: String?): Color = when (risk) {
    CommandRiskValues.MEDIUM -> StatusWarning
    CommandRiskValues.HIGH -> StatusCritical
    else -> StatusOnline
}

/** 审批方式标签（auto 表示策略自动审批，人工未介入）。 */
fun commandApprovalLabel(mode: String?): String = when (mode) {
    CommandApprovalModeValues.AUTO -> "自动审批"
    else -> "人工审批"
}

/** 风险等级徽章。 */
@Composable
fun CommandRiskBadge(risk: String?, modifier: Modifier = Modifier) {
    val color = commandRiskColor(risk)
    Text(
        text = commandRiskLabel(risk),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 自动审批徽章（仅自动审批行显示）。 */
@Composable
fun CommandAutoApprovalBadge(modifier: Modifier = Modifier) {
    Text(
        text = "自动",
        style = MaterialTheme.typography.labelMedium,
        color = SusuPurple,
        modifier = modifier
            .background(SusuPurple.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 状态徽章。 */
@Composable
fun CommandStatusBadge(status: String, modifier: Modifier = Modifier) {
    val color = commandStatusColor(status)
    Text(
        text = commandStatusLabel(status),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
