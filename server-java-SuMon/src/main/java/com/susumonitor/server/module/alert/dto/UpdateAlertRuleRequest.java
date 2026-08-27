package com.susumonitor.server.module.alert.dto;

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
public class UpdateAlertRuleRequest {

    // 告警阈值，必须非负。
    @NotNull(message = "threshold value must not be null")
    @PositiveOrZero(message = "threshold value must be non-negative")
    @JsonProperty("threshold_value")
    private BigDecimal thresholdValue;
    // 告警等级: warning/critical。
    @NotBlank(message = "level must not be blank")
    private String level;
    // 是否启用。
    @NotNull(message = "enabled must not be null")
    private Boolean enabled;
    // 连续越界确认次数（可选，传入则更新；默认保持原值）。
    @JsonProperty("confirm_count")
    private Integer confirmCount;
    // 通知邮件地址，多个用英文逗号分隔（传入则更新，默认保持原值）。
    @Size(max = 500, message = "notify email must be at most 500 characters")
    @JsonProperty("notify_email")
    private String notifyEmail;
    // 钉钉机器人 Webhook URL（传入则更新，默认保持原值）。
    @Size(max = 500, message = "notify dingtalk must be at most 500 characters")
    @JsonProperty("notify_dingtalk")
    private String notifyDingtalk;
    // 自定义 Webhook URL（传入则更新，默认保持原值）。
    @Size(max = 500, message = "notify webhook must be at most 500 characters")
    @JsonProperty("notify_webhook")
    private String notifyWebhook;
}
