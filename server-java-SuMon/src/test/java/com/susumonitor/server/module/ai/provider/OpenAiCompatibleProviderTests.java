package com.susumonitor.server.module.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.model.AiAlertFacts;
import com.susumonitor.server.module.ai.model.AiDiagnosisContext;
import com.susumonitor.server.module.ai.vo.AiAlertExplanationVo;
import com.susumonitor.server.module.ai.vo.AiDiagnosisVo;
import com.susumonitor.server.module.ai.vo.AiEvidenceVo;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

/** 验证 OpenAI-compatible provider 的固定请求契约、严格响应解析与错误映射。 */
class OpenAiCompatibleProviderTests {

    private static final String BASE_URL = "https://model.example.test/v1";
    private static final String MODEL = "test-model";
    private static final String API_KEY = "test-api-key";

    private AppProperties appProperties;
    private ObjectMapper objectMapper;
    private MockRestServiceServer mockServer;
    private RestClient.Builder restClientBuilder;
    private OpenAiCompatibleProvider provider;

    /** 每个用例重建 appProperties 和未被 mock 化的 RestClient.Builder，再在用例内 bind。 */
    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        AppProperties.Ai ai = appProperties.getAi();
        ai.setEnabled(true);
        ai.setProvider("openai-compatible");
        ai.setBaseUrl(BASE_URL);
        ai.setApiKey(API_KEY);
        ai.setModel(MODEL);
        // 默认关闭重试，使既有错误映射用例保持单次请求；重试用例自行打开并使用毫秒级退避。
        ai.setRetryMaxAttempts(0);
        ai.setRetryBackoffBaseMs(1);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        provider = new OpenAiCompatibleProvider(appProperties, restClientBuilder, objectMapper, true);
    }

    /** 发送固定请求：HTTPS endpoint、Bearer 认证头、白名单上下文字段。 */
    @Test
    void shouldSendFixedEndpointAndAuthorizationHeader() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(openAiSuccessBody(), MediaType.APPLICATION_JSON));

        AiDiagnosisVo result = provider.diagnose("why high?", context());

        assertTrue(result.isModelUsed());
        assertEquals("openai-compatible", result.getProvider());
        assertEquals(MODEL, result.getModel());
        assertEquals("ai-diagnosis-v1", result.getPromptVersion());
        assertEquals(10, result.getUsage().getInputTokens());
        assertEquals(5, result.getUsage().getOutputTokens());
        assertEquals(15, result.getUsage().getTotalTokens());
        mockServer.verify();
    }

    /** provider 关闭时即使输入合法也不能发出任何请求。 */
    @Test
    void shouldFailClosedWhenDisabled() {
        appProperties.getAi().setEnabled(false);
        provider = new OpenAiCompatibleProvider(appProperties, restClientBuilder, objectMapper, true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED, exception.getErrorCode());
        mockServer.verify();
    }

    /** 非 HTTPS endpoint 在发出请求前被拒绝，防止密钥走明文通道。 */
    @Test
    void shouldRejectNonHttpsEndpointBeforeRequest() {
        appProperties.getAi().setBaseUrl("http://model.example.test/v1");
        provider = new OpenAiCompatibleProvider(appProperties, restClientBuilder, objectMapper, true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED, exception.getErrorCode());
        mockServer.verify();
    }

    /** deny-private 下个人 Provider 指向环回地址：出站前以 50306 拦截，不发出任何 HTTP 请求。 */
    @Test
    void shouldBlockPrivateEndpointBeforeRequestForUserProvider() {
        appProperties.getAi().setBaseUrl("https://127.0.0.1:9/v1");
        provider = new OpenAiCompatibleProvider(restClientBuilder, objectMapper, appProperties.getAi(),
                new AiEgressPolicy(appProperties));

        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_PROVIDER_ENDPOINT_BLOCKED, exception.getErrorCode());
        mockServer.verify();
    }

    /** provider 429 响应映射为 AI 限流错误。 */
    @Test
    void shouldMapRateLimitResponse() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_RATE_LIMIT_REACHED, exception.getErrorCode());
        mockServer.verify();
    }

    /** provider 5xx 响应映射为不可用错误，不透传原始错误体。 */
    @Test
    void shouldMapServerErrorToUnavailable() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withServerError());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_PROVIDER_UNAVAILABLE, exception.getErrorCode());
        mockServer.verify();
    }

    /** 4xx 非限流错误也应映射为 provider 不可用。 */
    @Test
    void shouldMapBadRequestToUnavailable() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withBadRequest());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_PROVIDER_UNAVAILABLE, exception.getErrorCode());
        mockServer.verify();
    }

    /** 非法 JSON 响应按响应无效处理，不落库不透传。 */
    @Test
    void shouldRejectMalformedJsonResponse() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("not-json-at-all", MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    /** 内容缺失或不合法时按响应无效处理。 */
    @Test
    void shouldRejectResponseWithoutTextContent() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    /** 未知输出字段在严格解析下被拒绝，防止模型自由扩展字段绕过校验。 */
    @Test
    void shouldRejectResponseWithUnknownTopLevelFields() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(openAiSuccessBody(",\"unexpected_field\": true"), MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    /** 429 限流按有界重试后成功，重试发生在同一调用内。 */
    @Test
    void shouldRetryOnRateLimitThenSucceed() {
        appProperties.getAi().setRetryMaxAttempts(2);
        appProperties.getAi().setRetryBackoffBaseMs(1);
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(openAiSuccessBody(), MediaType.APPLICATION_JSON));

        AiDiagnosisVo result = provider.diagnose("why high?", context());

        assertTrue(result.isModelUsed());
        assertEquals(10, result.getUsage().getInputTokens());
        mockServer.verify();
    }

    /** 重试次数耗尽后仍映射为 42906，不吞掉限流语义。 */
    @Test
    void shouldExhaustRetriesOnRateLimit() {
        appProperties.getAi().setRetryMaxAttempts(1);
        appProperties.getAi().setRetryBackoffBaseMs(1);
        mockServer.expect(org.springframework.test.web.client.ExpectedCount.twice(),
                        requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_RATE_LIMIT_REACHED, exception.getErrorCode());
        mockServer.verify();
    }

    /** 5xx 不属于可重试错误：仅一次请求，立即映射为不可用。 */
    @Test
    void shouldNotRetryOnServerError() {
        appProperties.getAi().setRetryMaxAttempts(2);
        appProperties.getAi().setRetryBackoffBaseMs(1);
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withServerError());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_PROVIDER_UNAVAILABLE, exception.getErrorCode());
        mockServer.verify();
    }

    /** 显式开启 allow-insecure-http 后允许 HTTP endpoint（联调/内网用途）。 */
    @Test
    void shouldAllowInsecureHttpWhenExplicitlyEnabled() {
        appProperties.getAi().setAllowInsecureHttp(true);
        appProperties.getAi().setBaseUrl("http://model.example.test/v1");
        provider = new OpenAiCompatibleProvider(appProperties, restClientBuilder, objectMapper, true);
        mockServer.expect(requestTo("http://model.example.test/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andRespond(withSuccess(openAiSuccessBody(), MediaType.APPLICATION_JSON));

        AiDiagnosisVo result = provider.diagnose("why high?", context());

        assertTrue(result.isModelUsed());
        mockServer.verify();
    }

    /** 明文放宽只覆盖 http://，其他 scheme 即使开启开关也始终拒绝。 */
    @Test
    void shouldRejectOtherSchemesEvenWhenInsecureAllowed() {
        appProperties.getAi().setAllowInsecureHttp(true);
        appProperties.getAi().setBaseUrl("ftp://model.example.test");
        provider = new OpenAiCompatibleProvider(appProperties, restClientBuilder, objectMapper, true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED, exception.getErrorCode());
    }

    /** 模型输出被 markdown 围栏包裹时仍可解析（真实网关实测行为），且不放宽字段校验。 */
    @Test
    void shouldParseFencedJsonContent() {
        String content = "```json\n" + openAiContent() + "\n```";
        // JSON 字符串中换行必须转义，与真实网关的序列化行为一致。
        String escapedContent = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + escapedContent + "\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                        MediaType.APPLICATION_JSON));

        AiDiagnosisVo result = provider.diagnose("why high?", context());

        assertTrue(result.isModelUsed());
        assertEquals("warning", result.getSeverity());
        mockServer.verify();
    }

    /** severity 不在允许枚举内时按响应无效处理（实测模型曾返回 "high"）。 */
    @Test
    void shouldRejectSeverityOutsideAllowedEnum() {
        String content = openAiContent().replace("\"severity\":\"warning\"", "\"severity\":\"high\"");
        String escapedContent = content.replace("\"", "\\\"");
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + escapedContent + "\"}}]}",
                        MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    /** 反伪造：模型自造的 evidence（含不可解析时间）被整体替换为 Java 白名单上下文证据。 */
    @Test
    void shouldReplaceModelEvidenceWithContextEvidence() {
        String content = openAiContent().replace("\"evidence\":[]",
                "\"evidence\":[{\"metric\":\"fabricated\",\"value\":\"x\","
                        + "\"observed_at\":\"current\",\"source\":\"monitoring_summary\"}]");
        String escapedContent = content.replace("\"", "\\\"");
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"" + escapedContent + "\"}}]}",
                        MediaType.APPLICATION_JSON));

        AiDiagnosisVo result = provider.diagnose("why high?", context());

        assertEquals(1, result.getEvidence().size());
        assertEquals("cpu_percent", result.getEvidence().get(0).getMetric());
        assertEquals("2026-08-31T00:00:00Z", result.getEvidence().get(0).getObservedAt());
        mockServer.verify();
    }

    /** 告警解释：合法响应解析为结构化解释并提取 usage。 */
    @Test
    void shouldParseExplanationContent() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(explanationSuccessBody(""), MediaType.APPLICATION_JSON));

        AiAlertExplanationVo result = provider.explainAlert(facts(), context());

        assertEquals("CPU elevated due to sustained load.", result.getSummary());
        assertEquals(1, result.getPossibleCauses().size());
        assertEquals(15, result.getUsage().getTotalTokens());
        mockServer.verify();
    }

    /** 告警解释：缺失 possible_causes 列表按响应无效处理，不放宽 schema 校验。 */
    @Test
    void shouldRejectExplanationWithMissingList() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(explanationBody(
                        "{\"summary\":\"CPU elevated due to sustained load.\","
                        + "\"impact\":[\"Risk of resource exhaustion.\"],"
                        + "\"suggestions\":[\"Review CPU trend and recent deployments.\"],"
                        + "\"limitations\":[\"Advisory only.\"]}"),
                        MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.explainAlert(facts(), context()));

        assertEquals(ErrorCode.AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    /** 告警解释：未知输出字段在严格解析下被拒绝（与诊断同 fail-closed 姿态）。 */
    @Test
    void shouldRejectExplanationWithUnknownField() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(explanationSuccessBody(",\"unexpected\":true"),
                        MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.explainAlert(facts(), context()));

        assertEquals(ErrorCode.AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    private AiDiagnosisContext context() {
        AiEvidenceVo evidence = new AiEvidenceVo();
        evidence.setMetric("cpu_percent");
        evidence.setValue(88.5);
        evidence.setObservedAt("2026-08-31T00:00:00Z");
        evidence.setSource("monitoring_summary");
        return new AiDiagnosisContext(7L, "online", "online", 30, List.of(evidence), List.of());
    }

    private String openAiSuccessBody() {
        return openAiSuccessBody("");
    }

    /** 组装 OpenAI-compatible 200 响应，content 为固定结构化 JSON；extraField 以逗号开头插入收尾前。 */
    private String openAiSuccessBody(String extraField) {
        String content = openAiContent();
        if (!extraField.isEmpty()) {
            content = content.substring(0, content.length() - 1) + extraField + "}";
        }
        String escapedContent = content.replace("\"", "\\\"");
        return "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + escapedContent + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }

    /** 合法结构化诊断内容 JSON（围栏用例会额外包裹此内容）。 */
    private String openAiContent() {
        return "{\"summary\":\"CPU elevated\",\"severity\":\"warning\","
                + "\"findings\":[{\"title\":\"High CPU\",\"description\":\"CPU above threshold\","
                + "\"confidence\":\"high\"}],"
                + "\"evidence\":[],\"recommendations\":[\"inspect metrics\"],"
                + "\"limitations\":[\"advisory only\"]}";
    }

    /** 组装告警解释的 OpenAI-compatible 200 响应；extraField 以逗号开头插入 content 收尾前。 */
    private String explanationSuccessBody(String extraField) {
        String content = "{\"summary\":\"CPU elevated due to sustained load.\","
                + "\"possible_causes\":[\"Application workload increase.\"],"
                + "\"impact\":[\"Risk of resource exhaustion.\"],"
                + "\"suggestions\":[\"Review CPU trend and recent deployments.\"],"
                + "\"limitations\":[\"Advisory only.\"]";
        if (!extraField.isEmpty()) {
            content = content + extraField;
        }
        return explanationBody(content + "}");
    }

    /** 将解释 content 转义后包进 OpenAI-compatible 200 响应，并附带 usage。 */
    private String explanationBody(String content) {
        String escapedContent = content.replace("\"", "\\\"");
        return "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + escapedContent + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }

    private AiAlertFacts facts() {
        return new AiAlertFacts(789L, 456L, 7L, "cpu", new java.math.BigDecimal("92.5"),
                new java.math.BigDecimal("80.0"), "warning", "2026-09-04T11:55:00Z");
    }
}