package com.susumonitor.api

import com.susumonitor.data.model.AgentTokenVo
import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.ConfirmSshHostKeyRequest
import com.susumonitor.data.model.CreateServerRequest
import com.susumonitor.data.model.PageResult
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerStatus
import com.susumonitor.data.model.SshHostKeyVo
import com.susumonitor.data.model.SshTestResult
import com.susumonitor.data.model.UpdateServerRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 服务器 API，端点与 OpenAPI `openapi-server.json` 对齐。
 * CRUD 与 SSH/Agent 管理端点要求 ROLE_ADMIN。
 */
interface ServerApi {

    /** 分页查询服务器列表，支持 keyword 模糊检索与排序。 */
    @GET("servers")
    suspend fun listServers(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("keyword") keyword: String? = null,
        @Query("sort_by") sortBy: String? = null,
        @Query("sort_order") sortOrder: String? = null,
    ): ApiResponse<PageResult<Server>>

    /** 获取单台服务器公开 VO。 */
    @GET("servers/{id}")
    suspend fun getServer(@Path("id") id: Long): ApiResponse<Server>

    /** 创建服务器（admin）。 */
    @POST("servers")
    suspend fun createServer(@Body body: CreateServerRequest): ApiResponse<Server>

    /** 更新服务器（admin）。 */
    @PUT("servers/{id}")
    suspend fun updateServer(
        @Path("id") id: Long,
        @Body body: UpdateServerRequest,
    ): ApiResponse<Server>

    /** 软删除服务器（admin）。 */
    @DELETE("servers/{id}")
    suspend fun deleteServer(@Path("id") id: Long): ApiResponse<Unit>

    /** 获取服务器状态快照（状态、Agent、上次心跳、投递遥测）。 */
    @GET("servers/{id}/status")
    suspend fun getServerStatus(@Path("id") id: Long): ApiResponse<ServerStatus>

    /** SSH 连接测试（admin；无请求体）。 */
    @POST("servers/{id}/ssh/test")
    suspend fun testSsh(@Path("id") id: Long): ApiResponse<SshTestResult>

    /** 确认/轮换 SSH 主机公钥指纹（admin）。 */
    @PUT("servers/{id}/ssh/host-key")
    suspend fun confirmSshHostKey(
        @Path("id") id: Long,
        @Body body: ConfirmSshHostKeyRequest,
    ): ApiResponse<SshHostKeyVo>

    /** 生成 Agent Token（admin；明文仅此响应一次）。 */
    @POST("servers/{id}/agent/register")
    suspend fun registerAgentToken(@Path("id") id: Long): ApiResponse<AgentTokenVo>

    /** 轮换 Agent Token（admin；明文仅此响应一次）。 */
    @POST("servers/{id}/agent/rotate")
    suspend fun rotateAgentToken(@Path("id") id: Long): ApiResponse<AgentTokenVo>

    /** 吊销 Agent Token（admin）。 */
    @DELETE("servers/{id}/agent/revoke")
    suspend fun revokeAgentToken(@Path("id") id: Long): ApiResponse<Unit>
}
