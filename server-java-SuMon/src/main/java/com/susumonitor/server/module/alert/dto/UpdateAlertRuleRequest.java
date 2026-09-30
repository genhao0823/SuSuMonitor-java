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
@Data
@Schema(description = "更新告警规则请求体：只允许更新阈值、等级与启用状态")
public class UpdateAlertRuleRequest {

    @NotNull(message = "threshold value must not be null")
    @PositiveOrZero(message = "threshold value must be non-negative")
    @JsonProperty("threshold_value")
    @Schema(description = "告警阈值，必须非负", minimum = "0")
    private BigDecimal thresholdValue;
    @NotBlank(message = "level must not be blank")
    @Schema(description = "告警等级", allowableValues = {"warning", "critical"})
    private String level;
    @NotNull(message = "enabled must not be null")
    @Schema(description = "是否启用")
    private Boolean enabled;
    @JsonProperty("confirm_count")
    @Schema(description = "连续越界确认次数（可选，不传保持原值）", minimum = "1")
    private Integer confirmCount;
    @Size(max = 500, message = "notify email must be at most 500 characters")
    @JsonProperty("notify_email")
    @Schema(description = "通知邮件地址（传入则更新，不传保持原值）", maxLength = 500)
    private String notifyEmail;
    @Size(max = 500, message = "notify dingtalk must be at most 500 characters")
    @JsonProperty("notify_dingtalk")
    @Schema(description = "钉钉机器人 Webhook URL（传入则更新，不传保持原值）", maxLength = 500)
    private String notifyDingtalk;
    @Size(max = 500, message = "notify webhook must be at most 500 characters")
    @JsonProperty("notify_webhook")
    @Schema(description = "自定义 Webhook URL（传入则更新，不传保持原值）", maxLength = 500)
    private String notifyWebhook;
}
