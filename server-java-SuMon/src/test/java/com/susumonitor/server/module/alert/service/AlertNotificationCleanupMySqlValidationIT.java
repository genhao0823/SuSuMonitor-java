package com.susumonitor.server.module.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.alert.mapper.AlertNotificationCleanupMapper;
import com.susumonitor.server.module.metrics.service.MetricsCleanupService.CleanupResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在显式隔离 MySQL 配置下验证通知投递清理 cutoff 边界、V27 索引与真实 Mapper SQL。
 */
@ActiveProfiles("metrics-validation")
@SpringBootTest
@ContextConfiguration(initializers = AlertNotificationCleanupMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class AlertNotificationCleanupMySqlValidationIT {

    private static final long VALIDATION_RECORD_ID = 900001L;

    /** 阻止真实 MySQL 验收误连开发库；只有显式启用且目标为本机隔离库时才允许加载测试上下文。 */
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
    private AlertNotificationCleanupService cleanupService;

    @Autowired
    private AlertNotificationCleanupMapper cleanupMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppProperties appProperties;

    /** 验证早于 cutoff 的记录删除，等于和晚于 cutoff 的记录保留（V27 created_at 索引路径）。 */
    @Test
    void cutoffBoundaryShouldDeleteOnlyEarlierRows() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM alert_notifications WHERE alert_record_id = ?", VALIDATION_RECORD_ID);
            jdbcTemplate.update("INSERT INTO alert_notifications (alert_record_id, channel, status, created_at) "
                            + "VALUES (?, 'dingtalk', 'pending', ?), (?, 'email', 'pending', ?), (?, 'webhook', 'pending', ?)",
                    VALIDATION_RECORD_ID, cutoff.minusSeconds(1),
                    VALIDATION_RECORD_ID, cutoff,
                    VALIDATION_RECORD_ID, cutoff.plusSeconds(1));
        });

        CleanupResult result = cleanupService.cleanupExpiredNotifications(cutoff).orElseThrow();

        assertNotNull(result);
        assertEquals(1, result.deletedRows());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(id) FROM alert_notifications WHERE alert_record_id = ?",
                Long.class, VALIDATION_RECORD_ID));
    }

    /** 验证单轮最大批次数限制，以及后续轮次可以继续删除剩余过期数据。 */
    @Test
    void cleanupShouldHonorBatchSizeAndMaximumBatches() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 2, 1, 0, 0, 0);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM alert_notifications WHERE alert_record_id = ?", VALIDATION_RECORD_ID);
            for (int index = 1; index <= 5; index++) {
                jdbcTemplate.update(
                        "INSERT INTO alert_notifications (alert_record_id, channel, status, created_at) VALUES (?, 'dingtalk', 'pending', ?)",
                        VALIDATION_RECORD_ID, cutoff.minusSeconds(index));
            }
        });

        appProperties.getAlert().setNotificationCleanupBatchSize(2);
        appProperties.getAlert().setNotificationCleanupMaxBatchesPerRun(2);

        CleanupResult first = cleanupService.cleanupExpiredNotifications(cutoff).orElseThrow();

        assertEquals(2, first.batchCount());
        assertEquals(4, first.deletedRows());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(id) FROM alert_notifications WHERE alert_record_id = ?",
                Long.class, VALIDATION_RECORD_ID));

        CleanupResult second = cleanupService.cleanupExpiredNotifications(cutoff).orElseThrow();

        assertEquals(1, second.deletedRows());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(id) FROM alert_notifications WHERE alert_record_id = ?",
                Long.class, VALIDATION_RECORD_ID));
    }
}
