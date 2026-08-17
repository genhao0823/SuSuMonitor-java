package com.susumonitor.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.susumonitor.server.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 JWT 的固定算法、标准声明、有效期和验签规则。
 */
class JwtTokenServiceTests {

    private SecretKey signingKey;

    private JwtTokenService jwtTokenService;

    /**
     * 为每个测试创建独立的 HS256 服务。
     */
    @BeforeEach
    void setUp() {
        signingKey = Keys.hmacShaKeyFor("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        AppProperties appProperties = new AppProperties();
        appProperties.getJwt().setExpireHours(72);
        jwtTokenService = new JwtTokenServiceImpl(signingKey, appProperties);
    }

    /**
     * 验证签发 Token 使用 HS256 并包含正式契约要求的声明。
     */
    @Test
    void issueTokenShouldContainRequiredClaims() {
        JwtTokenService.IssuedToken issuedToken = jwtTokenService.issueToken(1L, "admin");
        var signedClaims = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(issuedToken.token());
        Claims claims = signedClaims.getPayload();

        assertEquals("HS256", signedClaims.getHeader().getAlgorithm());
        assertEquals("susumonitor", claims.getIssuer());
        assertEquals("1", claims.getSubject());
        assertEquals("admin", claims.get("username", String.class));
        assertEquals(259200L, issuedToken.expiresInSeconds());
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertNotNull(UUID.fromString(claims.getId()));
    }

    /**
     * 验证正常 Token 能解析为最小身份信息。
     */
    @Test
    void parseTokenShouldReturnIdentity() {
        JwtTokenService.IssuedToken issuedToken = jwtTokenService.issueToken(7L, "approved_user");

        JwtTokenService.ParsedToken parsedToken = jwtTokenService.parseToken(issuedToken.token());

        assertEquals(7L, parsedToken.userId());
        assertEquals("approved_user", parsedToken.username());
        assertNotNull(UUID.fromString(parsedToken.tokenId()));
        assertNotNull(parsedToken.expiresAt());
    }

    /**
     * 验证使用其他密钥签发的 Token 被拒绝。
     */
    @Test
    void parseTokenShouldRejectWrongSignature() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
        String token = validBuilder().signWith(otherKey, Jwts.SIG.HS256).compact();

        assertThrows(JwtException.class, () -> jwtTokenService.parseToken(token));
    }

    /**
     * 验证错误 issuer 被拒绝。
     */
    @Test
    void parseTokenShouldRejectWrongIssuer() {
        String token = validBuilder().issuer("other-service").signWith(signingKey, Jwts.SIG.HS256).compact();

        assertThrows(JwtException.class, () -> jwtTokenService.parseToken(token));
    }

    /**
     * 验证错误 audience 被拒绝。
     */
    @Test
    void parseTokenShouldRejectWrongAudience() {
        String token = Jwts.builder()
                .issuer("susumonitor")
                .audience().add("other-api").and()
                .subject("1")
                .claim("username", "admin")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000L))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        assertThrows(JwtException.class, () -> jwtTokenService.parseToken(token));
    }

    /**
     * 验证已过期 Token 被拒绝。
     */
    @Test
    void parseTokenShouldRejectExpiredToken() {
        long currentTime = System.currentTimeMillis();
        String token = Jwts.builder()
                .issuer("susumonitor")
                .audience().add("susumonitor-api").and()
                .subject("1")
                .claim("username", "admin")
                .issuedAt(new Date(currentTime - 120000L))
                .expiration(new Date(currentTime - 60000L))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        assertThrows(JwtException.class, () -> jwtTokenService.parseToken(token));
    }

    /**
     * 验证即使签名有效，非 HS256 算法也不能通过正式契约。
     */
    @Test
    void parseTokenShouldRejectNonHs256Algorithm() {
        SecretKey hs512Key = Keys.hmacShaKeyFor(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8));
        JwtTokenService hs512ParserService = serviceWithKey(hs512Key);
        String token = validBuilder().signWith(hs512Key, Jwts.SIG.HS512).compact();

        assertThrows(JwtException.class, () -> hs512ParserService.parseToken(token));
    }

    /**
     * 验证非数字 subject 被拒绝。
     */
    @Test
    void parseTokenShouldRejectInvalidSubject() {
        String token = validBuilder().subject("not-a-user-id").signWith(signingKey, Jwts.SIG.HS256).compact();

        assertThrows(JwtException.class, () -> jwtTokenService.parseToken(token));
    }

    /**
     * 验证非 UUID Token ID 被拒绝。
     */
    @Test
    void parseTokenShouldRejectInvalidTokenId() {
        String token = validBuilder().id("not-a-uuid").signWith(signingKey, Jwts.SIG.HS256).compact();

        assertThrows(JwtException.class, () -> jwtTokenService.parseToken(token));
    }

    /**
     * 使用指定密钥创建 JWT 服务，供算法白名单测试共享同一验签密钥。
     *
     * @param key JWT 密钥
     * @return JWT 服务
     */
    private JwtTokenService serviceWithKey(SecretKey key) {
        AppProperties appProperties = new AppProperties();
        appProperties.getJwt().setExpireHours(24);
        return new JwtTokenServiceImpl(key, appProperties);
    }

    /**
     * 创建包含正式必需声明的 JWT Builder，供异常场景定向覆盖字段。
     *
     * @return JWT Builder
     */
    private io.jsonwebtoken.JwtBuilder validBuilder() {
        return Jwts.builder()
                .issuer("susumonitor")
                .audience().add("susumonitor-api").and()
                .subject("1")
                .claim("username", "admin")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000L))
                .id(UUID.randomUUID().toString());
    }
}
