package com.susumonitor.server.module.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.service.AiUserProviderConfigService;
import com.susumonitor.server.module.ai.tools.AiReadOnlyTools;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

/** 验证按用户 provider 解析：个人优先、停用/未配置回退全局、双缺失 fail-closed 与缓存复用。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiProviderResolverTests {

    private static final Long ACTOR_ID = 11L;

    @Mock private AiUserProviderConfigService configService;
    @Mock private ObjectProvider<OpenAiCompatibleProvider> globalProvider;
    @Mock private ObjectProvider<SpringAiChatClient> globalToolClient;
    @Mock private ObjectProvider<AiReadOnlyTools> qaTools;
    @Mock private ObjectProvider<RestClient.Builder> restClientBuilder;

    private AppProperties appProperties;
    private AiProviderResolver resolver;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        AppProperties.Ai ai = appProperties.getAi();
        ai.setEnabled(true);
        ai.setProvider("openai-compatible");
        ai.setBaseUrl("https://global.example.test/v1");
        ai.setApiKey("global-key");
        ai.setModel("global-model");
        resolver = new AiProviderResolver(configService, globalProvider, globalToolClient, qaTools,
                restClientBuilder, new com.fasterxml.jackson.databind.ObjectMapper(), appProperties);
        when(restClientBuilder.getObject()).thenReturn(RestClient.builder());
    }

    /** 无个人配置时回退全局 provider，并标注 GLOBAL 来源。 */
    @Test
    void withoutPersonalConfigShouldFallBackToGlobal() {
        OpenAiCompatibleProvider global = mockProvider();
        when(configService.findDecryptedByUserId(ACTOR_ID)).thenReturn(null);
        when(globalProvider.getIfAvailable()).thenReturn(global);

        AiProviderResolver.Resolution resolution = resolver.resolveForActor(ACTOR_ID);

        assertEquals(global, resolution.provider());
        assertEquals("global-model", resolution.model());
        assertEquals(AiProviderResolver.Source.GLOBAL, resolution.source());
    }

    /** 启用的个人配置优先于全局，model 取个人值。 */
    @Test
    void enabledPersonalConfigShouldTakePriority() {
        OpenAiCompatibleProvider global = mockProvider();
        when(globalProvider.getIfAvailable()).thenReturn(global);
        stubPersonal(true, "personal-model");

        AiProviderResolver.Resolution resolution = resolver.resolveForActor(ACTOR_ID);

        assertEquals(AiProviderResolver.Source.PERSONAL, resolution.source());
        assertEquals("personal-model", resolution.model());
        assertNotNull(resolution.provider());
        assertEquals(global, globalProvider.getIfAvailable());
    }

    /** 停用（enabled=false）的个人配置视为未配置，仍回退全局。 */
    @Test
    void disabledPersonalConfigShouldFallBackToGlobal() {
        OpenAiCompatibleProvider global = mockProvider();
        when(globalProvider.getIfAvailable()).thenReturn(global);
        stubPersonal(false, "personal-model");

        AiProviderResolver.Resolution resolution = resolver.resolveForActor(ACTOR_ID);

        assertEquals(AiProviderResolver.Source.GLOBAL, resolution.source());
        assertEquals("global-model", resolution.model());
    }

    /** 个人与全局均不可用时 fail-closed 50304。 */
    @Test
    void missingGlobalAndPersonalShouldFailClosed() {
        when(configService.findDecryptedByUserId(ACTOR_ID)).thenReturn(null);
        when(globalProvider.getIfAvailable()).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> resolver.resolveForActor(ACTOR_ID));

        assertEquals(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED, exception.getErrorCode());
    }

    /** 相同配置指纹的重复解析复用同一 provider 实例；配置变更后重建。 */
    @Test
    void sameConfigShouldReuseCachedProviderInstance() {
        when(globalProvider.getIfAvailable()).thenReturn(mockProvider());
        stubPersonal(true, "personal-model");
        AtomicInteger created = new AtomicInteger();
        when(restClientBuilder.getObject()).thenAnswer(invocation -> {
            created.incrementAndGet();
            return RestClient.builder();
        });

        AiProviderResolver.Resolution first = resolver.resolveForActor(ACTOR_ID);
        AiProviderResolver.Resolution second = resolver.resolveForActor(ACTOR_ID);

        assertEquals(1, created.get(), "provider should be cached across resolutions");
        assertEquals(first.provider(), second.provider());

        // 配置变更（换模型）后指纹失配，缓存重建。
        stubPersonal(true, "changed-model");
        resolver.resolveForActor(ACTOR_ID);
        assertEquals(2, created.get(), "changed config should rebuild provider");
    }

    /** 问答解析：qa 未启用时工具客户端为 null，但无工具降级 provider 仍可用。 */
    @Test
    void qaResolutionShouldExposeNoToolsProviderWithoutToolsBean() {
        appProperties.getAi().getQa().setEnabled(false);
        when(globalProvider.getIfAvailable()).thenReturn(mockProvider());

        AiProviderResolver.QaResolution resolution = resolver.resolveQaForActor(ACTOR_ID);

        assertNull(resolution.toolClient());
        assertNotNull(resolution.noToolsProvider());
        assertEquals("global-model", resolution.model());
    }

    private void stubPersonal(boolean enabled, String model) {
        when(configService.findDecryptedByUserId(ACTOR_ID)).thenReturn(
                new AiUserProviderConfigService.DecryptedUserAiConfig(ACTOR_ID,
                        "https://personal.example.test/v1", "personal-key", model, enabled));
        when(configService.effectiveAi("https://personal.example.test/v1", "personal-key", model))
                .thenAnswer(invocation -> {
                    AppProperties.Ai copy = new AppProperties.Ai();
                    copy.setProvider("openai-compatible");
                    copy.setBaseUrl(invocation.getArgument(0));
                    copy.setApiKey(invocation.getArgument(1));
                    copy.setModel(invocation.getArgument(2));
                    copy.setEnabled(true);
                    return copy;
                });
    }

    private OpenAiCompatibleProvider mockProvider() {
        return new OpenAiCompatibleProvider(RestClient.builder(),
                new com.fasterxml.jackson.databind.ObjectMapper(), effectiveGlobalAi());
    }

    private AppProperties.Ai effectiveGlobalAi() {
        return appProperties.getAi();
    }
}
