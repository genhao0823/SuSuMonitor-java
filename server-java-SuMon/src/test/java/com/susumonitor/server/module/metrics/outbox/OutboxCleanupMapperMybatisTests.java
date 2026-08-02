package com.susumonitor.server.module.metrics.outbox;

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
 * 验证已发布 Outbox 分批清理的状态、边界和排序语义。
 */
class OutboxCleanupMapperMybatisTests {

    private PooledDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:outbox_cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS message_outbox");
            statement.execute("""
                    CREATE TABLE message_outbox (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        event_id VARCHAR(36) NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        published_at TIMESTAMP
                    )
                    """);
        }
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(OutboxCleanupMapper.class);
        try (var mapperXml = Resources.getResourceAsReader("mapper/metrics/OutboxCleanupMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(mapperXml, configuration,
                    "mapper/metrics/OutboxCleanupMapper.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void cleanupShouldDeleteOnlyOldPublishedRowsInOldestOrder() throws Exception {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 0, 0);
        insert("oldest", "published", cutoff.minusDays(2));
        insert("next", "published", cutoff.minusDays(1));
        insert("boundary", "published", cutoff);
        insert("pending", "pending", cutoff.minusDays(3));

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(1, session.getMapper(OutboxCleanupMapper.class).deletePublishedBeforeBatch(cutoff, 1));
            assertEquals(3, countRows());
            assertEquals(0, countByEventId("oldest"));
            assertEquals(1, countByEventId("next"));
            assertEquals(1, countByEventId("boundary"));
            assertEquals(1, countByEventId("pending"));

            assertEquals(1, session.getMapper(OutboxCleanupMapper.class).deletePublishedBeforeBatch(cutoff, 10));
            assertEquals(0, countByEventId("next"));
            assertEquals(1, countByEventId("boundary"));
            assertEquals(1, countByEventId("pending"));
        }
    }

    private void insert(String eventId, String status, LocalDateTime publishedAt) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO message_outbox(event_id, status, published_at) VALUES (?, ?, ?)")) {
            statement.setString(1, eventId);
            statement.setString(2, status);
            statement.setObject(3, publishedAt);
            statement.executeUpdate();
        }
    }

    private int countRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM message_outbox")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int countByEventId(String eventId) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM message_outbox WHERE event_id = ?")) {
            statement.setString(1, eventId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
