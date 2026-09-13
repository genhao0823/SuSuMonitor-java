package com.susumonitor.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import io.jsonwebtoken.MalformedJwtException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 验证 Bearer 过滤器的 Header 规则、数据库状态回查和认证上下文。
 */
class JwtAuthenticationFilterTests {

    private JwtTokenService jwtTokenService;

    private UserMapper userMapper;

    private JwtAuthenticationFilter filter;

    private AppProperties appProperties;

    /**
     * 为每个测试创建隔离的过滤器依赖。
     */
    @BeforeEach
    void setUp() {
        jwtTokenService = mock(JwtTokenService.class);
        userMapper = mock(UserMapper.class);
        appProperties = new AppProperties();
        SecurityErrorHandler errorHandler = new SecurityErrorHandler(new ObjectMapper());
        // 默认无 Redis 黑名单（Redis 未启用场景）：ObjectProvider 返回空。
        ObjectProvider<RedisTokenBlacklist> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<AppProperties> propertiesProvider = mock(ObjectProvider.class);
        when(propertiesProvider.getIfAvailable()).thenReturn(appProperties);
        filter = new JwtAuthenticationFilter(jwtTokenService, userMapper, errorHandler, emptyProvider,
                propertiesProvider);
        SecurityContextHolder.clearContext();
    }

    /**
     * 防止测试线程残留认证上下文。
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 验证没有 Authorization Header 时继续执行，由安全链决定接口是否公开。
     */
    @Test
    void missingAuthorizationShouldContinueWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertNull(SecurityContextHolder.getContext().getAuthentication()));

        verify(jwtTokenService, never()).parseToken(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * 验证合法管理员 Token 使用数据库角色建立认证上下文，且 credentials 为空。
     */
    @Test
    void validTokenShouldCreateAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenService.parseToken("valid-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id", Instant.parse("2026-08-18T00:00:00Z")));
        when(userMapper.selectAuthenticationUserById(1L)).thenReturn(user("approved", "admin"));
        AtomicReference<Authentication> authentication = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                authentication.set(SecurityContextHolder.getContext().getAuthentication()));

        assertEquals("ROLE_ADMIN", authentication.get().getAuthorities().iterator().next().getAuthority());
        assertNull(authentication.get().getCredentials());
    }

    /**
     * 验证非法认证方案返回统一 401。
     */
    @Test
    void nonBearerAuthorizationShouldReturnUnauthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic credentials");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertEquals(40100, new ObjectMapper().readTree(response.getContentAsByteArray()).get("code").asInt());
    }

    /**
     * 验证 JWT 解析失败不回查数据库并返回统一 401。
     */
    @Test
    void invalidJwtShouldReturnUnauthorized() throws Exception {        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenService.parseToken("invalid-token")).thenThrow(new MalformedJwtException("invalid"));

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(401, response.getStatus());
        verify(userMapper, never()).selectAuthenticationUserById(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * 验证已被吊销（登出）的 token 在过滤器阶段直接 401，不回查数据库。
     */
    @Test
    void revokedTokenShouldReturnUnauthorized() throws Exception {
        RedisTokenBlacklist blacklist = mock(RedisTokenBlacklist.class);
        when(blacklist.isRevoked("token-id")).thenReturn(true);
        ObjectProvider<RedisTokenBlacklist> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(blacklist);
        ObjectProvider<AppProperties> propertiesProvider = mock(ObjectProvider.class);
        when(propertiesProvider.getIfAvailable()).thenReturn(appProperties);
        filter = new JwtAuthenticationFilter(jwtTokenService, userMapper,
                new SecurityErrorHandler(new ObjectMapper()), provider, propertiesProvider);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenService.parseToken("valid-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id", Instant.parse("2026-08-18T00:00:00Z")));

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(401, response.getStatus());
        verify(userMapper, never()).selectAuthenticationUserById(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * 验证 fail_closed（默认）：Redis 黑名单查询故障时返回 503/50302 专用错误面，
     * 不与数据库错误混同，且不回查数据库（2026-09-14 安全评审决策一）。
     */
    @Test
    void blacklistFailureShouldFailClosedWith503() throws Exception {
        RedisTokenBlacklist blacklist = mock(RedisTokenBlacklist.class);
        when(blacklist.isRevoked("token-id"))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        ObjectProvider<RedisTokenBlacklist> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(blacklist);
        ObjectProvider<AppProperties> propertiesProvider = mock(ObjectProvider.class);
        when(propertiesProvider.getIfAvailable()).thenReturn(appProperties);
        filter = new JwtAuthenticationFilter(jwtTokenService, userMapper,
                new SecurityErrorHandler(new ObjectMapper()), provider, propertiesProvider);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenService.parseToken("valid-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id",
                        Instant.parse("2026-08-18T00:00:00Z")));

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(503, response.getStatus());
        assertEquals(50302, new ObjectMapper().readTree(response.getContentAsByteArray()).get("code").asInt());
        verify(userMapper, never()).selectAuthenticationUserById(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * 验证 fail_open：配置切换后 Redis 黑名单查询故障时放行（继续走数据库状态回查并建立认证）。
     */
    @Test
    void blacklistFailureShouldFailOpenWhenConfigured() throws Exception {
        appProperties.getSecurity().setTokenBlacklistOnError("fail_open");
        RedisTokenBlacklist blacklist = mock(RedisTokenBlacklist.class);
        when(blacklist.isRevoked("token-id"))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        ObjectProvider<RedisTokenBlacklist> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(blacklist);
        ObjectProvider<AppProperties> propertiesProvider = mock(ObjectProvider.class);
        when(propertiesProvider.getIfAvailable()).thenReturn(appProperties);
        filter = new JwtAuthenticationFilter(jwtTokenService, userMapper,
                new SecurityErrorHandler(new ObjectMapper()), provider, propertiesProvider);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenService.parseToken("valid-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id",
                        Instant.parse("2026-08-18T00:00:00Z")));
        when(userMapper.selectAuthenticationUserById(1L)).thenReturn(user("approved", "admin"));
        AtomicReference<Authentication> authentication = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                authentication.set(SecurityContextHolder.getContext().getAuthentication()));

        assertEquals("ROLE_ADMIN", authentication.get().getAuthorities().iterator().next().getAuthority());
    }

    /**
     * 验证用户审核状态失效后旧 Token 立即返回 401。
     */
    @Test
    void pendingUserShouldInvalidateExistingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenService.parseToken("valid-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id", Instant.parse("2026-08-18T00:00:00Z")));
        when(userMapper.selectAuthenticationUserById(1L)).thenReturn(user("pending", "admin"));

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(401, response.getStatus());
    }

    /**
     * 验证认证成功后的下游异常不会被过滤器误转换为 JWT 内部错误。
     */
    @Test
    void downstreamExceptionShouldPropagate() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenService.parseToken("valid-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id", Instant.parse("2026-08-18T00:00:00Z")));
        when(userMapper.selectAuthenticationUserById(1L)).thenReturn(user("approved", "admin"));

        assertThrows(IllegalStateException.class, () -> filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    throw new IllegalStateException("downstream failure");
                }));
    }

    /**
     * 创建鉴权回查用户。
     *
     * @param reviewStatus 审核状态
     * @param role 角色
     * @return 用户实体
     */
    private UserEntity user(String reviewStatus, String role) {
        UserEntity userEntity = new UserEntity();
        userEntity.setId(1L);
        userEntity.setUsername("admin");
        userEntity.setRole(role);
        userEntity.setReviewStatus(reviewStatus);
        userEntity.setCreatedAt(LocalDateTime.now());
        return userEntity;
    }
}
