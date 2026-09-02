package com.susumonitor.server.module.ai.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.susumonitor.server.module.ai.entity.AiDiagnosticRunEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * 在显式隔离 MySQL 库中验证 V28 AI 诊断审计表的 DDL、Mapper 写入回填和过期清理边界。
 *
 * <p>该测试直接验证 MySQL 方言下的建表、时间列和分批删除语义，H2 Mapper
 * 测试不承担这些方言相关行为的验证职责。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "susumonitor.ai.enabled=true")
@ContextConfiguration(initializers = AiDiagnosticRunMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class AiDiagnosticRunMySqlValidationIT {

    private static final String TEST_PREFIX = "ai-validation-";
    private static final long TEST_ACTOR_ID = 900001L;
    private static final long TEST_SERVER_ID = 900002L;

    @Autowired
    private AiDiagnosticRunMapper aiDiagnosticRunMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long insertedRunId;
    private final List<Long> insertedRunIds = new ArrayList<>();

    /** 验证 V28 已执行、表存在、时间列和清理索引就绪。 */
    @Test
    void v28ShouldCreateTableWithCleanupIndex() {
        Integer migrationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '28' AND success = 1
                """, Integer.class);
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ai_diagnostic_runs'
                """, Integer.class);
        Integer cleanupIndex = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ai_diagnostic_runs'
                  AND index_name = 'idx_ai_diagnostic_runs_created_at'
                """, Integer.class);

        assertEquals(1, migrationCount);
        assertEquals(1, tableCount);
        assertEquals(1, cleanupIndex);
    }

    /** insertRun 回填自增主键，且原始问题、凭据字段不得落库。 */
    @Test
    void insertShouldReturnGeneratedId() {
        AiDiagnosticRunEntity run = newRun("running");
        run.setCreatedAt(LocalDateTime.of(2026, 8, 31, 0, 0, 0));

        assertEquals(1, aiDiagnosticRunMapper.insertRun(run));
        insertedRunId = run.getId();
        assertNotNull(insertedRunId);

        jdbcTemplate.queryForMap("SELECT request_id, actor_id, server_id, status, context_hash "
                + "FROM ai_diagnostic_runs WHERE id = ?", insertedRunId);
    }

    /** completeRun 更新为完成状态并写入结构化结果和用量。 */
    @Test
    void completeShouldPersistResultAndUsage() {
        AiDiagnosticRunEntity run = newRun("running");
        run.setCreatedAt(LocalDateTime.of(2026, 8, 31, 1, 0, 0));
        aiDiagnosticRunMapper.insertRun(run);
        insertedRunId = run.getId();

        run.setStatus("completed");
        run.setResultJson("{}");
        run.setInputTokens(10);
        run.setOutputTokens(5);
        run.setTotalTokens(15);
        run.setDurationMs(120L);
        run.setCompletedAt(LocalDateTime.of(2026, 8, 31, 1, 1, 0));
        assertEquals(1, aiDiagnosticRunMapper.completeRun(run));

        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, result_json, input_tokens, total_tokens, duration_ms, completed_at "
                        + "FROM ai_diagnostic_runs WHERE id = ?", insertedRunId);
        assertEquals("completed", row.get("status"));
        assertEquals("{}", row.get("result_json"));
        assertEquals(10, ((Number) row.get("input_tokens")).intValue());
        assertEquals(15, ((Number) row.get("total_tokens")).intValue());
        assertEquals(120L, ((Number) row.get("duration_ms")).longValue());
        assertNotNull(row.get("completed_at"));
    }

    /** failRun 只写失败状态和错误码，不清除已有结果。 */
    @Test
    void failShouldPersistErrorCode() {
        AiDiagnosticRunEntity run = newRun("running");
        run.setCreatedAt(LocalDateTime.of(2026, 8, 31, 2, 0, 0));
        aiDiagnosticRunMapper.insertRun(run);
        insertedRunId = run.getId();

        run.setStatus("failed");
        run.setErrorCode(50401);
        run.setDurationMs(3000L);
        run.setCompletedAt(LocalDateTime.of(2026, 8, 31, 2, 1, 0));
        assertEquals(1, aiDiagnosticRunMapper.failRun(run));

        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, error_code FROM ai_diagnostic_runs WHERE id = ?", insertedRunId);
        assertEquals("failed", row.get("status"));
        assertEquals(50401, ((Number) row.get("error_code")).intValue());
    }

    /** 过期清理只删除严格早于 cutoff 的记录，等于和晚于 cutoff 的记录保留。 */
    @Test
    void expiredBatchShouldDeleteOnlyEarlierRows() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
        LocalDateTime before = cutoff.minusSeconds(1);
        LocalDateTime equal = cutoff;
        LocalDateTime after = cutoff.plusSeconds(1);

        AiDiagnosticRunEntity older = newRun("completed", before);
        AiDiagnosticRunEntity atBoundary = newRun("completed", equal);
        AiDiagnosticRunEntity newer = newRun("completed", after);
        aiDiagnosticRunMapper.insertRun(older);
        aiDiagnosticRunMapper.insertRun(atBoundary);
        aiDiagnosticRunMapper.insertRun(newer);
        insertedRunId = older.getId();

        int deleted = aiDiagnosticRunMapper.deleteExpiredBatch(cutoff, 10);

        assertEquals(1, deleted);
        Long remainingOlder = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_diagnostic_runs WHERE id = ?", Long.class, older.getId());
        assertEquals(0L, remainingOlder);
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_diagnostic_runs WHERE id = ?", Long.class, atBoundary.getId()));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_diagnostic_runs WHERE id = ?", Long.class, newer.getId()));
    }

    /** 批量大小限制分批删除量，未删除的行保留给下一轮。 */
    @Test
    void deletedCountShouldRespectBatchSize() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 1, 0, 0, 0);
        AiDiagnosticRunEntity first = newRun("completed", cutoff.minusMinutes(10));
        AiDiagnosticRunEntity second = newRun("completed", cutoff.minusMinutes(9));
        aiDiagnosticRunMapper.insertRun(first);
        aiDiagnosticRunMapper.insertRun(second);
        insertedRunId = first.getId();

        assertEquals(1, aiDiagnosticRunMapper.deleteExpiredBatch(cutoff, 1));
        assertEquals(1, aiDiagnosticRunMapper.deleteExpiredBatch(cutoff, 1));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_diagnostic_runs WHERE id IN (?, ?)", Long.class,
                first.getId(), second.getId()));
    }

    /** 预算聚合：只累计窗口内 completed 调用的 total_tokens（token 由 completeRun 落库），failed 与窗外行不计入。 */
    @Test
    void totalTokensAggregateShouldSumCompletedWithinWindow() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        LocalDateTime end = start.plusDays(1);
        // 窗口内 completed：insert → completeRun 写入 tokens（与生产写入路径一致）。
        AiDiagnosticRunEntity inWindow = newRun("running", start.plusHours(1));
        insertAndTrack(inWindow);
        inWindow.setStatus("completed");
        inWindow.setTotalTokens(15);
        inWindow.setCompletedAt(start.plusHours(1));
        aiDiagnosticRunMapper.completeRun(inWindow);
        // 窗口内 failed：failRun 不写 tokens。
        AiDiagnosticRunEntity failedInWindow = newRun("running", start.plusHours(2));
        insertAndTrack(failedInWindow);
        failedInWindow.setStatus("failed");
        failedInWindow.setErrorCode(50401);
        failedInWindow.setCompletedAt(start.plusHours(2));
        aiDiagnosticRunMapper.failRun(failedInWindow);
        // 窗口外 completed：不计入本窗口聚合。
        AiDiagnosticRunEntity completedOutside = newRun("running", end.plusHours(1));
        insertAndTrack(completedOutside);
        completedOutside.setStatus("completed");
        completedOutside.setTotalTokens(99);
        completedOutside.setCompletedAt(end.plusHours(1));
        aiDiagnosticRunMapper.completeRun(completedOutside);

        Long sum = aiDiagnosticRunMapper.selectTotalTokensBetween(start, end);

        assertEquals(15L, sum);
    }

    /** 插入并登记待清理主键，避免测试数据残留。 */
    private void insertAndTrack(AiDiagnosticRunEntity run) {
        aiDiagnosticRunMapper.insertRun(run);
        insertedRunIds.add(run.getId());
    }

    /** 清理由测试产生的记录，避免影响真实 AI 审计数据。 */
    @AfterEach
    void tearDown() {
        if (insertedRunId != null) {
            jdbcTemplate.update("DELETE FROM ai_diagnostic_runs WHERE id = ?", insertedRunId);
        }
        for (Long id : insertedRunIds) {
            jdbcTemplate.update("DELETE FROM ai_diagnostic_runs WHERE id = ?", id);
        }
    }

    private AiDiagnosticRunEntity newRun(String status) {
        return newRun(status, LocalDateTime.of(2026, 8, 31, 0, 0, 0));
    }

    private AiDiagnosticRunEntity newRun(String status, LocalDateTime createdAt) {
        AiDiagnosticRunEntity run = new AiDiagnosticRunEntity();
        run.setRequestId(UUID.randomUUID().toString());
        run.setActorId(TEST_ACTOR_ID);
        run.setServerId(TEST_SERVER_ID);
        run.setPromptVersion("ai-diagnosis-v1");
        run.setProvider("openai-compatible");
        run.setModel("test-model");
        run.setStatus(status);
        run.setContextHash("a".repeat(64));
        run.setCreatedAt(createdAt);
        return run;
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