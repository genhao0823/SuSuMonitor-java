package com.susumonitor.server.module.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.auth.entity.AuthBootstrapStateEntity;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.security.CredentialCipher;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 启用 Mockito 扩展，为测试初始化字段上的 Mock 对象。
@ExtendWith(MockitoExtension.class)
class BootstrapTokenServiceImplTests {

    // 43 字符的最小合法令牌样例（base64url 形态，仅测试用，非真实凭据）。
    private static final String SAMPLE_TOKEN = "A7f3kP9qR2vX5mZ8bC1dE4gH6jL0nS3uW5yY2aB9cD8eF0gHh";

    @Mock
    private AuthBootstrapStateMapper authBootstrapStateMapper;

    private CredentialCipher credentialCipher;

    private AppProperties appProperties;

    private BootstrapTokenServiceImpl bootstrapTokenService;

    /**
     * 在每个测试前使用固定 256 位测试密钥与真实密码器创建待测服务。
     */
    @BeforeEach
    void setUp() {
        byte[] keyBytes = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
        credentialCipher = new CredentialCipher(secretKey);
        appProperties = new AppProperties();
        bootstrapTokenService = new BootstrapTokenServiceImpl(
                authBootstrapStateMapper, credentialCipher, appProperties);
    }

    // 验证首管理员已初始化时启动装配为空操作，不写任何令牌。
    @Test
    void ensureTokenReadyShouldNoOpWhenAdminInitialized() {
        when(authBootstrapStateMapper.selectState()).thenReturn(pendingState(true, null));

        bootstrapTokenService.ensureTokenReady();

        verify(authBootstrapStateMapper, never()).saveBootstrapToken(anyString(), any());
    }

    // 验证状态行缺失（如测试上下文）时装配静默跳过。
    @Test
    void ensureTokenReadyShouldNoOpWhenStateMissing() {
        when(authBootstrapStateMapper.selectState()).thenReturn(null);

        bootstrapTokenService.ensureTokenReady();

        verify(authBootstrapStateMapper, never()).saveBootstrapToken(anyString(), any());
    }

    // 验证环境变量预置令牌长度非法时 fail-fast 阻止启动。
    @Test
    void ensureTokenReadyShouldFailFastOnShortPresetToken() {
        appProperties.getSecurity().setBootstrapToken("too-short-token");

        assertThrows(IllegalStateException.class, () -> bootstrapTokenService.ensureTokenReady());
        verify(authBootstrapStateMapper, never()).saveBootstrapToken(anyString(), any());
    }

    // 验证环境变量预置令牌被加密落库且可解密回原文。
    @Test
    void ensureTokenReadyShouldLoadPresetTokenFromEnvironment() {
        appProperties.getSecurity().setBootstrapToken(SAMPLE_TOKEN);
        when(authBootstrapStateMapper.selectState()).thenReturn(pendingState(false, null));

        bootstrapTokenService.ensureTokenReady();

        ArgumentCaptor<String> cipherCaptor = ArgumentCaptor.forClass(String.class);
        verify(authBootstrapStateMapper).saveBootstrapToken(cipherCaptor.capture(), any());
        assertTrue(cipherCaptor.getValue().startsWith("v1:"));
        assertEquals(SAMPLE_TOKEN, credentialCipher.decryptForBootstrap(cipherCaptor.getValue()));
    }

    // 验证重启且令牌未消费时仅回显横幅，不重复生成覆盖。
    @Test
    void ensureTokenReadyShouldReprintPendingTokenWithoutOverwrite() {
        String existingCipher = credentialCipher.encryptForBootstrap(SAMPLE_TOKEN);
        when(authBootstrapStateMapper.selectState()).thenReturn(pendingState(false, existingCipher));

        assertDoesNotThrow(() -> bootstrapTokenService.ensureTokenReady());

        verify(authBootstrapStateMapper, never()).saveBootstrapToken(anyString(), any());
    }

    // 验证无预置且无存量密文时自动生成 256-bit 令牌并落库。
    @Test
    void ensureTokenReadyShouldGenerateTokenWhenNoneExists() {
        when(authBootstrapStateMapper.selectState()).thenReturn(pendingState(false, null));

        bootstrapTokenService.ensureTokenReady();

        ArgumentCaptor<String> cipherCaptor = ArgumentCaptor.forClass(String.class);
        verify(authBootstrapStateMapper).saveBootstrapToken(cipherCaptor.capture(), any());
        String generated = credentialCipher.decryptForBootstrap(cipherCaptor.getValue());
        assertEquals(43, generated.length());
    }

    // 验证状态行写入被并发初始化抢占（返回 0）时装配不报错。
    @Test
    void ensureTokenReadyShouldTolerateConcurrentInitializationOnSave() {
        appProperties.getSecurity().setBootstrapToken(SAMPLE_TOKEN);
        when(authBootstrapStateMapper.selectState()).thenReturn(pendingState(false, null));
        when(authBootstrapStateMapper.saveBootstrapToken(anyString(), any())).thenReturn(0);

        assertDoesNotThrow(() -> bootstrapTokenService.ensureTokenReady());
    }

    // 验证候选令牌为空时返回 40310。
    @Test
    void verifyAgainstShouldThrowRequiredWhenCandidateBlank() {
        AuthBootstrapStateEntity state = pendingState(false,
                credentialCipher.encryptForBootstrap(SAMPLE_TOKEN));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> bootstrapTokenService.verifyAgainst(state, null));

        assertEquals(ErrorCode.AUTH_BOOTSTRAP_REQUIRED, exception.getErrorCode());
    }

    // 验证状态行无密文（服务端未签发）时按 40311 fail-closed 拒绝。
    @Test
    void verifyAgainstShouldThrowInvalidWhenCipherMissing() {
        AuthBootstrapStateEntity state = pendingState(false, null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> bootstrapTokenService.verifyAgainst(state, SAMPLE_TOKEN));

        assertEquals(ErrorCode.AUTH_BOOTSTRAP_TOKEN_INVALID, exception.getErrorCode());
    }

    // 验证候选令牌与签发值不匹配时返回 40311。
    @Test
    void verifyAgainstShouldThrowInvalidOnMismatch() {
        AuthBootstrapStateEntity state = pendingState(false,
                credentialCipher.encryptForBootstrap(SAMPLE_TOKEN));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> bootstrapTokenService.verifyAgainst(state, "B".repeat(43)));

        assertEquals(ErrorCode.AUTH_BOOTSTRAP_TOKEN_INVALID, exception.getErrorCode());
    }

    // 验证候选令牌与签发值一致时校验通过。
    @Test
    void verifyAgainstShouldPassOnMatch() {
        AuthBootstrapStateEntity state = pendingState(false,
                credentialCipher.encryptForBootstrap(SAMPLE_TOKEN));

        assertDoesNotThrow(() -> bootstrapTokenService.verifyAgainst(state, SAMPLE_TOKEN));
    }

    // 验证 bootstrap 上下文与服务器上下文密文互不通用（AAD 隔离）。
    @Test
    void bootstrapCipherShouldBeIsolatedFromServerContext() {
        String bootstrapCipher = credentialCipher.encryptForBootstrap(SAMPLE_TOKEN);

        assertThrows(IllegalStateException.class,
                () -> credentialCipher.decrypt(42L, "ssh_password", bootstrapCipher));
    }

    // 创建待初始化（或已初始化）状态行测试数据。
    private AuthBootstrapStateEntity pendingState(boolean adminInitialized, String cipher) {
        AuthBootstrapStateEntity state = new AuthBootstrapStateEntity();
        state.setId(1L);
        state.setAdminInitialized(adminInitialized);
        state.setBootstrapTokenCipher(cipher);
        state.setBootstrapTokenGeneratedAt(cipher == null ? null : LocalDateTime.now());
        return state;
    }
}
