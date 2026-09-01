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
import com.susumonitor.server.module.ai.model.AiDiagnosisContext;
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
                .andRespond(withSuccess(openAiSuccessBody("\"unexpected_field\": true"), MediaType.APPLICATION_JSON));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.diagnose("why high?", context()));

        assertEquals(ErrorCode.AI_RESPONSE_INVALID, exception.getErrorCode());
    }

    private AiDiagnosisContext context() {
        AiEvidenceVo evidence = new AiEvidenceVo();
        evidence.setMetric("cpu_percent");
        evidence.setValue(88.5);
        evidence.setObservedAt(OffsetDateTime.of(2026, 8, 31, 0, 0, 0, 0, ZoneOffset.UTC));
        evidence.setSource("monitoring_summary");
        return new AiDiagnosisContext(7L, "online", "online", 30, List.of(evidence), List.of());
    }

    private String openAiSuccessBody() {
        return openAiSuccessBody("");
    }

    /** 组装 OpenAI-compatible 200 响应，content 为固定结构化 JSON。 */
    private String openAiSuccessBody(String extraField) {
        String content = "{\"summary\":\"CPU elevated\",\"severity\":\"warning\","
                + "\"findings\":[{\"title\":\"High CPU\",\"description\":\"CPU above threshold\","
                + "\"confidence\":\"high\"}],"
                + "\"evidence\":[],\"recommendations\":[\"inspect metrics\"],"
                + "\"limitations\":[\"advisory only\"]"
                + extraField + "}";
        String escapedContent = content.replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"content\":\"" + escapedContent + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }
}