package com.susumonitor.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证消费幂等记录 Mapper 的真实参数绑定、主键回填与唯一键幂等语义。
 */
class ConsumeRecordMapperMybatisTests {

    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        PooledDataSource dataSource = new PooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:consume_record_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS message_consume_records");
            statement.execute("""
                    CREATE TABLE message_consume_records (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        consumer VARCHAR(64) NOT NULL,
                        event_id VARCHAR(36) NOT NULL,
                        status VARCHAR(20) NOT NULL DEFAULT 'consumed',
                        attempts INT NOT NULL DEFAULT 0,
                        last_error VARCHAR(500),
                        consumed_at TIMESTAMP,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_consume_event (consumer, event_id)
                    )
                    """);
        }

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ConsumeRecordMapper.class);
        try (var mapperXml = Resources.getResourceAsReader("mapper/alert/ConsumeRecordMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(mapperXml, configuration, "mapper/alert/ConsumeRecordMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** upsertConsumed 应回填主键。 */
    @Test
    void upsertConsumedShouldGenerateId() {
        ConsumeRecordEntity record = newRecord();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(1, session.getMapper(ConsumeRecordMapper.class).upsertConsumed(record));
        }

        assertNotNull(record.getId());
    }

    /** 同一 consumer+event_id 重复成功消费不冲突：upsert 幂等翻转，仍只有一行。 */
    @Test
    void upsertConsumedTwiceShouldKeepSingleRow() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ConsumeRecordMapper mapper = session.getMapper(ConsumeRecordMapper.class);
            mapper.upsertConsumed(newRecord());
            mapper.upsertConsumed(newRecord());

            assertTrue(mapper.existsConsumed("alert-evaluator", "event-1"));
            try (var rows = session.getConnection().createStatement().executeQuery(
                    "SELECT COUNT(1) FROM message_consume_records WHERE consumer='alert-evaluator' AND event_id='event-1'")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    /** existsConsumed 在已消费后命中、未消费时不命中。 */
    @Test
    void existsConsumedShouldReflectInsertedRows() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ConsumeRecordMapper mapper = session.getMapper(ConsumeRecordMapper.class);
            assertFalse(mapper.existsConsumed("alert-evaluator", "event-1"));

            ConsumeRecordEntity record = newRecord();
            mapper.upsertConsumed(record);

            assertTrue(mapper.existsConsumed("alert-evaluator", "event-1"));
            assertFalse(mapper.existsConsumed("alert-evaluator", "event-other"));
            assertFalse(mapper.existsConsumed("other-consumer", "event-1"));
        }
    }

    /** failed 行不视为幂等命中：DLQ 重放可重新处理该事件。 */
    @Test
    void failedRowShouldNotMatchExistsConsumed() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ConsumeRecordMapper mapper = session.getMapper(ConsumeRecordMapper.class);
            mapper.upsertFailed("alert-evaluator", "event-1", 3, "evaluation failed");

            assertFalse(mapper.existsConsumed("alert-evaluator", "event-1"));
        }
    }

    /** upsertFailed 写入失败状态与原因（失败留痕）。 */
    @Test
    void upsertFailedShouldWriteStatusAndError() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ConsumeRecordMapper mapper = session.getMapper(ConsumeRecordMapper.class);
            mapper.upsertConsumed(newRecord());

            // MySQL 语义：命中已有行时 ON DUPLICATE KEY UPDATE 返回 2（insert+update）。
            assertTrue(mapper.upsertFailed("alert-evaluator", "event-1", 3, "evaluation failed") >= 1);

            assertFalse(mapper.existsConsumed("alert-evaluator", "event-1"));
            try (var rows = session.getConnection().createStatement().executeQuery(
                    "SELECT status, attempts, last_error FROM message_consume_records"
                            + " WHERE consumer='alert-evaluator' AND event_id='event-1'")) {
                rows.next();
                assertEquals("failed", rows.getString(1));
                assertEquals(3, rows.getInt(2));
                assertEquals("evaluation failed", rows.getString(3));
            }
        }
    }

    /** 重放成功路径：failed 行被 upsertConsumed 翻转为 consumed，恢复幂等命中。 */
    @Test
    void upsertConsumedShouldFlipFailedRowToConsumed() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ConsumeRecordMapper mapper = session.getMapper(ConsumeRecordMapper.class);
            mapper.upsertFailed("alert-evaluator", "event-1", 3, "evaluation failed");
            assertFalse(mapper.existsConsumed("alert-evaluator", "event-1"));

            mapper.upsertConsumed(newRecord());

            assertTrue(mapper.existsConsumed("alert-evaluator", "event-1"));
        }
    }

    /** 聚合查询应按消费者分组并按 created_at 窗口过滤（MVP-14 失败率统计）。 */
    @Test
    void countByConsumerAndStatusShouldGroupAndFilterByWindow() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 12, 0, 0);
        insertRow("alert-notifier", "failed", now.minusMinutes(10));
        insertRow("alert-notifier", "failed", now.minusMinutes(120));
        insertRow("alert-notifier", "consumed", now.minusMinutes(5));
        insertRow("alert-resolved-notifier", "failed", now.minusMinutes(1));
        insertRow("alert-evaluator", "failed", now.minusMinutes(200));

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ConsumeRecordMapper mapper = session.getMapper(ConsumeRecordMapper.class);
            LocalDateTime since = now.minusMinutes(60);

            var failedWindow = toCountMap(mapper.countByConsumerAndStatus("failed", since));
            assertEquals(1L, failedWindow.get("alert-notifier"));
            assertEquals(1L, failedWindow.get("alert-resolved-notifier"));
            assertEquals(2, failedWindow.size());

            var consumedWindow = toCountMap(mapper.countByConsumerAndStatus("consumed", since));
            assertEquals(1L, consumedWindow.get("alert-notifier"));
            assertEquals(1, consumedWindow.size());
        }
    }

    /** H2 与 MySQL 对无引号列标签的大小写折叠不同，聚合结果键统一归一化。 */
    private java.util.Map<String, Long> toCountMap(java.util.List<java.util.Map<String, Object>> rows) {
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row : rows) {
            String consumer = String.valueOf(row.getOrDefault("consumer", row.getOrDefault("CONSUMER", "")));
            Object count = row.getOrDefault("count", row.getOrDefault("COUNT", 0L));
            counts.put(consumer, ((Number) count).longValue());
        }
        return counts;
    }

    /** 插入一条带显式 created_at 的消费记录（窗口过滤测试用）。 */
    private static int rowSequence;

    private void insertRow(String consumer, String status, LocalDateTime createdAt) throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            try (var statement = session.getConnection().prepareStatement(
                    "INSERT INTO message_consume_records (consumer, event_id, status, attempts, created_at)"
                            + " VALUES (?, ?, ?, 0, ?)")) {
                statement.setString(1, consumer);
                statement.setString(2, "event-" + (++rowSequence));
                statement.setString(3, status);
                statement.setObject(4, createdAt);
                statement.executeUpdate();
            }
        }
    }

    private ConsumeRecordEntity newRecord() {
        ConsumeRecordEntity record = new ConsumeRecordEntity();
        record.setConsumer("alert-evaluator");
        record.setEventId("event-1");
        record.setStatus(ConsumeStatus.CONSUMED.ruleValue());
        record.setAttempts(0);
        record.setConsumedAt(LocalDateTime.now());
        return record;
    }
}
