package com.susumonitor.server.module.admin.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/**
 * 批量审核结果 VO：汇报逐用户处理统计与失败明细，供前端展示"成功 N 项、失败 M 项"。
 */
// 类级 @Schema 描述批量审核结果模型，供 springdoc 生成 /api-docs 的响应模型说明。
@Data
@Schema(description = "批量审核处理统计")
public class BatchReviewResult {

    // 成功审核的用户数。
    @Schema(description = "成功审核的用户数")
    private int processed;

    // 失败的用户数（状态已变化/不存在/参数非法）。
    @Schema(description = "失败的用户数（状态已变化/不存在）")
    private int failed;

    // 失败的用户 ID 列表（前端可映射用户名展示明细）。
    @JsonProperty("failed_ids")
    @Schema(description = "失败的用户 ID 列表")
    private List<Long> failedIds;
}