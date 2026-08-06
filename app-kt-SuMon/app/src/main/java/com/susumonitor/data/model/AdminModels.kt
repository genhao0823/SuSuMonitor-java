package com.susumonitor.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 审核状态枚举。 */
object ReviewStatusValues {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
}

/** 管理员视角的用户条目，与 OpenAPI `AdminUserVo` 对齐（admin 模块为 camelCase，云端实测）。 */
@Serializable
data class AdminUserVo(
    val id: Long,
    val username: String,
    val role: String,
    @SerialName("reviewStatus") val reviewStatus: String,
    @SerialName("createdAt") val createdAt: String,
)

/** 批量审核请求体，与 OpenAPI `BatchReviewRequest` 对齐。 */
@Serializable
data class BatchReviewRequest(
    @SerialName("user_ids") val userIds: List<Long>,
)

/** 批量审核结果，与 OpenAPI `BatchReviewResult` 对齐。 */
@Serializable
data class BatchReviewResult(
    val processed: Int,
    val failed: Int,
    @SerialName("failed_ids") val failedIds: List<Long>,
)

/** 用户审核查询参数（对齐 openapi-admin listUsers parameters）。 */
data class AdminUserQuery(
    val status: String? = null,
    val keyword: String? = null,
    val page: Int = 1,
    val pageSize: Int = 20,
)
