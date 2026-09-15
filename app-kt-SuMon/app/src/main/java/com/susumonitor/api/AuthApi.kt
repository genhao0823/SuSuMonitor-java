package com.susumonitor.api

import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.BootstrapStatus
import com.susumonitor.data.model.CurrentUser
import com.susumonitor.data.model.LoginRequest
import com.susumonitor.data.model.LoginVo
import com.susumonitor.data.model.RegisterRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 认证 API，端点与 OpenAPI `openapi-auth.json` 0.3.0 对齐。
 */
interface AuthApi {

    /** 注册用户。首管理员未初始化时须携带一次性初始化令牌（40310/40311）。 */
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): ApiResponse<CurrentUser>

    /** 查询首管理员初始化状态（公开端点），决定注册页是否展示令牌输入框。 */
    @GET("auth/bootstrap-status")
    suspend fun bootstrapStatus(): ApiResponse<BootstrapStatus>

    /** 登录。仅 approved 用户可登录；pending/rejected 返回 40300。 */
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): ApiResponse<LoginVo>

    /** 获取当前用户最新数据库状态。 */
    @GET("auth/me")
    suspend fun me(): ApiResponse<CurrentUser>

    /** 无状态退出，客户端自行删除 token。 */
    @POST("auth/logout")
    suspend fun logout(): ApiResponse<Unit>
}
