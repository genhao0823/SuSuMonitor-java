package com.susumonitor.server.module.ai.service;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.entity.AiUserProviderConfigEntity;
import com.susumonitor.server.module.ai.mapper.AiUserProviderConfigMapper;
import com.susumonitor.server.module.ai.provider.AiEgressPolicy;
import com.susumonitor.server.module.ai.provider.AiProviderException;
import com.susumonitor.server.module.ai.provider.OpenAiCompatibleProvider;
import com.susumonitor.server.module.ai.vo.AiProviderConfigTestVo;
import com.susumonitor.server.module.ai.vo.AiProviderConfigVo;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 管理员个人 AI 服务商配置的存取与校验。
 *
 * <p>安全契约：api_key 仅以 AES-256-GCM 密文落库（AAD 绑定 user_id）；明文只在
 * 本服务内解密后交给 {@link AiProviderResolver} 构造 provider 或执行连通性测试，
 * 绝不进入日志、审计表或 VO（对外仅暴露掩码）。endpoint 默认强制 HTTPS，仅当全局
 * {@code allow-insecure-http=true} 时放宽 http://（与既有全局 provider 同一口径）。
 * 保存与连通性测试前执行 {@link AiEgressPolicy} 出站地址校验（SSRF 防护，
 * deny-private 默认拒绝环回/私网/metadata，allow-private 放行内网网关）。</p>
 */
@Slf4j
@Service
// 与 AI 模块其他 Bean 一致：仅在 AI 启用时装配，保证关闭时零加载（也避免测试上下文加载 MyBatis 依赖）。
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiUserProviderConfigService {

    /** 当前唯一支持的服务商类型：OpenAI 兼容 chat completions。 */
    private static final String SUPPORTED_PROVIDER = "openai-compatible";

    private static final int MAX_URL_LENGTH = 512;

    private static final int MAX_MODEL_LENGTH = 128;

    private static final int MAX_API_KEY_LENGTH = 256;

    private final AiUserProviderConfigMapper mapper;
    private final com.susumonitor.server.security.CredentialCipher cipher;
    /** 原型 Builder：每次构造独立 provider 实例取新实例，避免共享可变请求工厂。 */
    private final ObjectProvider<RestClient.Builder> restClientBuilder;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final AppProperties appProperties;
    /** 个人 Provider 出站地址策略：保存/测试期预检 + 传入测试用 provider 做调用期校验。 */
    private final AiEgressPolicy egressPolicy;

    /** 注入 Mapper、凭据密码器、原型 RestClient Builder、全局配置与出站地址策略。 */
    public AiUserProviderConfigService(AiUserProviderConfigMapper mapper,
            com.susumonitor.server.security.CredentialCipher cipher,
            ObjectProvider<RestClient.Builder> restClientBuilder,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            AppProperties appProperties, AiEgressPolicy egressPolicy) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.egressPolicy = egressPolicy;
    }

    /** 解密后的个人配置（仅内存传递，禁止日志/序列化）。 */
    public record DecryptedUserAiConfig(Long userId, String baseUrl, String apiKey,
            String model, boolean enabled) {
    }

    /** 按用户加载解密后的配置；未配置时返回 null。 */
    public DecryptedUserAiConfig findDecryptedByUserId(Long userId) {
        AiUserProviderConfigEntity entity = mapper.selectByUserId(userId);
        if (entity == null) {
            return null;
        }
        return new DecryptedUserAiConfig(userId, entity.getBaseUrl(),
                cipher.decryptForUser(userId, entity.getApiKeyCiphertext()),
                entity.getModel(), Boolean.TRUE.equals(entity.getEnabled()));
    }

    /** 返回掩码后的个人配置视图；未配置时 configured=false 且其余字段为 null。 */
    public AiProviderConfigVo getVo(Long userId) {
        AiUserProviderConfigEntity entity = mapper.selectByUserId(userId);
        AiProviderConfigVo vo = new AiProviderConfigVo();
        if (entity == null) {
            vo.setConfigured(false);
            return vo;
        }
        vo.setConfigured(true);
        vo.setProvider(entity.getProvider());
        vo.setBaseUrl(entity.getBaseUrl());
        vo.setModel(entity.getModel());
        vo.setApiKeyMasked(mask(safeDecryptForMask(userId, entity.getApiKeyCiphertext())));
        vo.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        vo.setUpdatedAt(entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
        return vo;
    }

    /** 掩码用解密失败不阻塞展示：密文损坏按未配置 Key 处理并记警告。 */
    private String safeDecryptForMask(Long userId, String ciphertext) {
        try {
            return cipher.decryptForUser(userId, ciphertext);
        } catch (RuntimeException exception) {
            log.warn("AI api key ciphertext undecryptable for mask, userId={}", userId);
            return null;
        }
    }

    /**
     * 保存（或覆盖）个人配置。
     *
     * @param rawApiKey 明文 Key；为空白且已有配置时表示保留原 Key，否则拒绝
     * @throws BusinessException 40002（endpoint/model/Key 校验失败）
     */
    public void upsert(Long userId, String baseUrl, String rawApiKey, String model, boolean enabled) {
        validateEndpoint(baseUrl);
        validateModel(model);
        AiUserProviderConfigEntity existing = mapper.selectByUserId(userId);
        String normalizedKey = normalizeKey(rawApiKey);
        String ciphertext;
        if (normalizedKey.isEmpty()) {
            if (existing == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
            }
            ciphertext = existing.getApiKeyCiphertext();
        } else {
            ciphertext = cipher.encryptForUser(userId, normalizedKey);
        }
        AiUserProviderConfigEntity config = new AiUserProviderConfigEntity();
        config.setUserId(userId);
        config.setProvider(SUPPORTED_PROVIDER);
        config.setBaseUrl(normalizeEndpoint(baseUrl));
        config.setApiKeyCiphertext(ciphertext);
        config.setModel(model.trim());
        config.setEnabled(enabled);
        if (mapper.upsert(config) != 1) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR);
        }
        log.info("AI user provider config saved, userId={}, enabled={}", userId, enabled);
    }

    /** 删除个人配置；返回是否存在过。 */
    public boolean deleteByUserId(Long userId) {
        return mapper.deleteByUserId(userId) > 0;
    }

    /**
     * 用给定参数发起一次最小真实调用（无工具单次问答），校验 endpoint/Key/模型可用性。
     *
     * <p>api_key 为空且用户已有配置时复用已存密钥，便于"只改模型先测通"的场景。
     * 测试请求不写审计表、不计入限流与预算。</p>
     */
    public AiProviderConfigTestVo test(Long userId, String baseUrl, String rawApiKey, String model) {
        validateEndpoint(baseUrl);
        String normalizedKey = normalizeKey(rawApiKey);
        if (normalizedKey.isEmpty()) {
            AiUserProviderConfigEntity existing = mapper.selectByUserId(userId);
            if (existing == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
            }
            normalizedKey = cipher.decryptForUser(userId, existing.getApiKeyCiphertext());
        }
        validateModel(model);
        AppProperties.Ai effective = effectiveAi(normalizeEndpoint(baseUrl), normalizedKey, model.trim());
        AiProviderConfigTestVo vo = new AiProviderConfigTestVo();
        long started = System.nanoTime();
        try {
            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                    restClientBuilder.getObject(), objectMapper, effective, egressPolicy);
            provider.askWithoutTools("ping", "{}");
            vo.setOk(true);
        } catch (AiProviderException exception) {
            vo.setOk(false);
            vo.setErrorCode(exception.getErrorCode().getCode());
            vo.setMessage(switch (exception.getErrorCode()) {
                // 配置校验失败单独给可读原因；出站策略拦截提示改用公网可达地址。
                case AI_DISABLED_OR_REDACTION_FAILED ->
                        "endpoint 或 key 不满足服务端校验要求（HTTPS/非空）";
                case AI_PROVIDER_ENDPOINT_BLOCKED ->
                        "endpoint 被出站策略拒绝（解析到环回/私网/云 metadata 地址）";
                default -> "AI 服务商调用失败";
            });
        } finally {
            vo.setLatencyMs((System.nanoTime() - started) / 1_000_000);
        }
        return vo;
    }

    /** 生成全局配置副本并覆盖为用户生效值（超时/重试/上限等治理参数保持全局口径）。 */
    public AppProperties.Ai effectiveAi(String baseUrl, String apiKey, String model) {
        AppProperties.Ai copy = new AppProperties.Ai();
        BeanUtils.copyProperties(appProperties.getAi(), copy);
        copy.setProvider(SUPPORTED_PROVIDER);
        copy.setBaseUrl(baseUrl);
        copy.setApiKey(apiKey);
        copy.setModel(model);
        copy.setEnabled(true);
        return copy;
    }

    /**
     * endpoint 校验：scheme 白名单（HTTPS 默认，HTTP 需全局显式放宽）、长度、可解析性
     * 与出站地址策略（deny-private 时拒绝解析到环回/私网/metadata 的 endpoint，SSRF 防护）。
     */
    private void validateEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.length() > MAX_URL_LENGTH
                || baseUrl.chars().anyMatch(Character::isWhitespace)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        boolean https = baseUrl.startsWith("https://");
        boolean insecureHttp = appProperties.getAi().isAllowInsecureHttp()
                && baseUrl.startsWith("http://");
        if (!https && !insecureHttp) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        // 出站地址校验放在 scheme/格式校验之后；allow-private 策略下策略内部直接放行。
        if (!egressPolicy.check(baseUrl).allowed()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    private void validateModel(String model) {
        if (model == null || model.isBlank() || model.trim().length() > MAX_MODEL_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    private String normalizeKey(String rawApiKey) {
        String normalized = rawApiKey == null ? "" : rawApiKey.trim();
        if (normalized.length() > MAX_API_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        return normalized;
    }

    /** 去掉末尾斜杠；provider 出站时固定追加 /chat/completions。 */
    private String normalizeEndpoint(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** API Key 掩码：保留前 3 后 4，其余以 **** 隐藏；过短或缺失返回固定掩码。 */
    static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "****";
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }
}
