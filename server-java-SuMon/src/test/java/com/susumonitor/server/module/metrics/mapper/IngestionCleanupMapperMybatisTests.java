package com.susumonitor.server.module.metrics.mapper;

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
 * 验证指标幂等接收记录分批清理的边界和分批语义。
 */
class IngestionCleanupMapperMybatisTests {

    private PooledDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:ingestion_cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS metrics_ingestions");
            statement.execute("""
                    CREATE TABLE metrics_ingestions (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        server_id BIGINT NOT NULL,
                        message_id VARCHAR(36) NOT NULL,
                        created_at TIMESTAMP
                    )
                    """);
        }
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(IngestionCleanupMapper.class);
        try (var mapperXml = Resources.getResourceAsReader("mapper/metrics/IngestionCleanupMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(mapperXml, configuration,
                    "mapper/metrics/IngestionCleanupMapper.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 验证只删除严格早于 cutoff 的记录，且按批次上限删除。 */
    @Test
    void cleanupShouldDeleteOnlyRowsOlderThanCutoff() throws Exception {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 0, 0);
        insert("oldest", cutoff.minusDays(2));
        insert("boundary", cutoff);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(1, session.getMapper(IngestionCleanupMapper.class).deleteExpiredBatch(cutoff, 10));
            assertEquals(1, countRows());
            assertEquals(0, countByMessageId("oldest"));
            assertEquals(1, countByMessageId("boundary"));
        }
    }

    /** 验证分批删除时每批最多删除 batchSize 行。 */
    @Test
    void cleanupShouldDeleteAtMostBatchSizeRowsPerBatch() throws Exception {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 0, 0);
        insert("one", cutoff.minusDays(1));
        insert("two", cutoff.minusDays(1));
        insert("three", cutoff.minusDays(1));

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(2, session.getMapper(IngestionCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(1, session.getMapper(IngestionCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(0, session.getMapper(IngestionCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(0, countRows());
        }
    }

    private void insert(String messageId, LocalDateTime createdAt) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO metrics_ingestions(server_id, message_id, created_at) VALUES (1, ?, ?)")) {
            statement.setString(1, messageId);
            statement.setObject(2, createdAt);
            statement.executeUpdate();
        }
    }

    private int countRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM metrics_ingestions")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int countByMessageId(String messageId) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM metrics_ingestions WHERE message_id = ?")) {
            statement.setString(1, messageId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
