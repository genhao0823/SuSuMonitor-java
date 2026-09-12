package com.susumonitor.server.module.ai.controller;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.GlobalExceptionHandler;
import com.susumonitor.server.common.RequestIdFilter;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.ai.mapper.AiAlertExplanationMapper;
import com.susumonitor.server.module.ai.mapper.AiDiagnosticRunMapper;
import com.susumonitor.server.module.ai.mapper.AiHealthReportMapper;
import com.susumonitor.server.module.ai.mapper.AiQaRunMapper;
import com.susumonitor.server.module.ai.mapper.AiUserProviderConfigMapper;
import com.susumonitor.server.module.ai.service.AiHealthReportService;
import com.susumonitor.server.module.ai.service.AiUserProviderConfigService;
import com.susumonitor.server.module.ai.vo.AiHealthReportVo;
import com.susumonitor.server.module.alert.consume.ConsumeRecordCleanupMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertNotificationCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.command.mapper.CommandRunMapper;
import com.susumonitor.server.module.metrics.mapper.IngestionCleanupMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxCleanupMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.server.mapper.SshTestHistoryMapper;
import com.susumonitor.server.module.terminal.mapper.TerminalSessionMapper;
import com.susumonitor.server.security.JwtTokenService;
import com.susumonitor.server.security.SecurityConfig;
import com.susumonitor.server.security.SecurityErrorHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证定时健康报告接口（F3）的 admin 权限边界、参数校验和统一响应契约。 */
@ActiveProfiles("test")
@WebMvcTest(controllers = AiHealthReportController.class,
        properties = {"susumonitor.ai.report.enabled=true", "susumonitor.ai.enabled=true"})
@Import({SecurityConfig.class, SecurityErrorHandler.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class AiHealthReportControllerTests {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String ADMIN_BEARER = "Bearer " + ADMIN_TOKEN;
    private static final String USER_TOKEN = "user-token";
    private static final String USER_BEARER = "Bearer " + USER_TOKEN;
    private static final String AUTHORIZATION = "Authorization";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // 替代报告编排服务与统一时钟，仅验证 HTTP 与安全契约。
    @MockitoBean
    private AiHealthReportService reportService;

    @MockitoBean
    private Clock clock;

    // 替代全局 Mapper 扫描注册的各域 Mapper，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private AiHealthReportMapper aiHealthReportMapper;

    @MockitoBean
    private AiQaRunMapper aiQaRunMapper;

    @MockitoBean
    private AiDiagnosticRunMapper aiDiagnosticRunMapper;

    @MockitoBean
    private AiAlertExplanationMapper aiAlertExplanationMapper;

    @MockitoBean
    private AiUserProviderConfigMapper aiUserProviderConfigMapper;

    @MockitoBean
    private AiUserProviderConfigService aiUserProviderConfigService;

    @MockitoBean
    private CommandRunMapper commandRunMapper;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private AuthBootstrapStateMapper authBootstrapStateMapper;

    @MockitoBean
    private ServerMapper serverMapper;

    @MockitoBean
    private SshTestHistoryMapper sshTestHistoryMapper;

    @MockitoBean
    private MetricsMapper metricsMapper;

    @MockitoBean
    private MetricsCleanupMapper metricsCleanupMapper;

    @MockitoBean
    private IngestionCleanupMapper ingestionCleanupMapper;

    @MockitoBean
    private OutboxCleanupMapper outboxCleanupMapper;

    @MockitoBean
    private AlertNotificationCleanupMapper alertNotificationCleanupMapper;

    @MockitoBean
    private ConsumeRecordCleanupMapper consumeRecordCleanupMapper;

    @MockitoBean
    private AlertRecordCleanupMapper alertRecordCleanupMapper;

    @MockitoBean
    private AlertRuleMapper alertRuleMapper;

    @MockitoBean
    private AlertRecordMapper alertRecordMapper;

    @MockitoBean
    private AlertNotificationMapper alertNotificationMapper;

    @MockitoBean
    private AlertStateMapper alertStateMapper;

    @MockitoBean
    private TerminalSessionMapper terminalSessionMapper;

    @MockitoBean
    private OutboxMapper outboxMapper;

    @MockitoBean
    private ConsumeRecordMapper consumeRecordMapper;

    /** 管理员分页查询历史报告，响应携带 page/page_size 契约字段。 */
    @Test
    void adminShouldListReports() throws Exception {
        authenticateAdmin();
        AiHealthReportVo report = report(1L, LocalDate.of(2026, 9, 10));
        PageResult<AiHealthReportVo> page = new PageResult<>();
        page.setItems(List.of(report));
        page.setTotal(1);
        page.setPage(1);
        page.setPageSize(20);
        when(reportService.list(1, 20)).thenReturn(page);

        mockMvc.perform(get("/api/ai/health-reports?page=1&page_size=20")
                        .header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].report_date").value("2026-09-10"))
                .andExpect(jsonPath("$.data.page_size").value(20));

        verify(reportService).list(1, 20);
    }

    /** 管理员按 ID 查询报告详情。 */
    @Test
    void adminShouldGetReportById() throws Exception {
        authenticateAdmin();
        when(reportService.get(1L)).thenReturn(report(1L, LocalDate.of(2026, 9, 10)));

        mockMvc.perform(get("/api/ai/health-reports/1").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.prompt_version").value("ai-health-report-v1"));
    }

    /** 管理员手动触发指定日期的报告生成。 */
    @Test
    void adminShouldGenerateReportForExplicitDate() throws Exception {
        authenticateAdmin();
        stubClock();
        LocalDate date = LocalDate.of(2026, 9, 10);
        when(reportService.generate(date, 2L)).thenReturn(report(9L, date));

        mockMvc.perform(post("/api/ai/health-reports/generate")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"report_date\":\"2026-09-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.report_date").value("2026-09-10"));

        verify(reportService).generate(date, 2L);
    }

    /** report_date 缺省时由服务端取昨日（以 stub 时钟为基准，避免真实日期滚动导致 flaky）。 */
    @Test
    void generateShouldDefaultToYesterday() throws Exception {
        authenticateAdmin();
        stubClock();
        // stubClock 固定 instant=2026-09-11T00:00:00Z，控制器取的昨日恒为 09-10。
        LocalDate yesterday = LocalDate.of(2026, 9, 10);
        when(reportService.generate(yesterday, 2L)).thenReturn(report(9L, yesterday));

        mockMvc.perform(post("/api/ai/health-reports/generate")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report_date").value(yesterday.toString()));

        verify(reportService).generate(yesterday, 2L);
    }

    /** 未来日期在控制器层拒绝，不进入服务层。 */
    @Test
    void futureReportDateShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();
        stubClock();

        mockMvc.perform(post("/api/ai/health-reports/generate")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"report_date\":\"2026-09-12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(reportService, never()).generate(any(), anyLong());
    }

    /** 普通 approved user 无法访问报告接口，返回 403。 */
    @Test
    void approvedNonAdminShouldBeForbidden() throws Exception {
        authenticateUser();

        mockMvc.perform(get("/api/ai/health-reports?page=1&page_size=20")
                        .header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verify(reportService, never()).list(any(), any());
    }

    /** 未认证请求由安全链返回统一 401。 */
    @Test
    void unauthenticatedRequestShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/ai/health-reports?page=1&page_size=20"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    /** 服务层业务异常映射为统一错误码。 */
    @Test
    void serviceExceptionShouldMapToApiError() throws Exception {
        authenticateAdmin();
        when(reportService.get(1L)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/ai/health-reports/1").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    private void stubClock() {
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-11T00:00:00Z"));
    }

    private AiHealthReportVo report(Long id, LocalDate date) {
        AiHealthReportVo vo = new AiHealthReportVo();
        vo.setId(id);
        vo.setReportDate(date);
        vo.setStatus(AiHealthReportService.STATUS_SUCCEEDED);
        vo.setProvider("openai-compatible");
        vo.setModel("test-model");
        vo.setPromptVersion("ai-health-report-v1");
        vo.setSummary("ok");
        vo.setTopConcerns(List.of());
        vo.setLimitations(List.of());
        vo.setUsage(new com.susumonitor.server.module.ai.vo.AiUsageVo());
        vo.setCreatedAt(java.time.OffsetDateTime.now());
        return vo;
    }

    private void authenticateAdmin() {
        when(jwtTokenService.parseToken(ADMIN_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(2L, "admin_user", "admin-token-id",
                        Instant.parse("2026-09-07T00:00:00Z")));
        when(userMapper.selectAuthenticationUserById(2L)).thenReturn(authenticationUser("admin"));
    }

    private void authenticateUser() {
        when(jwtTokenService.parseToken(USER_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(3L, "approved_user", "user-token-id",
                        Instant.parse("2026-09-07T00:00:00Z")));
        when(userMapper.selectAuthenticationUserById(3L)).thenReturn(authenticationUser("user"));
    }

    private UserEntity authenticationUser(String role) {
        UserEntity user = new UserEntity();
        user.setId(role.equals("admin") ? 2L : 3L);
        user.setUsername(role.equals("admin") ? "admin_user" : "approved_user");
        user.setRole(role);
        user.setReviewStatus("approved");
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }
}
