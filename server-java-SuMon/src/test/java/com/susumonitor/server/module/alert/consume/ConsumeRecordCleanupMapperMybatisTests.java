package com.susumonitor.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * 验证消费幂等记录分批清理的边界、分批语义和不区分状态删除规则。
 */
class ConsumeRecordCleanupMapperMybatisTests {

    private PooledDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:consume_record_cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS message_consume_records");
            statement.execute("""
                    CREATE TABLE message_consume_records (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        consumer VARCHAR(64) NOT NULL,
                        event_id VARCHAR(36) NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        created_at TIMESTAMP
                    )
                    """);
        }
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ConsumeRecordCleanupMapper.class);
        try (var mapperXml = Resources.getResourceAsReader("mapper/alert/ConsumeRecordCleanupMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(mapperXml, configuration,
                    "mapper/alert/ConsumeRecordCleanupMapper.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 验证 consumed 与 failed 状态行统一按 cutoff 清理，边界行保留。 */
    @Test
    void cleanupShouldDeleteBothStatusesOlderThanCutoff() throws Exception {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 0, 0);
        insert("old-consumed", "consumed", cutoff.minusDays(2));
        insert("old-failed", "failed", cutoff.minusDays(1));
        insert("boundary", "consumed", cutoff);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(2, session.getMapper(ConsumeRecordCleanupMapper.class).deleteExpiredBatch(cutoff, 10));
            assertEquals(1, countRows());
            assertEquals(0, countByEventId("old-consumed"));
            assertEquals(0, countByEventId("old-failed"));
            assertEquals(1, countByEventId("boundary"));
        }
    }

    /** 验证分批删除时每批最多删除 batchSize 行。 */
    @Test
    void cleanupShouldDeleteAtMostBatchSizeRowsPerBatch() throws Exception {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 0, 0);
        insert("one", "consumed", cutoff.minusDays(1));
        insert("two", "failed", cutoff.minusDays(1));
        insert("three", "consumed", cutoff.minusDays(1));

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(2, session.getMapper(ConsumeRecordCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(1, session.getMapper(ConsumeRecordCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(0, session.getMapper(ConsumeRecordCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(0, countRows());
        }
    }

    private void insert(String eventId, String status, LocalDateTime createdAt) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO message_consume_records(consumer, event_id, status, created_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, "alert-evaluator");
            statement.setString(2, eventId);
            statement.setString(3, status);
            statement.setObject(4, createdAt);
            statement.executeUpdate();
        }
    }

    private int countRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM message_consume_records")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int countByEventId(String eventId) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM message_consume_records WHERE event_id = ?")) {
            statement.setString(1, eventId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
