package com.susumonitor.server.module.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.entity.AiUserProviderConfigEntity;
import com.susumonitor.server.module.ai.mapper.AiUserProviderConfigMapper;
import com.susumonitor.server.module.ai.provider.AiEgressPolicy;
import com.susumonitor.server.module.ai.vo.AiProviderConfigVo;
import com.susumonitor.server.security.CredentialCipher;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

/** 验证个人 AI 服务商配置服务：密文存储、掩码视图、scheme fail-closed 与保留 Key 语义。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiUserProviderConfigServiceTests {

    private static final Long USER_ID = 7L;

    @Mock private AiUserProviderConfigMapper mapper;
    @Mock private ObjectProvider<RestClient.Builder> restClientBuilder;
    @Mock private AiEgressPolicy egressPolicy;

    private CredentialCipher cipher;
    private AppProperties appProperties;
    private AiUserProviderConfigService service;

    @BeforeEach
    void setUp() {
        javax.crypto.KeyGenerator generator;
        try {
            generator = javax.crypto.KeyGenerator.getInstance("AES");
            generator.init(256);
            SecretKey key = generator.generateKey();
            cipher = new CredentialCipher(key);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        appProperties = new AppProperties();
        appProperties.getAi().setEnabled(true);
        appProperties.getAi().setAllowInsecureHttp(false);
        // egress 策略默认放行：本类聚焦 scheme/掩码/密文语义，出站拦截用例单独覆盖。
        when(egressPolicy.check(any())).thenReturn(new AiEgressPolicy.Verdict(true, null));
        service = new AiUserProviderConfigService(mapper, cipher, restClientBuilder,
                new com.fasterxml.jackson.databind.ObjectMapper(), appProperties, egressPolicy);
    }

    /** 保存时 api_key 以密文落库且可按用户解密回原文，绝不存明文。 */
    @Test
    void upsertShouldStoreCiphertextAndRoundTrip() {
        when(mapper.upsert(any())).thenReturn(1);

        service.upsert(USER_ID, "https://api.example.test/v1", "sk-secret-abcdef", "test-model", true);

        ArgumentCaptor<AiUserProviderConfigEntity> captor =
                ArgumentCaptor.forClass(AiUserProviderConfigEntity.class);
        verify(mapper).upsert(captor.capture());
        AiUserProviderConfigEntity stored = captor.getValue();
        assertNotEquals("sk-secret-abcdef", stored.getApiKeyCiphertext());
        assertTrue(stored.getApiKeyCiphertext().startsWith("v1:"));
        assertEquals("sk-secret-abcdef", cipher.decryptForUser(USER_ID, stored.getApiKeyCiphertext()));
        assertEquals("https://api.example.test/v1", stored.getBaseUrl());
        assertTrue(stored.getEnabled());
    }

    /** api_key 空白且已有配置时保留原密文（只改 base_url/model 场景）。 */
    @Test
    void upsertWithBlankKeyShouldKeepStoredCiphertext() {
        AiUserProviderConfigEntity existing = new AiUserProviderConfigEntity();
        existing.setUserId(USER_ID);
        existing.setApiKeyCiphertext(cipher.encryptForUser(USER_ID, "sk-old-key-1234"));
        when(mapper.selectByUserId(USER_ID)).thenReturn(existing);
        when(mapper.upsert(any())).thenReturn(1);

        service.upsert(USER_ID, "https://api.example.test/v1", "  ", "new-model", true);

        ArgumentCaptor<AiUserProviderConfigEntity> captor =
                ArgumentCaptor.forClass(AiUserProviderConfigEntity.class);
        verify(mapper).upsert(captor.capture());
        assertEquals(existing.getApiKeyCiphertext(), captor.getValue().getApiKeyCiphertext());
        assertEquals("new-model", captor.getValue().getModel());
    }

    /** api_key 空白且从未配置时拒绝保存。 */
    @Test
    void upsertWithBlankKeyAndNoExistingShouldFail() {
        assertThrows(BusinessException.class,
                () -> service.upsert(USER_ID, "https://api.example.test/v1", "", "m", true));
    }

    /** 默认拒绝 http:// 明文 endpoint；显式放宽后放行。 */
    @Test
    void insecureHttpShouldBeRejectedUnlessExplicitlyAllowed() {
        assertThrows(BusinessException.class,
                () -> service.upsert(USER_ID, "http://127.0.0.1:8045/v1", "sk-key-1234", "m", true));

        appProperties.getAi().setAllowInsecureHttp(true);
        when(mapper.upsert(any())).thenReturn(1);
        // 放行分支改用公网字面量：本用例只验证 scheme 门，不与出站地址策略语义交叉。
        service.upsert(USER_ID, "http://93.184.216.34:8045/v1", "sk-key-1234", "m", true);
        verify(mapper).upsert(any());
    }

    /** deny-private 出站策略拦截私网 endpoint：保存阶段即拒绝且不触达 Mapper。 */
    @Test
    void upsertShouldRejectEndpointBlockedByEgressPolicy() {
        when(egressPolicy.check(any())).thenReturn(
                new AiEgressPolicy.Verdict(false, "endpoint 解析到受限地址（环回/私网/链路本地/云 metadata）"));

        assertThrows(BusinessException.class,
                () -> service.upsert(USER_ID, "https://internal-gateway.example.test/v1", "sk-key-1234", "m", true));
        verify(mapper, times(0)).upsert(any());
    }

    /** 连通性测试同样被出站策略预检拦截，不发起真实调用。 */
    @Test
    void testShouldRejectEndpointBlockedByEgressPolicy() {
        when(egressPolicy.check(any())).thenReturn(new AiEgressPolicy.Verdict(false, "blocked"));

        assertThrows(BusinessException.class,
                () -> service.test(USER_ID, "https://169.254.169.254/v1", "sk-key-1234", "m"));
    }

    /** 配置视图只返回掩码，且能完整还原 provider/base_url/model 等非敏感字段。 */
    @Test
    void getVoShouldMaskApiKeyAndExposeConfiguredFields() {
        AiUserProviderConfigEntity entity = new AiUserProviderConfigEntity();
        entity.setUserId(USER_ID);
        entity.setProvider("openai-compatible");
        entity.setBaseUrl("https://api.example.test/v1");
        entity.setApiKeyCiphertext(cipher.encryptForUser(USER_ID, "sk-masked-key-9876"));
        entity.setModel("test-model");
        entity.setEnabled(true);
        when(mapper.selectByUserId(USER_ID)).thenReturn(entity);

        AiProviderConfigVo vo = service.getVo(USER_ID);

        assertTrue(vo.getConfigured());
        assertEquals("https://api.example.test/v1", vo.getBaseUrl());
        assertEquals("test-model", vo.getModel());
        assertNotNull(vo.getApiKeyMasked());
        assertFalse(vo.getApiKeyMasked().contains("sk-masked-key-9876"));
        assertTrue(vo.getApiKeyMasked().contains("****"));
        assertTrue(vo.getEnabled());
    }

    /** 未配置时 configured=false 且敏感字段为 null。 */
    @Test
    void getVoWithoutConfigShouldReturnNotConfigured() {
        when(mapper.selectByUserId(USER_ID)).thenReturn(null);

        AiProviderConfigVo vo = service.getVo(USER_ID);

        assertFalse(vo.getConfigured());
        assertNull(vo.getBaseUrl());
        assertNull(vo.getApiKeyMasked());
    }

    /** 连通性测试沿用同一 scheme fail-closed 校验。 */
    @Test
    void testShouldRejectInsecureHttpWithoutGlobalAllowance() {
        assertThrows(BusinessException.class,
                () -> service.test(USER_ID, "http://127.0.0.1:8045/v1", "sk-key-1234", "m"));
    }

    /** 掩码工具：长 Key 保留前 3 后 4，短 Key 固定 ****。 */
    @Test
    void maskShouldHideKeyContent() {
        assertEquals("sk-****cdef", AiUserProviderConfigService.mask("sk-abcdefcdef"));
        assertEquals("****", AiUserProviderConfigService.mask("short"));
        assertEquals("****", AiUserProviderConfigService.mask(null));
        List.of(AiUserProviderConfigService.mask("sk-abcdefcdef")).forEach(
                masked -> assertFalse(masked.contains("abcdef")));
    }
}
