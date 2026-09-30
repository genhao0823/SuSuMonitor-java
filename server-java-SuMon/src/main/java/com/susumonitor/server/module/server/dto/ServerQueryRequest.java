package com.susumonitor.server.module.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 接收服务器分页、关键词和排序查询参数。
 */
@Data
public class ServerQueryRequest {

    @Min(1)
    private Integer page = 1;

    @Min(1)
    @Max(100)
    @JsonProperty("page_size")
    private Integer pageSize = 20;

    @Size(max = 100)
    private String keyword;

    // 排序字段只允许映射层支持的固定白名单，避免动态 SQL 注入。
    @Pattern(regexp = "^(id|name|host|status|created_at|updated_at)$")
    @JsonProperty("sort_by")
    private String sortBy = "id";

    @Pattern(regexp = "^(asc|desc)$")
    @JsonProperty("sort_order")
    private String sortOrder = "desc";
}
