package com.susumonitor.server.module.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.alert.mapper.AlertNotificationCleanupMapper;
import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
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
 * 验证通知投递清理服务的分批、上限和重叠执行规则。
 */
@ExtendWith(MockitoExtension.class)
class AlertNotificationCleanupServiceTests {

    @Mock
    private AlertNotificationCleanupMapper notificationCleanupMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AppProperties appProperties;

    /** 初始化合法的通知投递清理配置。 */
    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getAlert().setNotificationRetentionDays(90);
        appProperties.getAlert().setNotificationCleanupBatchSize(2);
        appProperties.getAlert().setNotificationCleanupMaxBatchesPerRun(3);
    }

    /** 验证没有过期数据时不产生删除结果。 */
    @Test
    void noExpiredNotificationsShouldFinishWithoutDeletingRows() {
        when(notificationCleanupMapper.deleteExpiredBatch(any(), any(Integer.class))).thenReturn(0);
        AlertNotificationCleanupService service = newService();

        Optional<CleanupResult> result = service.cleanupExpiredNotifications();

        assertTrue(result.isPresent());
        assertEquals(0, result.get().batchCount());
        assertEquals(0, result.get().deletedRows());
    }

    /** 验证删除数量达到批次上限时停止继续清理。 */
    @Test
    void cleanupShouldStopAtMaximumBatchCount() {
        when(notificationCleanupMapper.deleteExpiredBatch(any(), any(Integer.class))).thenReturn(2, 2, 2, 2);
        AlertNotificationCleanupService service = newService();

        CleanupResult result = service.cleanupExpiredNotifications().orElseThrow();

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
        }).when(notificationCleanupMapper).deleteExpiredBatch(any(), any(Integer.class));
        AlertNotificationCleanupService service = newService();

        Thread first = new Thread(service::cleanupExpiredNotifications);
        first.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(service.cleanupExpiredNotifications().isEmpty());
        release.countDown();
        first.join(2_000);
    }

    /** 创建待测试的清理服务，并让事务模板执行实际回调。 */
    private AlertNotificationCleanupService newService() {
        doAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        return new AlertNotificationCleanupServiceImpl(notificationCleanupMapper, appProperties, new BatchCleanupExecutor(transactionTemplate));
    }
}
