package com.susumonitor.server.module.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ClientIpResolver;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.auth.limit.LoginRateLimiter;
import com.susumonitor.server.module.auth.limit.RegisterRateLimiter;
import com.susumonitor.server.module.auth.service.UserService;
import com.susumonitor.server.security.JwtAuthenticationFilter;
import com.susumonitor.server.security.JwtTokenService;
import com.susumonitor.server.security.RedisTokenBlacklist;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证登出黑名单写入的故障语义（2026-09-14 安全评审决策一）：
 * fail_closed（默认）抛 50302 业务异常要求客户端重试，fail_open 记 warn 后应答成功。
 */
class AuthControllerRevokePolicyTests {

    private RedisTokenBlacklist blacklist;

    private AppProperties appProperties;

    private AuthController controller;

    /**
     * 构造被测控制器：直接注入 mock 依赖，登出所需 ParsedToken 放入 request attribute。
     */
    @BeforeEach
    void setUp() {
        blacklist = mock(RedisTokenBlacklist.class);
        ObjectProvider<RedisTokenBlacklist> blacklistProvider = mock(ObjectProvider.class);
        when(blacklistProvider.getIfAvailable()).thenReturn(blacklist);
        ObjectProvider<LoginRateLimiter> loginLimiter = mock(ObjectProvider.class);
        ObjectProvider<RegisterRateLimiter> registerLimiter = mock(ObjectProvider.class);
        appProperties = new AppProperties();
        controller = new AuthController(mock(UserService.class), blacklistProvider,
                loginLimiter, registerLimiter, mock(ClientIpResolver.class),
                Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC), appProperties);
    }

    /**
     * 构造携带有效 ParsedToken 的请求（TTL 恒为正：过期时间在固定 Clock 之后 1 小时）。
     */
    private MockHttpServletRequest requestWithParsedToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.JWT_PARSED_TOKEN_ATTRIBUTE,
                new JwtTokenService.ParsedToken(1L, "admin", "token-id",
                        Instant.parse("2026-09-14T01:00:00Z")));
        return request;
    }

    /** fail_closed（默认）：黑名单写入故障时抛 503/50302，客户端应重试否则 Token 未被吊销。 */
    @Test
    void revokeFailureShouldFailClosedWith50302() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("redis down"))
                .when(blacklist).revoke(org.mockito.ArgumentMatchers.eq("token-id"),
                        org.mockito.ArgumentMatchers.any(java.time.Duration.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.logout(requestWithParsedToken()));

        assertEquals(com.susumonitor.server.common.ErrorCode.REDIS_UNAVAILABLE, exception.getErrorCode());
    }

    /** fail_open：黑名单写入故障时记 warn 并应答成功（Token 存活至自然过期，契约已标注）。 */
    @Test
    void revokeFailureShouldFailOpenWhenConfigured() {
        appProperties.getSecurity().setTokenBlacklistOnError("fail_open");
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("redis down"))
                .when(blacklist).revoke(org.mockito.ArgumentMatchers.eq("token-id"),
                        org.mockito.ArgumentMatchers.any(java.time.Duration.class));

        ApiResponse<Void> response = controller.logout(requestWithParsedToken());

        assertEquals(0, response.getCode());
        verify(blacklist).revoke(org.mockito.ArgumentMatchers.eq("token-id"),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }
}
