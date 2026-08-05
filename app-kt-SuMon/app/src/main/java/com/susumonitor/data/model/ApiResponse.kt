package com.susumonitor.data.model

import kotlinx.serialization.Serializable

/**
 * 后端统一响应外壳，与 OpenAPI `ErrorResponse` / 各 `*Response` schema 对齐。
 *
 * @property code 业务码：0=成功；非 0 参见 [com.susumonitor.util.ErrorCodes]
 * @property message 结果描述
 * @property data 业务数据；错误或空响应时为 null
 */
@Serializable
data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T? = null,
)
