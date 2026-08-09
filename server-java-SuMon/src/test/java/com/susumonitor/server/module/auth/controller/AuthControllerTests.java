package com.susumonitor.server.module.auth.controller;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.GlobalExceptionHandler;
import com.susumonitor.server.module.auth.dto.RegisterRequest;
import com.susumonitor.server.module.auth.dto.LoginRequest;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.auth.service.UserService;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import com.susumonitor.server.module.auth.vo.LoginVo;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.module.metrics.mapper.IngestionCleanupMapper;
import com.susumonitor.server.security.JwtTokenService;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.terminal.mapper.TerminalSessionMapper;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

// 启用 Spring Boot 测试上下文和 MockMvc，验证注册 Controller 的 HTTP 行为。
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
// 启用 MockMvc，使测试无需启动真实 HTTP 端口即可调用 Controller。
@AutoConfigureMockMvc
class AuthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // 使用模拟 UserService，隔离 Controller 测试与数据库和业务实现的依赖。
    @MockitoBean
    private UserService userService;

    // 提供系统就绪检查所需的数据源替身，避免排除数据库自动配置后上下文无法创建。
    @MockitoBean
    private DataSource dataSource;

    // 提供 MyBatis Mapper 替身，避免控制器测试加载真实 Mapper 所需的会话工厂。
    @MockitoBean
    private UserMapper userMapper;

    // 提供初始化状态 Mapper 替身，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private AuthBootstrapStateMapper authBootstrapStateMapper;

    // 提供服务器 Mapper 替身，避免新增 Mapper 扫描后加载真实 MyBatis 会话工厂。
    @MockitoBean
    private ServerMapper serverMapper;

    @MockitoBean
    private MetricsMapper metricsMapper;

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
    private PlatformTransactionManager transactionManager;

    // 提供 JWT 服务替身，使安全过滤器测试可控制 Token 解析结果。
    @MockitoBean
    private JwtTokenService jwtTokenService;
    @MockitoBean
    private OutboxMapper outboxMapper;
    @MockitoBean
    private ConsumeRecordMapper consumeRecordMapper;



    @Test
    // 验证合法注册请求返回统一成功响应和请求 ID。
    void registerShouldReturnCreatedUser() throws Exception {
        RegisterRequest request = request("admin", "Password123");
        CurrentUserVo user = user("admin", "admin", "approved");
        when(userService.register(any(RegisterRequest.class))).thenReturn(user);

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.role").value("admin"))
                .andExpect(jsonPath("$.data.reviewStatus").value("approved"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    // 验证 DTO 校验拒绝不符合用户名规则的注册请求。
    void registerShouldRejectInvalidUsername() throws Exception {
        RegisterRequest request = request("bad-name", "Password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    // 验证密码参数校验失败时，日志只记录字段名而不记录密码原文。
    void invalidPasswordShouldNotAppearInValidationLog() throws Exception {
        String secretMarker = "SECRET_PASSWORD_MARKER_THAT_MUST_NOT_APPEAR_IN_LOG_01234567890123456789";
        RegisterRequest request = request("admin", secretMarker);
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        try {
            mockMvc.perform(post("/api/auth/register")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40002));

            String logOutput = listAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining(System.lineSeparator()));
            assertTrue(logOutput.contains("password"));
            assertFalse(logOutput.contains(secretMarker));
            assertFalse(logOutput.contains("rejected value"));
        } finally {
            logger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    @Test
    // 验证用户名冲突被转换为统一的 409 资源冲突响应。
    void registerShouldReturnConflictForDuplicateUsername() throws Exception {
        RegisterRequest request = request("admin", "Password123");
        when(userService.register(any(RegisterRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_CONFLICT));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message").value("resource conflict"));
    }

    @Test
    // 验证登录接口返回正式契约定义的 Token、有效期和用户信息。
    void loginShouldReturnTokenResponse() throws Exception {
        LoginRequest request = loginRequest("admin", "Password123");
        LoginVo loginVo = new LoginVo();
        loginVo.setToken("jwt-token");
        loginVo.setTokenType("Bearer");
        loginVo.setExpiresIn(259200L);
        loginVo.setUser(user("admin", "admin", "approved"));
        when(userService.login(any(LoginRequest.class))).thenReturn(loginVo);

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .header("X-Correlation-ID", "login-flow-1")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(header().string("X-Correlation-ID", "login-flow-1"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.data.token").value("jwt-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(259200L))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist());
    }

    @Test
    // 验证超长登录参数由 Bean Validation 返回 40002。
    void loginShouldRejectOversizedUsername() throws Exception {
        LoginRequest request = loginRequest("a".repeat(51), "Password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    // 验证无法解析的登录 JSON 返回参数错误而不是内部错误。
    void loginShouldRejectMalformedJson() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{malformed-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    // 验证缺失 Token 访问当前用户接口返回统一 401 和追踪 Header。
    void meWithoutTokenShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("X-Correlation-ID", "missing-token-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(header().string("X-Correlation-ID", "missing-token-1"))
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    // 验证合法 Token 使用数据库最新用户快照访问 me 和无状态 logout。
    void authenticatedUserShouldAccessMeAndLogout() throws Exception {
        UserEntity authenticationUser = authenticationUser();
        when(jwtTokenService.parseToken("valid-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id"));
        when(userMapper.selectAuthenticationUserById(1L)).thenReturn(authenticationUser);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.role").value("admin"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isEmpty());

        org.mockito.Mockito.verify(userMapper, org.mockito.Mockito.times(2))
                .selectAuthenticationUserById(1L);
    }

    @Test
    // 验证公开登录接口忽略客户端携带的过期或非法 Token。
    void loginShouldIgnoreInvalidAuthorizationHeader() throws Exception {
        LoginRequest request = loginRequest("admin", "Password123");
        LoginVo loginVo = new LoginVo();
        loginVo.setToken("new-token");
        loginVo.setTokenType("Bearer");
        loginVo.setExpiresIn(259200L);
        loginVo.setUser(user("admin", "admin", "approved"));
        when(userService.login(any(LoginRequest.class))).thenReturn(loginVo);

        mockMvc.perform(post("/api/auth/login")
                        .header("Authorization", "Bearer expired-or-invalid-token")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("new-token"));
    }

    @Test
    // 验证普通 approved 用户不能访问管理员路径，并返回统一 403。
    void normalUserShouldNotAccessAdminPath() throws Exception {
        UserEntity authenticationUser = authenticationUser();
        authenticationUser.setUsername("normal_user");
        authenticationUser.setRole("user");
        when(jwtTokenService.parseToken("user-token"))
                .thenReturn(new JwtTokenService.ParsedToken(2L, "normal_user", "token-id"));
        when(userMapper.selectAuthenticationUserById(2L)).thenReturn(authenticationUser);

        mockMvc.perform(get("/api/admin/users/pending")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300))
                .andExpect(jsonPath("$.data").isEmpty());
    }


    private RegisterRequest request(String username, String password) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    // 创建登录 Controller 测试请求。
    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    // 创建 Bearer 过滤器回查到的安全用户数据。
    private UserEntity authenticationUser() {
        UserEntity userEntity = new UserEntity();
        userEntity.setId(1L);
        userEntity.setUsername("admin");
        userEntity.setRole("admin");
        userEntity.setReviewStatus("approved");
        userEntity.setCreatedAt(java.time.LocalDateTime.now());
        return userEntity;
    }

    private CurrentUserVo user(String username, String role, String reviewStatus) {
        CurrentUserVo user = new CurrentUserVo();
        user.setId(1L);
        user.setUsername(username);
        user.setRole(role);
        user.setReviewStatus(reviewStatus);
        user.setCreatedAt(OffsetDateTime.now());
        return user;
    }
}
