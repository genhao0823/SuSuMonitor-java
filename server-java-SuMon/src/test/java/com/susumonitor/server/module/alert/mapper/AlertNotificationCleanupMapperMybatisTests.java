package com.susumonitor.server.module.alert.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.datasource.pooled.PooledDataSource;

/**
 * 验证通知投递清理 Mapper 的边界删除与分批语义（H2，独立库）。
 */
class AlertNotificationCleanupMapperMybatisTests {

    private DataSource dataSource;

    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:alert_notification_cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS alert_notifications");
            statement.execute("""
                    CREATE TABLE alert_notifications (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        alert_record_id BIGINT NOT NULL,
                        channel VARCHAR(20) NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(AlertNotificationCleanupMapper.class);
        try (var mapperXml = Resources.getResourceAsReader("mapper/alert/AlertNotificationCleanupMapper.xml")) {
            new XMLMapperBuilder(mapperXml, configuration,
                    "mapper/alert/AlertNotificationCleanupMapper.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 验证只删除严格早于 cutoff 的记录，边界行保留。 */
    @Test
    void cleanupShouldDeleteOnlyRowsCreatedBeforeCutoff() throws Exception {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 0, 0);
        insert("oldest", cutoff.minusDays(2));
        insert("boundary", cutoff);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(1, session.getMapper(AlertNotificationCleanupMapper.class).deleteExpiredBatch(cutoff, 10));
            assertEquals(1, countRows());
            assertEquals(1, countByChannel("boundary"));
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
            assertEquals(2, session.getMapper(AlertNotificationCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(1, session.getMapper(AlertNotificationCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(0, session.getMapper(AlertNotificationCleanupMapper.class).deleteExpiredBatch(cutoff, 2));
            assertEquals(0, countRows());
        }
    }

    private void insert(String channel, LocalDateTime createdAt) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO alert_notifications(alert_record_id, channel, status, created_at) VALUES (?, ?, 'pending', ?)")) {
            statement.setLong(1, 1L);
            statement.setString(2, channel);
            statement.setObject(3, createdAt);
            statement.executeUpdate();
        }
    }

    private int countRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM alert_notifications")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int countByChannel(String channel) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM alert_notifications WHERE channel = ?")) {
            statement.setString(1, channel);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
