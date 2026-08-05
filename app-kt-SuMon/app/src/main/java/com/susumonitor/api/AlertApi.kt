package com.susumonitor.api

import com.susumonitor.data.model.AlertRecord
import com.susumonitor.data.model.AlertRule
import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.PageResult
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 告警 API，端点与 OpenAPI `openapi-alert.json` 对齐。
 * 本版只读：查询规则/记录 + 标记已读；不包含规则 CRUD。
 */
interface AlertApi {

    /** 列出全部告警规则（created_at DESC）。 */
    @GET("alerts/rules")
    suspend fun listRules(): ApiResponse<List<AlertRule>>

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
}
