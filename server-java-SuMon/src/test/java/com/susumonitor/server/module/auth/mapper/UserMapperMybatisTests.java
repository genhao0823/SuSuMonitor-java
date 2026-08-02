package com.susumonitor.server.module.auth.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.Statement;
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
 * 验证待审核用户分页/搜索 Mapper 的真实参数绑定：keyword 模糊过滤 + LIMIT 分页。
 */
class UserMapperMybatisTests {

    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        PooledDataSource dataSource = new PooledDataSource(
                "org.h2.Driver", "jdbc:h2:mem:user_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS users");
            statement.execute("""
                    CREATE TABLE users (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(64) NOT NULL,
                        password_hash VARCHAR(100),
                        role VARCHAR(20) NOT NULL,
                        review_status VARCHAR(20) NOT NULL,
                        reviewed_by BIGINT,
                        reviewed_at TIMESTAMP,
                        created_at TIMESTAMP,
                        updated_at TIMESTAMP
                    )
                    """);
        }
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(UserMapper.class);
        try (var mapperXml = Resources.getResourceAsReader("mapper/auth/UserMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(mapperXml, configuration,
                    "mapper/auth/UserMapper.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 无关键字分页：按 created_at ASC 限制 offset/pageSize。 */
    @Test
    void pagePendingUsersWithoutKeywordShouldLimitRows() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            insertPending(mapper, "alice");
            insertPending(mapper, "bob");
            insertPending(mapper, "carol");
            insertReview(mapper, "zoe");

            List<com.susumonitor.server.module.auth.entity.UserEntity> page = mapper.selectPagePendingUsers(null, 0, 2);
            long total = mapper.countPendingUsers(null);

            assertEquals(2, page.size());
            assertEquals("alice", page.get(0).getUsername());
            assertEquals("bob", page.get(1).getUsername());
            assertEquals(3, total); // zoe 已审核不计入
        }
    }

    /** keyword 模糊过滤：匹配中断且仅返回匹配数。 */
    @Test
    void pagePendingUsersWithKeywordShouldFilterByUsername() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            insertPending(mapper, "alice");
            insertPending(mapper, "bob");

            List<com.susumonitor.server.module.auth.entity.UserEntity> page = mapper.selectPagePendingUsers("li", 0, 20);
            long total = mapper.countPendingUsers("li");

            assertEquals(1, page.size());
            assertEquals("alice", page.get(0).getUsername());
            assertEquals(1, total);
        }
    }

    private void insertPending(UserMapper mapper, String username) throws Exception {
        // 纯 MyBatis 环境无 MyBatis-Plus 注入器（BaseMapper.insert 无绑定），用 JDBC 原生插入。
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            try (var stmt = session.getConnection().createStatement()) {
                int rows = stmt.executeUpdate("INSERT INTO users (username, password_hash, role, review_status, created_at)"
                        + " VALUES ('" + username + "', 'hash', 'user', 'pending', CURRENT_TIMESTAMP)");
                if (rows != 1) {
                    throw new IllegalStateException("insert failed for " + username + ", rows=" + rows);
                }
            }
        }
    }

    private void insertReview(UserMapper mapper, String username) throws Exception {
        insertPending(mapper, username);
        // 模拟已审核用户：直接改状态。
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            try (var stmt = session.getConnection().createStatement()) {
                stmt.executeUpdate("UPDATE users SET review_status = 'approved' WHERE username = '" + username + "'");
            }
        }
    }
}