package com.susumonitor.server.module.metrics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;

/**
 * 在显式隔离 MySQL 配置下验证 Metrics 清理 cutoff 边界和真实 Mapper SQL。
 */
@ActiveProfiles("metrics-validation")
@SpringBootTest
@ContextConfiguration(initializers = MetricsCleanupMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class MetricsCleanupMySqlValidationIT {

    private static final long VALIDATION_SERVER_ID = 900001L;

    /**
     * 阻止真实 MySQL 验收误连开发库；只有显式启用且目标为本机隔离库时才允许加载测试上下文。
     */
    private static void validateTargetDatabase() {
        String host = System.getenv("DB_HOST");
        String database = System.getenv("DB_NAME");
        if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))) {
            throw new IllegalStateException("MySQL validation requires a local DB_HOST");
        }
        if (database == null || database.isBlank()
                || "susumonitor".equalsIgnoreCase(database)
                || !database.toLowerCase().contains("validation")) {
            throw new IllegalStateException("MySQL validation requires an isolated validation DB_NAME");
        }
    }

    /** 在 Spring 创建数据源之前校验真实 MySQL 验收目标，避免不安全配置触发数据库连接。 */
    static final class TargetDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            validateTargetDatabase();
        }
    }

    @Autowired
    private MetricsCleanupService cleanupService;

    @Autowired
    private MetricsCleanupMapper cleanupMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppProperties appProperties;

    /** 验证早于 cutoff 的记录删除，等于和晚于 cutoff 的记录保留。 */
    @Test
    void cutoffBoundaryShouldDeleteOnlyEarlierRows() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM metrics WHERE server_id = ?", VALIDATION_SERVER_ID);
            jdbcTemplate.update("INSERT INTO metrics (server_id, collected_at) VALUES (?, ?), (?, ?), (?, ?)",
                    VALIDATION_SERVER_ID, cutoff.minusSeconds(1),
                    VALIDATION_SERVER_ID, cutoff,
                    VALIDATION_SERVER_ID, cutoff.plusSeconds(1));
        });

        CleanupResult result = cleanupService.cleanupExpiredMetrics(cutoff).orElseThrow();

        assertNotNull(result);
        assertEquals(1, result.deletedRows());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(id) FROM metrics WHERE server_id = ?", Long.class, VALIDATION_SERVER_ID));
    }

    /** 验证单轮最大批次数限制，以及后续轮次可以继续删除剩余过期数据。 */
    @Test
    void cleanupShouldHonorBatchSizeAndMaximumBatches() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 2, 1, 0, 0, 0);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM metrics WHERE server_id = ?", VALIDATION_SERVER_ID);
            for (int index = 1; index <= 5; index++) {
                jdbcTemplate.update("INSERT INTO metrics (server_id, collected_at) VALUES (?, ?)",
                        VALIDATION_SERVER_ID, cutoff.minusSeconds(index));
            }
        });

        appProperties.getMetrics().setCleanupBatchSize(2);
        appProperties.getMetrics().setCleanupMaxBatchesPerRun(2);

        CleanupResult first = cleanupService.cleanupExpiredMetrics(cutoff).orElseThrow();

        assertEquals(2, first.batchCount());
        assertEquals(4, first.deletedRows());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(id) FROM metrics WHERE server_id = ?", Long.class, VALIDATION_SERVER_ID));

        CleanupResult second = cleanupService.cleanupExpiredMetrics(cutoff).orElseThrow();

        assertEquals(1, second.deletedRows());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(id) FROM metrics WHERE server_id = ?", Long.class, VALIDATION_SERVER_ID));
    }
}
