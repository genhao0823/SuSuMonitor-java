package com.susumonitor.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

/**
 * 启用 Spring 异步方法执行（供告警外部通知使用）并注册出站 HTTP 客户端与通知执行器。
 */
@Slf4j
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

    /**
     * 尽力而为通知的专用有界执行器（邮件/钉钉/Webhook 出站）。
     *
     * <p>通知方（命令事后通知、AI 解释/健康报告推送）在此线程池异步发送，
     * 绝不阻塞 WS 消息处理与 RabbitMQ 消费线程；有界队列 + 丢弃策略：
     * 超载时记日志后放弃该次通知（best-effort 语义——通知内容本身已落库可回看，
     * 丢失不产生数据问题），绝不排队无限积压或退化为调用方同步执行。</p>
     */
    @Bean(name = "notificationExecutor")
    public ThreadPoolTaskExecutor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("notify-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.setRejectedExecutionHandler((task, target) ->
                log.warn("notification executor saturated, notification discarded (best-effort)"));
        return executor;
    }
}
