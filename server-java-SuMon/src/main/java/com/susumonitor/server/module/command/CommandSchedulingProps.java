package com.susumonitor.server.module.command;

import com.susumonitor.server.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 命令域调度参数载体：为 @Scheduled SpEL 提供稳定的 bean 引用，
 * 避免调度器自引用或依赖 @ConfigurationProperties 生成 bean 名。
 */
@Component("commandSchedulingProps")
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandSchedulingProps {

    private final AppProperties appProperties;

    /** 注入应用配置。 */
    public CommandSchedulingProps(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /** 扫描间隔毫秒值（供 @Scheduled fixedDelayString 引用）。 */
    public long getSweepIntervalMillis() {
        return appProperties.getAi().getCommand().getSweepIntervalSeconds() * 1000L;
    }
}
