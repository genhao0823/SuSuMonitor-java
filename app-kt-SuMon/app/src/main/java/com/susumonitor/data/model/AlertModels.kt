package com.susumonitor.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 告警指标枚举（与 OpenAPI `CreateAlertRuleRequest.metric` 对齐）。 */
object AlertMetricValues {
    const val CPU = "cpu"
    const val MEMORY = "memory"
    const val DISK = "disk"
    const val TEMPERATURE = "temperature"
    const val LOAD = "load"
}

/** 告警等级（与 OpenAPI `AlertRule.level` 对齐）。 */
object AlertLevelValues {
    const val WARNING = "warning"
    const val CRITICAL = "critical"
}

/** 告警记录状态（与 OpenAPI `AlertRecord.status` 对齐）。 */
object AlertStatusValues {
    const val UNREAD = "unread"
    const val READ = "read"
    const val RESOLVED = "resolved"
}

/** 告警规则（与 OpenAPI `AlertRule` schema 对齐）。 */
@Serializable
data class AlertRule(
    val id: Long,
    @SerialName("server_id") val serverId: Long? = null,
    val metric: String,
    val operator: String,
    @SerialName("threshold_value") val thresholdValue: Double,
    val level: String,
    @SerialName("confirm_count") val confirmCount: Int,
    @SerialName("notify_email") val notifyEmail: String? = null,
    @SerialName("notify_dingtalk") val notifyDingtalk: String? = null,
    @SerialName("notify_webhook") val notifyWebhook: String? = null,
    val enabled: Boolean,
    @SerialName("created_by") val createdBy: Long? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** 告警记录（与 OpenAPI `AlertRecord` schema 对齐）。 */
@Serializable
data class AlertRecord(
    val id: Long,
    @SerialName("rule_id") val ruleId: Long? = null,
    @SerialName("server_id") val serverId: Long,
    val metric: String,
    @SerialName("current_value") val currentValue: Double,
    @SerialName("threshold_value") val thresholdValue: Double,
    val level: String,
    val status: String,
    val message: String? = null,
    @SerialName("read_by") val readBy: Long? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("triggered_at") val triggeredAt: String,
    @SerialName("notified_at") val notifiedAt: String? = null,
    @SerialName("notify_channels") val notifyChannels: String? = null,
    @SerialName("created_at") val createdAt: String,
)

/** 告警记录分页查询参数（与 OpenAPI `listAlertRecords` parameters 对齐）。 */
data class AlertRecordQuery(
    val page: Int = 1,
    val pageSize: Int = 20,
    val serverId: Long? = null,
    val status: String? = null,
)

/** `/ws/monitor` alert.push payload 顶层结构。 */
@Serializable
data class AlertPushPayload(
    @SerialName("server_id") val serverId: Long,
    val alert: AlertPushAlert,
)

/** alert.push 内嵌的简化告警对象（不承诺携带 message/read_by/created_at）。 */
@Serializable
data class AlertPushAlert(
    val id: Long,
    @SerialName("rule_id") val ruleId: Long? = null,
    val metric: String,
    @SerialName("current_value") val currentValue: Double,
    @SerialName("threshold_value") val thresholdValue: Double,
    val level: String,
    val status: String,
    @SerialName("triggered_at") val triggeredAt: String,
)
