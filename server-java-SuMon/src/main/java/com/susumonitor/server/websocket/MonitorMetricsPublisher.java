package com.susumonitor.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.metrics.service.MetricsService.MetricsReportedEvent;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.TextMessage;

/** 在 Metrics 事务提交后向有权限的 Monitor 订阅者广播指标更新。 */
@Component
public class MonitorMetricsPublisher {

    private final ObjectMapper objectMapper;
    private final MonitorSubscriptionRegistry registry;
    private final Clock clock;
    private final MonitorSessionTerminationService terminationService;

    /** 注入 JSON 序列化器和订阅注册表。 */
    public MonitorMetricsPublisher(ObjectMapper objectMapper, MonitorSubscriptionRegistry registry, Clock clock,
            MonitorSessionTerminationService terminationService) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.clock = clock;
        this.terminationService = terminationService;
    }

    /** 仅在数据库事务成功提交后发送指标更新。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(MetricsReportedEvent event) {
        MetricsLatestVo metrics = event.metrics();
        for (MonitorWebSocketSession subscriber : registry.subscribers(metrics.getServerId())) {
            try {
                if (!subscriber.send(new TextMessage(message(metrics)))) {
                    terminationService.terminateNormally(subscriber);
                }
            } catch (MonitorBackpressureException exception) {
                terminationService.terminateForBackpressure(subscriber);
            } catch (IOException exception) {
                terminationService.terminateNormally(subscriber);
            }
        }
    }

    /** 构建 metrics.update WebSocket 消息 JSON 字符串。 */
    private String message(MetricsLatestVo metrics) throws IOException {
        var payload = objectMapper.createObjectNode()
                .put("server_id", metrics.getServerId())
                .set("metrics", objectMapper.valueToTree(metrics));
        return objectMapper.createObjectNode()
                .put("type", "metrics.update")
                .put("message_id", java.util.UUID.randomUUID().toString())
                .put("timestamp", OffsetDateTime.now(clock).toString())
                .set("payload", payload)
                .toString();
    }
}
