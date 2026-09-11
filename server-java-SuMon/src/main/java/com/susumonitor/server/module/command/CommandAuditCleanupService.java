package com.susumonitor.server.module.command;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.command.mapper.CommandRunMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 命令域审计保留期分批清理：删除超过保留期的 ai_command_runs 行。
 *
 * <p>防重入与每批独立事务由共享执行器承担，与既有清理服务同模式。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandAuditCleanupService {

    private final CommandRunMapper mapper;
    private final AppProperties appProperties;
    private final BatchCleanupExecutor batchCleanupExecutor;

    /** 注入审计 Mapper、配置与共享批量清理执行器。 */
    public CommandAuditCleanupService(CommandRunMapper mapper, AppProperties appProperties,
            BatchCleanupExecutor batchCleanupExecutor) {
        this.mapper = mapper;
        this.appProperties = appProperties;
        this.batchCleanupExecutor = batchCleanupExecutor;
    }

    /** 按当前保留期执行一轮清理；重叠触发返回空。 */
    public Optional<CleanupResult> cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getAi().getCommand().getAuditRetentionDays());
        return cleanupExpired(cutoff);
    }

    /** 按固定时间边界清理（验收与测试用）。 */
    public Optional<CleanupResult> cleanupExpired(LocalDateTime cutoff) {
        AppProperties.Ai.Command commandProps = appProperties.getAi().getCommand();
        return batchCleanupExecutor.run("command-audit", cutoff, mapper::deleteExpiredBatch,
                commandProps.getAuditCleanupBatchSize(), commandProps.getAuditCleanupMaxBatchesPerRun());
    }
}
