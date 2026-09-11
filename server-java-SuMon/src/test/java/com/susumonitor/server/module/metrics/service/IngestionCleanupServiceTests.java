package com.susumonitor.server.module.metrics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.metrics.mapper.IngestionCleanupMapper;
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
 * 验证指标幂等接收记录清理服务的分批、上限和重叠执行规则。
 */
@ExtendWith(MockitoExtension.class)
class IngestionCleanupServiceTests {

    @Mock
    private IngestionCleanupMapper ingestionCleanupMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AppProperties appProperties;

    /** 初始化合法的接收记录清理配置。 */
    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getMetrics().setIngestionRetentionDays(7);
        appProperties.getMetrics().setIngestionCleanupBatchSize(2);
        appProperties.getMetrics().setIngestionCleanupMaxBatchesPerRun(3);
    }

    /** 验证没有过期数据时不产生删除结果。 */
    @Test
    void noExpiredIngestionsShouldFinishWithoutDeletingRows() {
        when(ingestionCleanupMapper.deleteExpiredBatch(any(), any(Integer.class))).thenReturn(0);
        IngestionCleanupService service = newService();

        Optional<CleanupResult> result = service.cleanupExpiredIngestions();

        assertTrue(result.isPresent());
        assertEquals(0, result.get().batchCount());
        assertEquals(0, result.get().deletedRows());
    }

    /** 验证删除数量达到批次上限时停止继续清理。 */
    @Test
    void cleanupShouldStopAtMaximumBatchCount() {
        when(ingestionCleanupMapper.deleteExpiredBatch(any(), any(Integer.class))).thenReturn(2, 2, 2, 2);
        IngestionCleanupService service = newService();

        CleanupResult result = service.cleanupExpiredIngestions().orElseThrow();

        assertEquals(3, result.batchCount());
        assertEquals(6, result.deletedRows());
    }

    /** 验证已有任务运行时第二次触发立即跳过。 */
    @Test
    void overlappingCleanupShouldBeSkipped() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return 0;
        }).when(ingestionCleanupMapper).deleteExpiredBatch(any(), any(Integer.class));
        IngestionCleanupService service = newService();

        Thread first = new Thread(service::cleanupExpiredIngestions);
        first.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(service.cleanupExpiredIngestions().isEmpty());
        release.countDown();
        first.join(2_000);
    }

    /** 创建待测试的清理服务，并让事务模板执行实际回调。 */
    private IngestionCleanupService newService() {
        doAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        return new IngestionCleanupServiceImpl(ingestionCleanupMapper, appProperties, new BatchCleanupExecutor(transactionTemplate));
    }
}
