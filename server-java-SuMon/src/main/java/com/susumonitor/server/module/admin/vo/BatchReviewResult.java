package com.susumonitor.server.module.admin.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

/**
 * 批量审核结果 VO：汇报逐用户处理统计与失败明细，供前端展示"成功 N 项、失败 M 项"。
 */
@Data
public class BatchReviewResult {

    // 成功审核的用户数。
    private int processed;

    // 失败的用户数（状态已变化/不存在/参数非法）。
    private int failed;

    // 失败的用户 ID 列表（前端可映射用户名展示明细）。
    @JsonProperty("failed_ids")
    private List<Long> failedIds;
}