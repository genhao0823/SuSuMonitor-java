package com.susumonitor.server.module.auth.controller;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.module.auth.dto.LoginRequest;
import com.susumonitor.server.module.auth.dto.RegisterRequest;
import com.susumonitor.server.module.auth.service.UserService;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import com.susumonitor.server.module.auth.vo.LoginVo;
import com.susumonitor.server.security.AuthenticatedUser;
import com.susumonitor.server.security.JwtAuthenticationFilter;
import com.susumonitor.server.security.JwtTokenService;
import com.susumonitor.server.security.RedisTokenBlacklist;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器，提供用户注册、登录、获取当前用户信息和登出接口。
 *
 * <p>所有认证接口统一以 /api/auth 为路径前缀，通过 UserService 完成认证业务。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    private final ObjectProvider<RedisTokenBlacklist> tokenBlacklist;

    private final Clock clock;

    /**
     * 接收注册请求并委托 UserService 完成用户创建业务。
     *
     * @param request 注册请求（触发 Bean Validation 校验）
     * @return 当前用户公开信息
     */
    @PostMapping("/register")
    public ApiResponse<CurrentUserVo> register(
            @Valid
            @RequestBody RegisterRequest request) {
        return ApiResponse.success(userService.register(request));
    }

    /**
     * 校验用户凭据并为已审核用户签发 JWT。
     *
     * @param request  登录请求
     * @param response HTTP 响应，用于禁止缓存敏感 Token
     * @return 登录结果
     */
    @PostMapping("/login")
    public ApiResponse<LoginVo> login(
            @Valid
            @RequestBody LoginRequest request,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        return ApiResponse.success(userService.login(request));
    }

    /**
     * 返回 Bearer 过滤器从数据库加载的最新安全用户快照。
     *
     * @param authenticatedUser 当前认证用户
     * @return 当前用户信息
     */
    @GetMapping("/me")
    public ApiResponse<CurrentUserVo> me(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return ApiResponse.success(authenticatedUser.toCurrentUserVo());
    }

    /**
     * 登出：Redis 启用时将当前 token 的 jti 写入黑名单（TTL=剩余有效期），
     * 此后任何实例携带该 token 请求均 401（真实失效）；Redis 未启用时保持
     * 无状态空操作（客户端删除本地 JWT）。
     *
     * @param request HTTP 请求（读取过滤器放置的 ParsedToken）
     * @return data 为 null 的统一成功响应
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        RedisTokenBlacklist blacklist = tokenBlacklist.getIfAvailable();
        if (blacklist != null) {
            JwtTokenService.ParsedToken parsedToken = (JwtTokenService.ParsedToken)
                    request.getAttribute(JwtAuthenticationFilter.JWT_PARSED_TOKEN_ATTRIBUTE);
            if (parsedToken != null) {
                Duration ttl = Duration.between(Instant.now(clock), parsedToken.expiresAt());
                if (!ttl.isNegative() && !ttl.isZero()) {
                    blacklist.revoke(parsedToken.tokenId(), ttl);
                }
            } else {
                log.warn("logout skipped: parsed token missing from request attribute");
            }
        }
        return ApiResponse.success(null);
    }
}
