package com.susumonitor.server.security;

import java.time.Instant;

/**
 * 定义用户 JWT 的签发和解析契约，供认证业务与安全过滤器依赖。
 */
public interface JwtTokenService {

    /** 为已认证用户签发 JWT。 */
    IssuedToken issueToken(Long userId, String username);

    /** 校验 JWT 并提取最小认证声明。 */
    ParsedToken parseToken(String token);

    /**
     * 表示签发结果，Token 仅返回给调用方，不参与日志输出。
     *
     * @param token JWT
     * @param expiresInSeconds 剩余有效秒数
     */
    record IssuedToken(String token, long expiresInSeconds) {
    }

    /**
     * 表示验签后可用于数据库身份回查的最小声明集合。
     *
     * @param userId 用户 ID
     * @param username 用户名
     * @param tokenId JWT ID
     * @param expiresAt 过期时间（Redis 黑名单 TTL 依据）
     */
    record ParsedToken(Long userId, String username, String tokenId, Instant expiresAt) {
    }
}
