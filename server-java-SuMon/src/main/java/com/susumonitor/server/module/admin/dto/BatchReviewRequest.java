package com.susumonitor.server.module.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

/**
 * 批量审核请求 DTO。
 *
 * <p>user_ids 必须非空且元素为正整数；批量操作逐 id 原子审核，
 * 单个失败（状态已变化/不存在）不影响其余项，返回处理统计。</p>
 */
// 类级 @Schema 描述批量审核请求体，供 springdoc 生成 /api-docs 的请求模型说明。
@Data
@Schema(description = "批量审核请求体")
public class BatchReviewRequest {

    // 待审核用户 ID 列表。
    @NotEmpty(message = "user_ids must not be empty")
    @JsonProperty("user_ids")
    // 描述 user_ids 字段，供 OpenAPI 文档展示。
    @Schema(description = "待审核用户 ID 列表（非空）")
    private List<Long> userIds;
}