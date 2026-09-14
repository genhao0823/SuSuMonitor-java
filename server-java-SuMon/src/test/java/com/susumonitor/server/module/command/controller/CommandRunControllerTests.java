package com.susumonitor.server.module.command.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.GlobalExceptionHandler;
import com.susumonitor.server.common.RequestIdFilter;
import com.susumonitor.server.module.ai.mapper.AiDiagnosticRunMapper;
import com.susumonitor.server.module.ai.service.AiUserProviderConfigService;
import com.susumonitor.server.module.ai.provider.AiProvider;
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
import com.susumonitor.server.module.command.AiCommandSuggestionService;
import com.susumonitor.server.module.command.CommandAutoApprovalPolicyService;
import com.susumonitor.server.module.command.CommandRiskLevel;
import com.susumonitor.server.module.command.CommandRunService;
import com.susumonitor.server.module.command.CommandTemplateRegistry;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import com.susumonitor.server.module.command.mapper.CommandAutoApprovalPolicyMapper;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证命令域端点的 admin 权限边界、参数校验和统一响应契约。 */
@ActiveProfiles("test")
@WebMvcTest(controllers = CommandRunController.class,
        properties = {"susumonitor.ai.command.enabled=true", "susumonitor.ai.enabled=true"})
@Import({SecurityConfig.class, SecurityErrorHandler.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class CommandRunControllerTests {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String ADMIN_BEARER = "Bearer " + ADMIN_TOKEN;
    private static final String USER_TOKEN = "user-token";
    private static final String USER_BEARER = "Bearer " + USER_TOKEN;
    private static final String AUTHORIZATION = "Authorization";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommandRunService commandRunService;

    @MockitoBean
    private CommandTemplateRegistry commandTemplateRegistry;

    @MockitoBean
    private AiCommandSuggestionService aiCommandSuggestionService;

    @MockitoBean
    private CommandAutoApprovalPolicyService autoApprovalPolicyService;

    // 策略 Mapper 同为 MapperScan 注册，测试切片无 MyBatis 会话工厂，需一并替代。
    @MockitoBean
    private CommandAutoApprovalPolicyMapper autoApprovalPolicyMapper;

    // 观察期评审报告服务为控制器构造依赖，切片需一并替代。
    @MockitoBean
    private com.susumonitor.server.module.command.CommandObservationReportService
            observationReportService;

    @MockitoBean
    private AiProvider aiProvider;

    @MockitoBean
    private AiDiagnosticRunMapper aiDiagnosticRunMapper;

    // ai.enabled=true 时个人 AI 配置控制器随切片装配，需替代其服务依赖。
    @MockitoBean
    private AiUserProviderConfigService aiUserProviderConfigService;

    // 个人 AI 配置 Mapper 同为 MapperScan 注册，测试切片无 MyBatis 会话工厂，需一并替代。
    @MockitoBean
    private com.susumonitor.server.module.ai.mapper.AiUserProviderConfigMapper aiUserProviderConfigMapper;

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

    // F2 运维问答回归补充：AiQaRunMapper 与命令域切片同为 ai.enabled=true 上下文，需一并替代。
    @MockitoBean
    private com.susumonitor.server.module.ai.mapper.AiQaRunMapper aiQaRunMapper;

    /** admin 可创建手动待审批运行，返回渲染预览命令。 */
    @Test
    void adminShouldCreateManualRun() throws Exception {
        authenticateAdmin();
        CommandRunEntity run = run(100L, "pending_approval");
        when(commandRunService.createPendingRun(anyLong(), anyLong(), anyString(), any(), anyString(), any()))
                .thenReturn(run);

        mockMvc.perform(post("/api/ai/commands/runs").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("""
                                {"server_id":7,"template_id":"service_status","params":{"unit":"nginx.service"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rendered_command").value("systemctl status nginx.service"))
                .andExpect(jsonPath("$.data.status").value("pending_approval"));
    }

    /** 未认证请求由安全链返回统一 401。 */
    @Test
    void unauthenticatedShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/ai/commands/runs")
                        .contentType("application/json")
                        .content("{\"server_id\":7,\"template_id\":\"uptime\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    /** 普通 approved user 无法访问命令域，返回 403。 */
    @Test
    void nonAdminShouldBeForbidden() throws Exception {
        authenticateUser();
        mockMvc.perform(post("/api/ai/commands/runs").header(AUTHORIZATION, USER_BEARER)
                        .contentType("application/json")
                        .content("{\"server_id\":7,\"template_id\":\"uptime\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        verify(commandRunService, never()).createPendingRun(any(), any(), any(), any(), any(), any());
    }

    /** 审批端点仅 admin；审批成功返回最新状态。 */
    @Test
    void adminShouldApproveRun() throws Exception {
        authenticateAdmin();
        when(commandRunService.approve(100L, 2L)).thenReturn(run(100L, "executing"));

        mockMvc.perform(post("/api/ai/commands/runs/100/approve").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("executing"));
    }

    /** 缺失 template_id 的请求在校验阶段拒绝。 */
    @Test
    void missingTemplateIdShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();
        mockMvc.perform(post("/api/ai/commands/runs").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"server_id\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /** 模板列表端点返回白名单契约内容（含风险等级）。 */
    @Test
    void adminShouldListTemplates() throws Exception {
        authenticateAdmin();
        when(commandTemplateRegistry.all())
                .thenReturn(java.util.List.copyOf(new CommandTemplateRegistry().all()));

        mockMvc.perform(get("/api/ai/commands/templates").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data[0].id").value("disk_free"))
                .andExpect(jsonPath("$.data[0].risk_level").value("low"))
                .andExpect(jsonPath("$.data[8].id").value("systemctl_reload"))
                .andExpect(jsonPath("$.data[8].risk_level").value("medium"))
                .andExpect(jsonPath("$.data[9].id").value("journalctl_vacuum"))
                .andExpect(jsonPath("$.data[9].risk_level").value("medium"));
    }

    /** admin 可读取自动审批策略。 */
    @Test
    void adminShouldGetAutoApprovalPolicy() throws Exception {
        authenticateAdmin();
        when(autoApprovalPolicyService.get()).thenReturn(new CommandAutoApprovalPolicyService.Snapshot(
                true, true, CommandRiskLevel.MEDIUM, null, 2L));

        mockMvc.perform(get("/api/ai/commands/auto-approval-policy").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.max_risk_level").value("medium"))
                .andExpect(jsonPath("$.data.updated_by").value(2));
    }

    /** admin 更新自动审批策略返回最新快照。 */
    @Test
    void adminShouldUpdateAutoApprovalPolicy() throws Exception {
        authenticateAdmin();
        when(autoApprovalPolicyService.update(true, "low", 2L)).thenReturn(
                new CommandAutoApprovalPolicyService.Snapshot(true, true, CommandRiskLevel.LOW, null, 2L));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/ai/commands/auto-approval-policy").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"max_risk_level\":\"low\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.max_risk_level").value("low"));
    }

    /** 阈值 high 不可作为自动审批档位，服务层拒绝后返回 40002。 */
    @Test
    void highThresholdShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();
        when(autoApprovalPolicyService.update(true, "high", 2L))
                .thenThrow(new com.susumonitor.server.common.BusinessException(
                        com.susumonitor.server.common.ErrorCode.INVALID_REQUEST_PARAMETER));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/ai/commands/auto-approval-policy").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"max_risk_level\":\"high\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /** 非 admin 不能读取策略。 */
    @Test
    void nonAdminShouldBeForbiddenOnPolicy() throws Exception {
        authenticateUser();
        mockMvc.perform(get("/api/ai/commands/auto-approval-policy").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    /** admin 可获取观察期评审报告，响应走统一 ApiResponse 包裹。 */
    @Test
    void adminShouldGetObservationReport() throws Exception {
        authenticateAdmin();
        com.susumonitor.server.module.command.vo.CommandObservationReportVo report =
                new com.susumonitor.server.module.command.vo.CommandObservationReportVo();
        report.setWindowDays(14);
        report.setTotalRuns(100L);
        report.setOverall("pass");
        when(observationReportService.generate(14)).thenReturn(report);

        mockMvc.perform(get("/api/ai/commands/observation-report").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.window_days").value(14))
                .andExpect(jsonPath("$.data.total_runs").value(100))
                .andExpect(jsonPath("$.data.overall").value("pass"));
        verify(observationReportService).generate(14);
    }

    /** window_days 缺省按 14 天处理。 */
    @Test
    void observationReportShouldDefaultTo14Days() throws Exception {
        authenticateAdmin();
        com.susumonitor.server.module.command.vo.CommandObservationReportVo report =
                new com.susumonitor.server.module.command.vo.CommandObservationReportVo();
        report.setWindowDays(14);
        report.setOverall("insufficient");
        when(observationReportService.generate(14)).thenReturn(report);

        mockMvc.perform(get("/api/ai/commands/observation-report").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall").value("insufficient"));
    }

    /** window_days 越界（0 或 91）在校验阶段拒绝，服务不被调用。 */
    @Test
    void observationReportShouldRejectOutOfRangeWindow() throws Exception {
        authenticateAdmin();
        mockMvc.perform(get("/api/ai/commands/observation-report")
                        .queryParam("window_days", "0").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        mockMvc.perform(get("/api/ai/commands/observation-report")
                        .queryParam("window_days", "91").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        verify(observationReportService, never()).generate(org.mockito.ArgumentMatchers.anyInt());
    }

    /** 非 admin 不能读取观察期评审报告。 */
    @Test
    void nonAdminShouldBeForbiddenOnObservationReport() throws Exception {
        authenticateUser();
        mockMvc.perform(get("/api/ai/commands/observation-report").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        verify(observationReportService, never()).generate(org.mockito.ArgumentMatchers.anyInt());
    }

    private void authenticateAdmin() {
        when(jwtTokenService.parseToken(ADMIN_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(2L, "admin_user", "admin-token-id",
                        Instant.parse("2026-09-03T00:00:00Z")));
        when(userMapper.selectAuthenticationUserById(2L)).thenReturn(authenticationUser(2L, "admin"));
    }

    private void authenticateUser() {
        when(jwtTokenService.parseToken(USER_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(3L, "approved_user", "user-token-id",
                        Instant.parse("2026-09-03T00:00:00Z")));
        when(userMapper.selectAuthenticationUserById(3L)).thenReturn(authenticationUser(3L, "user"));
    }

    private UserEntity authenticationUser(Long id, String role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername(role.equals("admin") ? "admin_user" : "approved_user");
        user.setRole(role);
        user.setReviewStatus("approved");
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    private CommandRunEntity run(Long id, String status) {
        CommandRunEntity run = new CommandRunEntity();
        run.setId(id);
        run.setExecutionId("exec-" + id);
        run.setProposerId(2L);
        run.setServerId(7L);
        run.setTemplateId("service_status");
        run.setParamsJson("{\"unit\":\"nginx.service\"}");
        run.setParamsHash("a".repeat(64));
        run.setRenderedCommand("systemctl status nginx.service");
        run.setStatus(status);
        run.setSource(CommandRunService.SOURCE_MANUAL);
        return run;
    }
}