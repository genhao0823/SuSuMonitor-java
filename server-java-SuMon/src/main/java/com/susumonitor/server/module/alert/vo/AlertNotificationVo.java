package com.susumonitor.server.module.alert.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 告警通知投递记录响应 VO。
 *
 * <p>一次告警每个渠道一行；status 为 pending/sent/failed，
 * nextAttemptAt 为 null 表示不再重试，lastError 记录最近失败原因。</p>
 */
// 类级 @Schema 描述告警通知投递记录模型，供 springdoc 生成响应模型说明。
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "告警通知投递记录（每条渠道一行）")
public class AlertNotificationVo {

    @Schema(description = "通知记录 ID")
    private Long id;
    @JsonProperty("alert_record_id")
    @Schema(description = "所属告警记录 ID")
    private Long alertRecordId;
    @Schema(description = "通知渠道", allowableValues = {"email", "dingtalk", "webhook"})
    private String channel;
    @Schema(description = "投递状态", allowableValues = {"pending", "sent", "failed"})
    private String status;
    @Schema(description = "尝试次数")
    private Integer attempts;
    @JsonProperty("next_attempt_at")
    @Schema(description = "下次重试时间；null=不再重试")
    private OffsetDateTime nextAttemptAt;
    @JsonProperty("last_error")
    @Schema(description = "最近一次失败原因")
    private String lastError;
    @JsonProperty("created_at")
    @Schema(description = "创建时间（UTC ISO-8601）")
    private OffsetDateTime createdAt;
    @JsonProperty("updated_at")
    @Schema(description = "更新时间（UTC ISO-8601）")
    private OffsetDateTime updatedAt;

    /** 将 Entity 的 LocalDateTime 转为 UTC OffsetDateTime。 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atOffset(java.time.ZoneOffset.UTC);
    }
}
