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
// 自动生成当前分页 VO 的 getter、setter、toString、equals 和 hashCode 方法。
@Data
// 类级 @Schema 描述通用分页结果模型，供 springdoc 生成响应模型说明。
@Schema(description = "通用分页结果")
public class PageResult<T> {

    @Schema(description = "当前页数据列表")
    private List<T> items;
    @Schema(description = "总记录数", minimum = "0")
    private long total;
    @Schema(description = "当前页码（从 1 起）", minimum = "1")
    private int page;
    // 将 Java 的 pageSize 属性映射为分页响应字段 page_size。
    @JsonProperty("page_size")
    @Schema(description = "每页大小（1-100）", minimum = "1", maximum = "100")
    private int pageSize;

}
