package com.susumonitor.server.module.ai.controller;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.susumonitor.server.module.ai.service.AiDiagnosisService;
import com.susumonitor.server.module.ai.vo.AiDiagnosisVo;
import com.susumonitor.server.module.ai.vo.AiFindingVo;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证 AI 诊断接口的 admin 权限边界、参数校验和统一响应契约。 */
@ActiveProfiles("test")
@WebMvcTest(controllers = AiDiagnosisController.class, properties = "susumonitor.ai.enabled=true")
@Import({SecurityConfig.class, SecurityErrorHandler.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class AiDiagnosisControllerTests {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String ADMIN_BEARER = "Bearer " + ADMIN_TOKEN;
    private static final String USER_TOKEN = "user-token";
    private static final String USER_BEARER = "Bearer " + USER_TOKEN;
    private static final String AUTHORIZATION = "Authorization";

    @Autowired
    private MockMvc mockMvc;

    // 替代 AI 编排服务，仅验证 HTTP 与安全契约。
    @MockitoBean
    private AiDiagnosisService aiDiagnosisService;

    // 替代 AI 审计 Mapper，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private com.susumonitor.server.module.ai.mapper.AiDiagnosticRunMapper aiDiagnosticRunMapper;

    // ai.enabled=true 时个人 AI 配置控制器随切片装配，需替代其服务依赖。
    @MockitoBean
    private com.susumonitor.server.module.ai.service.AiUserProviderConfigService aiUserProviderConfigService;

    // 个人 AI 配置 Mapper 同为 MapperScan 注册，测试切片无 MyBatis 会话工厂，需一并替代。
    @MockitoBean
    private com.susumonitor.server.module.ai.mapper.AiUserProviderConfigMapper aiUserProviderConfigMapper;

    // F2 运维问答回归补充：AiQaRunMapper 与诊断同为 ai.enabled 条件装配，切片上下文需一并替代。
    @MockitoBean
    private com.susumonitor.server.module.ai.mapper.AiQaRunMapper aiQaRunMapper;

    // 提供可控 JWT 解析结果，覆盖认证场景。
    @MockitoBean
    private JwtTokenService jwtTokenService;

    // 提供认证用户回查替身，避免连接数据库。
    @MockitoBean
    private UserMapper userMapper;

    // 替代全局 Mapper 扫描注册的初始化状态 Mapper。
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

    /** 管理员可发起合法诊断并收到结构化结果。 */
    @Test
    void adminShouldCreateDiagnosis() throws Exception {
        authenticateAdmin();
        when(aiDiagnosisService.diagnose(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(diagnosis());

        mockMvc.perform(post("/api/ai/diagnoses").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content(body(1L, "why high?", 30)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary").value("CPU elevated"))
                .andExpect(jsonPath("$.data.model_used").value(true))
                .andExpect(jsonPath("$.data.prompt_version").value("ai-diagnosis-v1"));

        verify(aiDiagnosisService).diagnose(2L, 1L, "why high?", 30);
    }

    /** 未认证请求由安全链返回统一 401。 */
    @Test
    void unauthenticatedRequestShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/ai/diagnoses")
                        .contentType("application/json")
                        .content(body(1L, "why high?", 30)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    /** 普通 approved user 无法访问 AI 诊断接口，返回 403。 */
    @Test
    void approvedNonAdminShouldBeForbidden() throws Exception {
        authenticateUser();

        mockMvc.perform(post("/api/ai/diagnoses").header(AUTHORIZATION, USER_BEARER)
                        .contentType("application/json")
                        .content(body(1L, "why high?", 30)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verify(aiDiagnosisService, never()).diagnose(any(), any(), any(), any());
    }

    /** 缺失 server_id 的请求体在校验阶段拒绝，不进入服务层。 */
    @Test
    void missingServerIdShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();

        mockMvc.perform(post("/api/ai/diagnoses").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"question\":\"why high?\",\"history_minutes\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(aiDiagnosisService, never()).diagnose(any(), any(), any(), any());
    }

    /** 非正数 server_id 在校验阶段拒绝。 */
    @Test
    void nonPositiveServerIdShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();

        mockMvc.perform(post("/api/ai/diagnoses").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content(body(0L, "why high?", 30)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /** 空 question 在校验阶段拒绝。 */
    @Test
    void blankQuestionShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();

        mockMvc.perform(post("/api/ai/diagnoses").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content(body(1L, "", 30)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /** 超出契约上限的历史窗口被参数校验拒绝。 */
    @Test
    void oversizedHistoryWindowShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();

        mockMvc.perform(post("/api/ai/diagnoses").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content(body(1L, "why high?", 1441)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /** 服务层业务异常映射为统一错误码，不透传 provider 细节。 */
    @Test
    void serviceExceptionShouldMapToApiError() throws Exception {
        authenticateAdmin();
        when(aiDiagnosisService.diagnose(anyLong(), anyLong(), anyString(), anyInt()))
                .thenThrow(new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED));

        mockMvc.perform(post("/api/ai/diagnoses").header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content(body(1L, "why high?", 30)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42906));
    }

    private void authenticateAdmin() {
        when(jwtTokenService.parseToken(ADMIN_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(2L, "admin_user", "admin-token-id",
                        Instant.parse("2026-08-31T00:00:00Z")));
        when(userMapper.selectAuthenticationUserById(2L)).thenReturn(authenticationUser("admin"));
    }

    private void authenticateUser() {
        when(jwtTokenService.parseToken(USER_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(3L, "approved_user", "user-token-id",
                        Instant.parse("2026-08-31T00:00:00Z")));
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

    private String body(Long serverId, String question, Integer historyMinutes) throws Exception {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("server_id", serverId);
        map.put("question", question);
        map.put("history_minutes", historyMinutes);
        return new ObjectMapper().writeValueAsString(map);
    }

    private AiDiagnosisVo diagnosis() {
        AiDiagnosisVo diagnosis = new AiDiagnosisVo();
        diagnosis.setSummary("CPU elevated");
        diagnosis.setSeverity("warning");
        AiFindingVo finding = new AiFindingVo();
        finding.setTitle("High CPU");
        finding.setDescription("CPU above threshold");
        finding.setConfidence("high");
        diagnosis.setFindings(List.of(finding));
        diagnosis.setEvidence(List.of());
        diagnosis.setRecommendations(List.of("inspect metrics"));
        diagnosis.setLimitations(List.of("advisory only"));
        diagnosis.setModelUsed(true);
        diagnosis.setProvider("openai-compatible");
        diagnosis.setModel("test-model");
        diagnosis.setPromptVersion("ai-diagnosis-v1");
        diagnosis.setUsage(new AiUsageVo());
        return diagnosis;
    }
}