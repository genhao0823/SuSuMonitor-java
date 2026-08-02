package com.susumonitor.server.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.module.metrics.outbox.OutboxCleanupService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 验证 Outbox 清理调度器委托服务且隔离单轮异常。
 */
class OutboxCleanupSchedulerTests {

    @Test
    void schedulerShouldDelegateCleanup() {
        OutboxCleanupService service = Mockito.mock(OutboxCleanupService.class);
        when(service.cleanupExpiredPublishedOutbox()).thenReturn(Optional.empty());

        new OutboxCleanupScheduler(service).cleanupExpiredPublishedOutbox();

        verify(service).cleanupExpiredPublishedOutbox();
    }

    @Test
    void schedulerShouldIsolateCleanupFailure() {
        OutboxCleanupService service = Mockito.mock(OutboxCleanupService.class);
        doThrow(new IllegalStateException("database unavailable")).when(service).cleanupExpiredPublishedOutbox();

        new OutboxCleanupScheduler(service).cleanupExpiredPublishedOutbox();

        verify(service).cleanupExpiredPublishedOutbox();
    }
}
