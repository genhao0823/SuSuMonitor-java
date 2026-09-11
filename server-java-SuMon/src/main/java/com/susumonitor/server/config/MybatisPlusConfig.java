package com.susumonitor.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配：注册分页内嵌拦截器，使各 Mapper 携带 IPage 参数的
 * 自定义 XML 查询自动执行 COUNT 并追加 LIMIT，替代各 Service 手写
 * offset + COUNT/LIMIT 分页（响应 PageResult 结构保持不变）。
 */
@Configuration
public class MybatisPlusConfig {

    /** 分页拦截器按 MySQL 方言生成分页语句。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
