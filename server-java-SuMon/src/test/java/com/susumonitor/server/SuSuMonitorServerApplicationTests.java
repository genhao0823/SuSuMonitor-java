package com.susumonitor.server;

import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.terminal.mapper.TerminalSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

// 激活独立测试配置，避免上下文测试读取本机敏感配置。
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class SuSuMonitorServerApplicationTests {

    @MockitoBean
    private DataSource dataSource;

    // 使用模拟 Mapper 替代真实数据库 Mapper，保持上下文测试不依赖数据库自动配置。
    @MockitoBean
    private UserMapper userMapper;

    // 使用模拟初始化状态 Mapper，避免上下文测试创建真实 MyBatis 会话依赖。
    @MockitoBean
    private AuthBootstrapStateMapper authBootstrapStateMapper;

    // 使用模拟服务器 Mapper，避免新增服务器 Mapper 扫描后创建真实 MyBatis 会话依赖。
    @MockitoBean
    private ServerMapper serverMapper;

    @MockitoBean
    private MetricsMapper metricsMapper;

    @MockitoBean
    private MetricsCleanupMapper metricsCleanupMapper;

    // 使用模拟告警 Mapper，避免新增告警模块 Mapper 扫描后创建真实 MyBatis 会话依赖。
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
    @MockitoBean
    private ConsumeRecordMapper consumeRecordMapper;



    @Test
    void contextLoads() {
        // Verifies that the Spring application context can start.
    }
}
