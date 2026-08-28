package com.susumonitor.server.module.alert.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.Data;

/**
 * 告警记录响应 VO，含触发值、阈值和状态。
 *
 * <p>时间字段转为 UTC ISO-8601 输出。
 * status 为 unread/read/resolved。</p>
 */
// 类级 @Schema 描述告警记录模型，供 springdoc 生成响应模型说明。
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "告警记录")
public class AlertRecordVo {

    @Schema(description = "记录 ID")
    private Long id;
    @JsonProperty("rule_id")
    @Schema(description = "触发规则的规则 ID")
    private Long ruleId;
    @JsonProperty("server_id")
    @Schema(description = "服务器 ID", minimum = "1")
    private Long serverId;
    @Schema(description = "告警指标")
    private String metric;
    @JsonProperty("current_value")
    @Schema(description = "触发时的当前指标值")
    private BigDecimal currentValue;
    @JsonProperty("threshold_value")
    @Schema(description = "触发时的规则阈值")
    private BigDecimal thresholdValue;
    @Schema(description = "告警等级", allowableValues = {"warning", "critical"})
    private String level;
    @Schema(description = "记录状态", allowableValues = {"unread", "read", "resolved"})
    private String status;
    @Schema(description = "告警消息")
    private String message;
    @JsonProperty("read_by")
    @Schema(description = "标记已读的用户 ID")
    private Long readBy;
    @JsonProperty("read_at")
    @Schema(description = "标记已读时间（未读为 null）")
    private OffsetDateTime readAt;
    @JsonProperty("triggered_at")
    @Schema(description = "触发时间（UTC ISO-8601）")
    private OffsetDateTime triggeredAt;
    @JsonProperty("resolved_at")
    // 描述恢复时间字段，仅 status=resolved 时存在，未恢复为 null。
    @Schema(description = "恢复时间；仅 status=resolved 时存在，未恢复为 null", format = "date-time")
    private OffsetDateTime resolvedAt;
    @JsonProperty("notified_at")
    @Schema(description = "外部通知发送完成时间；null=未成功发送")
    private OffsetDateTime notifiedAt;
    @JsonProperty("notify_channels")
    @Schema(description = "成功送达的渠道，逗号分隔（email/dingtalk/webhook）；null=未成功发送")
    private String notifyChannels;
    @JsonProperty("created_at")
    @Schema(description = "记录创建时间（UTC ISO-8601）")
    private OffsetDateTime createdAt;

    /** 将 Entity 的 LocalDateTime 转为 UTC OffsetDateTime。 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
