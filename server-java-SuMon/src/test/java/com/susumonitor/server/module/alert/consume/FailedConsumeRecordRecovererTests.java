package com.susumonitor.server.module.alert.consume;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

/**
 * FailedConsumeRecordRecoverer 单元测试：可解析 event_id 才留痕、不可重试 attempts=1、
 * 重试耗尽 attempts=maxAttempts、DB 异常不阻断 reject、非法 JSON 仅告警。
 */
class FailedConsumeRecordRecovererTests {

    private static final String EVENT_ID = "9f4c2d10-8b7f-4c3d-a5e0-1ef5b67f2f1a";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ConsumeRecordMapper consumeRecordMapper;

    private MessageRecoverer delegate;

    private FailedConsumeRecordRecoverer recoverer;

    @BeforeEach
    void setUp() {
        consumeRecordMapper = mock(ConsumeRecordMapper.class);
        delegate = mock(MessageRecoverer.class);
        recoverer = new FailedConsumeRecordRecoverer(consumeRecordMapper, objectMapper, 3, delegate);
    }

    /** 重试耗尽（非不可重试异常）：留痕 attempts=最大尝试次数，且照常委托 reject。 */
    @Test
    void retryExhaustedWritesFailedRecordWithMaxAttempts() {
        Message message = envelopeMessage(EVENT_ID);

        recoverer.recover(message, new RuntimeException("db unavailable"));

        verify(consumeRecordMapper).upsertFailed(eq(AlertMessageConsumer.CONSUMER_NAME), eq(EVENT_ID),
                eq(3), argThat(error -> error != null && error.contains("RuntimeException")));
        verify(delegate).recover(eq(message), any());
    }

    /** 不可重试数据错误（AmqpRejectAndDontRequeueException 链）：attempts=1（零重试即拒）。 */
    @Test
    void nonRetryableDataErrorWritesFailedRecordWithSingleAttempt() {
        Message message = envelopeMessage(EVENT_ID);

        recoverer.recover(message, new AmqpRejectAndDontRequeueException("invalid metrics event contract"));

        verify(consumeRecordMapper).upsertFailed(eq(AlertMessageConsumer.CONSUMER_NAME), eq(EVENT_ID),
                eq(1), argThat(error -> error != null && error.contains("invalid metrics event contract")));
        verify(delegate).recover(eq(message), any());
    }

    /** 非法 JSON 无法定位事件：不留痕仅跳过，仍委托 reject（消息照常进 DLQ）。 */
    @Test
    void unparseableBodySkipsRecordAndStillDelegates() {
        Message message = new Message("not-a-json{{".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        recoverer.recover(message, new RuntimeException("boom"));

        verify(consumeRecordMapper, never()).upsertFailed(any(), any(), anyInt(), any());
        verify(delegate).recover(eq(message), any());
    }

    /** 留痕 DB 写入失败是 best-effort：仅记日志，不阻断 reject。 */
    @Test
    void recordWriteFailureDoesNotBlockReject() {
        Message message = envelopeMessage(EVENT_ID);
        doThrow(new RuntimeException("db down")).when(consumeRecordMapper)
                .upsertFailed(any(), any(), anyInt(), any());

        recoverer.recover(message, new RuntimeException("boom"));

        verify(delegate).recover(eq(message), any());
    }

    /** last_error 超长时截断到 500 字符（与 V15 列定义一致）。 */
    @Test
    void longErrorIsTruncatedToColumnLimit() {
        Message message = envelopeMessage(EVENT_ID);
        String longMessage = "x".repeat(1000);

        recoverer.recover(message, new RuntimeException(longMessage));

        verify(consumeRecordMapper).upsertFailed(eq(AlertMessageConsumer.CONSUMER_NAME), eq(EVENT_ID),
                eq(3), argThat(error -> error != null && error.length() == FailedConsumeRecordRecoverer.MAX_ERROR_LENGTH));
    }

    private Message envelopeMessage(String eventId) {
        return new Message(envelopeJson(eventId).getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private String envelopeJson(String eventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("server_id", 1L);
        payload.put("message_id", "1a08f7b1-51c8-4b46-929a-8879f349a3a2");
        payload.put("collected_at", "2026-07-31T07:59:59Z");
        payload.put("cpu_percent", 80.5);
        payload.put("memory_percent", 70.1);
        payload.put("memory_used", 8192L);
        payload.put("memory_total", 16384L);
        payload.put("disk_percent", 55.2);
        payload.put("disk_used", 1024L);
        payload.put("disk_total", 2048L);
        payload.put("net_rx", 1000L);
        payload.put("net_tx", 2000L);
        payload.put("temperature", 65.3);
        payload.put("load_avg", 2.5);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId);
        envelope.put("event_type", "metrics.reported");
        envelope.put("schema_version", 1);
        envelope.put("occurred_at", "2026-07-31T08:00:00Z");
        envelope.put("producer", "metrics-service");
        envelope.put("payload", payload);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
