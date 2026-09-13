package com.susumonitor.server.security;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.HttpMethod;

/**
 * 验证 Bearer JWT，并依据数据库最新用户状态建立无状态认证上下文。
 *
 * <p>Redis 启用时额外校验 JWT 黑名单（登出真实失效，跨实例生效）：黑名单查询遇
 * Redis 故障时按 {@code susumonitor.security.token-blacklist-on-error} 分流——
 * fail_closed（默认）以 503/50302 拒绝请求，fail_open 记 warn 后放行（2026-09-14
 * 安全评审决策一）。认证成功后将 ParsedToken 放入 request attribute，供登出等端点
 * 读取 tokenId 与剩余有效期。</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String ADMIN_ROLE = "admin";

    private static final String USER_ROLE = "user";

    private static final String APPROVED_STATUS = "approved";

    /** 认证成功后放置 ParsedToken 的 request attribute 名（登出端点读取）。 */
    public static final String JWT_PARSED_TOKEN_ATTRIBUTE = "jwt_parsed_token";

    /** 黑名单查询故障信号：Redis 不可用时由 checkBlacklist 抛出，交由过滤器按策略分流。 */
    private static final class TokenBlacklistUnavailableException extends RuntimeException {

        /** 用 Redis 原始异常作为 cause，保留排查线索但不外泄到响应。 */
        private TokenBlacklistUnavailableException(Throwable cause) {
            super(cause);
        }
    }

    private static final ZoneId APPLICATION_ZONE = ZoneOffset.UTC;

    private final JwtTokenService jwtTokenService;

    private final UserMapper userMapper;

    private final SecurityErrorHandler securityErrorHandler;

    private final ObjectProvider<RedisTokenBlacklist> tokenBlacklist;

    /**
     * 应用配置的惰性引用：WebMvcTest 切片上下文不含 AppProperties Bean，
     * 用 ObjectProvider 注入避免切片装配失败；缺席时按默认 fail_closed 处理。
     */
    private final ObjectProvider<AppProperties> appProperties;

    /**
     * 创建 Bearer 过滤器并注入 Token、用户状态和错误响应依赖。
     *
     * @param jwtTokenService JWT 服务
     * @param userMapper 用户数据访问接口
     * @param securityErrorHandler 安全错误处理器
     * @param tokenBlacklist Redis 黑名单（可选，Redis 未启用时为空）
     * @param appProperties 应用配置（读取黑名单故障语义开关；切片上下文可缺席）
     */
    public JwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            UserMapper userMapper,
            SecurityErrorHandler securityErrorHandler,
            ObjectProvider<RedisTokenBlacklist> tokenBlacklist,
            ObjectProvider<AppProperties> appProperties) {
        this.jwtTokenService = jwtTokenService;
        this.userMapper = userMapper;
        this.securityErrorHandler = securityErrorHandler;
        this.tokenBlacklist = tokenBlacklist;
        this.appProperties = appProperties;
    }

    /**
     * 公开接口忽略 Authorization Header，确保过期 Token 不阻止健康检查、注册或重新登录。
     *
     * @param request HTTP 请求
     * @return 是否跳过 JWT 验证
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return (HttpMethod.GET.matches(request.getMethod())
                && ("/api/health".equals(path) || "/api/ready".equals(path)))
                || (HttpMethod.POST.matches(request.getMethod())
                && ("/api/auth/register".equals(path) || "/api/auth/login".equals(path)));
    }

    /**
     * 解析可选 Bearer Header；存在凭据时必须验证成功，否则立即返回统一错误。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 过滤器链
     * @throws ServletException 下游 Servlet 异常
     * @throws IOException HTTP 输入输出异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String token = extractBearerToken(authorization);
            JwtTokenService.ParsedToken parsedToken = jwtTokenService.parseToken(token);
            RedisTokenBlacklist blacklist = tokenBlacklist.getIfAvailable();
            if (blacklist != null && checkBlacklist(blacklist, parsedToken.tokenId())) {
                throw new BadCredentialsException("token has been revoked");
            }
            UserEntity userEntity = userMapper.selectAuthenticationUserById(parsedToken.userId());
            if (!eligibleUser(userEntity, parsedToken)) {
                throw new BadCredentialsException("authentication is no longer valid");
            }
            request.setAttribute(JWT_PARSED_TOKEN_ATTRIBUTE, parsedToken);
            SecurityContextHolder.setContext(createSecurityContext(userEntity));
        } catch (TokenBlacklistUnavailableException exception) {
            // fail-closed 语义（2026-09-14 决策一）：Redis 故障时拒绝请求，返回 503/50302 专用错误面，
            // 不与数据库故障混同；已登出 Token 在 Redis 故障窗口内必然无法复活。
            LOGGER.error("JWT blacklist unavailable, failing closed (token-blacklist-on-error=fail_closed)",
                    exception.getCause());
            securityErrorHandler.writeTokenBlacklistUnavailable(response);
            return;
        } catch (JwtException | BadCredentialsException exception) {
            LOGGER.debug("JWT authentication rejected");
            securityErrorHandler.commence(request, response, new BadCredentialsException("unauthorized"));
            return;
        } catch (DataAccessException exception) {
            LOGGER.error("Database error during JWT authentication", exception);
            securityErrorHandler.writeDatabaseError(response);
            return;
        } catch (RuntimeException exception) {
            LOGGER.error("Unexpected JWT authentication error", exception);
            securityErrorHandler.writeInternalError(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 查询黑名单并应用故障语义（2026-09-14 安全评审决策一）。
     *
     * <p>查询正常时返回吊销与否；Redis 故障（DataAccessException）时按
     * {@code susumonitor.security.token-blacklist-on-error} 分流——fail_closed 抛出
     * {@link TokenBlacklistUnavailableException} 由外层转 503；fail_open 记 warn 后
     * 按未吊销继续，已登出 Token 可能在故障窗口内复活至自然过期。</p>
     *
     * @param blacklist Redis 黑名单
     * @param tokenId JWT jti
     * @return 该 Token 是否已被吊销
     */
    private boolean checkBlacklist(RedisTokenBlacklist blacklist, String tokenId) {
        try {
            return blacklist.isRevoked(tokenId);
        } catch (DataAccessException exception) {
            if (shouldFailOpen()) {
                LOGGER.warn("JWT blacklist unavailable, failing open; revoked tokens may pass "
                        + "until Redis recovers (token-blacklist-on-error=fail_open)");
                return false;
            }
            throw new TokenBlacklistUnavailableException(exception);
        }
    }

    /**
     * 判断是否按可用性优先放行（fail_open）；默认、配置缺席（切片上下文）与非法值
     * 均按 fail_closed 处理（安全默认）。
     *
     * @return true 表示 fail_open
     */
    private boolean shouldFailOpen() {
        AppProperties properties = appProperties.getIfAvailable();
        return properties != null
                && "fail_open".equals(properties.getSecurity().getTokenBlacklistOnError());
    }

    /**
     * 只接受单个非空 Bearer Token，拒绝其他认证方案和包含空白的凭据。
     *
     * @param authorization Authorization Header
     * @return JWT 字符串
     */
    private String extractBearerToken(String authorization) {
        if (!authorization.startsWith(BEARER_PREFIX)) {
            throw new BadCredentialsException("invalid authorization scheme");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new BadCredentialsException("invalid bearer token");
        }
        return token;
    }

    /**
     * 校验数据库最新审核状态和角色，并确认 Token 用户名仍与数据库一致。
     *
     * @param userEntity 数据库用户
     * @param parsedToken 已验证 Token
     * @return 用户是否仍可认证
     */
    private boolean eligibleUser(UserEntity userEntity, JwtTokenService.ParsedToken parsedToken) {
        return userEntity != null
                && APPROVED_STATUS.equals(userEntity.getReviewStatus())
                && validRole(userEntity.getRole())
                && parsedToken.username().equals(userEntity.getUsername());
    }

    /**
     * 角色只允许项目定义的管理员和普通用户。
     *
     * @param role 数据库角色
     * @return 角色是否合法
     */
    private boolean validRole(String role) {
        return ADMIN_ROLE.equals(role) || USER_ROLE.equals(role);
    }

    /**
     * 使用安全用户快照和数据库最新角色建立认证上下文，不保存 Token 凭据。
     *
     * @param userEntity 数据库用户
     * @return 新认证上下文
     */
    private SecurityContext createSecurityContext(UserEntity userEntity) {
        AuthenticatedUser principal = new AuthenticatedUser(
                userEntity.getId(),
                userEntity.getUsername(),
                userEntity.getRole(),
                userEntity.getReviewStatus(),
                toOffsetDateTime(userEntity.getReviewedAt()),
                toOffsetDateTime(userEntity.getCreatedAt()));
        String authority = "ROLE_" + userEntity.getRole().toUpperCase(Locale.ROOT);
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(authority)));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        return context;
    }

    /**
     * 按应用时区将数据库时间转换为接口时间。
     *
     * @param dateTime 数据库时间
     * @return 带偏移量时间
     */
    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(APPLICATION_ZONE).toOffsetDateTime();
    }
}
