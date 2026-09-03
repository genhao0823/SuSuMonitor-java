package com.susumonitor.server.module.command.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.susumonitor.server.module.command.entity.CommandRunEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 在显式隔离 MySQL 库中验证 V29 AI 命令审计表的 DDL、审批 CAS 语义和过期清理边界。
 *
 * <p>该测试直接验证 MySQL 方言下的建表、唯一索引和状态机 CAS 更新行为，H2 Mapper
 * 测试不承担这些方言相关行为的验证职责。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {"susumonitor.ai.enabled=true", "susumonitor.ai.command.enabled=true"})
@ContextConfiguration(initializers = AiCommandRunMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class AiCommandRunMySqlValidationIT {

    private static final long TEST_PROPOSER_ID = 900001L;
    private static final long TEST_SERVER_ID = 900002L;
    private static final long TEST_APPROVER_ID = 900003L;

    @Autowired
    private CommandRunMapper commandRunMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> insertedRunIds = new ArrayList<>();

    /** 验证 V29 已执行、表存在且 execution_id 唯一索引就绪。 */
    @Test
    void v29ShouldCreateTableWithIndexes() {
        Integer migrationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '29' AND success = 1
                """, Integer.class);
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ai_command_runs'
                """, Integer.class);
        Integer uniqueIndex = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ai_command_runs'
                  AND index_name = 'uk_ai_command_runs_execution_id'
                  AND non_unique = 0
                """, Integer.class);

        assertEquals(1, migrationCount);
        assertEquals(1, tableCount);
        assertEquals(1, uniqueIndex);
    }

    /** insertRun 回填自增主键，且审批审计字段按实体的值落库。 */
    @Test
    void insertShouldReturnGeneratedId() {
        CommandRunEntity run = newRun("pending_approval",
                LocalDateTime.of(2026, 8, 31, 0, 0, 0), null);

        assertEquals(1, commandRunMapper.insertRun(run));
        insertedRunIds.add(run.getId());
        assertNotNull(run.getId());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT execution_id, proposer_id, server_id, "
                + "template_id, status, source FROM ai_command_runs WHERE id = ?", run.getId());
        assertEquals(run.getExecutionId(), row.get("execution_id"));
        assertEquals(TEST_PROPOSER_ID, ((Number) row.get("proposer_id")).longValue());
        assertEquals(TEST_SERVER_ID, ((Number) row.get("server_id")).longValue());
        assertEquals("service_status", row.get("template_id"));
        assertEquals("pending_approval", row.get("status"));
        assertEquals("manual", row.get("source"));
    }

    /** approveRun 只在 pending_approval 且未过期时成功，过期行 CAS 失败。 */
    @Test
    void approveShouldCasFromPending() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        CommandRunEntity live = newRun("pending_approval", now.minusMinutes(1), now.plusMinutes(5));
        CommandRunEntity expired = newRun("pending_approval", now.minusMinutes(10), now.minusMinutes(1));
        insertAndTrack(live);
        insertAndTrack(expired);

        assertEquals(1, commandRunMapper.approveRun(live.getId(), TEST_APPROVER_ID, now));
        assertEquals(0, commandRunMapper.approveRun(expired.getId(), TEST_APPROVER_ID, now));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, approver_id FROM ai_command_runs WHERE id = ?", live.getId());
        assertEquals("approved", row.get("status"));
        assertEquals(TEST_APPROVER_ID, ((Number) row.get("approver_id")).longValue());
    }

    /** completeRun 只在 approved/executing 状态生效，终态行的迟到结果幂等丢弃。 */
    @Test
    void completeShouldBeIdempotentOnTerminalState() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        LocalDateTime finishAt = now.plusSeconds(30);
        CommandRunEntity run = newRun("pending_approval", now.minusMinutes(1), now.plusMinutes(5));
        insertAndTrack(run);

        assertEquals(1, commandRunMapper.approveRun(run.getId(), TEST_APPROVER_ID, now));
        assertEquals(1, commandRunMapper.markExecuting(run.getId()));
        assertEquals(1, commandRunMapper.completeRun(
                run.getExecutionId(), "succeeded", "{}", 0, true, 100L, null, finishAt));
        assertEquals(0, commandRunMapper.completeRun(
                run.getExecutionId(), "succeeded", "{}", 0, true, 100L, null, finishAt));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, exit_code, duration_ms FROM ai_command_runs WHERE id = ?", run.getId());
        assertEquals("succeeded", row.get("status"));
        assertEquals(0, ((Number) row.get("exit_code")).intValue());
        assertEquals(100L, ((Number) row.get("duration_ms")).longValue());
    }

    /** rejectRun 仅作用于 pending_approval，已 approve 的行拒绝失败且状态不变。 */
    @Test
    void rejectShouldCasFromPending() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        CommandRunEntity run = newRun("pending_approval", now.minusMinutes(1), now.plusMinutes(5));
        insertAndTrack(run);
        assertEquals(1, commandRunMapper.approveRun(run.getId(), TEST_APPROVER_ID, now));

        assertEquals(0, commandRunMapper.rejectRun(run.getId(), TEST_APPROVER_ID, now));

        assertEquals("approved", jdbcTemplate.queryForObject(
                "SELECT status FROM ai_command_runs WHERE id = ?", String.class, run.getId()));
    }

    /** 过期与超时扫描命中对应行，updateStatusByIds 按 id 批量置终态。 */
    @Test
    void sweepSelectAndStatusUpdate() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        CommandRunEntity expiredPending = newRun("pending_approval", now.minusMinutes(30), now.minusMinutes(1));
        CommandRunEntity staleExecuting = newRun("executing", now.minusMinutes(30), null);
        insertAndTrack(expiredPending);
        insertAndTrack(staleExecuting);
        // updated_at 由 ON UPDATE CURRENT_TIMESTAMP 维护，回拨以模拟长时间未收到执行结果。
        jdbcTemplate.update("UPDATE ai_command_runs SET updated_at = ? WHERE id = ?",
                now.minusMinutes(20), staleExecuting.getId());

        List<Long> expiredIds = commandRunMapper.selectExpiredIds(now, 10);
        List<Long> timeoutIds = commandRunMapper.selectTimeoutIds(now.minusMinutes(10), 10);
        assertTrue(expiredIds.contains(expiredPending.getId()));
        assertTrue(timeoutIds.contains(staleExecuting.getId()));

        assertEquals(1, commandRunMapper.updateStatusByIds(
                List.of(expiredPending.getId()), "expired", null, now));
        assertEquals(1, commandRunMapper.updateStatusByIds(
                List.of(staleExecuting.getId()), "timeout", 50402, now));

        assertEquals("expired", jdbcTemplate.queryForObject(
                "SELECT status FROM ai_command_runs WHERE id = ?", String.class, expiredPending.getId()));
        Map<String, Object> timeoutRow = jdbcTemplate.queryForMap(
                "SELECT status, error_code FROM ai_command_runs WHERE id = ?", staleExecuting.getId());
        assertEquals("timeout", timeoutRow.get("status"));
        assertEquals(50402, ((Number) timeoutRow.get("error_code")).intValue());
    }

    /** 过期清理按 batchSize 分批删除，未删除的行保留给下一轮。 */
    @Test
    void deletedCountShouldRespectBatchSize() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
        CommandRunEntity first = newRun("succeeded", cutoff.minusMinutes(10), null);
        CommandRunEntity second = newRun("succeeded", cutoff.minusMinutes(9), null);
        insertAndTrack(first);
        insertAndTrack(second);

        assertEquals(1, commandRunMapper.deleteExpiredBatch(cutoff, 1));
        assertEquals(1, commandRunMapper.deleteExpiredBatch(cutoff, 1));
        assertEquals(0, commandRunMapper.deleteExpiredBatch(cutoff, 1));
    }

    /** 插入并登记待清理主键，避免测试数据残留。 */
    private void insertAndTrack(CommandRunEntity run) {
        commandRunMapper.insertRun(run);
        insertedRunIds.add(run.getId());
    }

    /** 清理由测试产生的记录，避免影响真实命令审计数据。 */
    @AfterEach
    void tearDown() {
        for (Long id : insertedRunIds) {
            jdbcTemplate.update("DELETE FROM ai_command_runs WHERE id = ?", id);
        }
        insertedRunIds.clear();
    }

    private CommandRunEntity newRun(String status, LocalDateTime createdAt, LocalDateTime expiresAt) {
        CommandRunEntity run = new CommandRunEntity();
        run.setExecutionId(UUID.randomUUID().toString());
        run.setProposerId(TEST_PROPOSER_ID);
        run.setServerId(TEST_SERVER_ID);
        run.setTemplateId("service_status");
        run.setParamsJson("{}");
        run.setParamsHash("a".repeat(64));
        run.setRenderedCommand("systemctl status x");
        run.setSource("manual");
        run.setStatus(status);
        run.setCreatedAt(createdAt);
        run.setExpiresAt(expiresAt);
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
