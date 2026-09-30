package com.susumonitor.server.config;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 解析并严格校验 AES-256-GCM 密钥，为凭据加解密提供统一密钥。
 */
@Configuration
public class AesGcmKeyConfig {

    private static final int AES_256_KEY_BYTES = 32;

    private static final String AES_ALGORITHM = "AES";

    /**
     * 将 Base64 配置转换为长度严格等于 256 位的 AES 密钥。
     *
     * @param appProperties 应用配置
     * @return AES-256 密钥
     */
    // 注册独立命名的 AES 密钥，凭据密码器通过 Qualifier 明确选择该 Bean。
    @Bean("aesGcmKey")
    public SecretKey aesGcmKey(AppProperties appProperties) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(appProperties.getSecurity().getAesGcmKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AES-GCM key must be valid Base64", exception);
        }

        if (keyBytes.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException("AES-GCM key must decode to exactly 32 bytes");
        }
        return new SecretKeySpec(keyBytes, AES_ALGORITHM);
    }
}
