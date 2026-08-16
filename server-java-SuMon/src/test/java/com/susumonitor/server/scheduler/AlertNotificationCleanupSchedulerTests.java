package com.susumonitor.server.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.module.alert.service.AlertNotificationCleanupService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 验证通知投递清理调度器委托服务且隔离单轮异常。
 */
class AlertNotificationCleanupSchedulerTests {

    @Test
    void schedulerShouldDelegateCleanup() {
        AlertNotificationCleanupService service = Mockito.mock(AlertNotificationCleanupService.class);
        when(service.cleanupExpiredNotifications()).thenReturn(Optional.empty());

        new AlertNotificationCleanupScheduler(service).cleanupExpiredNotifications();

        verify(service).cleanupExpiredNotifications();
    }

    @Test
    void schedulerShouldIsolateCleanupFailure() {
        AlertNotificationCleanupService service = Mockito.mock(AlertNotificationCleanupService.class);
        doThrow(new IllegalStateException("database unavailable")).when(service).cleanupExpiredNotifications();

        new AlertNotificationCleanupScheduler(service).cleanupExpiredNotifications();

        verify(service).cleanupExpiredNotifications();
    }
}
