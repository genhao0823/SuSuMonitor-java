package com.susumonitor.server.module.system.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 消费统计响应 VO（MVP-14 监控收尾）：耗时窗口 + 失败率窗口组合快照。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConsumeStatsVo {

    private String consumer;
    @JsonProperty("total_count")
    private Long totalCount;
    @JsonProperty("avg_ms")
    private Long avgMs;
    @JsonProperty("max_ms")
    private Long maxMs;
    @JsonProperty("last_sample_at")
    private OffsetDateTime lastSampleAt;
    @JsonProperty("failed_window")
    private Long failedWindow;
    @JsonProperty("consumed_window")
    private Long consumedWindow;
    @JsonProperty("failure_rate")
    private Double failureRate;
}
