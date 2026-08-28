package com.susumonitor.server.module.system.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 队列积压快照响应 VO（MVP-14 监控收尾）。
 */
// 类级 @Schema 描述队列积压快照模型，供 springdoc 生成响应模型说明。
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "单个队列的积压探测快照")
public class QueueBacklogVo {

    @Schema(description = "队列名称")
    private String queue;
    /** business=业务队列（超阈值告警对象）；dead_letter=死信队列（仅记录快照）。 */
    @Schema(description = "队列类型", allowableValues = {"business", "dead_letter"})
    private String type;
    @Schema(description = "当前积压消息数")
    private Long messages;
    @JsonProperty("warn_threshold")
    @Schema(description = "业务队列告警阈值（死信队列为 null）")
    private Integer warnThreshold;
    @JsonProperty("checked_at")
    @Schema(description = "最近探测时间（未探测为 null）")
    private OffsetDateTime checkedAt;
    @Schema(description = "本轮探测是否失败")
    private boolean error;
}
