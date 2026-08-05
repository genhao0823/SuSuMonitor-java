package com.susumonitor.api

import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.Metrics
import com.susumonitor.data.model.PageResult
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 指标 API，端点与 OpenAPI `openapi-server.json` 对齐。
 */
interface MetricsApi {

    /** 最新固定宽表指标。 */
    @GET("servers/{id}/metrics/latest")
    suspend fun getLatest(@Path("id") serverId: Long): ApiResponse<Metrics>

    /** 历史指标分页查询（时间范围 UTC ISO-8601）。 */
    @GET("servers/{id}/metrics")
    suspend fun getHistory(
        @Path("id") serverId: Long,
        @Query("start_time") startTime: String,
        @Query("end_time") endTime: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 500,
    ): ApiResponse<PageResult<Metrics>>
}
