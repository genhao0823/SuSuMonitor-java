package com.susumonitor.server.common.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/**
 * 通用分页查询结果：各 Service 以 MyBatis-Plus 分页插件（IPage 参数）执行
 * COUNT/LIMIT 查询后填充，响应结构保持 items/total/page/page_size 不变。
 *
 * @param <T> 列表数据类型
 */
@Data
@Schema(description = "通用分页结果")
public class PageResult<T> {

    @Schema(description = "当前页数据列表")
    private List<T> items;
    @Schema(description = "总记录数", minimum = "0")
    private long total;
    @Schema(description = "当前页码（从 1 起）", minimum = "1")
    private int page;
    @JsonProperty("page_size")
    @Schema(description = "每页大小（1-100）", minimum = "1", maximum = "100")
    private int pageSize;

}
