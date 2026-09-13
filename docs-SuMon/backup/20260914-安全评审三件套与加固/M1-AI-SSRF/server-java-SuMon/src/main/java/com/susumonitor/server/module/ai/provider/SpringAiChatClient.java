package com.susumonitor.server.module.ai.provider;

import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.tools.AiReadOnlyTools;
import com.susumonitor.server.module.ai.vo.AiUsageVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 基于 Spring AI（ChatClient + Tool Calling）的运维问答调用端。
 *
 * <p>使用官方 OpenAI 兼容模块手动装配（不经 starter 自动配置），仅在
 * {@code susumonitor.ai.qa.enabled=true} 时创建 Bean；endpoint/key/model 复用
 * {@code susumonitor.ai.*} 既有配置，与 OpenAiCompatibleProvider 出站行为一致
 * （baseUrl + /chat/completions、HTTPS 默认、明文需显式放宽）。工具循环由
 * Spring AI ToolCallingManager 驱动，轮次预算由 {@link AiReadOnlyTools} 内的
 * 请求级守卫兜底强制。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ai.qa.enabled", havingValue = "true")
public class SpringAiChatClient {

    private static final int MAX_ANSWER_CHARS = 16000;

    /** 本实例生效的 AI 配置：全局 Bean 引用 live 配置对象；按用户实例持有解析后的快照。 */
    private final AppProperties.Ai ai;
    private final AiReadOnlyTools tools;
    private final ChatClient chatClient;

    /** 手动装配 OpenAI 兼容模型与 ChatClient；超时与诊断 provider 共用同一配置。 */
    // 类中另有按用户配置的公共构造器；必须显式指定 Spring 装配入口，避免多构造器歧义。
    @org.springframework.beans.factory.annotation.Autowired
    public SpringAiChatClient(AppProperties appProperties, AiReadOnlyTools tools) {
        this(appProperties, tools, appProperties.getAi());
    }

    /**
     * 按用户自定义配置构造独立实例（{@link AiProviderResolver} 缓存使用）。
     *
     * <p>{@code effectiveAi} 为解析后的配置快照（全局配置副本 + 用户 baseUrl/apiKey/model）；
     * {@link AiReadOnlyTools} 为无状态单例，可跨用户安全共享。</p>
     *
     * @param appProperties 应用配置（提供超时等全局参数）
     * @param tools 只读工具注册表（全局单例）
     * @param effectiveAi 解析后的生效配置
     */
    public SpringAiChatClient(AppProperties appProperties, AiReadOnlyTools tools, AppProperties.Ai effectiveAi) {
        this.ai = effectiveAi;
        this.tools = tools;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(ai.getConnectTimeoutMs());
        requestFactory.setReadTimeout(ai.getReadTimeoutMs());
        // completionsPath 显式对齐 OpenAiCompatibleProvider：baseUrl 由配置完整提供
        // （通常已含 /v1），路径固定拼 /chat/completions，避免 Spring AI 默认 /v1 前缀重复。
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(ai.getBaseUrl() == null || ai.getBaseUrl().isBlank()
                        ? "https://api.openai.com" : ai.getBaseUrl())
                .apiKey(ai.getApiKey() == null ? "" : ai.getApiKey())
                .completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(ai.getModel())
                .temperature(0.2)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 执行一次工具化问答。
     *
     * @throws AiProviderException 50304（配置非法）、42906（provider 429）、
     *                             50401（超时）、50303（不可用）、50305（响应非法）
     */
    public AiQaAnswer ask(String question) {
        validateConfiguration(ai);
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .system(AiQaPrompt.QA_SYSTEM_PROMPT)
                    .user(question)
                    .tools(tools)
                    .call()
                    .chatResponse();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) {
                throw new AiProviderException(ErrorCode.AI_RATE_LIMIT_REACHED, exception);
            }
            throw new AiProviderException(ErrorCode.AI_PROVIDER_UNAVAILABLE, exception);
        } catch (ResourceAccessException exception) {
            if (hasTimeoutCause(exception)) {
                throw new AiProviderException(ErrorCode.AI_PROVIDER_TIMEOUT, exception);
            }
            throw new AiProviderException(ErrorCode.AI_PROVIDER_UNAVAILABLE, exception);
        }
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            throw new AiProviderException(ErrorCode.AI_RESPONSE_INVALID);
        }
        String content = response.getResults().get(0).getOutput().getText();
        if (content == null || content.isBlank() || content.length() > MAX_ANSWER_CHARS) {
            throw new AiProviderException(ErrorCode.AI_RESPONSE_INVALID);
        }
        return new AiQaAnswer(content.trim(), toUsage(response.getMetadata().getUsage()));
    }

    private AiUsageVo toUsage(Usage usage) {
        AiUsageVo usageVo = new AiUsageVo();
        if (usage == null) {
            return usageVo;
        }
        Integer input = usage.getPromptTokens();
        Integer output = usage.getCompletionTokens();
        Integer total = usage.getTotalTokens();
        usageVo.setInputTokens(input == null ? 0 : input);
        usageVo.setOutputTokens(output == null ? 0 : output);
        usageVo.setTotalTokens(total == null
                ? usageVo.getInputTokens() + usageVo.getOutputTokens() : total);
        return usageVo;
    }

    /** 校验 provider 配置，非法时 fail-closed 拒绝出站（口径与诊断 provider 一致）。 */
    private void validateConfiguration(AppProperties.Ai ai) {
        boolean https = !blank(ai.getBaseUrl()) && ai.getBaseUrl().startsWith("https://");
        boolean insecureHttp = ai.isAllowInsecureHttp()
                && !blank(ai.getBaseUrl()) && ai.getBaseUrl().startsWith("http://");
        if (blank(ai.getBaseUrl()) || blank(ai.getApiKey()) || blank(ai.getModel())
                || (!https && !insecureHttp)) {
            throw new AiProviderException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
