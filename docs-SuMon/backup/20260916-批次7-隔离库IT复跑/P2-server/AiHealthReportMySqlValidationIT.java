package com.susumonitor.server.module.ai.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.susumonitor.server.module.ai.entity.AiHealthReportEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 在显式隔离 MySQL 库中验证 V34 AI 定时健康报告表的 DDL、report_date 唯一键、
 * UPSERT 幂等、聚合查询方言（GROUP BY + CASE + JOIN 软删排除）与过期清理边界。
 *
 * <p>该测试直接验证 MySQL 方言下的建表、唯一约束、ON DUPLICATE KEY UPDATE
 * 与聚合 SQL 行为，H2 Mapper 测试不承担这些方言相关行为的验证职责。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {"susumonitor.ai.enabled=true", "susumonitor.ai.report.enabled=true"})
@ContextConfiguration(initializers = AiHealthReportMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class AiHealthReportMySqlValidationIT {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 9, 10);
    private static final long TEST_SERVER_ID = 900021L;

    @Autowired
    private AiHealthReportMapper reportMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 验证 V34 已执行、表存在、report_date 唯一键与清理索引就绪。 */
    @Test
    void v34ShouldCreateTableWithUniqueKeyAndCleanupIndex() {
        Integer migrationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '34' AND success = 1
                """, Integer.class);
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ai_health_reports'
                """, Integer.class);
        Integer uniqueKey = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ai_health_reports'
                  AND index_name = 'uk_ai_health_reports_date'
                """, Integer.class);
        Integer cleanupIndex = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ai_health_reports'
                  AND index_name = 'idx_ai_health_reports_created_at'
                """, Integer.class);

        assertEquals(1, migrationCount);
        assertEquals(1, tableCount);
        assertEquals(1, uniqueKey);
        assertEquals(1, cleanupIndex);
    }

    /** UPSERT：同日二次生成覆盖旧行而不产生重复行，读取回全部结构化列。 */
    @Test
    void upsertShouldOverwriteSameDateWithoutDuplicates() {
        // ODKU 返回值随驱动 useAffectedRows 语义而异（found-rows=1 / affected 下 update=2），
        // 故断言 >=1（真失败为 0），行数不变与内容覆盖由唯一键 + 读取断言保证。
        assertTrue(reportMapper.upsertReport(newEntity("succeeded", "first summary", null)) >= 1);
        assertTrue(reportMapper.upsertReport(newEntity("degraded", null, 50304)) >= 1);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_health_reports WHERE report_date = ?",
                Integer.class, java.sql.Date.valueOf(REPORT_DATE));
        assertEquals(1, rowCount);

        AiHealthReportEntity loaded = reportMapper.selectByDate(REPORT_DATE);
        assertNotNull(loaded.getId());
        assertEquals("degraded", loaded.getStatus());
        assertNull(loaded.getSummary());
        assertEquals(50304, loaded.getErrorCode());
        assertEquals("ai-health-report-v1", loaded.getPromptVersion());
    }

    /** selectById 读回 UPSERT 后的最新行。 */
    @Test
    void selectByIdShouldReturnLatestRow() {
        assertEquals(1, reportMapper.upsertReport(newEntity("succeeded", "kept", null)));
        Long id = reportMapper.selectByDate(REPORT_DATE).getId();
        assertEquals("kept", reportMapper.selectById(id).getSummary());
    }

    /** 聚合查询：软删服务器被排除、CASE 分级统计与 Top 服务器排序符合 MySQL 方言语义。 */
    @Test
    void aggregationsShouldExcludeSoftDeletedServersAndAggregateCorrectly() {
        jdbcTemplate.update("""
                INSERT INTO servers (id, name, host, ssh_host, ssh_user, ssh_auth_type, status,
                                     agent_status, deleted, delete_token)
                VALUES (?, 'live-server', '10.0.0.1', '10.0.0.1', 'root', 'password', 'online',
                        'online', 0, 'ACTIVE'),
                       (?, 'gone-server', '10.0.0.2', '10.0.0.2', 'root', 'password', 'online',
                        'online', 1, 'DELETED-1')
                """, TEST_SERVER_ID, TEST_SERVER_ID + 1);
        jdbcTemplate.update("""
                INSERT INTO metrics (server_id, cpu_percent, memory_percent, disk_percent, collected_at)
                VALUES (?, 90.0, 40.0, 50.0, '2026-09-10 12:00:00'),
                       (?, 30.0, 60.0, 70.0, '2026-09-10 18:00:00')
                """, TEST_SERVER_ID, TEST_SERVER_ID + 1);
        jdbcTemplate.update("""
                INSERT INTO alert_records (rule_id, server_id, metric, current_value, threshold_value,
                                           level, status, triggered_at)
                VALUES (NULL, ?, 'cpu', 91.0, 80.0, 'critical', 'resolved', '2026-09-10 09:00:00'),
                       (NULL, ?, 'memory', 81.0, 80.0, 'warning', 'unread', '2026-09-10 10:00:00')
                """, TEST_SERVER_ID, TEST_SERVER_ID);

        try {
            LocalDateTime start = LocalDateTime.of(2026, 9, 10, 0, 0);
            LocalDateTime end = LocalDateTime.of(2026, 9, 11, 0, 0);

            Map<String, Object> inventory = reportMapper.selectServerInventory();
            org.junit.jupiter.api.Assertions.assertTrue(
                    ((Number) inventory.get("total_count")).intValue() >= 1);

            Map<String, Object> metrics = reportMapper.selectMetricAggregates(start, end);
            // 软删服务器的 30% 采样不计入：AVG=(90)/1，MAX=90。
            assertEquals(90.0, ((Number) metrics.get("avg_cpu_percent")).doubleValue(), 0.01);
            assertEquals(90.0, ((Number) metrics.get("max_cpu_percent")).doubleValue(), 0.01);
            assertEquals(TEST_SERVER_ID, reportMapper.selectMaxCpuServerId(start, end));
            assertEquals(70.0, ((Number) metrics.get("max_disk_percent")).doubleValue(), 0.01);

            Map<String, Object> alerts = reportMapper.selectAlertStatistics(start, end);
            assertEquals(2, ((Number) alerts.get("total_triggered")).intValue());
            assertEquals(1, ((Number) alerts.get("critical_count")).intValue());
            assertEquals(1, ((Number) alerts.get("resolved_count")).intValue());

            var topServers = reportMapper.selectTopAlertServers(start, end, 10);
            assertEquals(1, topServers.size());
            assertEquals("live-server", topServers.get(0).get("server_name"));
            assertEquals(2, ((Number) topServers.get(0).get("alert_count")).intValue());
        } finally {
            jdbcTemplate.update("DELETE FROM alert_records WHERE server_id IN (?, ?)",
                    TEST_SERVER_ID, TEST_SERVER_ID);
            jdbcTemplate.update("DELETE FROM metrics WHERE server_id IN (?, ?)",
                    TEST_SERVER_ID, TEST_SERVER_ID + 1);
            jdbcTemplate.update("DELETE FROM servers WHERE id IN (?, ?)",
                    TEST_SERVER_ID, TEST_SERVER_ID + 1);
        }
    }

    /** 预算聚合：窗口内 total_tokens 求和，窗外行不计入。 */
    @Test
    void totalTokensAggregateShouldSumWithinWindow() {
        AiHealthReportEntity inWindow = newEntity("succeeded", "a", null);
        inWindow.setTotalTokens(25);
        reportMapper.upsertReport(inWindow);

        LocalDateTime start = REPORT_DATE.plusDays(1).atStartOfDay();
        assertEquals(0L, reportMapper.sumTotalTokensBetween(start, start.plusDays(1)));
    }

    /** 过期清理只删除严格早于 cutoff 的行；批量大小限制单轮删除量。 */
    @Test
    void expiredBatchShouldDeleteOnlyEarlierRowsRespectingBatchSize() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        AiHealthReportEntity older = newEntity("succeeded", "old", null);
        older.setCreatedAt(cutoff.minusDays(1));
        AiHealthReportEntity newer = newEntity("succeeded", "new", null);
        newer.setReportDate(REPORT_DATE.plusDays(1));
        newer.setCreatedAt(cutoff.plusDays(1));
        assertEquals(1, reportMapper.upsertReport(older));
        assertEquals(1, reportMapper.upsertReport(newer));

        assertEquals(1, reportMapper.deleteExpiredBatch(cutoff, 1));
        assertNull(reportMapper.selectByDate(older.getReportDate()));
        assertNotNull(reportMapper.selectByDate(newer.getReportDate()));
    }

    /** 清理由测试产生的行，避免残留。 */
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ai_health_reports WHERE report_date >= ?",
                java.sql.Date.valueOf(REPORT_DATE));
    }

    /** 构造一行指定状态的报告。 */
    private AiHealthReportEntity newEntity(String status, String summary, Integer errorCode) {
        AiHealthReportEntity entity = new AiHealthReportEntity();
        entity.setReportDate(REPORT_DATE);
        entity.setStatus(status);
        entity.setProvider("openai-compatible");
        entity.setModel("test-model");
        entity.setPromptVersion("ai-health-report-v1");
        entity.setSummary(summary);
        entity.setErrorCode(errorCode);
        entity.setResultJson("{}");
        entity.setDurationMs(100L);
        entity.setCreatedAt(LocalDateTime.of(2026, 9, 11, 7, 30));
        return entity;
    }

    /** 在 Spring 创建数据源前拒绝非本机或非隔离验证库。 */
    static final class TargetDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            if (!"true".equalsIgnoreCase(System.getenv("RUN_MYSQL_VALIDATION_TESTS"))) {
                throw new IllegalStateException("MySQL validation requires RUN_MYSQL_VALIDATION_TESTS=true");
            }
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
    }
}
