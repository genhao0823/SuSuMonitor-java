package com.susumonitor.server.module.alert.consume;

import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 消费失败率窗口聚合（MVP-14 监控收尾）。
 *
 * <p>以 message_consume_records（V15 幂等/失败留痕表）为数据源，按消费者聚合
 * 统计窗口内的 failed 与 consumed 行数，供失败率 = failed/(consumed+failed)
 * 计算。数据所有权：本服务是 consume 记录的模块内契约入口，system 等其它模块
 * 不得直接访问 ConsumeRecordMapper。</p>
 */
@Service
@ConditionalOnProperty(name = "susumonitor.rabbitmq.enabled", havingValue = "true")
public class ConsumeStatsService {

    /** 单消费者窗口计数（不可变）。 */
    public record WindowCounts(long failed, long consumed) {
    }

    private final ConsumeRecordMapper consumeRecordMapper;

    private final AppProperties appProperties;

    private final Clock clock;

    /** 注入消费幂等记录 Mapper、配置与应用时钟。 */
    public ConsumeStatsService(ConsumeRecordMapper consumeRecordMapper, AppProperties appProperties, Clock clock) {
        this.consumeRecordMapper = consumeRecordMapper;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    /**
     * 返回窗口内各消费者的 failed/consumed 计数；窗口外记录不计入。
     *
     * @return consumer 名 → 窗口计数
     */
    public Map<String, WindowCounts> windowCounts() {
        LocalDateTime since = LocalDateTime.now(clock)
                .minusMinutes(appProperties.getRabbitmq().getConsumeStatsWindowMinutes());
        Map<String, Long> failed = toCountMap(consumeRecordMapper.countByConsumerAndStatus("failed", since));
        Map<String, Long> consumed = toCountMap(consumeRecordMapper.countByConsumerAndStatus("consumed", since));

        Set<String> consumers = new HashSet<>();
        consumers.addAll(failed.keySet());
        consumers.addAll(consumed.keySet());

        Map<String, WindowCounts> result = new HashMap<>();
        for (String consumer : consumers) {
            result.put(consumer, new WindowCounts(
                    failed.getOrDefault(consumer, 0L), consumed.getOrDefault(consumer, 0L)));
        }
        return Map.copyOf(result);
    }

    /** H2 与 MySQL 对无引号列标签的大小写折叠不同，聚合结果键统一归一化。 */
    private Map<String, Long> toCountMap(List<Map<String, Object>> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String consumer = String.valueOf(row.getOrDefault("consumer", row.getOrDefault("CONSUMER", "")));
            Object count = row.getOrDefault("count", row.getOrDefault("COUNT", 0L));
            counts.put(consumer, ((Number) count).longValue());
        }
        return counts;
    }
}
