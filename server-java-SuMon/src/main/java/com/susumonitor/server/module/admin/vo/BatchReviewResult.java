package com.susumonitor.server.module.admin.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/**
 * 批量审核结果 VO：汇报逐用户处理统计与失败明细，供前端展示"成功 N 项、失败 M 项"。
 */
@Data
@Schema(description = "批量审核处理统计")
public class BatchReviewResult {

    @Schema(description = "成功审核的用户数")
    private int processed;

    @Schema(description = "失败的用户数（状态已变化/不存在）")
    private int failed;

    @JsonProperty("failed_ids")
    @Schema(description = "失败的用户 ID 列表")
    private List<Long> failedIds;
}