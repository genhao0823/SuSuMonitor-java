package com.susumonitor.api

import com.susumonitor.data.model.AiDiagnosis
import com.susumonitor.data.model.AiDiagnosisRequest
import com.susumonitor.data.model.AiQa
import com.susumonitor.data.model.AiQaRequest
import com.susumonitor.data.model.ApiResponse
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * AI 智能运维 API，端点与 OpenAPI `openapi-ai.json` 对齐。
 * 全部端点要求 ROLE_ADMIN；后端 `susumonitor.ai.enabled=false` 时端点 404。
 */
interface AiApi {

    /** 只读健康诊断（provider 失败时后端返回 200 + model_used=false 兜底摘要）。 */
    @POST("ai/diagnoses")
    suspend fun diagnose(@Body body: AiDiagnosisRequest): ApiResponse<AiDiagnosis>

    /** 只读工具调用问答（F2）。 */
    @POST("ai/qa")
    suspend fun ask(@Body body: AiQaRequest): ApiResponse<AiQa>
}
