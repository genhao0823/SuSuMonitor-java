package com.susumonitor.server.module.alert.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 告警通知投递记录响应 VO。
 *
 * <p>一次告警每个渠道一行；status 为 pending/sent/failed，
 * nextAttemptAt 为 null 表示不再重试，lastError 记录最近失败原因。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AlertNotificationVo {

    private Long id;
    @JsonProperty("alert_record_id")
    private Long alertRecordId;
    private String channel;
    private String status;
    private Integer attempts;
    @JsonProperty("next_attempt_at")
    private OffsetDateTime nextAttemptAt;
    @JsonProperty("last_error")
    private String lastError;
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    /** 将 Entity 的 LocalDateTime 转为 UTC OffsetDateTime。 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atOffset(java.time.ZoneOffset.UTC);
    }
}
