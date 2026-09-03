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
import com.susumonitor.server.module.command.CommandRunService;
import com.susumonitor.server.module.command.CommandTemplateRegistry;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
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
    private AiProvider aiProvider;

    @MockitoBean
    private AiDiagnosticRunMapper aiDiagnosticRunMapper;

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

    /** 模板列表端点返回白名单契约内容。 */
    @Test
    void adminShouldListTemplates() throws Exception {
        authenticateAdmin();
        when(commandTemplateRegistry.all())
                .thenReturn(java.util.List.copyOf(new CommandTemplateRegistry().all()));

        mockMvc.perform(get("/api/ai/commands/templates").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].id").value("disk_free"));
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