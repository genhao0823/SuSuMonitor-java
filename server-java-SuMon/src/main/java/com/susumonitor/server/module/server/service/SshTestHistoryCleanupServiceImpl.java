package com.susumonitor.server.module.server.service;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.server.mapper.SshTestHistoryMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 分批清理超过保留期的 SSH 测试历史（ssh_test_history）；防重入与批量循环由共享执行器承担。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ssh-test-history.cleanup-enabled", havingValue = "true")
public class SshTestHistoryCleanupServiceImpl implements SshTestHistoryCleanupService {

    private final SshTestHistoryMapper sshTestHistoryMapper;
    private final AppProperties appProperties;
    private final BatchCleanupExecutor batchCleanupExecutor;

    /**
     * 构造 SSH 测试历史清理服务。
     *
     * @param sshTestHistoryMapper SSH 测试历史 Mapper
     * @param appProperties 应用配置
     * @param batchCleanupExecutor 共享批量清理执行器
     */
    public SshTestHistoryCleanupServiceImpl(SshTestHistoryMapper sshTestHistoryMapper,
            AppProperties appProperties, BatchCleanupExecutor batchCleanupExecutor) {
        this.sshTestHistoryMapper = sshTestHistoryMapper;
        this.appProperties = appProperties;
        this.batchCleanupExecutor = batchCleanupExecutor;
    }

    /**
     * 清理当前保留周期之前的 SSH 测试历史；已有任务运行时立即跳过。
     *
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    @Override
    public Optional<CleanupResult> cleanupExpiredSshTestHistory() {
        LocalDateTime cutoffTime = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getSshTestHistory().getRetentionDays());
        return cleanupExpiredSshTestHistory(cutoffTime);
    }

    /**
     * 按指定边界执行清理，供独立数据库验收固定 cutoff 边界。
     *
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    @Override
    public Optional<CleanupResult> cleanupExpiredSshTestHistory(LocalDateTime cutoffTime) {
        return batchCleanupExecutor.run("ssh-test-history", cutoffTime,
                sshTestHistoryMapper::deleteExpiredBatch,
                appProperties.getSshTestHistory().getCleanupBatchSize(),
                appProperties.getSshTestHistory().getCleanupMaxBatchesPerRun());
    }

}
