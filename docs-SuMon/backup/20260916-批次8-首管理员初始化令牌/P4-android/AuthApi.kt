package com.susumonitor.api

import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.CurrentUser
import com.susumonitor.data.model.LoginRequest
import com.susumonitor.data.model.LoginVo
import com.susumonitor.data.model.RegisterRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 认证 API，端点与 OpenAPI `openapi-auth.json` 对齐。
 */
interface AuthApi {

    /** 注册用户。首个注册用户自动成为 admin/approved，后续为 user/pending。 */
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): ApiResponse<CurrentUser>

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
