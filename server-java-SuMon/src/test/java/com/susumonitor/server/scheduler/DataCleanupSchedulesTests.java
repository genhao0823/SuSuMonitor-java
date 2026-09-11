package com.susumonitor.server.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.susumonitor.server.module.alert.service.AlertNotificationCleanupService;
import com.susumonitor.server.module.metrics.outbox.OutboxCleanupService;
import com.susumonitor.server.module.metrics.service.MetricsCleanupService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 验证统一清理调度入口只负责触发清理服务、隔离单轮异常，
 * 并在服务 Bean 未装配（模块/开关关闭）时跳过。
 */
class DataCleanupSchedulesTests {

    /** 调度方法委托对应清理服务并记录空结果。 */
    @Test
    void schedulesShouldDelegateToCleanupServices() {
        MetricsCleanupService metrics = Mockito.mock(MetricsCleanupService.class);
        when(metrics.cleanupExpiredMetrics()).thenReturn(Optional.empty());
        OutboxCleanupService outbox = Mockito.mock(OutboxCleanupService.class);
        when(outbox.cleanupExpiredPublishedOutbox()).thenReturn(Optional.empty());

        DataCleanupSchedules schedules = new DataCleanupSchedules(
                provider(metrics), provider(null), provider(null), provider(null), provider(null),
                provider(outbox), provider(null), provider(null), provider(null), provider(null),
                provider(null), provider(null));

        schedules.cleanupExpiredMetrics();
        schedules.cleanupExpiredPublishedOutbox();

        verify(metrics).cleanupExpiredMetrics();
        verify(outbox).cleanupExpiredPublishedOutbox();
    }

    /** 服务抛出的异常被调度入口吞掉，只影响当前轮次。 */
    @Test
    void schedulesShouldIsolateCleanupFailure() {
        AlertNotificationCleanupService service = Mockito.mock(AlertNotificationCleanupService.class);
        doThrow(new IllegalStateException("database unavailable")).when(service).cleanupExpiredNotifications();

        DataCleanupSchedules schedules = new DataCleanupSchedules(
                provider(null), provider(null), provider(null), provider(service), provider(null),
                provider(null), provider(null), provider(null), provider(null), provider(null),
                provider(null), provider(null));

        schedules.cleanupExpiredNotifications();

        verify(service).cleanupExpiredNotifications();
    }

    /** 服务 Bean 未装配（模块关闭或清理开关关闭）时本轮跳过且不触发任何服务。 */
    @Test
    void schedulesShouldSkipWhenServiceBeanAbsent() {
        MetricsCleanupService service = Mockito.mock(MetricsCleanupService.class);

        DataCleanupSchedules schedules = new DataCleanupSchedules(
                provider(null), provider(null), provider(null), provider(null), provider(null),
                provider(null), provider(null), provider(null), provider(null), provider(null),
                provider(null), provider(null));

        schedules.cleanupExpiredMetrics();

        verifyNoInteractions(service);
    }

    /** 构造返回固定 Bean 的 ObjectProvider 桩；null 表示 Bean 未装配。 */
    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T service) {
        ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }
}
