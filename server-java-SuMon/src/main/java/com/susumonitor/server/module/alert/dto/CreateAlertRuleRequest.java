package com.susumonitor.server.module.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 创建告警规则请求 DTO。
 *
 * <p>serverId 为 null 表示通用规则，匹配所有服务器。
 * metric 必须是 cpu/memory/disk/temperature/load 之一。
 * operator 必须是 >/>=/</<= 之一。
 * level 必须是 warning/critical 之一。</p>
 */
@Data
@Schema(description = "创建告警规则请求体")
public class CreateAlertRuleRequest {

    @JsonProperty("server_id")
    @Schema(description = "服务器 ID；null 表示通用规则（匹配所有服务器）")
    private Long serverId;
    @NotBlank(message = "metric must not be blank")
    @Schema(description = "告警指标；load 对应 metrics.load_avg 暴露的系统负载均值",
            allowableValues = {"cpu", "memory", "disk", "temperature", "load"})
    private String metric;
    @NotBlank(message = "operator must not be blank")
    @Schema(description = "比较操作符", allowableValues = {">", ">=", "<", "<="})
    private String operator;
    @NotNull(message = "threshold value must not be null")
    @PositiveOrZero(message = "threshold value must be non-negative")
    @JsonProperty("threshold_value")
    @Schema(description = "告警阈值，必须非负", minimum = "0")
    private BigDecimal thresholdValue;
    @NotBlank(message = "level must not be blank")
    @Schema(description = "告警等级", allowableValues = {"warning", "critical"})
    private String level;
    @JsonProperty("confirm_count")
    @Schema(description = "连续越界确认次数（可选，默认 1=立即触发）", minimum = "1", defaultValue = "1")
    private Integer confirmCount;
    @Size(max = 500, message = "notify email must be at most 500 characters")
    @JsonProperty("notify_email")
    @Schema(description = "通知邮件地址，多个用英文逗号分隔（可选）", maxLength = 500)
    private String notifyEmail;
    @Size(max = 500, message = "notify dingtalk must be at most 500 characters")
    @JsonProperty("notify_dingtalk")
    @Schema(description = "钉钉机器人 Webhook URL（可选）", maxLength = 500)
    private String notifyDingtalk;
    @Size(max = 500, message = "notify webhook must be at most 500 characters")
    @JsonProperty("notify_webhook")
    @Schema(description = "自定义 Webhook URL，POST JSON（可选）", maxLength = 500)
    private String notifyWebhook;
}
