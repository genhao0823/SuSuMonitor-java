package com.susumonitor.data.repository

import com.susumonitor.api.AdminApi
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AdminUserQuery
import com.susumonitor.data.model.AdminUserVo
import com.susumonitor.data.model.BatchReviewRequest
import com.susumonitor.data.model.BatchReviewResult
import com.susumonitor.data.model.PageResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理员用户审核仓库：列表/搜索 + 单条/批量通过拒绝。
 */
@Singleton
class AdminRepository @Inject constructor(
    private val adminApi: AdminApi,
) {

    /** 分页查询用户列表。 */
    suspend fun listUsers(query: AdminUserQuery): PageResult<AdminUserVo> {
        val response = adminApi.listUsers(
            page = query.page,
            pageSize = query.pageSize,
            status = query.status,
            keyword = query.keyword,
        )
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 批量通过待审核用户。 */
    suspend fun batchApprove(userIds: List<Long>): BatchReviewResult {
        val response = adminApi.batchApprove(BatchReviewRequest(userIds))
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 批量拒绝待审核用户。 */
    suspend fun batchReject(userIds: List<Long>): BatchReviewResult {
        val response = adminApi.batchReject(BatchReviewRequest(userIds))
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 通过指定用户。 */
    suspend fun approveUser(id: Long) {
        val response = adminApi.approveUser(id)
        if (response.code != com.susumonitor.util.ErrorCodes.SUCCESS) {
            throw ApiException.Business(response.code, response.message)
        }
    }

    /** 拒绝指定用户。 */
    suspend fun rejectUser(id: Long) {
        val response = adminApi.rejectUser(id)
        if (response.code != com.susumonitor.util.ErrorCodes.SUCCESS) {
            throw ApiException.Business(response.code, response.message)
        }
    }
}
