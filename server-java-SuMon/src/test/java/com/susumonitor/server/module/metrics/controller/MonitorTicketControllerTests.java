package com.susumonitor.server.module.metrics.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;

import com.susumonitor.server.common.GlobalExceptionHandler;
import com.susumonitor.server.common.RequestIdFilter;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.metrics.mapper.IngestionCleanupMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxCleanupMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.alert.mapper.AlertNotificationCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordCleanupMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.server.mapper.SshTestHistoryMapper;
import com.susumonitor.server.module.terminal.mapper.TerminalSessionMapper;
import com.susumonitor.server.security.JwtTokenService;
import com.susumonitor.server.security.SecurityConfig;
import com.susumonitor.server.security.SecurityErrorHandler;
import com.susumonitor.server.websocket.MonitorTicketService;
import com.susumonitor.server.websocket.MonitorTicketVo;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证 Monitor ticket 签发接口的访问矩阵：任一认证用户可签发，未认证请求被安全链拦截。 */
// 激活测试配置，避免读取本机敏感配置。
@ActiveProfiles("test")
// 只加载 Monitor Ticket Controller 所需的 MVC 测试切片。
@WebMvcTest(MonitorTicketController.class)
// 引入真实安全链、请求追踪和统一异常映射，验证完整 HTTP 边界。
@Import({SecurityConfig.class, SecurityErrorHandler.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class MonitorTicketControllerTests {

    private static final String USER_TOKEN = "user-token";
    private static final String USER_BEARER = "Bearer " + USER_TOKEN;
    private static final String AUTHORIZATION = "Authorization";

    // 注入 MockMvc，通过真实 MVC 和安全过滤器链调用目标接口。
    @Autowired
    private MockMvc mockMvc;

    // 隔离 ticket 签发业务，仅验证 Controller 契约与访问矩阵。
    @MockitoBean
    private MonitorTicketService monitorTicketService;

    // 提供可控 JWT 解析结果，覆盖认证场景。
    @MockitoBean
    private JwtTokenService jwtTokenService;

    // 提供认证用户回查替身，避免连接数据库。
    @MockitoBean
    private UserMapper userMapper;

    // 替代全局 Mapper 扫描注册的初始化状态 Mapper。
    @MockitoBean
    private AuthBootstrapStateMapper authBootstrapStateMapper;

    // 替代全局 Mapper 扫描注册的服务器 Mapper。
    @MockitoBean
    private ServerMapper serverMapper;

    // 替代全局 Mapper 扫描注册的 SSH 测试历史 Mapper，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private SshTestHistoryMapper sshTestHistoryMapper;

    // 替代全局 Mapper 扫描注册的指标 Mapper。
    @MockitoBean
    private MetricsMapper metricsMapper;

    // 替代全局 Mapper 扫描注册的指标清理 Mapper。
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
    private OutboxMapper outboxMapper;
    @MockitoBean
    private ConsumeRecordMapper consumeRecordMapper;

    /** 验证非 admin 的已审核用户可签发 ticket，且认证用户快照贯通到业务层。 */
    @Test
    void approvedUserShouldIssueMonitorTicket() throws Exception {
        authenticateUser();
        when(monitorTicketService.issue(any()))
                .thenReturn(new MonitorTicketVo("one-time-ticket",
                        OffsetDateTime.parse("2026-09-16T00:00:30Z")));

        mockMvc.perform(post("/api/ws/monitor-ticket").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ticket").value("one-time-ticket"))
                .andExpect(jsonPath("$.data.expires_at").value("2026-09-16T00:00:30Z"));

        // 参数贯通断言：安全链解析出的认证用户快照（ID=2 的 approved user）原样传入签发业务。
        verify(monitorTicketService).issue(argThat(
                user -> user != null && Long.valueOf(2L).equals(user.id())));
    }

    /** 验证未认证请求被安全链以 401 拦截，且不触达 ticket 签发业务。 */
    @Test
    void unauthenticatedShouldReturn401() throws Exception {
        mockMvc.perform(post("/api/ws/monitor-ticket"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(monitorTicketService);
    }

    private void authenticateUser() {
        when(jwtTokenService.parseToken(USER_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(2L, "approved_user", "user-token-id",
                        java.time.Instant.parse("2026-08-18T00:00:00Z")));
        when(userMapper.selectAuthenticationUserById(2L)).thenReturn(authenticationUser());
    }

    /** 构造非 admin 的已审核普通用户，覆盖「任一认证用户可读」访问矩阵。 */
    private UserEntity authenticationUser() {
        UserEntity user = new UserEntity();
        user.setId(2L);
        user.setUsername("approved_user");
        user.setRole("user");
        user.setReviewStatus("approved");
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }
}
