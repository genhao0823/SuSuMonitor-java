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
 * 更新告警规则请求 DTO。
 *
 * <p>只允许更新阈值、等级和启用状态，不允许修改 metric、operator 和 serverId。
 * 这些核心字段在创建后不可变，修改它们等价于新建规则。</p>
 */
// 类级 @Schema 描述更新告警规则请求体，供 springdoc 生成 /api-docs 的请求模型说明。
@Data
@Schema(description = "更新告警规则请求体：只允许更新阈值、等级与启用状态")
public class UpdateAlertRuleRequest {

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
    // 是否启用。
    @NotNull(message = "enabled must not be null")
    // 描述启用状态字段，供 OpenAPI 文档展示。
    @Schema(description = "是否启用")
    private Boolean enabled;
    // 连续越界确认次数（可选，传入则更新；默认保持原值）。
    @JsonProperty("confirm_count")
    // 描述确认次数字段，不传表示保持原值。
    @Schema(description = "连续越界确认次数（可选，不传保持原值）", minimum = "1")
    private Integer confirmCount;
    // 通知邮件地址，多个用英文逗号分隔（传入则更新，默认保持原值）。
    @Size(max = 500, message = "notify email must be at most 500 characters")
    @JsonProperty("notify_email")
    // 描述通知邮件字段，不传表示保持原值。
    @Schema(description = "通知邮件地址（传入则更新，不传保持原值）", maxLength = 500)
    private String notifyEmail;
    // 钉钉机器人 Webhook URL（传入则更新，默认保持原值）。
    @Size(max = 500, message = "notify dingtalk must be at most 500 characters")
    @JsonProperty("notify_dingtalk")
    // 描述钉钉 Webhook 字段，不传表示保持原值。
    @Schema(description = "钉钉机器人 Webhook URL（传入则更新，不传保持原值）", maxLength = 500)
    private String notifyDingtalk;
    // 自定义 Webhook URL（传入则更新，默认保持原值）。
    @Size(max = 500, message = "notify webhook must be at most 500 characters")
    @JsonProperty("notify_webhook")
    // 描述自定义 Webhook 字段，不传表示保持原值。
    @Schema(description = "自定义 Webhook URL（传入则更新，不传保持原值）", maxLength = 500)
    private String notifyWebhook;
}
