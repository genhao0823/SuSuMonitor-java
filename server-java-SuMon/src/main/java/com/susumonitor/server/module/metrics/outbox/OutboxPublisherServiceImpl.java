package com.susumonitor.server.module.metrics.outbox;

import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox 发布实现（MVP-10）：短事务选取 + 逐行 Confirm 同步等待 + 状态回写。
 *
 * <p>投递语义为"至少一次"：Confirm 超时但消息实际到达 Broker 时，重试会重复
 * 发布同一 event_id——消费侧（MVP-11）以 event_id 幂等，符合冻结契约。</p>
 *
 * <p>发布失败不丢弃：attempts+1 并按指数退避（封顶 max-backoff-seconds）安排
 * 下次轮询；Broker 恢复后自动补发。单行异常不中断本轮其余行。</p>
 */
@Slf4j
@Service
public class OutboxPublisherServiceImpl implements OutboxPublisherService {

    private final OutboxMapper outboxMapper;

    private final RabbitTemplate rabbitTemplate;

    private final TransactionTemplate transactionTemplate;

    private final AppProperties.Rabbitmq properties;

    private final Clock clock;

    /** 注入 Outbox 数据访问、发布模板、事务模板与配置。 */
    public OutboxPublisherServiceImpl(OutboxMapper outboxMapper, RabbitTemplate rabbitTemplate,
            TransactionTemplate transactionTemplate, AppProperties properties, Clock clock) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties.getRabbitmq();
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public int publishOnce() {
        List<OutboxEntity> rows = transactionTemplate.execute(
                status -> outboxMapper.selectPendingForPublish(properties.getBatchSize()));
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int published = 0;
        for (OutboxEntity row : rows) {
            if (publishRow(row)) {
                published++;
            }
        }
        return published;
    }

    /**
     * 投递单行并回写状态。
     *
     * @param row 待发布行
     * @return 是否成功发布
     */
    private boolean publishRow(OutboxEntity row) {
        try {
            CorrelationData correlationData = new CorrelationData(row.getId().toString());
            rabbitTemplate.convertAndSend(properties.getExchange(), properties.getRoutingKey(),
                    row.getPayload(), correlationData);
            boolean ack = correlationData.getFuture()
                    .get(properties.getPublishTimeoutMs(), TimeUnit.MILLISECONDS).isAck();
            if (ack) {
                outboxMapper.markPublished(row.getId(), LocalDateTime.now(clock));
                return true;
            }
            markRetry(row, "broker nack");
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markRetry(row, truncateError(exception));
            return false;
        } catch (AmqpException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            markRetry(row, truncateError(exception));
            return false;
        } catch (RuntimeException exception) {
            markRetry(row, truncateError(exception));
            return false;
        }
    }

    /**
     * 回写退避重试：attempts+1，next_attempt_at = now + min(2^attempts, 封顶)。
     */
    private void markRetry(OutboxEntity row, String error) {
        int nextAttempts = row.getAttempts() + 1;
        long delaySeconds = Math.min(1L << Math.min(nextAttempts, 31), properties.getMaxBackoffSeconds());
        int updated = outboxMapper.markRetry(row.getId(), nextAttempts,
                LocalDateTime.now(clock).plusSeconds(delaySeconds), error);
        if (updated == 0) {
            log.warn("outbox row already processed or missing, id={}", row.getId());
        }
        log.warn("outbox publish failed, id={}, attempts={}, nextDelaySeconds={}, error={}",
                row.getId(), nextAttempts, delaySeconds, error);
    }

    /**
     * 截断异常消息至 500 字符，避免数据库 error 字段溢出。
     *
     * @param exception 异常对象
     * @return 截断后的错误描述
     */
    private String truncateError(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName()
                : exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
