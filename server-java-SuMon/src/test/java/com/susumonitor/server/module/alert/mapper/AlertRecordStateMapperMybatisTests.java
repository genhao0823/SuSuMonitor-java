package com.susumonitor.server.module.alert.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.susumonitor.server.module.alert.entity.AlertRecordEntity;
import com.susumonitor.server.module.alert.entity.AlertStateEntity;
import java.math.BigDecimal;
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
 * 验证告警记录和状态 XML Mapper 的真实参数绑定和主键回填行为。
 */
class AlertRecordStateMapperMybatisTests {

    private SqlSessionFactory sqlSessionFactory;

    /** 每个用例初始化独立 H2 库，确保 DML 断言不受其他用例影响。 */
    @BeforeEach
    void setUp() throws Exception {
        PooledDataSource dataSource = new PooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:alert_record_state_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS alert_states");
            statement.execute("DROP TABLE IF EXISTS alert_records");
            statement.execute("""
                    CREATE TABLE alert_records (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        rule_id BIGINT,
                        server_id BIGINT NOT NULL,
                        metric VARCHAR(30) NOT NULL,
                        current_value DECIMAL(12, 2) NOT NULL,
                        threshold_value DECIMAL(12, 2) NOT NULL,
                        level VARCHAR(20) NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        message VARCHAR(500) NOT NULL,
                        read_by BIGINT,
                        read_at TIMESTAMP,
                        triggered_at TIMESTAMP NOT NULL,
                        resolved_at TIMESTAMP,
                        notified_at TIMESTAMP,
                        notify_channels VARCHAR(100),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE alert_states (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        rule_id BIGINT NOT NULL,
                        server_id BIGINT NOT NULL,
                        active BOOLEAN NOT NULL,
                        breach_count INT NOT NULL DEFAULT 0,
                        alert_record_id BIGINT,
                        first_triggered_at TIMESTAMP,
                        last_triggered_at TIMESTAMP,
                        resolved_at TIMESTAMP,
                        version INT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(AlertRecordMapper.class);
        configuration.addMapper(AlertStateMapper.class);
        parseMapper(configuration, "mapper/alert/AlertRecordMapper.xml");
        parseMapper(configuration, "mapper/alert/AlertStateMapper.xml");
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** INSERT 应按 record 前缀绑定参数，并将数据库生成的 ID 回写到实体。 */
    @Test
    void insertRecordShouldGenerateId() {
        AlertRecordEntity record = newRecord();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(1, session.getMapper(AlertRecordMapper.class).insertRecord(record));
        }

        assertNotNull(record.getId());
    }

    /** INSERT 应按 state 前缀绑定参数，并将数据库生成的 ID 回写到实体。 */
    @Test
    void insertStateShouldGenerateId() {
        AlertStateEntity state = newState();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(1, session.getMapper(AlertStateMapper.class).insertState(state));
        }

        assertNotNull(state.getId());
    }

    /** 恢复后 DELETE 应删除状态行；版本不匹配时乐观锁防误删，删除后查询为 null。 */
    @Test
    void deleteStateShouldRemoveRowWithVersionMatch() {
        AlertStateMapper mapper;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            mapper = session.getMapper(AlertStateMapper.class);
            AlertStateEntity state = newState();
            assertEquals(1, mapper.insertState(state));

            // 版本不匹配：不删除（乐观锁）。
            assertEquals(0, mapper.deleteState(state.getId(), 99));
            assertEquals(1, mapper.deleteState(state.getId(), state.getVersion()));
            assertEquals(null, mapper.selectByRuleAndServer(1L, 1L));
        }
    }

    /** 恢复 UPDATE 应把 status 置为 resolved 并写入 resolved_at（V26）。 */
    @Test
    void updateStatusToResolvedShouldPersistResolvedAt() {
        AlertRecordMapper mapper;
        AlertRecordEntity record = newRecord();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            mapper = session.getMapper(AlertRecordMapper.class);
            assertEquals(1, mapper.insertRecord(record));
            LocalDateTime resolvedAt = LocalDateTime.of(2026, 8, 15, 10, 0);
            assertEquals(1, mapper.updateStatusToResolved(record.getId(), resolvedAt));
            AlertRecordEntity loaded = mapper.selectRecordById(record.getId());
            assertEquals("resolved", loaded.getStatus());
            assertEquals(resolvedAt, loaded.getResolvedAt());
            // 已 resolved 的记录再次恢复不更新（status != 'resolved' 守卫）。
            assertEquals(0, mapper.updateStatusToResolved(record.getId(), resolvedAt.plusHours(1)));
        }
    }

    private void parseMapper(Configuration configuration, String resource) throws Exception {
        try (var mapperXml = Resources.getResourceAsReader(resource)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(mapperXml, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
    }

    private AlertRecordEntity newRecord() {
        AlertRecordEntity record = new AlertRecordEntity();
        record.setRuleId(1L);
        record.setServerId(1L);
        record.setMetric("cpu");
        record.setCurrentValue(new BigDecimal("90"));
        record.setThresholdValue(new BigDecimal("80"));
        record.setLevel("warning");
        record.setStatus("unread");
        record.setMessage("cpu > 80 (current: 90)");
        record.setTriggeredAt(LocalDateTime.now());
        return record;
    }

    private AlertStateEntity newState() {
        AlertStateEntity state = new AlertStateEntity();
        state.setRuleId(1L);
        state.setServerId(1L);
        state.setActive(true);
        state.setBreachCount(0);
        state.setAlertRecordId(1L);
        state.setFirstTriggeredAt(LocalDateTime.now());
        state.setLastTriggeredAt(LocalDateTime.now());
        state.setVersion(0);
        return state;
    }
}
