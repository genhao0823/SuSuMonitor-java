package com.susumonitor.server.common.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

/**
 * 通用分页查询结果，由 MyBatis Plus 分页插件填充。
 *
 * @param <T> 列表数据类型
 */
// 自动生成当前分页 VO 的 getter、setter、toString、equals 和 hashCode 方法。
@Data
public class PageResult<T> {

    private List<T> items;
    private long total;
    private int page;
    // 将 Java 的 pageSize 属性映射为分页响应字段 page_size。
    @JsonProperty("page_size")
    private int pageSize;

}
