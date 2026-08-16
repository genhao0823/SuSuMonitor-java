package com.susumonitor.server.module.system.controller;

import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.server.mapper.SshTestHistoryMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertNotificationCleanupMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxCleanupMapper;
import com.susumonitor.server.module.metrics.mapper.IngestionCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.consume.AlertMessageConsumer;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.system.RabbitHealthChecker;
import com.susumonitor.server.module.system.QueueBacklogSnapshotRegistry;
import com.susumonitor.server.module.alert.consume.ConsumeStatsService;
import com.susumonitor.server.module.alert.consume.ConsumeTimingStatsRegistry;
import com.susumonitor.server.module.terminal.mapper.TerminalSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 激活独立测试配置，避免 Controller 测试读取本机敏感配置。
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class SystemControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private Connection connection;

    // 使用模拟 Mapper 替代真实数据库 Mapper，保持 Controller 测试不依赖数据库自动配置。
    @MockitoBean
    private UserMapper userMapper;

    // 提供初始化状态 Mapper 替身，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private AuthBootstrapStateMapper authBootstrapStateMapper;

    // 提供服务器 Mapper 替身，避免新增 Mapper 扫描后加载真实 MyBatis 会话工厂。
    @MockitoBean
    private ServerMapper serverMapper;

    // 替代全局 Mapper 扫描注册的 SSH 测试历史 Mapper，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private SshTestHistoryMapper sshTestHistoryMapper;

    @MockitoBean
    private MetricsMapper metricsMapper;

    @MockitoBean
    private MetricsCleanupMapper metricsCleanupMapper;

    // 替代全局 Mapper 扫描注册的指标幂等接收记录清理 Mapper。
    @MockitoBean
    private IngestionCleanupMapper ingestionCleanupMapper;

    // 替代全局 Mapper 扫描注册的 Outbox 清理 Mapper，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private OutboxCleanupMapper outboxCleanupMapper;

    // 替代全局 Mapper 扫描注册的通知投递清理 Mapper，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private AlertNotificationCleanupMapper alertNotificationCleanupMapper;

    // 替代全局 Mapper 扫描注册的消费幂等记录清理 Mapper。
    @MockitoBean
    private ConsumeRecordCleanupMapper consumeRecordCleanupMapper;

    // 替代全局 Mapper 扫描注册的告警记录清理 Mapper。
    @MockitoBean
    private AlertRecordCleanupMapper alertRecordCleanupMapper;


    // 使用模拟告警 Mapper，避免告警模块 Mapper 扫描后创建真实 MyBatis 会话依赖。
    @MockitoBean
    private AlertRuleMapper alertRuleMapper;

    @MockitoBean
    private AlertRecordMapper alertRecordMapper;
    @MockitoBean
    private AlertNotificationMapper alertNotificationMapper;

    @MockitoBean
    private AlertStateMapper alertStateMapper;

    // 使用模拟终端 Mapper，避免 V12 Mapper 扫描后创建真实 MyBatis 会话依赖。
    @MockitoBean
    private TerminalSessionMapper terminalSessionMapper;

    @MockitoBean
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private OutboxMapper outboxMapper;

    // RabbitMQ 探活替身：ready 检查 Broker 分支（enabled=false 时业务 Bean 不存在，仅测试注入 mock）。
    @MockitoBean
    private RabbitHealthChecker rabbitHealthChecker;
    @MockitoBean
    private ConsumeRecordMapper consumeRecordMapper;

    // 监控快照组件替身：enabled=false 时条件 Bean 不存在，测试注入 mock 验证端点组合逻辑。
    @MockitoBean
    private ConsumeTimingStatsRegistry consumeTimingStatsRegistry;
    @MockitoBean
    private ConsumeStatsService consumeStatsService;
    @MockitoBean
    private QueueBacklogSnapshotRegistry queueBacklogSnapshotRegistry;


    // 条件装配断言：test profile（susumonitor.rabbitmq.enabled=false）下发布器不应加载。
    @Autowired(required = false)
    private com.susumonitor.server.scheduler.OutboxPublisherScheduler outboxPublisherScheduler;

    @Test
    void healthShouldReturnSuccessAndRequestId() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", matchesPattern(
                        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.application").value("susumonitor"));
    }

    @Test
    void healthShouldIgnoreClientRequestIdAndReturnValidCorrelationId() throws Exception {
        mockMvc.perform(get("/api/health")
                        .header("X-Request-ID", "client-controlled-id")
                        .header("X-Correlation-ID", "mockmvc-correlation-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not("client-controlled-id")))
                .andExpect(header().string("X-Correlation-ID", "mockmvc-correlation-1"));
    }

    @Test
    void healthShouldIgnoreInvalidCorrelationId() throws Exception {
        mockMvc.perform(get("/api/health")
                        .header("X-Correlation-ID", "invalid correlation value"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Correlation-ID"));
    }

    @Test
    void readyShouldReturnSuccessWhenDatabaseConnectionIsValid() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(rabbitHealthChecker.isHealthy()).thenReturn(true);

        mockMvc.perform(get("/api/ready"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database").value("ok"));
    }

    @Test
    void readyShouldReturnDatabaseErrorWhenConnectionFails() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection failed"));

        mockMvc.perform(get("/api/ready"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("database error"));
    }

    /** Broker 可用时 ready 保持成功（存活但未就绪语义的反面）。 */
    @Test
    void readyShouldReturnSuccessWhenRabbitMqIsHealthy() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(rabbitHealthChecker.isHealthy()).thenReturn(true);

        mockMvc.perform(get("/api/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    /** Broker 不可达时 ready 返回 50301（HTTP 503），应用存活不退出。 */
    @Test
    void readyShouldReturnServiceUnavailableWhenRabbitMqIsDown() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(rabbitHealthChecker.isHealthy()).thenReturn(false);

        mockMvc.perform(get("/api/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(50301))
                .andExpect(jsonPath("$.message").value("rabbitmq unavailable"));
    }

    /** test profile 关闭 Outbox 时发布器不应加载（条件装配）。 */
    @Test
    void outboxPublisherShouldNotLoadWhenDisabled() {
        org.junit.jupiter.api.Assertions.assertNull(outboxPublisherScheduler);
    }

    /** 未认证访问监控快照端点返回 401。 */
    @Test
    void rabbitmqConsumersShouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/system/rabbitmq/consumers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    /** 管理员访问监控快照端点：组件为空时返回空列表（enabled=false 条件语义）。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    void rabbitmqConsumersShouldReturnEmptyListWhenComponentsAbsent() throws Exception {
        mockMvc.perform(get("/api/system/rabbitmq/consumers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /** 管理员访问队列快照端点：无探测结果时返回空列表。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    void rabbitmqQueuesShouldReturnEmptyListWhenNoSnapshot() throws Exception {
        mockMvc.perform(get("/api/system/rabbitmq/queues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // 条件装配断言：test profile（susumonitor.rabbitmq.enabled=false）下消费者不应加载。
    @Autowired(required = false)
    private AlertMessageConsumer alertMessageConsumer;

    /** test profile 关闭 RabbitMQ 时消费端不应加载（条件装配）。 */
    @Test
    void alertConsumerShouldNotLoadWhenDisabled() {
        org.junit.jupiter.api.Assertions.assertNull(alertMessageConsumer);
    }
}
