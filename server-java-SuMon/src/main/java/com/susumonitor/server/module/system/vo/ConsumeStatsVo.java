package com.susumonitor.server.module.system.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 消费统计响应 VO（MVP-14 监控收尾）：耗时窗口 + 失败率窗口组合快照。
 */
// 类级 @Schema 描述消费统计快照模型，供 springdoc 生成响应模型说明。
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "单个消费者的耗时窗口与失败率窗口组合快照")
public class ConsumeStatsVo {

    @Schema(description = "消费者名称")
    private String consumer;
    @JsonProperty("total_count")
    @Schema(description = "累计处理消息数")
    private Long totalCount;
    @JsonProperty("avg_ms")
    @Schema(description = "平均处理耗时毫秒（无样本为 null）")
    private Long avgMs;
    @JsonProperty("max_ms")
    @Schema(description = "最大处理耗时毫秒")
    private Long maxMs;
    @JsonProperty("last_sample_at")
    @Schema(description = "最近一次样本时间（无样本为 null）")
    private OffsetDateTime lastSampleAt;
    @JsonProperty("failed_window")
    @Schema(description = "窗口内失败数")
    private Long failedWindow;
    @JsonProperty("consumed_window")
    @Schema(description = "窗口内消费数")
    private Long consumedWindow;
    @JsonProperty("failure_rate")
    @Schema(description = "窗口内失败率（0-1 小数）")
    private Double failureRate;
}
