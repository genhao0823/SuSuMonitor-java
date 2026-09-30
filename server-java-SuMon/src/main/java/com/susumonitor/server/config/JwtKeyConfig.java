package com.susumonitor.server.config;

import io.jsonwebtoken.security.Keys;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 解析并校验 JWT 签名密钥，为后续 Token 签发和验签提供统一密钥。
 */
@Configuration
public class JwtKeyConfig {

    private static final int MINIMUM_HS256_KEY_BYTES = 32;

    /**
     * 将 Base64 JWT 密钥转换为满足 HS256 最低强度要求的 SecretKey。
     *
     * @param appProperties 应用配置
     * @return JWT 签名密钥
     */
    @Bean("jwtSigningKey")
    public SecretKey jwtSigningKey(AppProperties appProperties) {
        byte[] secretBytes;
        try {
            secretBytes = Base64.getDecoder().decode(appProperties.getJwt().getSecret());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT secret must be valid Base64", exception);
        }

        if (secretBytes.length < MINIMUM_HS256_KEY_BYTES) {
            throw new IllegalStateException("JWT secret must decode to at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(secretBytes);
    }
}
