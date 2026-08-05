package com.susumonitor.server.module.alert.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AlertRecordVo {

    private Long id;
    @JsonProperty("rule_id")
    private Long ruleId;
    @JsonProperty("server_id")
    private Long serverId;
    private String metric;
    @JsonProperty("current_value")
    private BigDecimal currentValue;
    @JsonProperty("threshold_value")
    private BigDecimal thresholdValue;
    private String level;
    private String status;
    private String message;
    @JsonProperty("read_by")
    private Long readBy;
    @JsonProperty("read_at")
    private OffsetDateTime readAt;
    @JsonProperty("triggered_at")
    private OffsetDateTime triggeredAt;
    @JsonProperty("notified_at")
    private OffsetDateTime notifiedAt;
    @JsonProperty("notify_channels")
    private String notifyChannels;
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    /** 将 Entity 的 LocalDateTime 转为 UTC OffsetDateTime。 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
