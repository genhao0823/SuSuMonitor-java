package com.susumonitor.server.module.alert.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AlertRuleVo {

    private Long id;
    @JsonProperty("server_id")
    private Long serverId;
    private String metric;
    private String operator;
    @JsonProperty("threshold_value")
    private BigDecimal thresholdValue;
    private String level;
    @JsonProperty("confirm_count")
    private Integer confirmCount;
    @JsonProperty("notify_email")
    private String notifyEmail;
    @JsonProperty("notify_dingtalk")
    private String notifyDingtalk;
    @JsonProperty("notify_webhook")
    private String notifyWebhook;
    private Boolean enabled;
    @JsonProperty("created_by")
    private Long createdBy;
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    /** 将 Entity 的 LocalDateTime 转为 UTC OffsetDateTime。 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
