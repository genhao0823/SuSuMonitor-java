package com.susumonitor.data.model

import kotlinx.serialization.Serializable

/** 注册请求，与 OpenAPI `RegisterRequest` 对齐。 */
@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
)

/** 登录请求，与 OpenAPI `LoginRequest` 对齐。 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

/**
 * 登录结果，与 OpenAPI `LoginVo` 对齐。
 * 注意：auth 模块契约字段为 camelCase（tokenType/expiresIn/user.reviewStatus），
 * 与 server/metrics/alert 模块的 snake_case 不同，此处不做 @SerialName 转换。
 */
@Serializable
data class LoginVo(
    val token: String,
    val tokenType: String,
    val expiresIn: Long,
    val user: CurrentUser,
)

/** 当前用户，与 OpenAPI `CurrentUser` 对齐（auth 契约 camelCase）。 */
@Serializable
data class CurrentUser(
    val id: Long,
    val username: String,
    val role: String,
    val reviewStatus: String,
    val reviewedAt: String? = null,
    val createdAt: String,
)

/** 持久化的登录会话（App 内部结构，非后端契约）。 */
data class Session(
    val token: String,
    val user: CurrentUser,
)
