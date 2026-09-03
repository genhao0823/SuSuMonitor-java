package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.command.CommandRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 命令域状态扫描调度：把过期 pending 置 expired、超时 executing 置 timeout。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandRunSweepScheduler {

    private final CommandRunService commandRunService;

    /** 构造扫描调度器。 */
    public CommandRunSweepScheduler(CommandRunService commandRunService) {
        this.commandRunService = commandRunService;
    }

    /** 按配置间隔执行一次双扫描；异常只影响当前轮次。 */
    // SpEL 引用独立参数 bean，避免调度器自引用造成的创建中循环。
    @Scheduled(fixedDelayString = "#{@commandSchedulingProps.sweepIntervalMillis}")
    public void sweepCommandRuns() {
        try {
            commandRunService.sweep();
        } catch (RuntimeException exception) {
            log.error("command run sweep failed", exception);
        }
    }
}
