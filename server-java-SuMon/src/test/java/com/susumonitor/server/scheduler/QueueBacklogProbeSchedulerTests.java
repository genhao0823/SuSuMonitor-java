package com.susumonitor.server.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.susumonitor.server.module.system.QueueBacklogProbeService;
import org.junit.jupiter.api.Test;

/**
 * 验证队列积压探测调度器委托服务且隔离单轮异常。
 */
class QueueBacklogProbeSchedulerTests {

    @Test
    void schedulerShouldDelegateProbe() {
        QueueBacklogProbeService service = mock(QueueBacklogProbeService.class);

        new QueueBacklogProbeScheduler(service).probeQueueBacklog();

        verify(service).probe();
    }

    @Test
    void schedulerShouldIsolateProbeFailure() {
        QueueBacklogProbeService service = mock(QueueBacklogProbeService.class);
        doThrow(new IllegalStateException("broker unavailable")).when(service).probe();

        new QueueBacklogProbeScheduler(service).probeQueueBacklog();

        verify(service).probe();
    }
}
