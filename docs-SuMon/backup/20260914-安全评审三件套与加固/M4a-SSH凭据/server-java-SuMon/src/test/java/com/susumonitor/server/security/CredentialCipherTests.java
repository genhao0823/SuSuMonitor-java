package com.susumonitor.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证凭据 AES-GCM 信封、随机 IV、AAD 绑定和篡改检测规则。
 */
class CredentialCipherTests {

    private static final Long SERVER_ID = 42L;

    private static final String CREDENTIAL_TYPE = "ssh_password";

    private static final String PLAINTEXT = "credential-test-value";

    private CredentialCipher credentialCipher;

    /**
     * 在每个测试前使用固定 256 位测试密钥创建密码器。
     */
    @BeforeEach
    void setUp() {
        byte[] keyBytes = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
        credentialCipher = new CredentialCipher(secretKey);
    }

    /**
     * 验证受支持凭据可以按 v1 信封正常加密并解密。
     */
    @Test
    void encryptAndDecryptShouldRestorePlaintext() {
        String envelope = credentialCipher.encrypt(SERVER_ID, CREDENTIAL_TYPE, PLAINTEXT);

        assertTrue(envelope.startsWith("v1:"));
        assertEquals(PLAINTEXT, credentialCipher.decrypt(SERVER_ID, CREDENTIAL_TYPE, envelope));
    }

    /**
     * 验证相同明文和 AAD 因随机 IV 产生不同密文。
     */
    @Test
    void encryptShouldUseRandomIv() {
        String firstEnvelope = credentialCipher.encrypt(SERVER_ID, CREDENTIAL_TYPE, PLAINTEXT);
        String secondEnvelope = credentialCipher.encrypt(SERVER_ID, CREDENTIAL_TYPE, PLAINTEXT);

        assertNotEquals(firstEnvelope, secondEnvelope);
    }

    /**
     * 验证密文不能在其他服务器 ID 上解密。
     */
    @Test
    void decryptWithWrongServerIdShouldFail() {
        String envelope = credentialCipher.encrypt(SERVER_ID, CREDENTIAL_TYPE, PLAINTEXT);

        assertThrows(
                IllegalStateException.class,
                () -> credentialCipher.decrypt(43L, CREDENTIAL_TYPE, envelope));
    }

    /**
     * 验证密文不能以其他凭据类型解密。
     */
    @Test
    void decryptWithWrongCredentialTypeShouldFail() {
        String envelope = credentialCipher.encrypt(SERVER_ID, CREDENTIAL_TYPE, PLAINTEXT);

        assertThrows(
                IllegalStateException.class,
                () -> credentialCipher.decrypt(SERVER_ID, "ssh_private_key", envelope));
    }

    /**
     * 验证 GCM 标签能够拒绝被篡改的密文。
     */
    @Test
    void decryptTamperedEnvelopeShouldFail() {
        String envelope = credentialCipher.encrypt(SERVER_ID, CREDENTIAL_TYPE, PLAINTEXT);
        byte[] payload = Base64.getDecoder().decode(envelope.substring("v1:".length()));
        payload[payload.length - 1] ^= 1;
        String tamperedEnvelope = "v1:" + Base64.getEncoder().encodeToString(payload);

        assertThrows(
                IllegalStateException.class,
                () -> credentialCipher.decrypt(SERVER_ID, CREDENTIAL_TYPE, tamperedEnvelope));
    }

    /**
     * 验证不在白名单中的凭据类型不能用于加密。
     */
    @Test
    void unsupportedCredentialTypeShouldFail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> credentialCipher.encrypt(SERVER_ID, "api_token", PLAINTEXT));
    }

    /**
     * 验证空上下文和空敏感参数均被拒绝。
     */
    @Test
    void blankArgumentsShouldFail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> credentialCipher.encrypt(null, CREDENTIAL_TYPE, PLAINTEXT));
        assertThrows(
                IllegalArgumentException.class,
                () -> credentialCipher.encrypt(SERVER_ID, " ", PLAINTEXT));
        assertThrows(
                IllegalArgumentException.class,
                () -> credentialCipher.encrypt(SERVER_ID, CREDENTIAL_TYPE, " "));
        assertThrows(
                IllegalArgumentException.class,
                () -> credentialCipher.decrypt(SERVER_ID, CREDENTIAL_TYPE, " "));
    }
}
