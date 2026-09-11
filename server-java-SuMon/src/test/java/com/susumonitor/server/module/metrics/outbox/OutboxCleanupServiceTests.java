package com.susumonitor.server.module.metrics.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 验证已发布 Outbox 清理的分批、上限和重叠执行规则。
 */
@ExtendWith(MockitoExtension.class)
class OutboxCleanupServiceTests {

    @Mock
    private OutboxCleanupMapper outboxCleanupMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getRabbitmq().setOutboxRetentionDays(30);
        appProperties.getRabbitmq().setOutboxCleanupBatchSize(2);
        appProperties.getRabbitmq().setOutboxCleanupMaxBatchesPerRun(3);
    }

    @Test
    void noExpiredPublishedOutboxShouldFinishWithoutDeletingRows() {
        when(outboxCleanupMapper.deletePublishedBeforeBatch(any(), any(Integer.class))).thenReturn(0);

        Optional<CleanupResult> result = newService().cleanupExpiredPublishedOutbox();

        assertTrue(result.isPresent());
        assertEquals(0, result.get().batchCount());
        assertEquals(0, result.get().deletedRows());
    }

    @Test
    void cleanupShouldStopAtMaximumBatchCount() {
        when(outboxCleanupMapper.deletePublishedBeforeBatch(any(), any(Integer.class))).thenReturn(2, 2, 2, 2);

        CleanupResult result = newService().cleanupExpiredPublishedOutbox().orElseThrow();

        assertEquals(3, result.batchCount());
        assertEquals(6, result.deletedRows());
    }

    @Test
    void cleanupShouldPassExplicitUtcCutoffToEveryBatch() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 3, 30);
        when(outboxCleanupMapper.deletePublishedBeforeBatch(cutoff, 2)).thenReturn(1, 0);

        CleanupResult result = newService().cleanupExpiredPublishedOutbox(cutoff).orElseThrow();

        assertEquals(cutoff, result.cutoffTime());
        assertEquals(1, result.batchCount());
        assertEquals(1, result.deletedRows());
    }

    @Test
    void overlappingCleanupShouldBeSkipped() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return 0;
        }).when(outboxCleanupMapper).deletePublishedBeforeBatch(any(), any(Integer.class));
        OutboxCleanupService service = newService();

        Thread first = new Thread(service::cleanupExpiredPublishedOutbox);
        first.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(service.cleanupExpiredPublishedOutbox().isEmpty());
        release.countDown();
        first.join(2_000);
    }

    private OutboxCleanupService newService() {
        doAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        return new OutboxCleanupServiceImpl(outboxCleanupMapper, appProperties, new BatchCleanupExecutor(transactionTemplate));
    }
}
