package com.susumonitor.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;

/**
 * 启用 Spring 异步方法执行（供告警外部通知使用）并注册出站 HTTP 客户端。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 供钉钉/自定义 Webhook 通知使用的出站 HTTP 客户端。 */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
