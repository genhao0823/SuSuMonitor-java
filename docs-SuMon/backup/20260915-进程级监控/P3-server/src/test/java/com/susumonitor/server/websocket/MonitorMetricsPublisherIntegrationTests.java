package com.susumonitor.server.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.metrics.dto.MetricsReportPayload;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxEnvelopeFactory;
import com.susumonitor.server.module.metrics.outbox.OutboxService;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.metrics.service.MetricsServiceImpl;
import com.susumonitor.server.module.server.entity.ServerEntity;
import com.susumonitor.server.module.server.service.ServerService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 验证 Metrics 事务提交后广播、事务回滚不广播的真实 Spring 事务事件边界。 */
@SpringJUnitConfig(MonitorMetricsPublisherIntegrationTests.TestConfiguration.class)
class MonitorMetricsPublisherIntegrationTests {

    private static final Long SERVER_ID = 1001L;

    @Autowired
    private MetricsService metricsService;

    @Autowired
    private MetricsMapper metricsMapper;

    @Autowired
    private MonitorSubscriptionRegistry registry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private WebSocketSession socketSession;

    /** 每次测试重新挂载可观察的 Monitor 订阅会话。 */
    @BeforeEach
    void setUp() throws Exception {
        reset(metricsMapper, registry);
        when(metricsMapper.insertMetric(any())).thenReturn(1);
        when(metricsMapper.insertIngestion(any())).thenReturn(1);
        socketSession = mock(WebSocketSession.class);
        when(socketSession.isOpen()).thenReturn(true);
        MonitorWebSocketSession subscriber = new MonitorWebSocketSession(socketSession, null);
        when(registry.subscribers(SERVER_ID)).thenReturn(List.of(subscriber));
    }

    /** 验证事务提交后监听器发送一次 metrics.update。 */
    @Test
    void shouldBroadcastAfterTransactionCommits() throws Exception {
        transactionTemplate.executeWithoutResult(status -> metricsService.report(
                SERVER_ID, UUID.randomUUID().toString(), payload()));

        verify(socketSession).sendMessage(any(TextMessage.class));
    }

    /** 验证事务回滚时 AFTER_COMMIT 监听器不会发送 WebSocket 消息。 */
    @Test
    void shouldNotBroadcastWhenTransactionRollsBack() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            metricsService.report(SERVER_ID, UUID.randomUUID().toString(), payload());
            status.setRollbackOnly();
        });

        verify(socketSession, never()).sendMessage(any(TextMessage.class));
    }

    private MetricsReportPayload payload() {
        MetricsReportPayload payload = new MetricsReportPayload();
        payload.setServerId(SERVER_ID);
        payload.setCollectedAt(OffsetDateTime.now(ZoneOffset.UTC));
        payload.setCpuPercent(BigDecimal.valueOf(35.2));
        return payload;
    }

    /** 提供最小真实事务环境和 Mockito 数据访问替身，不运行 Flyway 或真实 MySQL。 */
    @Configuration
    @EnableTransactionManagement
    @Import(MonitorMetricsPublisher.class)
    static class TestConfiguration {

        /** 创建 H2 内存数据源，仅用于驱动真实事务同步回调。 */
        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }

        /** 使用真实 DataSourceTransactionManager 触发 AFTER_COMMIT 和回滚回调。 */
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        /** 提供显式事务模板以精确控制提交和回滚路径。 */
        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        /** 提供测试所需的 JSON 序列化器。 */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        /** 固定广播时间源，避免测试依赖系统时间。 */
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
        }

        /** 提供 Metrics Mapper 替身，避免执行 SQL。 */
        @Bean
        MetricsMapper metricsMapper() {
            return mock(MetricsMapper.class);
        }

        /** 提供 ServerService 替身，满足 MetricsService 构造依赖（servers 表访问走服务契约）。 */
        @Bean
        ServerService serverService() {
            ServerService serverService = mock(ServerService.class);
            when(serverService.existsActiveForUpdate(SERVER_ID)).thenReturn(true);
            return serverService;
        }

        /** 提供 OutboxService 替身，隔离本集成测试与 outbox 写入路径。 */
        @Bean
        OutboxService outboxService() {
            return mock(OutboxService.class);
        }

        /** 提供信封工厂替身，隔离本集成测试与信封序列化路径。 */
        @Bean
        OutboxEnvelopeFactory outboxEnvelopeFactory() {
            return mock(OutboxEnvelopeFactory.class);
        }

        /** 提供订阅注册表替身，观察广播是否发生。 */
        @Bean
        MonitorSubscriptionRegistry monitorSubscriptionRegistry() {
            return mock(MonitorSubscriptionRegistry.class);
        }

        /** 提供发布器在失败时收口 Monitor 会话所需的协调服务替身。 */
        @Bean
        MonitorSessionTerminationService monitorSessionTerminationService() {
            return mock(MonitorSessionTerminationService.class);
        }

        /** 使用 Spring 事件发布器构造受事务代理管理的 MetricsService。 */
        @Bean
        MetricsService metricsService(MetricsMapper metricsMapper, ServerService serverService,
                OutboxService outboxService, OutboxEnvelopeFactory outboxEnvelopeFactory,
                ApplicationEventPublisher eventPublisher) {
            return new MetricsServiceImpl(metricsMapper, serverService, outboxService,
                    outboxEnvelopeFactory, eventPublisher);
        }
    }
}
