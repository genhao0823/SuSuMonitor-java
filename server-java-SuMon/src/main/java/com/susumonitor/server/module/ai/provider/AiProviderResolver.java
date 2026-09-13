package com.susumonitor.server.module.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.service.AiUserProviderConfigService;
import com.susumonitor.server.module.ai.tools.AiReadOnlyTools;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 按调用者解析生效 AI 配置：个人配置优先，未配置（或已停用）时回退服务器全局配置。
 *
 * <p>个人 provider / 工具化问答客户端按 (baseUrl, apiKey, model, enabled) 指纹缓存，
 * 配置变更后指纹失配自动重建，无需显式失效；缓存上限超限时整体清空（管理员量级，
 * 重建成本仅为构造轻量 HTTP 客户端）。全局路径直接复用既有单例 Bean，不进缓存。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiProviderResolver {

    /** 配置来源：PERSONAL=管理员个人配置；GLOBAL=服务器全局配置兜底。 */
    public enum Source { PERSONAL, GLOBAL }

    /** 缓存条目上限：超过后整体清空（防御性上限，正常管理员量级远达不到）。 */
    private static final int MAX_CACHE_ENTRIES = 256;

    /** 诊断/命令建议等同步 AI 接口的解析结果。 */
    public record Resolution(AiProvider provider, String providerName, String model, Source source) {
    }

    /** 问答接口的解析结果：toolClient 可为 null（qa 未启用或工具装配缺失）。 */
    public record QaResolution(SpringAiChatClient toolClient, OpenAiCompatibleProvider noToolsProvider,
            String providerName, String model, Source source) {
    }

    private record ProviderCacheEntry(String fingerprint, OpenAiCompatibleProvider provider) {
    }

    private record ChatClientCacheEntry(String fingerprint, SpringAiChatClient client) {
    }

    private final AiUserProviderConfigService configService;
    private final ObjectProvider<OpenAiCompatibleProvider> globalProvider;
    private final ObjectProvider<SpringAiChatClient> globalToolClient;
    private final ObjectProvider<AiReadOnlyTools> qaTools;
    private final ObjectProvider<RestClient.Builder> restClientBuilder;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    /** 个人 Provider 出站地址策略：传给按用户配置构造的实例，启用调用前 SSRF 校验。 */
    private final AiEgressPolicy egressPolicy;
    private final ConcurrentHashMap<Long, ProviderCacheEntry> providerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ChatClientCacheEntry> chatClientCache = new ConcurrentHashMap<>();

    /** 注入个人配置服务、全局 provider/工具客户端（可缺省）与构造所需组件。 */
    public AiProviderResolver(AiUserProviderConfigService configService,
            ObjectProvider<OpenAiCompatibleProvider> globalProvider,
            ObjectProvider<SpringAiChatClient> globalToolClient,
            ObjectProvider<AiReadOnlyTools> qaTools,
            ObjectProvider<RestClient.Builder> restClientBuilder,
            ObjectMapper objectMapper, AppProperties appProperties, AiEgressPolicy egressPolicy) {
        this.configService = configService;
        this.globalProvider = globalProvider;
        this.globalToolClient = globalToolClient;
        this.qaTools = qaTools;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.egressPolicy = egressPolicy;
    }

    /**
     * 解析诊断/命令建议使用的 provider。
     *
     * @throws BusinessException 50304（个人与全局均不可用）
     */
    public Resolution resolveForActor(Long actorId) {
        AiUserProviderConfigService.DecryptedUserAiConfig personal =
                configService.findDecryptedByUserId(actorId);
        if (personal != null && personal.enabled()) {
            String fingerprint = fingerprint(personal);
            OpenAiCompatibleProvider provider = cachedProvider(actorId, fingerprint,
                    configService.effectiveAi(personal.baseUrl(), personal.apiKey(), personal.model()));
            return new Resolution(provider, "openai-compatible", personal.model(), Source.PERSONAL);
        }
        return globalResolution();
    }

    /**
     * 解析问答使用的工具化客户端与无工具降级 provider。
     *
     * @throws BusinessException 50304（个人与全局均不可用）
     */
    public QaResolution resolveQaForActor(Long actorId) {
        AiUserProviderConfigService.DecryptedUserAiConfig personal =
                configService.findDecryptedByUserId(actorId);
        if (personal != null && personal.enabled()) {
            String fingerprint = fingerprint(personal);
            AppProperties.Ai effective = configService.effectiveAi(
                    personal.baseUrl(), personal.apiKey(), personal.model());
            OpenAiCompatibleProvider provider = cachedProvider(actorId, fingerprint, effective);
            SpringAiChatClient client = appProperties.getAi().getQa().isEnabled()
                    ? cachedChatClient(actorId, fingerprint, effective) : null;
            return new QaResolution(client, provider, "openai-compatible", personal.model(),
                    Source.PERSONAL);
        }
        return globalQaResolution();
    }

    /** 全局兜底：无全局 provider Bean（AI 未启用）时 fail-closed。 */
    private Resolution globalResolution() {
        OpenAiCompatibleProvider global = globalProvider.getIfAvailable();
        if (global == null) {
            throw new BusinessException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED);
        }
        AppProperties.Ai ai = appProperties.getAi();
        return new Resolution(global, ai.getProvider(), ai.getModel(), Source.GLOBAL);
    }

    private QaResolution globalQaResolution() {
        OpenAiCompatibleProvider global = globalProvider.getIfAvailable();
        if (global == null) {
            throw new BusinessException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED);
        }
        AppProperties.Ai ai = appProperties.getAi();
        return new QaResolution(globalToolClient.getIfAvailable(), global,
                ai.getProvider(), ai.getModel(), Source.GLOBAL);
    }

    /** 指纹失配时重建并替换缓存条目；compute 保证同一用户并发解析只建一次。 */
    private OpenAiCompatibleProvider cachedProvider(Long actorId, String fingerprint,
            AppProperties.Ai effective) {
        ProviderCacheEntry entry = providerCache.compute(actorId, (key, existing) -> {
            if (existing != null && existing.fingerprint().equals(fingerprint)) {
                return existing;
            }
            evictIfNeeded(providerCache.size(), chatClientCache.size());
            return new ProviderCacheEntry(fingerprint, new OpenAiCompatibleProvider(
                    restClientBuilder.getObject(), objectMapper, effective, egressPolicy));
        });
        return entry.provider();
    }

    private SpringAiChatClient cachedChatClient(Long actorId, String fingerprint,
            AppProperties.Ai effective) {
        AiReadOnlyTools tools = qaTools.getIfAvailable();
        if (tools == null) {
            return null;
        }
        ChatClientCacheEntry entry = chatClientCache.compute(actorId, (key, existing) -> {
            if (existing != null && existing.fingerprint().equals(fingerprint)) {
                return existing;
            }
            evictIfNeeded(providerCache.size(), chatClientCache.size());
            return new ChatClientCacheEntry(fingerprint,
                    new SpringAiChatClient(appProperties, tools, effective, egressPolicy));
        });
        return entry.client();
    }

    /** 缓存总量超限时整体清空（任一缓存触发都会同时清掉另一侧的伴生实例）。 */
    private void evictIfNeeded(int providerCount, int chatClientCount) {
        if (providerCount >= MAX_CACHE_ENTRIES || chatClientCount >= MAX_CACHE_ENTRIES) {
            providerCache.clear();
            chatClientCache.clear();
        }
    }

    /** 指纹失配时重建并替换缓存条目；compute 保证同一用户并发解析只建一次。
     *  指纹追加出站策略值：策略在 deny-private/allow-private 间切换后旧缓存立即失配重建。 */
    private String fingerprint(AiUserProviderConfigService.DecryptedUserAiConfig config) {
        return config.baseUrl() + "|" + config.model() + "|" + config.enabled()
                + "|" + config.apiKey()
                + "|" + appProperties.getAi().getUserProviderEgressPolicy();
    }
}
