package com.susumonitor.data.repository

import com.susumonitor.api.CommandApi
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AutoApprovalPolicy
import com.susumonitor.data.model.AutoApprovalPolicyRequest
import com.susumonitor.data.model.CommandRun
import com.susumonitor.data.model.CommandSuggestionRequest
import com.susumonitor.data.model.CommandTemplate
import com.susumonitor.data.model.ManualCommandRequest
import com.susumonitor.data.model.PageResult
import com.susumonitor.util.ErrorCodes
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

/**
 * 审批制命令域仓库（M1 + 自动审批策略）：模板 / AI 建议 / 手动发起 / 查询 / 审批驳回 / 策略。
 * 后端命令开关关闭时端点返回 HTTP 404，映射为 [CommandDomainNotEnabledException]。
 */
@Singleton
class CommandRepository @Inject constructor(
    private val commandApi: CommandApi,
) {

    /** 命令域未在后端启用（HTTP 404）。 */
    class CommandDomainNotEnabledException(message: String) : Exception(message)

    /** 命令提交频率超限（42907）。 */
    class CommandRateLimitedException(message: String) : Exception(message)

    /** 白名单模板列表。 */
    suspend fun templates(): List<CommandTemplate> = commandCall {
        val response = commandApi.templates()
        response.data ?: throw mapError(response.code, response.message)
    }

    /** 读取自动审批策略（行缺失时后端按禁用返回）。 */
    suspend fun autoApprovalPolicy(): AutoApprovalPolicy = commandCall {
        val response = commandApi.getAutoApprovalPolicy()
        response.data ?: throw mapError(response.code, response.message)
    }

    /** 更新自动审批策略并返回最新快照。 */
    suspend fun updateAutoApprovalPolicy(enabled: Boolean, maxRiskLevel: String): AutoApprovalPolicy =
        commandCall {
            val response = commandApi.updateAutoApprovalPolicy(
                AutoApprovalPolicyRequest(enabled, maxRiskLevel),
            )
            response.data ?: throw mapError(response.code, response.message)
        }

    /** AI 根据意图生成待审批命令（返回可能为空列表）。 */
    suspend fun suggest(serverId: Long, intent: String): List<CommandRun> = commandCall {
        val response = commandApi.suggest(CommandSuggestionRequest(serverId, intent))
        response.data ?: throw mapError(response.code, response.message)
    }

    /** 手动按模板发起待审批命令。 */
    suspend fun createManual(serverId: Long, templateId: String, params: Map<String, String>): CommandRun =
        commandCall {
            val response = commandApi.createManual(ManualCommandRequest(serverId, templateId, params))
            response.data ?: throw mapError(response.code, response.message)
        }

    /** 分页查询运行记录。 */
    suspend fun listRuns(
        page: Int = 1,
        pageSize: Int = 20,
        serverId: Long? = null,
        status: String? = null,
    ): PageResult<CommandRun> = commandCall {
        val response = commandApi.listRuns(page, pageSize, serverId, status)
        response.data ?: throw mapError(response.code, response.message)
    }

    /** 单条运行记录详情。 */
    suspend fun getRun(id: Long): CommandRun = commandCall {
        val response = commandApi.getRun(id)
        response.data ?: throw mapError(response.code, response.message)
    }

    /** 批准并下发执行。 */
    suspend fun approve(id: Long): CommandRun = commandCall {
        val response = commandApi.approve(id)
        response.data ?: throw mapError(response.code, response.message)
    }

    /** 驳回待审批命令。 */
    suspend fun reject(id: Long): CommandRun = commandCall {
        val response = commandApi.reject(id)
        response.data ?: throw mapError(response.code, response.message)
    }

    /**
     * 模板参数本地预校验：按后端下发的全匹配正则逐项校验，
     * 返回首个不合法参数名（全部合法返回 null）。提交前调用以减少无效请求。
     */
    fun validateParams(template: CommandTemplate, params: Map<String, String>): String? =
        template.params.firstOrNull { spec ->
            val value = params[spec.name]
            value.isNullOrEmpty() || !Regex(spec.pattern).matches(value)
        }?.name

    /**
     * 统一错误语义化：后端错误以非 2xx HTTP 状态返回（HttpException），
     * 信封 code 只在 200 响应中出现，两条路径都要经过 [mapError]。
     */
    private inline fun <T> commandCall(block: () -> T): T =
        try {
            block()
        } catch (e: HttpException) {
            val api = ApiException.from(e)
            val code = (api as? ApiException.Business)?.code ?: 0
            throw mapError(code, api.message ?: "请求失败")
        }

    private fun mapError(code: Int, message: String): Exception = when (code) {
        ErrorCodes.RESOURCE_NOT_FOUND -> CommandDomainNotEnabledException("命令执行功能未启用")
        ErrorCodes.COMMAND_RUN_NOT_FOUND -> ApiException.Business(code, "命令记录不存在或已被清理")
        ErrorCodes.COMMAND_RUN_STATE_CONFLICT -> ApiException.Business(code, "命令状态已变更，请刷新后重试")
        ErrorCodes.COMMAND_AGENT_OFFLINE -> ApiException.Business(code, "目标服务器 Agent 离线，无法下发命令")
        ErrorCodes.COMMAND_RATE_LIMIT_REACHED -> CommandRateLimitedException("命令提交过于频繁，请稍后再试")
        else -> ApiException.Business(code, message)
    }
}
