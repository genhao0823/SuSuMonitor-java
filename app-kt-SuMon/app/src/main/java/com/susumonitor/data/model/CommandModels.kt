package com.susumonitor.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 命令运行状态（与后端 `CommandRunService` 状态常量对齐）。 */
object CommandStatusValues {
    const val PENDING_APPROVAL = "pending_approval"
    const val APPROVED = "approved"
    const val EXECUTING = "executing"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val REJECTED = "rejected"
    const val EXPIRED = "expired"
    const val TIMEOUT = "timeout"

    /** 是否为终态（轮询可停止）。 */
    fun isFinal(status: String): Boolean =
        status == SUCCEEDED || status == FAILED || status == REJECTED ||
            status == EXPIRED || status == TIMEOUT
}

/** 命令来源（与 OpenAPI `CommandRun.source` 对齐）。 */
object CommandSourceValues {
    const val AI = "ai"
    const val MANUAL = "manual"
}

/** 命令风险等级（与 OpenAPI `CommandRun.risk_level` 对齐）。 */
object CommandRiskValues {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
}

/** 审批方式（与 OpenAPI `CommandRun.approval_mode` 对齐；auto 时 approver_id 为空）。 */
object CommandApprovalModeValues {
    const val MANUAL = "manual"
    const val AUTO = "auto"
}

/** L1 白名单命令模板，与 `GET /api/ai/commands/templates` 返回对齐。 */
@Serializable
data class CommandTemplate(
    val id: String,
    val argv: List<String> = emptyList(),
    @SerialName("risk_level") val riskLevel: String? = null,
    val params: List<TemplateParamSpec> = emptyList(),
)

/** 模板参数约束：pattern 为后端下发的全匹配正则。 */
@Serializable
data class TemplateParamSpec(
    val name: String,
    val pattern: String,
)

/** AI 命令建议请求，与 OpenAPI `CommandSuggestionRequest` 对齐。 */
@Serializable
data class CommandSuggestionRequest(
    @SerialName("server_id") val serverId: Long,
    val intent: String,
)

/** 手动按模板发起待审批命令，与 OpenAPI `ManualCommandRequest` 对齐。 */
@Serializable
data class ManualCommandRequest(
    @SerialName("server_id") val serverId: Long,
    @SerialName("template_id") val templateId: String,
    val params: Map<String, String> = emptyMap(),
)

/** AI 建议元数据（manual 来源为 null），与后端 `CommandRunVo.Proposal` 对齐。 */
@Serializable
data class CommandProposal(
    val reason: String? = null,
    val model: String? = null,
    @SerialName("prompt_version") val promptVersion: String? = null,
)

/** 脱敏截断后的执行结果（未完成时为 null），与后端 `CommandRunVo.Result` 对齐。 */
@Serializable
data class CommandRunResult(
    val stdout: String? = null,
    val stderr: String? = null,
    val truncated: Boolean? = null,
    val error: String? = null,
)

/** 一次命令运行记录，与后端 `CommandRunVo` 对齐。 */
@Serializable
data class CommandRun(
    val id: Long,
    @SerialName("execution_id") val executionId: String,
    @SerialName("server_id") val serverId: Long,
    @SerialName("template_id") val templateId: String,
    val params: Map<String, String> = emptyMap(),
    @SerialName("rendered_command") val renderedCommand: String,
    val status: String,
    val source: String,
    @SerialName("risk_level") val riskLevel: String? = null,
    @SerialName("approval_mode") val approvalMode: String? = null,
    val proposal: CommandProposal? = null,
    val result: CommandRunResult? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("proposer_id") val proposerId: Long? = null,
    @SerialName("approver_id") val approverId: Long? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

/** 自动审批策略快照，与 `GET /api/ai/commands/auto-approval-policy` 返回对齐。 */
@Serializable
data class AutoApprovalPolicy(
    val enabled: Boolean,
    @SerialName("max_risk_level") val maxRiskLevel: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("updated_by") val updatedBy: Long? = null,
)

/** 更新自动审批策略请求体（阈值仅允许 low / medium）。 */
@Serializable
data class AutoApprovalPolicyRequest(
    val enabled: Boolean,
    @SerialName("max_risk_level") val maxRiskLevel: String,
)
