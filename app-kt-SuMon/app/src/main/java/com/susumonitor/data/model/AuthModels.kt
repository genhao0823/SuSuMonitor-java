package com.susumonitor.data.model

import kotlinx.serialization.Serializable

/**
 * 注册请求，与 OpenAPI `RegisterRequest` 对齐。
 * bootstrapToken 仅在首管理员未初始化时必填（缺失 40310/无效 40311），
 * 经服务器启动日志横幅或 AUTH_BOOTSTRAP_TOKEN 获取；首管理员存在后服务端忽略。
 * 默认 null 在 kotlinx.serialization 默认配置下不参与序列化，保持存量请求体不变。
 */
@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val bootstrapToken: String? = null,
)

/** 首管理员初始化状态，与 OpenAPI `BootstrapStatus` 对齐（auth 契约 camelCase）。 */
@Serializable
data class BootstrapStatus(
    val bootstrapPending: Boolean,
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
