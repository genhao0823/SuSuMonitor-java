package com.susumonitor.api

import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.HealthStatus
import com.susumonitor.data.model.MonitorTicket
import com.susumonitor.data.model.ReadyStatus
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 系统与 Monitor Ticket API，端点与 OpenAPI `openapi-system.json` 对齐。
 */
interface SystemApi {

    /** 后端存活状态。 */
    @GET("health")
    suspend fun health(): ApiResponse<HealthStatus>

    /** 后端 + MySQL + RabbitMQ 就绪状态。 */
    @GET("ready")
    suspend fun ready(): ApiResponse<ReadyStatus>

    /** 获取 30 秒有效期的一次性 Monitor Ticket（凭据不进 WS URL）。 */
    @POST("ws/monitor-ticket")
    suspend fun monitorTicket(): ApiResponse<MonitorTicket>
}
