package com.susumonitor.server.module.alert.mapper;

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
 * 验证告警记录分批清理的边界和分批语义（按 triggered_at 业务时间清理）。
 */
class AlertRecordCleanupMapperMybatisTests {

    private PooledDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:alert_record_cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS alert_records");
            statement.execute("""
                    CREATE TABLE alert_records (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        server_id BIGINT NOT NULL,
                        triggered_at TIMESTAMP
                    )
                    """);
        }
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(AlertRecordCleanupMapper.class);
        try (var mapperXml = Resources.getResourceAsReader("mapper/alert/AlertRecordCleanupMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(mapperXml, configuration,
                    "mapper/alert/AlertRecordCleanupMapper.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 验证只删除严格早于 cutoff 的记录，边界行保留。 */
    @Test
    void cleanupShouldDeleteOnlyRowsTriggeredBeforeCutoff() throws Exception {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 0, 0);
        insert("oldest", cutoff.minusDays(2));
        insert("boundary", cutoff);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(1, session.getMapper(AlertRecordCleanupMapper.class).deleteExpiredBatch(cutoff, 10));
            assertEquals(1, countRows());
            assertEquals(0, countByServerId(1L));
            assertEquals(1, countByServerId(2L));
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
            assertEquals(2, session.getMapper(AlertRecordCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(1, session.getMapper(AlertRecordCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(0, session.getMapper(AlertRecordCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(0, countRows());
        }
    }

    private void insert(String tag, LocalDateTime triggeredAt) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO alert_records(server_id, triggered_at) VALUES (?, ?)")) {
            statement.setLong(1, "oldest".equals(tag) || "one".equals(tag) ? 1L : 2L);
            statement.setObject(2, triggeredAt);
            statement.executeUpdate();
        }
    }

    private int countRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM alert_records")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int countByServerId(long serverId) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM alert_records WHERE server_id = ?")) {
            statement.setLong(1, serverId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
