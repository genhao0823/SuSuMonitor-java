package com.susumonitor.server.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 使用 AES-256-GCM 和服务器凭据上下文加密、解密敏感凭据。
 */
// 将凭据密码器注册为 Spring Bean，供需要持久化敏感凭据的业务组件注入。
@Component
public class CredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final String ENVELOPE_PREFIX = "v1:";

    private static final String AAD_TEMPLATE = "susumonitor:server:%d:credential:%s";

    /** 用户维度 AAD 模板：与服务器维度隔离，密文只能在其归属用户的 AI 场景下解密。 */
    private static final String USER_AAD_TEMPLATE = "susumonitor:user:%d:credential:%s";

    /** 用户维度当前唯一支持的凭据类型：管理员个人 AI 服务商 API Key。 */
    private static final String USER_CREDENTIAL_TYPE_AI_API_KEY = "ai_api_key";

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    private static final Set<String> SUPPORTED_CREDENTIAL_TYPES = Set.of(
            "ssh_password", "ssh_private_key", "ssh_private_key_passphrase");

    private final SecretKey secretKey;

    private final SecureRandom secureRandom;

    /**
     * 使用指定 AES 密钥创建凭据密码器。
     *
     * @param secretKey AES-256-GCM 密钥
    */
    public CredentialCipher(
            // 按 Bean 名称选择 AES-GCM 密钥，避免误注入同为 SecretKey 的 JWT 签名密钥。
            @Qualifier("aesGcmKey") SecretKey secretKey) {
        this.secretKey = secretKey;
        this.secureRandom = new SecureRandom();
    }

    /**
     * 使用随机 IV 和服务器凭据上下文加密明文，并生成 v1 信封。
     *
     * @param serverId 正数服务器 ID
     * @param credentialType 凭据类型
     * @param plaintext 非空凭据明文
     * @return v1 格式密文信封
     */
    public String encrypt(Long serverId, String credentialType, String plaintext) {
        validateContext(serverId, credentialType);
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Credential plaintext must not be blank");
        }

        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, serverId, credentialType, iv);
            byte[] encryptedBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelopeBytes = new byte[iv.length + encryptedBytes.length];
            System.arraycopy(iv, 0, envelopeBytes, 0, iv.length);
            System.arraycopy(encryptedBytes, 0, envelopeBytes, iv.length, encryptedBytes.length);
            return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(envelopeBytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential encryption failed", exception);
        }
    }

    /**
     * 校验 v1 信封和服务器凭据上下文后解密密文。
     *
     * @param serverId 正数服务器 ID
     * @param credentialType 凭据类型
     * @param envelope v1 格式密文信封
     * @return 凭据明文
     */
    public String decrypt(Long serverId, String credentialType, String envelope) {
        validateContext(serverId, credentialType);
        if (envelope == null || envelope.isBlank()) {
            throw new IllegalArgumentException("Credential envelope must not be blank");
        }
        if (!envelope.startsWith(ENVELOPE_PREFIX)) {
            throw new IllegalArgumentException("Credential envelope version is invalid");
        }

        byte[] envelopeBytes;
        try {
            envelopeBytes = Base64.getDecoder().decode(envelope.substring(ENVELOPE_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Credential envelope payload must be valid Base64", exception);
        }
        if (envelopeBytes.length <= IV_BYTES) {
            throw new IllegalArgumentException("Credential envelope payload is invalid");
        }

        byte[] iv = new byte[IV_BYTES];
        byte[] encryptedBytes = new byte[envelopeBytes.length - IV_BYTES];
        System.arraycopy(envelopeBytes, 0, iv, 0, iv.length);
        System.arraycopy(envelopeBytes, iv.length, encryptedBytes, 0, encryptedBytes.length);
        try {
            Cipher cipher = createCipher(Cipher.DECRYPT_MODE, serverId, credentialType, iv);
            return new String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential decryption failed", exception);
        }
    }

    /**
     * 校验 v1 信封和服务器凭据上下文后解密密文，直接以 char[] 返回明文。
     *
     * <p>与 {@link #decrypt} 的差异：全程不创建 String——UTF-8 字节经
     * {@link java.nio.charset.CharBuffer} 直接解码为 char[]，并在返回前清零中间
     * 明文字节缓冲，消除"凭据以 String 驻留堆"的暴露面（2026-09-14 安全评审）。
     * 适用于密码/口令类短凭据；私钥因 SSHJ API 只接受 String 仍走 {@link #decrypt}。</p>
     *
     * @param serverId 正数服务器 ID
     * @param credentialType 凭据类型
     * @param envelope v1 格式密文信封
     * @return 凭据明文字符数组（调用方负责用后清零）
     */
    public char[] decryptToCharArray(Long serverId, String credentialType, String envelope) {
        validateContext(serverId, credentialType);
        if (envelope == null || envelope.isBlank()) {
            throw new IllegalArgumentException("Credential envelope must not be blank");
        }
        if (!envelope.startsWith(ENVELOPE_PREFIX)) {
            throw new IllegalArgumentException("Credential envelope version is invalid");
        }

        byte[] envelopeBytes;
        try {
            envelopeBytes = Base64.getDecoder().decode(envelope.substring(ENVELOPE_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Credential envelope payload must be valid Base64", exception);
        }
        if (envelopeBytes.length <= IV_BYTES) {
            throw new IllegalArgumentException("Credential envelope payload is invalid");
        }

        byte[] iv = new byte[IV_BYTES];
        byte[] encryptedBytes = new byte[envelopeBytes.length - IV_BYTES];
        System.arraycopy(envelopeBytes, 0, iv, 0, iv.length);
        System.arraycopy(envelopeBytes, iv.length, encryptedBytes, 0, encryptedBytes.length);
        try {
            Cipher cipher = createCipher(Cipher.DECRYPT_MODE, serverId, credentialType, iv);
            byte[] plainBytes = cipher.doFinal(encryptedBytes);
            try {
                CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plainBytes));
                char[] chars = new char[decoded.remaining()];
                decoded.get(chars);
                return chars;
            } finally {
                // 返回前清零明文字节缓冲：byte[] 不再被引用后可被 GC 回收，降低堆转储暴露窗口。
                Arrays.fill(plainBytes, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential decryption failed", exception);
        }
    }

    /**
     * 使用随机 IV 和用户凭据上下文加密明文（当前仅用于 AI API Key），并生成 v1 信封。
     *
     * <p>AAD 与服务器维度严格隔离：同一密文换用户或换用途均无法解密。</p>
     *
     * @param userId 正数用户 ID
     * @param plaintext 非空凭据明文
     * @return v1 格式密文信封
     */
    public String encryptForUser(Long userId, String plaintext) {
        validateUserContext(userId);
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Credential plaintext must not be blank");
        }
        return encryptWithAad(userAad(userId), plaintext);
    }

    /**
     * 校验 v1 信封和用户凭据上下文后解密密文。
     *
     * @param userId 正数用户 ID
     * @param envelope v1 格式密文信封
     * @return 凭据明文
     */
    public String decryptForUser(Long userId, String envelope) {
        validateUserContext(userId);
        if (envelope == null || envelope.isBlank()) {
            throw new IllegalArgumentException("Credential envelope must not be blank");
        }
        return decryptWithAad(userAad(userId), envelope);
    }

    private String userAad(Long userId) {
        return USER_AAD_TEMPLATE.formatted(userId, USER_CREDENTIAL_TYPE_AI_API_KEY);
    }

    private void validateUserContext(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User ID must be greater than zero");
        }
    }

    /** 使用随机 IV 和指定 AAD 加密明文，生成 v1 信封（服务器/用户维度共用实现）。 */
    private String encryptWithAad(String aad, String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, aad, iv);
            byte[] encryptedBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelopeBytes = new byte[iv.length + encryptedBytes.length];
            System.arraycopy(iv, 0, envelopeBytes, 0, iv.length);
            System.arraycopy(encryptedBytes, 0, envelopeBytes, iv.length, encryptedBytes.length);
            return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(envelopeBytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential encryption failed", exception);
        }
    }

    /** 校验 v1 信封后按指定 AAD 解密密文（服务器/用户维度共用实现）。 */
    private String decryptWithAad(String aad, String envelope) {
        byte[] envelopeBytes;
        try {
            envelopeBytes = Base64.getDecoder().decode(envelope.substring(ENVELOPE_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Credential envelope payload must be valid Base64", exception);
        }
        if (envelopeBytes.length <= IV_BYTES) {
            throw new IllegalArgumentException("Credential envelope payload is invalid");
        }
        byte[] iv = new byte[IV_BYTES];
        byte[] encryptedBytes = new byte[envelopeBytes.length - IV_BYTES];
        System.arraycopy(envelopeBytes, 0, iv, 0, iv.length);
        System.arraycopy(envelopeBytes, iv.length, encryptedBytes, 0, encryptedBytes.length);
        try {
            Cipher cipher = createCipher(Cipher.DECRYPT_MODE, aad, iv);
            return new String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential decryption failed", exception);
        }
    }

    /**
     * 创建绑定随机 IV 和 AAD 的 AES-GCM Cipher。
     *
     * @param mode 加密或解密模式
     * @param aad 附加认证数据（服务器或用户维度模板渲染结果）
     * @param iv 12 字节 GCM IV
     * @return 已初始化的 Cipher
     * @throws GeneralSecurityException JCA 初始化失败
     */
    private Cipher createCipher(int mode, String aad, byte[] iv)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, secretKey, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    /**
     * 创建绑定随机 IV 和 AAD 的 AES-GCM Cipher（服务器维度入口，保持既有调用契约）。
     *
     * @param mode 加密或解密模式
     * @param serverId 服务器 ID
     * @param credentialType 凭据类型
     * @param iv 12 字节 GCM IV
     * @return 已初始化的 Cipher
     * @throws GeneralSecurityException JCA 初始化失败
     */
    private Cipher createCipher(int mode, Long serverId, String credentialType, byte[] iv)
            throws GeneralSecurityException {
        return createCipher(mode, AAD_TEMPLATE.formatted(serverId, credentialType), iv);
    }

    /**
     * 拒绝无效服务器 ID 和不受支持的凭据类型，确保 AAD 契约固定。
     *
     * @param serverId 服务器 ID
     * @param credentialType 凭据类型
     */
    private void validateContext(Long serverId, String credentialType) {
        if (serverId == null || serverId <= 0) {
            throw new IllegalArgumentException("Server ID must be greater than zero");
        }
        if (credentialType == null || credentialType.isBlank()) {
            throw new IllegalArgumentException("Credential type must not be blank");
        }
        if (!SUPPORTED_CREDENTIAL_TYPES.contains(credentialType)) {
            throw new IllegalArgumentException("Credential type is unsupported");
        }
    }
}
