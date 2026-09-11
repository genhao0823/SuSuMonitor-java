package com.susumonitor.server.module.ai.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.susumonitor.server.module.ai.entity.AiQaRunEntity;
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
 * 在显式隔离 MySQL 库中验证 V31 AI 运维问答审计表的 DDL、Mapper 写入回填和过期清理边界。
 *
 * <p>该测试直接验证 MySQL 方言下的建表、时间列和分批删除语义，H2 Mapper
 * 测试不承担这些方言相关行为的验证职责。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "susumonitor.ai.enabled=true")
@ContextConfiguration(initializers = AiQaRunMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class AiQaRunMySqlValidationIT {

    private static final long TEST_ACTOR_ID = 900011L;
    private static final long TEST_SERVER_ID = 900012L;

    @Autowired
    private AiQaRunMapper aiQaRunMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long insertedRunId;
    private final List<Long> insertedRunIds = new ArrayList<>();

    /** 验证 V31 已执行、表存在、时间列和清理索引就绪。 */
    @Test
    void v31ShouldCreateTableWithCleanupIndex() {
        Integer migrationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '31' AND success = 1
                """, Integer.class);
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ai_qa_runs'
                """, Integer.class);
        Integer cleanupIndex = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ai_qa_runs'
                  AND index_name = 'idx_ai_qa_runs_created_at'
                """, Integer.class);

        assertEquals(1, migrationCount);
        assertEquals(1, tableCount);
        assertEquals(1, cleanupIndex);
    }

    /** insertRun 回填自增主键，且原始问题不落库（仅 question_hash）。 */
    @Test
    void insertShouldReturnGeneratedId() {
        AiQaRunEntity run = newRun("running");
        run.setCreatedAt(LocalDateTime.of(2026, 9, 7, 0, 0, 0));

        assertEquals(1, aiQaRunMapper.insertRun(run));
        insertedRunId = run.getId();
        assertNotNull(insertedRunId);

        jdbcTemplate.queryForMap("SELECT request_id, actor_id, server_id, status, question_hash "
                + "FROM ai_qa_runs WHERE id = ?", insertedRunId);
    }

    /** 全局问答允许 server_id 为 NULL 并正常落库。 */
    @Test
    void insertShouldAllowNullServerId() {
        AiQaRunEntity run = newRun("running");
        run.setServerId(null);
        run.setCreatedAt(LocalDateTime.of(2026, 9, 7, 1, 0, 0));

        assertEquals(1, aiQaRunMapper.insertRun(run));
        insertedRunId = run.getId();

        Long serverId = jdbcTemplate.queryForObject(
                "SELECT server_id FROM ai_qa_runs WHERE id = ?", Long.class, insertedRunId);
        assertEquals(null, serverId);
    }

    /** completeRun 更新为完成状态并写入工具调用审计、结构化结果和用量。 */
    @Test
    void completeShouldPersistToolCallsAndUsage() {
        AiQaRunEntity run = newRun("running");
        run.setCreatedAt(LocalDateTime.of(2026, 9, 7, 2, 0, 0));
        aiQaRunMapper.insertRun(run);
        insertedRunId = run.getId();

        run.setStatus("completed");
        run.setToolCallsJson("[{\"tool\":\"get_latest_metrics\",\"args\":\"{\\\"server_id\\\":1}\"}]");
        run.setResultJson("{}");
        run.setInputTokens(10);
        run.setOutputTokens(5);
        run.setTotalTokens(15);
        run.setDurationMs(120L);
        run.setCompletedAt(LocalDateTime.of(2026, 9, 7, 2, 1, 0));
        assertEquals(1, aiQaRunMapper.completeRun(run));

        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, tool_calls_json, result_json, input_tokens, total_tokens "
                        + "FROM ai_qa_runs WHERE id = ?", insertedRunId);
        assertEquals("completed", row.get("status"));
        assertEquals(10, ((Number) row.get("input_tokens")).intValue());
        assertEquals(15, ((Number) row.get("total_tokens")).intValue());
        assertNotNull(row.get("tool_calls_json"));
    }

    /** failRun 只写失败状态和错误码，不清除已有结果。 */
    @Test
    void failShouldPersistErrorCode() {
        AiQaRunEntity run = newRun("running");
        run.setCreatedAt(LocalDateTime.of(2026, 9, 7, 3, 0, 0));
        aiQaRunMapper.insertRun(run);
        insertedRunId = run.getId();

        run.setStatus("failed");
        run.setErrorCode(50401);
        run.setDurationMs(3000L);
        run.setCompletedAt(LocalDateTime.of(2026, 9, 7, 3, 1, 0));
        assertEquals(1, aiQaRunMapper.failRun(run));

        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, error_code FROM ai_qa_runs WHERE id = ?", insertedRunId);
        assertEquals("failed", row.get("status"));
        assertEquals(50401, ((Number) row.get("error_code")).intValue());
    }

    /** 过期清理只删除严格早于 cutoff 的记录，等于和晚于 cutoff 的记录保留。 */
    @Test
    void expiredBatchShouldDeleteOnlyEarlierRows() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 9, 1, 0, 0, 0);

        AiQaRunEntity older = newRun("completed", cutoff.minusSeconds(1));
        AiQaRunEntity atBoundary = newRun("completed", cutoff);
        AiQaRunEntity newer = newRun("completed", cutoff.plusSeconds(1));
        insertAndTrack(older);
        insertAndTrack(atBoundary);
        insertAndTrack(newer);

        int deleted = aiQaRunMapper.deleteExpiredBatch(cutoff, 10);

        assertEquals(1, deleted);
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_qa_runs WHERE id = ?", Long.class, older.getId()));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_qa_runs WHERE id = ?", Long.class, atBoundary.getId()));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_qa_runs WHERE id = ?", Long.class, newer.getId()));
    }

    /** 预算聚合：只累计窗口内 completed 问答的 total_tokens，failed 与窗外行不计入。 */
    @Test
    void totalTokensAggregateShouldSumCompletedWithinWindow() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 7, 0, 0, 0);
        LocalDateTime end = start.plusDays(1);
        AiQaRunEntity inWindow = newRun("running", start.plusHours(1));
        insertAndTrack(inWindow);
        inWindow.setStatus("completed");
        inWindow.setTotalTokens(15);
        inWindow.setCompletedAt(start.plusHours(1));
        aiQaRunMapper.completeRun(inWindow);
        AiQaRunEntity failedInWindow = newRun("running", start.plusHours(2));
        insertAndTrack(failedInWindow);
        failedInWindow.setStatus("failed");
        failedInWindow.setErrorCode(50303);
        failedInWindow.setCompletedAt(start.plusHours(2));
        aiQaRunMapper.failRun(failedInWindow);

        Long sum = aiQaRunMapper.selectTotalTokensBetween(start, end);

        assertEquals(15L, sum);
    }

    /** 插入并登记待清理主键，避免测试数据残留。 */
    private void insertAndTrack(AiQaRunEntity run) {
        aiQaRunMapper.insertRun(run);
        insertedRunIds.add(run.getId());
    }

    /** 清理由测试产生的记录，避免影响真实问答审计数据。 */
    @AfterEach
    void tearDown() {
        if (insertedRunId != null) {
            jdbcTemplate.update("DELETE FROM ai_qa_runs WHERE id = ?", insertedRunId);
        }
        for (Long id : insertedRunIds) {
            jdbcTemplate.update("DELETE FROM ai_qa_runs WHERE id = ?", id);
        }
    }

    private AiQaRunEntity newRun(String status) {
        return newRun(status, LocalDateTime.of(2026, 9, 7, 0, 0, 0));
    }

    private AiQaRunEntity newRun(String status, LocalDateTime createdAt) {
        AiQaRunEntity run = new AiQaRunEntity();
        run.setRequestId(UUID.randomUUID().toString());
        run.setActorId(TEST_ACTOR_ID);
        run.setServerId(TEST_SERVER_ID);
        run.setPromptVersion("ai-qa-v1");
        run.setProvider("openai-compatible");
        run.setModel("test-model");
        run.setStatus(status);
        run.setQuestionHash("b".repeat(64));
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
