package com.susumonitor.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;

/**
 * 启用 Spring 异步方法执行（供告警外部通知使用）并注册出站 HTTP 客户端。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 供钉钉/自定义 Webhook 通知使用的出站 HTTP 客户端。
     *
     * <p>连接/读取超时取 {@code susumonitor.alert.notification-http-*-timeout-ms}，
     * 防止渠道挂起拖死通知线程（2026-08-30 通知加固语义，此前键存在但未生效）。</p>
     */
    @Bean
    public RestTemplate restTemplate(AppProperties appProperties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(
                appProperties.getAlert().getNotificationHttpConnectTimeoutMs());
        requestFactory.setReadTimeout(
                appProperties.getAlert().getNotificationHttpReadTimeoutMs());
        return new RestTemplate(requestFactory);
    }
}
