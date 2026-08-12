package com.susumonitor.server.module.server.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.susumonitor.server.module.server.entity.SshTestHistoryEntity;
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
 * 验证 SSH 测试历史 XML Mapper 的真实参数绑定、主键回填与最近记录排序。
 *
 * <p>测试直接加载生产 Mapper XML，防止 Mockito 测试遗漏 MyBatis 参数名或 keyProperty 配置错误。</p>
 */
class SshTestHistoryMapperMybatisTests {

    private SqlSessionFactory sqlSessionFactory;

    /** 每个用例初始化独立 H2 库，确保 DML 断言不受其他用例影响。 */
    @BeforeEach
    void setUp() throws Exception {
        PooledDataSource dataSource = new PooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:ssh_test_history_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ssh_test_history");
            statement.execute("""
                    CREATE TABLE ssh_test_history (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        server_id BIGINT NOT NULL,
                        connected BOOLEAN NOT NULL,
                        error_code INT NULL,
                        host_key_algorithm VARCHAR(50) NULL,
                        host_key_fingerprint VARCHAR(200) NULL,
                        auth_type VARCHAR(20) NOT NULL,
                        duration_ms BIGINT NOT NULL,
                        tested_at TIMESTAMP NOT NULL,
                        created_at TIMESTAMP NOT NULL
                    )
                    """);
        }

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(SshTestHistoryMapper.class);
        try (var mapperXml = Resources.getResourceAsReader("mapper/server/SshTestHistoryMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(mapperXml, configuration,
                    "mapper/server/SshTestHistoryMapper.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** INSERT 应绑定 entity 前缀参数，并将数据库生成的 ID 回写到实体。 */
    @Test
    void insertShouldGenerateId() {
        SshTestHistoryEntity entity = newSuccessEntity();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SshTestHistoryMapper mapper = session.getMapper(SshTestHistoryMapper.class);
            assertEquals(1, mapper.insert(entity));
        }

        assertNotNull(entity.getId());
    }

    /** 最近记录查询应按测试时间倒序并应用 limit，成功记录保留主机公钥信息。 */
    @Test
    void selectRecentShouldOrderByTestedAtDescAndLimit() {
        SshTestHistoryEntity first = insertEntity(newSuccessEntity(LocalDateTime.now().minusMinutes(5)));
        SshTestHistoryEntity second = insertEntity(newSuccessEntity(LocalDateTime.now().minusMinutes(1)));

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SshTestHistoryMapper mapper = session.getMapper(SshTestHistoryMapper.class);
            List<SshTestHistoryEntity> records = mapper.selectRecentByServerId(1L, 1);
            assertEquals(1, records.size());
            assertEquals(second.getId(), records.getFirst().getId());
            assertEquals("ssh-rsa", records.getFirst().getHostKeyAlgorithm());
            assertEquals("SHA256:test", records.getFirst().getHostKeyFingerprint());
            assertNull(records.getFirst().getErrorCode());
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SshTestHistoryMapper mapper = session.getMapper(SshTestHistoryMapper.class);
            List<SshTestHistoryEntity> records = mapper.selectRecentByServerId(1L, 10);
            assertEquals(2, records.size());
            assertEquals(second.getId(), records.get(0).getId());
            assertEquals(first.getId(), records.get(1).getId());
        }
    }

    /** 失败记录应保留错误码，且按服务器隔离查询。 */
    @Test
    void selectRecentShouldFilterByServerAndKeepErrorCode() {
        SshTestHistoryEntity failure = newFailureEntity(50002);
        insertEntity(failure);
        insertEntity(newSuccessEntity(LocalDateTime.now()));

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SshTestHistoryMapper mapper = session.getMapper(SshTestHistoryMapper.class);
            List<SshTestHistoryEntity> records = mapper.selectRecentByServerId(99L, 10);
            assertEquals(0, records.size());
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SshTestHistoryMapper mapper = session.getMapper(SshTestHistoryMapper.class);
            List<SshTestHistoryEntity> records = mapper.selectRecentByServerId(1L, 10);
            assertEquals(2, records.size());
            SshTestHistoryEntity failed = records.stream().filter(r -> !r.getConnected()).findFirst().orElseThrow();
            assertEquals(50002, failed.getErrorCode());
            assertNull(failed.getHostKeyAlgorithm());
        }
    }

    private SshTestHistoryEntity insertEntity(SshTestHistoryEntity entity) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(1, session.getMapper(SshTestHistoryMapper.class).insert(entity));
        }
        assertNotNull(entity.getId());
        return entity;
    }

    private SshTestHistoryEntity newSuccessEntity() {
        return newSuccessEntity(LocalDateTime.now());
    }

    private SshTestHistoryEntity newSuccessEntity(LocalDateTime testedAt) {
        SshTestHistoryEntity entity = new SshTestHistoryEntity();
        entity.setServerId(1L);
        entity.setConnected(true);
        entity.setErrorCode(null);
        entity.setHostKeyAlgorithm("ssh-rsa");
        entity.setHostKeyFingerprint("SHA256:test");
        entity.setAuthType("password");
        entity.setDurationMs(123L);
        entity.setTestedAt(testedAt);
        entity.setCreatedAt(testedAt);
        return entity;
    }

    private SshTestHistoryEntity newFailureEntity(int errorCode) {
        SshTestHistoryEntity entity = new SshTestHistoryEntity();
        entity.setServerId(1L);
        entity.setConnected(false);
        entity.setErrorCode(errorCode);
        entity.setAuthType("password");
        entity.setDurationMs(0L);
        entity.setTestedAt(LocalDateTime.now());
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
