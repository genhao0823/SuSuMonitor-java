package com.susumonitor.api

import com.susumonitor.data.model.AiDiagnosis
import com.susumonitor.data.model.AiDiagnosisRequest
import com.susumonitor.data.model.AiHealthReport
import com.susumonitor.data.model.AiQa
import com.susumonitor.data.model.AiQaRequest
import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.GenerateHealthReportRequest
import com.susumonitor.data.model.PageResult
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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

    /** 分页查询定时健康报告（F3，按 report_date 倒序；后端 report 开关关闭时 404）。 */
    @GET("ai/health-reports")
    suspend fun listHealthReports(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
    ): ApiResponse<PageResult<AiHealthReport>>

    /** 单份健康报告完整视图（含聚合事实快照）。 */
    @GET("ai/health-reports/{id}")
    suspend fun getHealthReport(@Path("id") id: Long): ApiResponse<AiHealthReport>

    /** 手动触发生成（或重生成）一份报告；report_date 缺省由后端取昨日。 */
    @POST("ai/health-reports/generate")
    suspend fun generateHealthReport(@Body body: GenerateHealthReportRequest): ApiResponse<AiHealthReport>
}
