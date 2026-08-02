package com.susumonitor.server.module.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

/**
 * 批量审核请求 DTO。
 *
 * <p>user_ids 必须非空且元素为正整数；批量操作逐 id 原子审核，
 * 单个失败（状态已变化/不存在）不影响其余项，返回处理统计。</p>
 */
@Data
public class BatchReviewRequest {

    // 待审核用户 ID 列表。
    @NotEmpty(message = "user_ids must not be empty")
    @JsonProperty("user_ids")
    private List<Long> userIds;
}