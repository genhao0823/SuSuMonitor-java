package com.susumonitor.server.security;

import com.susumonitor.server.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 使用固定 HS256 契约签发和解析 SuSuMonitor 用户 JWT。
 */
// 将 JWT 服务注册为 Spring Bean，供登录业务和 Bearer 过滤器复用。
@Service
public class JwtTokenServiceImpl implements JwtTokenService {

    private static final String JWT_ISSUER = "susumonitor";

    private static final String JWT_AUDIENCE = "susumonitor-api";

    private static final String USERNAME_CLAIM = "username";

    private static final String HS256_ALGORITHM = "HS256";

    private static final long CLOCK_SKEW_SECONDS = 30L;

    private final SecretKey signingKey;

    private final long expiresInSeconds;

    private final JwtParser jwtParser;

    /**
     * 创建可复用的线程安全 JWT 解析器，并固化签名密钥与标准声明要求。
     *
     * @param signingKey HS256 签名密钥
     * @param appProperties 应用配置
     */
    public JwtTokenServiceImpl(
            // 明确选择 JWT 签名密钥，避免误注入同为 SecretKey 的凭据加密密钥。
            @Qualifier("jwtSigningKey") SecretKey signingKey,
            AppProperties appProperties) {
        this.signingKey = signingKey;
        this.expiresInSeconds = Duration.ofHours(appProperties.getJwt().getExpireHours()).toSeconds();
        this.jwtParser = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(JWT_ISSUER)
                .requireAudience(JWT_AUDIENCE)
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .build();
    }

    /**
     * 为已通过登录校验的用户签发 JWT。
     *
     * @param userId 用户 ID
     * @param username 用户名
     * @return Token 和剩余有效秒数
     */
    public IssuedToken issueToken(Long userId, String username) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(expiresInSeconds);
        String token = Jwts.builder()
                .issuer(JWT_ISSUER)
                .audience()
                .add(JWT_AUDIENCE)
                .and()
                .subject(userId.toString())
                .claim(USERNAME_CLAIM, username)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresInSeconds);
    }

    /**
     * 验证 JWT 签名和声明，并提取建立认证上下文所需的最小身份信息。
     *
     * @param token Bearer JWT
     * @return 已验证的用户 ID、用户名和 Token ID
     */
    public ParsedToken parseToken(String token) {
        Jws<Claims> signedClaims = jwtParser.parseSignedClaims(token);
        if (!HS256_ALGORITHM.equals(signedClaims.getHeader().getAlgorithm())) {
            throw new MalformedJwtException("unsupported JWT algorithm");
        }

        Claims claims = signedClaims.getPayload();
        String subject = claims.getSubject();
        String username = claims.get(USERNAME_CLAIM, String.class);
        String tokenId = claims.getId();
        Date issuedAt = claims.getIssuedAt();
        Date expiresAt = claims.getExpiration();
        if (subject == null || username == null || username.isBlank() || tokenId == null
                || issuedAt == null || expiresAt == null) {
            throw new MalformedJwtException("required JWT claim is missing");
        }

        Long userId = parseUserId(subject);
        validateTokenId(tokenId);
        validateTimeRange(issuedAt, expiresAt);
        return new ParsedToken(userId, username, tokenId, expiresAt.toInstant());
    }

    /**
     * 将 JWT subject 转为正数用户 ID。
     *
     * @param subject JWT subject
     * @return 用户 ID
     */
    private Long parseUserId(String subject) {
        try {
            Long userId = Long.valueOf(subject);
            if (userId <= 0) {
                throw new MalformedJwtException("invalid JWT subject");
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw new MalformedJwtException("invalid JWT subject", exception);
        }
    }

    /**
     * 要求 jti 使用 UUID，降低 Token ID 碰撞并保持契约一致。
     *
     * @param tokenId JWT ID
     */
    private void validateTokenId(String tokenId) {
        try {
            UUID.fromString(tokenId);
        } catch (IllegalArgumentException exception) {
            throw new MalformedJwtException("invalid JWT ID", exception);
        }
    }

    /**
     * 校验签发时间和过期时间的相对关系，并拒绝超出允许偏差的未来 Token。
     *
     * @param issuedAt 签发时间
     * @param expiresAt 过期时间
     */
    private void validateTimeRange(Date issuedAt, Date expiresAt) {
        Instant issuedInstant = issuedAt.toInstant();
        Instant expiresInstant = expiresAt.toInstant();
        if (!expiresInstant.isAfter(issuedInstant)
                || issuedInstant.isAfter(Instant.now().plusSeconds(CLOCK_SKEW_SECONDS))) {
            throw new MalformedJwtException("invalid JWT time range");
        }
    }

}
