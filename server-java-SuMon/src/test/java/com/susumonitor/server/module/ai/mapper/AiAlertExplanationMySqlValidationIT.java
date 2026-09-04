package com.susumonitor.server.module.ai.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.susumonitor.server.module.ai.entity.AiAlertExplanationEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 在显式隔离 MySQL 库中验证 V30 AI 告警解释表的 DDL、1:1 唯一键、
 * Mapper 写入回填、按记录查询和过期清理边界。
 *
 * <p>该测试直接验证 MySQL 方言下的建表、唯一约束和分批删除语义，
 * H2 Mapper 测试不承担这些方言相关行为的验证职责。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {"susumonitor.ai.enabled=true", "susumonitor.ai.explanation.enabled=true"})
@ContextConfiguration(initializers = AiAlertExplanationMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class AiAlertExplanationMySqlValidationIT {

    private static final long TEST_SERVER_ID = 900011L;
    private static final long TEST_RULE_ID = 900012L;

    /** 每行独立 record ID：表对 alert_record_id 有 1:1 唯一键，重复插入属冲突用例专用。 */
    private final AtomicLong recordSequence = new AtomicLong(900010L);

    /** 记录用于唯一键冲突用例的固定 record ID。 */
    private Long currentRecordId;

    @Autowired
    private AiAlertExplanationMapper explanationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> insertedIds = new ArrayList<>();

    /** 验证 V30 已执行、表存在、1:1 唯一键与清理索引就绪。 */
    @Test
    void v30ShouldCreateTableWithUniqueKeyAndCleanupIndex() {
        Integer migrationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '30' AND success = 1
                """, Integer.class);
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ai_alert_explanations'
                """, Integer.class);
        Integer uniqueKey = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ai_alert_explanations'
                  AND index_name = 'uk_ai_alert_explanations_record'
                """, Integer.class);
        Integer cleanupIndex = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ai_alert_explanations'
                  AND index_name = 'idx_ai_alert_explanations_created_at'
                """, Integer.class);

        assertEquals(1, migrationCount);
        assertEquals(1, tableCount);
        assertEquals(1, uniqueKey);
        assertEquals(1, cleanupIndex);
    }

    /** insert 回填自增主键，selectByRecordId 读回全部结构化列。 */
    @Test
    void insertShouldReturnGeneratedIdAndSelectByRecordId() {
        AiAlertExplanationEntity entity = newEntity(LocalDateTime.of(2026, 9, 4, 12, 0, 0));
        assertEquals(1, explanationMapper.insert(entity));
        insertedIds.add(entity.getId());
        assertNotNull(entity.getId());

        AiAlertExplanationEntity loaded = explanationMapper.selectByRecordId(currentRecordId);
        assertEquals(entity.getId(), loaded.getId());
        assertEquals(currentRecordId, loaded.getAlertRecordId());
        assertEquals(TEST_SERVER_ID, loaded.getServerId());
        assertEquals(TEST_RULE_ID, loaded.getRuleId());
        assertEquals("cpu elevated", loaded.getSummary());
        assertEquals(15, loaded.getTotalTokens());
    }

    /** 同一告警记录的第二条解释触发 1:1 唯一键冲突。 */
    @Test
    void duplicateRecordIdShouldViolateUniqueKey() {
        AiAlertExplanationEntity first = newEntity(LocalDateTime.of(2026, 9, 4, 12, 0, 0));
        explanationMapper.insert(first);
        insertedIds.add(first.getId());

        AiAlertExplanationEntity second = newEntity(first.getAlertRecordId(),
                LocalDateTime.of(2026, 9, 4, 12, 5, 0));

        assertThrows(DataIntegrityViolationException.class, () -> explanationMapper.insert(second));
    }

    /** 过期清理只删除严格早于 cutoff 的行；批量大小限制单轮删除量。 */
    @Test
    void expiredBatchShouldDeleteOnlyEarlierRowsRespectingBatchSize() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        AiAlertExplanationEntity older = newEntity(cutoff.minusSeconds(1));
        AiAlertExplanationEntity atBoundary = newEntity(cutoff);
        explanationMapper.insert(older);
        explanationMapper.insert(atBoundary);
        insertedIds.add(older.getId());
        insertedIds.add(atBoundary.getId());

        assertEquals(1, explanationMapper.deleteExpiredBatch(cutoff, 1));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_alert_explanations WHERE id = ?", Long.class, older.getId()));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_alert_explanations WHERE id = ?", Long.class, atBoundary.getId()));
    }

    /** 预算聚合：窗口内已落库解释的 total_tokens 求和，窗外行不计入。 */
    @Test
    void totalTokensAggregateShouldSumWithinWindow() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 4, 0, 0, 0);
        LocalDateTime end = start.plusDays(1);
        AiAlertExplanationEntity inWindow = newEntity(start.plusHours(1));
        explanationMapper.insert(inWindow);
        insertedIds.add(inWindow.getId());
        AiAlertExplanationEntity outside = newEntity(end.plusHours(2));
        explanationMapper.insert(outside);
        insertedIds.add(outside.getId());

        assertEquals(15L, explanationMapper.sumTotalTokensBetween(start, end));
    }

    /** 清理由测试产生的行，避免残留。 */
    @AfterEach
    void tearDown() {
        for (Long id : insertedIds) {
            jdbcTemplate.update("DELETE FROM ai_alert_explanations WHERE id = ?", id);
        }
    }

    /** 构造一行新解释：record ID 递增，记录到 currentRecordId 供按记录查询断言。 */
    private AiAlertExplanationEntity newEntity(LocalDateTime createdAt) {
        AiAlertExplanationEntity entity = newEntity(recordSequence.getAndIncrement(), createdAt);
        currentRecordId = entity.getAlertRecordId();
        return entity;
    }

    /** 以指定 record ID 构造一行解释（唯一键冲突用例复用同一 ID）。 */
    private AiAlertExplanationEntity newEntity(Long alertRecordId, LocalDateTime createdAt) {
        AiAlertExplanationEntity entity = new AiAlertExplanationEntity();
        entity.setAlertRecordId(alertRecordId);
        entity.setServerId(TEST_SERVER_ID);
        entity.setRuleId(TEST_RULE_ID);
        entity.setEventId(java.util.UUID.randomUUID().toString());
        entity.setPromptVersion("ai-alert-explanation-v1");
        entity.setProvider("openai-compatible");
        entity.setModel("test-model");
        entity.setSummary("cpu elevated");
        entity.setResultJson("{}");
        entity.setTotalTokens(15);
        entity.setDurationMs(100L);
        entity.setCreatedAt(createdAt);
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
