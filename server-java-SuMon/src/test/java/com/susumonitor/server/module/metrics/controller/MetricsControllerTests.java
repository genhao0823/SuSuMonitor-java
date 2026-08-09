package com.susumonitor.server.module.metrics.controller;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.susumonitor.server.common.GlobalExceptionHandler;
import com.susumonitor.server.common.RequestIdFilter;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.module.metrics.mapper.IngestionCleanupMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.metrics.vo.MetricsHistoryVo;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.security.JwtTokenService;
import com.susumonitor.server.security.SecurityConfig;
import com.susumonitor.server.security.SecurityErrorHandler;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.terminal.mapper.TerminalSessionMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证 Metrics 查询接口的权限、参数边界和统一响应契约。 */
// 激活测试配置，避免读取本机敏感配置。
@ActiveProfiles("test")
// 只加载 Metrics Controller 所需的 MVC 测试切片。
@WebMvcTest(MetricsController.class)
// 引入真实安全链、请求追踪和统一异常映射，验证完整 HTTP 边界。
@Import({SecurityConfig.class, SecurityErrorHandler.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class MetricsControllerTests {

    private static final String USER_TOKEN = "user-token";
    private static final String USER_BEARER = "Bearer " + USER_TOKEN;
    private static final String AUTHORIZATION = "Authorization";

    // 注入 MockMvc，通过真实 MVC 和安全过滤器链调用目标接口。
    @Autowired
    private MockMvc mockMvc;

    // 隔离 Metrics 业务和数据库，仅验证 Controller 契约。
    @MockitoBean
    private MetricsService metricsService;

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

    // 替代全局 Mapper 扫描注册的指标 Mapper。
    @MockitoBean
    private MetricsMapper metricsMapper;

    // 替代全局 Mapper 扫描注册的指标清理 Mapper。
    @MockitoBean
    private MetricsCleanupMapper metricsCleanupMapper;

    // 替代全局 Mapper 扫描注册的指标幂等接收记录清理 Mapper。
    @MockitoBean
    private IngestionCleanupMapper ingestionCleanupMapper;

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



    /** 验证已审核用户可查询最新指标，且响应字段保持 snake_case。 */
    @Test
    void approvedUserShouldGetLatestMetrics() throws Exception {
        authenticateUser();
        when(metricsService.latest(1L)).thenReturn(latestMetrics());

        mockMvc.perform(get("/api/servers/1/metrics/latest").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.server_id").value(1))
                .andExpect(jsonPath("$.data.cpu_percent").value(35.2))
                .andExpect(jsonPath("$.data.collected_at").isNotEmpty());
    }

    /** 验证历史查询参数透传和分页响应字段。 */
    @Test
    void approvedUserShouldGetMetricsHistory() throws Exception {
        authenticateUser();
        PageResult<MetricsHistoryVo> page = historyPage();
        when(metricsService.history(eq(1L), any(OffsetDateTime.class), any(OffsetDateTime.class), eq(2), eq(10)))
                .thenReturn(page);

        mockMvc.perform(get("/api/servers/1/metrics")
                        .header(AUTHORIZATION, USER_BEARER)
                        .param("start_time", "2026-07-23T00:00:00Z")
                        .param("end_time", "2026-07-23T01:00:00Z")
                        .param("page", "2")
                        .param("page_size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.page_size").value(10))
                .andExpect(jsonPath("$.data.items[0].server_id").value(1));
    }

    /** 验证未认证请求由安全链返回统一 401。 */
    @Test
    void unauthenticatedRequestShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/servers/1/metrics/latest"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(40100));
    }

    /** 验证非法服务器 ID 在进入 Service 前被参数校验拒绝。 */
    @Test
    void nonPositiveServerIdShouldReturnInvalidParameter() throws Exception {
        authenticateUser();

        mockMvc.perform(get("/api/servers/0/metrics/latest").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(metricsService, never()).latest(any());
    }

    /** 验证超出契约上限的分页大小被参数校验拒绝。 */
    @Test
    void oversizedPageShouldReturnInvalidParameter() throws Exception {
        authenticateUser();

        mockMvc.perform(get("/api/servers/1/metrics")
                        .header(AUTHORIZATION, USER_BEARER)
                        .param("start_time", "2026-07-23T00:00:00Z")
                        .param("end_time", "2026-07-23T01:00:00Z")
                        .param("page_size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(metricsService, never()).history(any(), any(), any(), any(), any());
    }

    /** 验证缺少必填时间参数时返回统一参数错误，而不是通用 500。 */
    @Test
    void missingTimeRangeShouldReturnInvalidParameter() throws Exception {
        authenticateUser();

        mockMvc.perform(get("/api/servers/1/metrics").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(metricsService, never()).history(any(), any(), any(), any(), any());
    }

    /** 验证非法时间格式返回统一参数错误。 */
    @Test
    void invalidTimeFormatShouldReturnInvalidParameter() throws Exception {
        authenticateUser();

        mockMvc.perform(get("/api/servers/1/metrics")
                        .header(AUTHORIZATION, USER_BEARER)
                        .param("start_time", "not-a-time")
                        .param("end_time", "2026-07-23T01:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(metricsService, never()).history(any(), any(), any(), any(), any());
    }

    private void authenticateUser() {
        when(jwtTokenService.parseToken(USER_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(2L, "approved_user", "user-token-id"));
        when(userMapper.selectAuthenticationUserById(2L)).thenReturn(authenticationUser());
    }

    private UserEntity authenticationUser() {
        UserEntity user = new UserEntity();
        user.setId(2L);
        user.setUsername("approved_user");
        user.setRole("user");
        user.setReviewStatus("approved");
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    private MetricsLatestVo latestMetrics() {
        MetricsLatestVo metrics = new MetricsLatestVo();
        metrics.setServerId(1L);
        metrics.setCpuPercent(BigDecimal.valueOf(35.2));
        metrics.setCollectedAt(OffsetDateTime.of(2026, 7, 23, 0, 30, 0, 0, ZoneOffset.UTC));
        return metrics;
    }

    private PageResult<MetricsHistoryVo> historyPage() {
        MetricsHistoryVo metrics = new MetricsHistoryVo();
        metrics.setServerId(1L);
        metrics.setCpuPercent(BigDecimal.valueOf(35.2));
        metrics.setCollectedAt(OffsetDateTime.of(2026, 7, 23, 0, 30, 0, 0, ZoneOffset.UTC));
        PageResult<MetricsHistoryVo> page = new PageResult<>();
        page.setItems(List.of(metrics));
        page.setTotal(1L);
        page.setPage(2);
        page.setPageSize(10);
        return page;
    }
}
