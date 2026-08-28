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
 * 告警规则响应 VO，不含敏感字段。
 *
 * <p>server/snake_case 字段名与服务器模块一致。
 * 时间字段转为 UTC ISO-8601 输出。</p>
 */
// 类级 @Schema 描述告警规则模型，供 springdoc 生成响应模型说明。
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "告警规则")
public class AlertRuleVo {

    @Schema(description = "规则 ID")
    private Long id;
    @JsonProperty("server_id")
    @Schema(description = "服务器 ID；null 表示通用规则")
    private Long serverId;
    @Schema(description = "告警指标", allowableValues = {"cpu", "memory", "disk", "temperature", "load"})
    private String metric;
    @Schema(description = "比较操作符", allowableValues = {">", ">=", "<", "<="})
    private String operator;
    @JsonProperty("threshold_value")
    @Schema(description = "告警阈值")
    private BigDecimal thresholdValue;
    @Schema(description = "告警等级", allowableValues = {"warning", "critical"})
    private String level;
    @JsonProperty("confirm_count")
    @Schema(description = "连续越界确认次数：1=立即触发，>1=连续 N 次越界触发", minimum = "1")
    private Integer confirmCount;
    @JsonProperty("notify_email")
    @Schema(description = "通知邮件地址，多个用英文逗号分隔", maxLength = 500)
    private String notifyEmail;
    @JsonProperty("notify_dingtalk")
    @Schema(description = "钉钉机器人 Webhook URL", maxLength = 500)
    private String notifyDingtalk;
    @JsonProperty("notify_webhook")
    @Schema(description = "自定义 Webhook URL，POST JSON", maxLength = 500)
    private String notifyWebhook;
    @Schema(description = "是否启用")
    private Boolean enabled;
    @JsonProperty("created_by")
    @Schema(description = "创建人用户 ID")
    private Long createdBy;
    @JsonProperty("created_at")
    @Schema(description = "创建时间（UTC ISO-8601）")
    private OffsetDateTime createdAt;
    @JsonProperty("updated_at")
    @Schema(description = "更新时间（UTC ISO-8601）")
    private OffsetDateTime updatedAt;

    /** 将 Entity 的 LocalDateTime 转为 UTC OffsetDateTime。 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
