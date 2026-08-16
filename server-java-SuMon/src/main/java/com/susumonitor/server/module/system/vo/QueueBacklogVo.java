package com.susumonitor.server.module.system.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 队列积压快照响应 VO（MVP-14 监控收尾）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueueBacklogVo {

    private String queue;
    /** business=业务队列（超阈值告警对象）；dead_letter=死信队列（仅记录快照）。 */
    private String type;
    private Long messages;
    @JsonProperty("warn_threshold")
    private Integer warnThreshold;
    @JsonProperty("checked_at")
    private OffsetDateTime checkedAt;
    private boolean error;
}
