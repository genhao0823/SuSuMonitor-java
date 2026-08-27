package com.susumonitor.server.module.alert.dto;

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
public class CreateAlertRuleRequest {

    // 服务器 ID，为 null 表示通用规则。
    @JsonProperty("server_id")
    private Long serverId;
    // 告警指标: cpu/memory/disk/temperature/load。
    @NotBlank(message = "metric must not be blank")
    private String metric;
    // 比较操作符: >/>=/</<=。
    @NotBlank(message = "operator must not be blank")
    private String operator;
    // 告警阈值，必须非负。
    @NotNull(message = "threshold value must not be null")
    @PositiveOrZero(message = "threshold value must be non-negative")
    @JsonProperty("threshold_value")
    private BigDecimal thresholdValue;
    // 告警等级: warning/critical。
    @NotBlank(message = "level must not be blank")
    private String level;
    // 连续越界确认次数（可选，默认 1=立即触发；>1=连续越界 N 次触发）。
    @JsonProperty("confirm_count")
    private Integer confirmCount;
    // 通知邮件地址，多个用英文逗号分隔（可选）。
    @Size(max = 500, message = "notify email must be at most 500 characters")
    @JsonProperty("notify_email")
    private String notifyEmail;
    // 钉钉机器人 Webhook URL（可选）。
    @Size(max = 500, message = "notify dingtalk must be at most 500 characters")
    @JsonProperty("notify_dingtalk")
    private String notifyDingtalk;
    // 自定义 Webhook URL（可选，POST JSON）。
    @Size(max = 500, message = "notify webhook must be at most 500 characters")
    @JsonProperty("notify_webhook")
    private String notifyWebhook;
}
