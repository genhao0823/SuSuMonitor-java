package com.susumonitor.server.module.alert.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
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
 * 验证告警规则 XML Mapper 的真实参数绑定和主键回填行为。
 *
 * <p>测试直接加载生产 Mapper XML，防止 Mockito 测试遗漏 MyBatis 参数名或 keyProperty 配置错误。</p>
 */
class AlertRuleMapperMybatisTests {

    private SqlSessionFactory sqlSessionFactory;

    /** 每个用例初始化独立 H2 库，确保 DML 断言不受其他用例影响。 */
    @BeforeEach
    void setUp() throws Exception {
        PooledDataSource dataSource = new PooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:alert_rule_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS alert_rules");
            statement.execute("""
                    CREATE TABLE alert_rules (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        server_id BIGINT,
                        metric VARCHAR(30) NOT NULL,
                        operator VARCHAR(5) NOT NULL,
                        threshold_value DECIMAL(12, 2) NOT NULL,
                        level VARCHAR(20) NOT NULL,
                        confirm_count INT NOT NULL DEFAULT 1,
                        notify_email VARCHAR(500),
                        notify_dingtalk VARCHAR(500),
                        notify_webhook VARCHAR(500),
                        enabled BOOLEAN NOT NULL,
                        deleted BOOLEAN NOT NULL DEFAULT FALSE,
                        deleted_at TIMESTAMP NULL,
                        created_by BIGINT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(AlertRuleMapper.class);
        try (var mapperXml = Resources.getResourceAsReader("mapper/alert/AlertRuleMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(mapperXml, configuration,
                    "mapper/alert/AlertRuleMapper.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** INSERT 应按 rule 前缀绑定参数，并将数据库生成的 ID 回写到实体。 */
    @Test
    void insertRuleShouldGenerateId() {
        AlertRuleEntity rule = newRule();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AlertRuleMapper mapper = session.getMapper(AlertRuleMapper.class);
            assertEquals(1, mapper.insertRule(rule));
        }

        assertNotNull(rule.getId());
    }

    /** UPDATE 应修改未软删除规则的配置字段。 */
    @Test
    void updateRuleShouldModifyRow() {
        AlertRuleEntity rule = insertRule();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AlertRuleMapper mapper = session.getMapper(AlertRuleMapper.class);
            assertEquals(1, mapper.updateRule(rule.getId(), new BigDecimal("90"), "critical", false, null, null, null, null));
            AlertRuleEntity updatedRule = mapper.selectActiveRuleById(rule.getId());
            assertEquals(new BigDecimal("90.00"), updatedRule.getThresholdValue());
            assertEquals("critical", updatedRule.getLevel());
            assertEquals(false, updatedRule.getEnabled());
        }
    }

    /** DELETE 使用软删除语义，规则不再出现在未删除查询结果中。 */
    @Test
    void softDeleteRuleShouldHideRow() {
        AlertRuleEntity rule = insertRule();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AlertRuleMapper mapper = session.getMapper(AlertRuleMapper.class);
            assertEquals(1, mapper.softDeleteRule(rule.getId(), java.time.LocalDateTime.now()));
            assertNull(mapper.selectActiveRuleById(rule.getId()));
        }
    }

    /** 活跃规则查重应区分通用规则、指定服务器规则和被排除的当前规则。 */
    @Test
    void existsActiveRuleShouldMatchExactSignature() {
        AlertRuleEntity rule = insertRule();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AlertRuleMapper mapper = session.getMapper(AlertRuleMapper.class);
            assertEquals(true, mapper.existsActiveRule(null, "cpu", ">", new BigDecimal("80"), "warning", null));
            assertEquals(false, mapper.existsActiveRule(1L, "cpu", ">", new BigDecimal("80"), "warning", null));
            assertEquals(false, mapper.existsActiveRule(null, "cpu", ">", new BigDecimal("80"), "warning", rule.getId()));
        }
    }

    private AlertRuleEntity insertRule() {
        AlertRuleEntity rule = newRule();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(1, session.getMapper(AlertRuleMapper.class).insertRule(rule));
        }
        assertNotNull(rule.getId());
        return rule;
    }

    private AlertRuleEntity newRule() {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setMetric("cpu");
        rule.setOperator(">");
        rule.setThresholdValue(new BigDecimal("80"));
        rule.setLevel("warning");
        rule.setConfirmCount(1);
        rule.setEnabled(true);
        rule.setCreatedBy(1L);
        return rule;
    }
}
