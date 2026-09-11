package com.susumonitor.data.repository

import com.susumonitor.api.AlertApi
import com.susumonitor.api.AiApi
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AiAlertExplanation
import com.susumonitor.data.model.AiDiagnosis
import com.susumonitor.data.model.AiDiagnosisRequest
import com.susumonitor.data.model.AiQa
import com.susumonitor.data.model.AiQaRequest
import com.susumonitor.util.ErrorCodes
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

/**
 * AI 智能运维仓库：只读诊断 / 运维问答 / 告警智能解释回看。
 * 后端 AI 开关关闭或解释未生成时端点返回 HTTP 404，统一映射为
 * [AiNotEnabledException] 供 UI 展示未启用空态；限流映射为 [AiRateLimitedException]。
 */
@Singleton
class AiRepository @Inject constructor(
    private val aiApi: AiApi,
    private val alertApi: AlertApi,
) {

    /** AI 功能未在后端启用或解释尚未生成（HTTP 404）。 */
    class AiNotEnabledException(message: String) : Exception(message)

    /** AI 限流/预算耗尽（42906）。 */
    class AiRateLimitedException(message: String) : Exception(message)

    /** 只读健康诊断。 */
    suspend fun diagnose(request: AiDiagnosisRequest): AiDiagnosis = aiCall {
        val response = aiApi.diagnose(request)
        response.data ?: throw mapError(response.code, response.message)
    }

    /** 运维问答（单轮）。 */
    suspend fun ask(request: AiQaRequest): AiQa = aiCall {
        val response = aiApi.ask(request)
        response.data ?: throw mapError(response.code, response.message)
    }

    /** 告警智能解释回看；未生成时后端 404，同样归为未启用语义。 */
    suspend fun alertExplanation(recordId: Long): AiAlertExplanation = aiCall {
        val response = alertApi.explanation(recordId)
        response.data ?: throw mapError(response.code, response.message)
    }

    /**
     * 统一错误语义化：后端错误以非 2xx HTTP 状态返回（HttpException），
     * 信封 code 只在 200 响应中出现，两条路径都要经过 [mapError]。
     */
    private inline fun <T> aiCall(block: () -> T): T =
        try {
            block()
        } catch (e: HttpException) {
            val api = ApiException.from(e)
            val code = (api as? ApiException.Business)?.code ?: 0
            throw mapError(code, api.message ?: "请求失败")
        }

    private fun mapError(code: Int, message: String): Exception = when (code) {
        ErrorCodes.RESOURCE_NOT_FOUND -> AiNotEnabledException("AI 功能未启用")
        ErrorCodes.AI_RATE_LIMIT_REACHED -> AiRateLimitedException("AI 调用频率或额度已达上限，请稍后再试")
        else -> ApiException.Business(code, message)
    }
}
