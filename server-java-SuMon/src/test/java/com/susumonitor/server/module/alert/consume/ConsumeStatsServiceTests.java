package com.susumonitor.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证消费失败率窗口聚合的合并与归一化语义。
 */
@ExtendWith(MockitoExtension.class)
class ConsumeStatsServiceTests {

    @Mock
    private ConsumeRecordMapper consumeRecordMapper;

    private final AppProperties appProperties = new AppProperties();

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC);

    private ConsumeStatsService newService() {
        return new ConsumeStatsService(consumeRecordMapper, appProperties, clock);
    }

    /** failed 与 consumed 两路聚合应按消费者合并为窗口计数。 */
    @Test
    void windowCountsShouldMergeFailedAndConsumed() {
        when(consumeRecordMapper.countByConsumerAndStatus(eq("failed"), any(LocalDateTime.class)))
                .thenReturn(List.of(row("alert-notifier", 3L), row("alert-evaluator", 1L)));
        when(consumeRecordMapper.countByConsumerAndStatus(eq("consumed"), any(LocalDateTime.class)))
                .thenReturn(List.of(row("alert-notifier", 7L)));

        Map<String, ConsumeStatsService.WindowCounts> counts = newService().windowCounts();

        assertEquals(2, counts.size());
        assertEquals(3L, counts.get("alert-notifier").failed());
        assertEquals(7L, counts.get("alert-notifier").consumed());
        assertEquals(1L, counts.get("alert-evaluator").failed());
        assertEquals(0L, counts.get("alert-evaluator").consumed());
    }

    /** 无任何记录时返回空窗口。 */
    @Test
    void windowCountsShouldBeEmptyWithoutRecords() {
        when(consumeRecordMapper.countByConsumerAndStatus(eq("failed"), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(consumeRecordMapper.countByConsumerAndStatus(eq("consumed"), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertTrue(newService().windowCounts().isEmpty());
    }

    /** 聚合行键大小写（H2 大写折叠）应归一化为原始 consumer 名。 */
    @Test
    void windowCountsShouldNormalizeCaseFoldedKeys() {
        when(consumeRecordMapper.countByConsumerAndStatus(eq("failed"), any(LocalDateTime.class)))
                .thenReturn(List.of(java.util.Map.of("CONSUMER", "alert-notifier", "COUNT", 2L)));
        when(consumeRecordMapper.countByConsumerAndStatus(eq("consumed"), any(LocalDateTime.class)))
                .thenReturn(List.of());

        Map<String, ConsumeStatsService.WindowCounts> counts = newService().windowCounts();

        assertEquals(1, counts.size());
        assertEquals(2L, counts.get("alert-notifier").failed());
    }

    private Map<String, Object> row(String consumer, long count) {
        return Map.of("consumer", consumer, "count", count);
    }
}
