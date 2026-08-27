package com.susumonitor.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** SSH 认证方式枚举。 */
object SshAuthTypeValues {
    const val PASSWORD = "password"
    const val PRIVATE_KEY = "private_key"
}

/**
 * 创建服务器请求，与 OpenAPI `CreateServerRequest` 对齐。
 * 凭据按 ssh_auth_type 二选一：password 方式必须带 ssh_password；private_key 方式必须带 ssh_private_key。
 */
@Serializable
data class CreateServerRequest(
    val name: String,
    val host: String,
    val description: String? = null,
    @SerialName("ssh_host") val sshHost: String,
    @SerialName("ssh_port") val sshPort: Int = 22,
    @SerialName("ssh_user") val sshUser: String,
    @SerialName("ssh_auth_type") val sshAuthType: String,
    @SerialName("ssh_password") val sshPassword: String? = null,
    @SerialName("ssh_private_key") val sshPrivateKey: String? = null,
    @SerialName("ssh_private_key_passphrase") val sshPrivateKeyPassphrase: String? = null,
)

/**
 * 更新服务器请求，与 OpenAPI `UpdateServerRequest` 对齐。
 * description 为全量语义；凭据字段省略=保留原值，空字符串不发送（由调用方过滤）。
 */
@Serializable
data class UpdateServerRequest(
    val name: String,
    val host: String,
    val description: String? = null,
    @SerialName("ssh_host") val sshHost: String,
    @SerialName("ssh_port") val sshPort: Int,
    @SerialName("ssh_user") val sshUser: String,
    @SerialName("ssh_auth_type") val sshAuthType: String,
    @SerialName("ssh_password") val sshPassword: String? = null,
    @SerialName("ssh_private_key") val sshPrivateKey: String? = null,
    @SerialName("ssh_private_key_passphrase") val sshPrivateKeyPassphrase: String? = null,
)

/** SSH 连接测试结果，与 OpenAPI `SshTestVo` 对齐。 */
@Serializable
data class SshTestResult(
    @SerialName("server_id") val serverId: Long,
    val connected: Boolean,
    @SerialName("host_key_algorithm") val hostKeyAlgorithm: String? = null,
    @SerialName("host_key_fingerprint") val hostKeyFingerprint: String? = null,
    @SerialName("auth_type") val authType: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("tested_at") val testedAt: String,
)

/** 主机指纹确认/轮换请求，与 OpenAPI `ConfirmSshHostKeyRequest` 对齐。 */
@Serializable
data class ConfirmSshHostKeyRequest(
    @SerialName("expected_fingerprint") val expectedFingerprint: String,
    val replace: Boolean = false,
)

/** 主机指纹确认结果，与 OpenAPI `SshHostKeyResult` 对齐。 */
@Serializable
data class SshHostKeyVo(
    @SerialName("server_id") val serverId: Long,
    @SerialName("host_key_algorithm") val hostKeyAlgorithm: String,
    @SerialName("host_key_fingerprint") val hostKeyFingerprint: String,
    val operation: String,
    @SerialName("verified_at") val verifiedAt: String,
)

/** Agent Token 一次性返回值，与 OpenAPI `AgentTokenResult` 对齐。明文仅在 register/rotate 响应出现一次。 */
@Serializable
data class AgentTokenVo(
    @SerialName("server_id") val serverId: Long,
    @SerialName("agent_token") val agentToken: String,
    @SerialName("created_at") val createdAt: String,
)
