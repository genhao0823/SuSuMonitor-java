package com.susumonitor.server.module.ai.controller;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.GlobalExceptionHandler;
import com.susumonitor.server.common.RequestIdFilter;
import com.susumonitor.server.module.ai.mapper.AiAlertExplanationMapper;
import com.susumonitor.server.module.ai.mapper.AiDiagnosticRunMapper;
import com.susumonitor.server.module.ai.service.AiUserProviderConfigService;
import com.susumonitor.server.module.ai.mapper.AiQaRunMapper;
import com.susumonitor.server.module.ai.service.AiQaService;
import com.susumonitor.server.module.ai.vo.AiQaVo;
import com.susumonitor.server.module.ai.vo.AiUsageVo;
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
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证运维问答接口的 admin 权限边界、参数校验和统一响应契约。 */
@ActiveProfiles("test")
@WebMvcTest(controllers = AiQaController.class, properties = "susumonitor.ai.enabled=true")
@Import({SecurityConfig.class, SecurityErrorHandler.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class AiQaControllerTests {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String ADMIN_BEARER = "Bearer " + ADMIN_TOKEN;
    private static final String USER_TOKEN = "user-token";
    private static final String USER_BEARER = "Bearer " + USER_TOKEN;
    private static final String AUTHORIZATION = "Authorization";

    @Autowired
    private MockMvc mockMvc;

    // 替代问答编排服务，仅验证 HTTP 与安全契约。
    @MockitoBean
    private AiQaService aiQaService;

    // 替代全局 Mapper 扫描注册的各域 Mapper，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private AiQaRunMapper aiQaRunMapper;

    @MockitoBean
    private AiDiagnosticRunMapper aiDiagnosticRunMapper;

    // ai.enabled=true 时个人 AI 配置控制器随切片装配，需替代其服务依赖。
    @MockitoBean
    private AiUserProviderConfigService aiUserProviderConfigService;

    // 个人 AI 配置 Mapper 同为 MapperScan 注册，测试切片无 MyBatis 会话工厂，需一并替代。
    @MockitoBean
    private com.susumonitor.server.module.ai.mapper.AiUserProviderConfigMapper aiUserProviderConfigMapper;

    @MockitoBean
    private AiAlertExplanationMapper aiAlertExplanationMapper;

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

    /** 管理员可发起全局问答并收到结构化结果（无 server_id 场景）。 */
    @Test
    void adminShouldAskGlobalQuestion() throws Exception {
        authenticateAdmin();
        when(aiQaService.ask(anyLong(), any(), anyString())).thenReturn(answer("整体正常"));

        mockMvc.perform(post("/api/ai/qa").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"question\":\"整体情况如何\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").value("整体正常"))
                .andExpect(jsonPath("$.data.prompt_version").value("ai-qa-v1"));

        verify(aiQaService).ask(2L, null, "整体情况如何");
    }

    /** 指定服务器的问答透传 server_id。 */
    @Test
    void adminShouldAskServerScopedQuestion() throws Exception {
        authenticateAdmin();
        when(aiQaService.ask(anyLong(), anyLong(), anyString())).thenReturn(answer("CPU 23%"));

        mockMvc.perform(post("/api/ai/qa").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"server_id\":1,\"question\":\"cpu 怎么样\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("CPU 23%"));

        verify(aiQaService).ask(2L, 1L, "cpu 怎么样");
    }

    /** 未认证请求由安全链返回统一 401。 */
    @Test
    void unauthenticatedRequestShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/ai/qa")
                        .contentType("application/json")
                        .content("{\"question\":\"cpu 怎么样\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    /** 普通 approved user 无法访问问答接口，返回 403。 */
    @Test
    void approvedNonAdminShouldBeForbidden() throws Exception {
        authenticateUser();

        mockMvc.perform(post("/api/ai/qa").header(AUTHORIZATION, USER_BEARER)
                        .contentType("application/json")
                        .content("{\"question\":\"cpu 怎么样\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verify(aiQaService, never()).ask(any(), any(), any());
    }

    /** 空 question 在校验阶段拒绝，不进入服务层。 */
    @Test
    void blankQuestionShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();

        mockMvc.perform(post("/api/ai/qa").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(aiQaService, never()).ask(any(), any(), any());
    }

    /** 非正数 server_id 在校验阶段拒绝。 */
    @Test
    void nonPositiveServerIdShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();

        mockMvc.perform(post("/api/ai/qa").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"server_id\":0,\"question\":\"cpu 怎么样\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /** 服务层业务异常映射为统一错误码，不透传 provider 细节。 */
    @Test
    void serviceExceptionShouldMapToApiError() throws Exception {
        authenticateAdmin();
        when(aiQaService.ask(anyLong(), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED));

        mockMvc.perform(post("/api/ai/qa").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"question\":\"cpu 怎么样\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42906));
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

    private AiQaVo answer(String text) {
        AiQaVo vo = new AiQaVo();
        vo.setAnswer(text);
        vo.setModelUsed(true);
        vo.setProvider("openai-compatible");
        vo.setModel("test-model");
        vo.setPromptVersion("ai-qa-v1");
        vo.setUsage(new AiUsageVo());
        return vo;
    }

    private String body(Long serverId, String question) throws Exception {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        if (serverId != null) {
            map.put("server_id", serverId);
        }
        map.put("question", question);
        return new ObjectMapper().writeValueAsString(map);
    }
}
