package com.susumonitor.api

import com.susumonitor.data.model.AlertNotification
import com.susumonitor.data.model.AlertRecord
import com.susumonitor.data.model.AlertRule
import com.susumonitor.data.model.AiAlertExplanation
import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.CreateAlertRuleRequest
import com.susumonitor.data.model.PageResult
import com.susumonitor.data.model.UpdateAlertRuleRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 告警 API，端点与 OpenAPI `openapi-alert.json` 对齐。
 * 规则查询任意已认证用户可调；规则 CRUD 要求 ROLE_ADMIN。
 */
interface AlertApi {

    /** 列出全部告警规则（created_at DESC）。 */
    @GET("alerts/rules")
    suspend fun listRules(): ApiResponse<List<AlertRule>>

    /** 创建告警规则（admin）。 */
    @POST("alerts/rules")
    suspend fun createRule(@Body body: CreateAlertRuleRequest): ApiResponse<AlertRule>

    /** 更新告警规则（admin；禁止修改 metric/operator/server_id）。 */
    @PUT("alerts/rules/{id}")
    suspend fun updateRule(
        @Path("id") id: Long,
        @Body body: UpdateAlertRuleRequest,
    ): ApiResponse<AlertRule>

    /** 软删除告警规则（admin）。 */
    @DELETE("alerts/rules/{id}")
    suspend fun deleteRule(@Path("id") id: Long): ApiResponse<Unit>

    /** 分页查询告警记录，支持 server_id / status 筛选。 */
    @GET("alerts/records")
    suspend fun listRecords(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("server_id") serverId: Long? = null,
        @Query("status") status: String? = null,
    ): ApiResponse<PageResult<AlertRecord>>

    /** 标记告警记录为已读（仅 unread 生效）。 */
    @PUT("alerts/records/{id}/read")
    suspend fun markRead(@Path("id") id: Long): ApiResponse<Unit>

    /** 告警智能解释回看（F1；异步生成后查询，未生成时 404）。 */
    @GET("alerts/records/{id}/explanation")
    suspend fun explanation(@Path("id") id: Long): ApiResponse<AiAlertExplanation>

    /** 单条告警的外发通知投递记录（渠道/状态/重试）。 */
    @GET("alerts/records/{id}/notifications")
    suspend fun notifications(@Path("id") id: Long): ApiResponse<List<AlertNotification>>
}
