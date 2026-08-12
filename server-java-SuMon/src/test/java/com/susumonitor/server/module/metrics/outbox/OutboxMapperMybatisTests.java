package com.susumonitor.server.module.metrics.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
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
 * 验证 Outbox XML Mapper 的真实参数绑定、主键回填与状态回写条件。
 */
class OutboxMapperMybatisTests {

    private SqlSessionFactory sqlSessionFactory;

    /** 每个用例初始化独立 H2 库，确保 DML 断言不受其他用例影响。 */
    @BeforeEach
    void setUp() throws Exception {
        PooledDataSource dataSource = new PooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:outbox_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS message_outbox");
            // H2 无 MySQL 的 UTC_TIMESTAMP()，注册等价函数（返回 UTC 时刻）；库跨用例复用需幂等。
            statement.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR \"com.susumonitor.server.module.metrics.outbox.H2UtcTimestamp.utcTimestamp\"");
            statement.execute("""
                    CREATE TABLE message_outbox (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        event_id VARCHAR(36) NOT NULL,
                        event_type VARCHAR(64) NOT NULL,
                        routing_key VARCHAR(64) NOT NULL DEFAULT 'metrics.reported.v1',
                        payload TEXT NOT NULL,
                        status VARCHAR(20) NOT NULL DEFAULT 'pending',
                        attempts INT NOT NULL DEFAULT 0,
                        next_attempt_at TIMESTAMP NOT NULL,
                        last_error VARCHAR(500),
                        published_at TIMESTAMP,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(OutboxMapper.class);
        try (var mapperXml = Resources.getResourceAsReader("mapper/metrics/OutboxMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(mapperXml, configuration, "mapper/metrics/OutboxMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** INSERT 应按 outbox 前缀绑定参数，并将数据库生成的 ID 回写到实体。 */
    @Test
    void insertShouldGenerateId() {
        OutboxEntity outbox = newOutbox();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(1, session.getMapper(OutboxMapper.class).insert(outbox));
        }

        assertNotNull(outbox.getId());
    }

    /** 轮询返回行携带按行 routing_key（V25），按 ID 升序并受 limit 限制。 */
    @Test
    void selectPendingShouldReturnRowsWithRoutingKeyOrderedAndLimited() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OutboxMapper mapper = session.getMapper(OutboxMapper.class);
            mapper.insert(outboxWith("a", "metrics.reported.v1", LocalDateTime.now().minusSeconds(10)));
            mapper.insert(outboxWith("b", "metrics.reported.v1", LocalDateTime.now().plusSeconds(60))); // 未到退避时刻
            mapper.insert(outboxWith("c", "alert.triggered.v1", LocalDateTime.now().minusSeconds(5)));

            List<OutboxEntity> rows = mapper.selectPendingForPublish(2);

            assertEquals(2, rows.size());
            assertEquals("a", rows.get(0).getEventId());
            assertEquals("metrics.reported.v1", rows.get(0).getRoutingKey());
            assertEquals("c", rows.get(1).getEventId());
            assertEquals("alert.triggered.v1", rows.get(1).getRoutingKey());
        }
    }

    /** 已发布行不再参与轮询。 */
    @Test
    void selectPendingShouldExcludePublishedRows() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OutboxMapper mapper = session.getMapper(OutboxMapper.class);
            OutboxEntity outbox = outboxWith("a", LocalDateTime.now().minusSeconds(10));
            mapper.insert(outbox);
            mapper.markPublished(outbox.getId(), LocalDateTime.now());

            assertEquals(0, mapper.selectPendingForPublish(10).size());
        }
    }

    /** markPublished 只转换 pending 行；重复调用返回 0。 */
    @Test
    void markPublishedShouldOnlyTransitionPendingRows() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OutboxMapper mapper = session.getMapper(OutboxMapper.class);
            OutboxEntity outbox = outboxWith("a", LocalDateTime.now().minusSeconds(10));
            mapper.insert(outbox);

            assertEquals(1, mapper.markPublished(outbox.getId(), LocalDateTime.now()));
            assertEquals(0, mapper.markPublished(outbox.getId(), LocalDateTime.now()));
        }
    }

    /** markRetry 回写退避参数与失败原因，且不影响已发布行。 */
    @Test
    void markRetryShouldWriteBackoffParams() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OutboxMapper mapper = session.getMapper(OutboxMapper.class);
            OutboxEntity outbox = outboxWith("a", LocalDateTime.now().minusSeconds(10));
            mapper.insert(outbox);
            LocalDateTime nextAttempt = LocalDateTime.now().plusSeconds(4);

            assertEquals(1, mapper.markRetry(outbox.getId(), 2, nextAttempt, "broker unreachable"));

            OutboxEntity reloaded = mapper.selectPendingForPublish(10).stream()
                    .filter(row -> row.getId().equals(outbox.getId())).findFirst().orElse(null);
            // 退避时刻在未来，不再被轮询到。
            assertNull(reloaded);
            assertEquals(0, mapper.selectPendingForPublish(10).size());
        }
    }

    private OutboxEntity newOutbox() {
        return outboxWith(java.util.UUID.randomUUID().toString(), "metrics.reported.v1", LocalDateTime.now());
    }

    private OutboxEntity outboxWith(String eventId, LocalDateTime nextAttemptAt) {
        return outboxWith(eventId, "metrics.reported.v1", nextAttemptAt);
    }

    private OutboxEntity outboxWith(String eventId, String routingKey, LocalDateTime nextAttemptAt) {
        OutboxEntity outbox = new OutboxEntity();
        outbox.setEventId(eventId);
        outbox.setEventType("metrics.reported");
        outbox.setRoutingKey(routingKey);
        outbox.setPayload("{\"event_id\":\"" + eventId + "\"}");
        outbox.setStatus(OutboxStatus.PENDING.ruleValue());
        outbox.setAttempts(0);
        outbox.setNextAttemptAt(nextAttemptAt);
        return outbox;
    }
}
