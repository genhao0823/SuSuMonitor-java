package com.susumonitor.api

import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.AutoApprovalPolicy
import com.susumonitor.data.model.AutoApprovalPolicyRequest
import com.susumonitor.data.model.CommandRun
import com.susumonitor.data.model.CommandSuggestionRequest
import com.susumonitor.data.model.CommandTemplate
import com.susumonitor.data.model.ManualCommandRequest
import com.susumonitor.data.model.PageResult
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * AI 审批制命令域 API（M1 + 自动审批策略），端点与 OpenAPI `openapi-command.json` 对齐。
 * 全部端点要求 ROLE_ADMIN；后端 `susumonitor.ai.command.enabled=false` 时 404。
 * 批准后无推送，结果靠轮询 [getRun] 获取。
 */
interface CommandApi {

    /** 白名单命令模板列表（id/argv/风险等级/参数正则）。 */
    @GET("ai/commands/templates")
    suspend fun templates(): ApiResponse<List<CommandTemplate>>

    /** 实例级自动审批策略（行缺失时后端按禁用返回）。 */
    @GET("ai/commands/auto-approval-policy")
    suspend fun getAutoApprovalPolicy(): ApiResponse<AutoApprovalPolicy>

    /** 更新自动审批策略（阈值仅 low/medium，high 后端拒绝）。 */
    @PUT("ai/commands/auto-approval-policy")
    suspend fun updateAutoApprovalPolicy(
        @Body body: AutoApprovalPolicyRequest,
    ): ApiResponse<AutoApprovalPolicy>

    /** AI 根据意图生成待审批命令（每条 suggestion 各成一个 pending 记录）。 */
    @POST("ai/commands/suggestions")
    suspend fun suggest(@Body body: CommandSuggestionRequest): ApiResponse<List<CommandRun>>

    /** 手动按模板发起待审批命令。 */
    @POST("ai/commands/runs")
    suspend fun createManual(@Body body: ManualCommandRequest): ApiResponse<CommandRun>

    /** 分页查询命令运行记录（可按服务器/状态过滤）。 */
    @GET("ai/commands/runs")
    suspend fun listRuns(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("server_id") serverId: Long? = null,
        @Query("status") status: String? = null,
    ): ApiResponse<PageResult<CommandRun>>

    /** 单条运行记录详情（含脱敏截断后的执行结果）。 */
    @GET("ai/commands/runs/{id}")
    suspend fun getRun(@Path("id") id: Long): ApiResponse<CommandRun>

    /** 批准并立即下发执行。 */
    @POST("ai/commands/runs/{id}/approve")
    suspend fun approve(@Path("id") id: Long): ApiResponse<CommandRun>

    /** 驳回待审批命令。 */
    @POST("ai/commands/runs/{id}/reject")
    suspend fun reject(@Path("id") id: Long): ApiResponse<CommandRun>
}
