package com.susumonitor.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 创建告警规则请求，与 OpenAPI `CreateAlertRuleRequest` 对齐。
 * server_id 省略或 null 表示全局规则。
 */
@Serializable
data class CreateAlertRuleRequest(
    @SerialName("server_id") val serverId: Long? = null,
    val metric: String,
    val operator: String,
    @SerialName("threshold_value") val thresholdValue: Double,
    val level: String,
    @SerialName("confirm_count") val confirmCount: Int? = null,
    @SerialName("notify_email") val notifyEmail: String? = null,
    @SerialName("notify_dingtalk") val notifyDingtalk: String? = null,
    @SerialName("notify_webhook") val notifyWebhook: String? = null,
)

/**
 * 更新告警规则请求，与 OpenAPI `UpdateAlertRuleRequest` 对齐。
 * 后端禁止修改 metric/operator/server_id，调用方仅传阈值/等级/启用/确认次数/通知。
 */
@Serializable
data class UpdateAlertRuleRequest(
    @SerialName("threshold_value") val thresholdValue: Double,
    val level: String,
    val enabled: Boolean,
    @SerialName("confirm_count") val confirmCount: Int? = null,
    @SerialName("notify_email") val notifyEmail: String? = null,
    @SerialName("notify_dingtalk") val notifyDingtalk: String? = null,
    @SerialName("notify_webhook") val notifyWebhook: String? = null,
)
