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
// 类级 @Schema 描述创建告警规则请求体，供 springdoc 生成 /api-docs 的请求模型说明。
@Data
@Schema(description = "创建告警规则请求体")
public class CreateAlertRuleRequest {

    // 服务器 ID，为 null 表示通用规则。
    @JsonProperty("server_id")
    // 描述 server_id 字段，null 表示通用规则匹配所有服务器。
    @Schema(description = "服务器 ID；null 表示通用规则（匹配所有服务器）")
    private Long serverId;
    // 告警指标: cpu/memory/disk/temperature/load。
    @NotBlank(message = "metric must not be blank")
    // 描述告警指标字段，load 对应 metrics.load_avg 系统负载均值。
    @Schema(description = "告警指标；load 对应 metrics.load_avg 暴露的系统负载均值",
            allowableValues = {"cpu", "memory", "disk", "temperature", "load"})
    private String metric;
    // 比较操作符: >/>=/</<=。
    @NotBlank(message = "operator must not be blank")
    // 描述比较操作符字段，允许值即契约定义的四个比较符。
    @Schema(description = "比较操作符", allowableValues = {">", ">=", "<", "<="})
    private String operator;
    // 告警阈值，必须非负。
    @NotNull(message = "threshold value must not be null")
    @PositiveOrZero(message = "threshold value must be non-negative")
    @JsonProperty("threshold_value")
    // 描述告警阈值字段，约束与校验注解一致。
    @Schema(description = "告警阈值，必须非负", minimum = "0")
    private BigDecimal thresholdValue;
    // 告警等级: warning/critical。
    @NotBlank(message = "level must not be blank")
    // 描述告警等级字段，允许值即契约定义的两个等级。
    @Schema(description = "告警等级", allowableValues = {"warning", "critical"})
    private String level;
    // 连续越界确认次数（可选，默认 1=立即触发；>1=连续越界 N 次触发）。
    @JsonProperty("confirm_count")
    // 描述确认次数字段，约束与契约一致。
    @Schema(description = "连续越界确认次数（可选，默认 1=立即触发）", minimum = "1", defaultValue = "1")
    private Integer confirmCount;
    // 通知邮件地址，多个用英文逗号分隔（可选）。
    @Size(max = 500, message = "notify email must be at most 500 characters")
    @JsonProperty("notify_email")
    // 描述通知邮件字段，供 OpenAPI 文档展示。
    @Schema(description = "通知邮件地址，多个用英文逗号分隔（可选）", maxLength = 500)
    private String notifyEmail;
    // 钉钉机器人 Webhook URL（可选）。
    @Size(max = 500, message = "notify dingtalk must be at most 500 characters")
    @JsonProperty("notify_dingtalk")
    // 描述钉钉 Webhook 字段，供 OpenAPI 文档展示。
    @Schema(description = "钉钉机器人 Webhook URL（可选）", maxLength = 500)
    private String notifyDingtalk;
    // 自定义 Webhook URL（可选，POST JSON）。
    @Size(max = 500, message = "notify webhook must be at most 500 characters")
    @JsonProperty("notify_webhook")
    // 描述自定义 Webhook 字段，供 OpenAPI 文档展示。
    @Schema(description = "自定义 Webhook URL，POST JSON（可选）", maxLength = 500)
    private String notifyWebhook;
}
