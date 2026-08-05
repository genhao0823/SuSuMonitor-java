package com.susumonitor.server.module.server.controller;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.GlobalExceptionHandler;
import com.susumonitor.server.common.RequestIdFilter;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.server.service.AgentTokenService;
import com.susumonitor.server.module.server.vo.AgentTokenVo;
import com.susumonitor.server.security.JwtTokenService;
import com.susumonitor.server.security.SecurityConfig;
import com.susumonitor.server.security.SecurityErrorHandler;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.terminal.mapper.TerminalSessionMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证 Agent Token 管理接口的管理员权限、参数校验和一次性响应契约。 */
// 激活测试配置，避免读取本机敏感配置。
@ActiveProfiles("test")
// 只加载 Agent Token Controller 所需的 MVC 测试切片。
@WebMvcTest(AgentTokenController.class)
// 引入真实安全链、请求追踪和统一异常映射，验证完整 HTTP 边界。
@Import({SecurityConfig.class, SecurityErrorHandler.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class AgentTokenControllerTests {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final String AUTHORIZATION = "Authorization";
    private static final String ADMIN_BEARER = "Bearer " + ADMIN_TOKEN;
    private static final String USER_BEARER = "Bearer " + USER_TOKEN;

    // 注入 MockMvc，通过真实 MVC 和安全过滤器链调用目标接口。
    @Autowired
    private MockMvc mockMvc;

    // 隔离 Agent Token 业务和数据库，仅验证 Controller 契约。
    @MockitoBean
    private AgentTokenService agentTokenService;

    // 提供可控 JWT 解析结果，覆盖管理员和普通用户场景。
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



    /** 验证管理员可注册和轮换 Token，明文仅出现在这两个一次性响应中。 */
    @Test
    void adminShouldRegisterAndRotateAgentToken() throws Exception {
        authenticateAdmin();
        when(agentTokenService.register(1L)).thenReturn(token("registered-token"));
        when(agentTokenService.rotate(1L)).thenReturn(token("rotated-token"));

        mockMvc.perform(post("/api/servers/1/agent/register").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.server_id").value(1))
                .andExpect(jsonPath("$.data.agent_token").value("registered-token"));

        mockMvc.perform(post("/api/servers/1/agent/rotate").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agent_token").value("rotated-token"));
    }

    /** 验证管理员撤销 Token 后返回统一空成功响应。 */
    @Test
    void adminShouldRevokeAgentToken() throws Exception {
        authenticateAdmin();

        mockMvc.perform(delete("/api/servers/1/agent/revoke").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(agentTokenService).revoke(1L);
    }

    /** 验证普通已审核用户不能管理 Agent Token。 */
    @Test
    void approvedUserShouldBeForbidden() throws Exception {
        authenticateUser();

        mockMvc.perform(post("/api/servers/1/agent/register").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(40300));

        verify(agentTokenService, never()).register(any());
    }

    /** 验证未认证请求由安全链返回统一 401。 */
    @Test
    void unauthenticatedRequestShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/servers/1/agent/register"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(40100));
    }

    /** 验证非法服务器 ID 在进入 Service 前被拒绝。 */
    @Test
    void nonPositiveServerIdShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();

        mockMvc.perform(post("/api/servers/0/agent/register").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(agentTokenService, never()).register(any());
    }

    /** 验证 Service 的资源不存在错误保持统一 404 契约。 */
    @Test
    void missingServerShouldReturnNotFound() throws Exception {
        authenticateAdmin();
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)).when(agentTokenService).revoke(99L);

        mockMvc.perform(delete("/api/servers/99/agent/revoke").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    private void authenticateAdmin() {
        when(jwtTokenService.parseToken(ADMIN_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "admin-token-id"));
        when(userMapper.selectAuthenticationUserById(1L)).thenReturn(authenticationUser(1L, "admin", "admin"));
    }

    private void authenticateUser() {
        when(jwtTokenService.parseToken(USER_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(2L, "approved_user", "user-token-id"));
        when(userMapper.selectAuthenticationUserById(2L))
                .thenReturn(authenticationUser(2L, "approved_user", "user"));
    }

    private UserEntity authenticationUser(Long id, String username, String role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setReviewStatus("approved");
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    private AgentTokenVo token(String value) {
        return new AgentTokenVo(1L, value,
                OffsetDateTime.of(2026, 7, 23, 0, 0, 0, 0, ZoneOffset.UTC));
    }
}
