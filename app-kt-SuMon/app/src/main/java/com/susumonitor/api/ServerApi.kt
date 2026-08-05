package com.susumonitor.api

import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.PageResult
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerStatus
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 服务器 API，端点与 OpenAPI `openapi-server.json` 对齐。
 */
interface ServerApi {

    /** 分页查询服务器列表，支持 keyword 模糊检索。 */
    @GET("servers")
    suspend fun listServers(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("keyword") keyword: String? = null,
    ): ApiResponse<PageResult<Server>>

    /** 获取单台服务器公开 VO。 */
    @GET("servers/{id}")
    suspend fun getServer(@Path("id") id: Long): ApiResponse<Server>

    /** 获取服务器状态快照（状态、Agent、上次心跳、投递遥测）。 */
    @GET("servers/{id}/status")
    suspend fun getServerStatus(@Path("id") id: Long): ApiResponse<ServerStatus>
}
