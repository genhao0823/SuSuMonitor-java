package com.susumonitor.api

import com.susumonitor.data.model.AdminUserQuery
import com.susumonitor.data.model.AdminUserVo
import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.BatchReviewRequest
import com.susumonitor.data.model.BatchReviewResult
import com.susumonitor.data.model.CurrentUser
import com.susumonitor.data.model.PageResult
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 管理员用户审核 API，端点与 OpenAPI `openapi-admin.json` 对齐。
 * 全部端点要求 ROLE_ADMIN。
 */
interface AdminApi {

    /** 分页查询用户列表（支持审核状态筛选与用户名搜索）。 */
    @GET("admin/users")
    suspend fun listUsers(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("status") status: String? = null,
        @Query("keyword") keyword: String? = null,
    ): ApiResponse<PageResult<AdminUserVo>>

    /** 批量通过待审核用户。 */
    @PUT("admin/users/batch-approve")
    suspend fun batchApprove(@Body body: BatchReviewRequest): ApiResponse<BatchReviewResult>

    /** 批量拒绝待审核用户。 */
    @PUT("admin/users/batch-reject")
    suspend fun batchReject(@Body body: BatchReviewRequest): ApiResponse<BatchReviewResult>

    /** 通过指定用户。 */
    @PUT("admin/users/{id}/approve")
    suspend fun approveUser(@Path("id") id: Long): ApiResponse<CurrentUser>

    /** 拒绝指定用户。 */
    @PUT("admin/users/{id}/reject")
    suspend fun rejectUser(@Path("id") id: Long): ApiResponse<CurrentUser>
}
