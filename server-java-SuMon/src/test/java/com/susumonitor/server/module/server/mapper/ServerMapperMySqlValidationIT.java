package com.susumonitor.server.module.server.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.susumonitor.server.module.server.entity.ServerEntity;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 在显式隔离 MySQL 下执行 ServerMapper XML，验证排序、分页和软删除过滤的真实数据库行为。
 */
@ActiveProfiles("metrics-validation")
@SpringBootTest
@ContextConfiguration(initializers = ServerMapperMySqlValidationIT.TargetDatabaseGuard.class)
class ServerMapperMySqlValidationIT {

    private static final String TEST_PREFIX = "j2-sort-validation";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 7, 23, 10, 0);

    @Autowired
    private ServerMapper serverMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 验证六个白名单字段的升降序均由真实 XML 执行，而不是由 Mockito 模拟。 */
    @Test
    void selectActiveServersShouldHonorEveryWhitelistedSortFieldAndDirection() {
        withTestRows(() -> {
            assertOrder("id", "asc", List.of("alpha", "bravo", "charlie"));
            assertOrder("id", "desc", List.of("charlie", "bravo", "alpha"));
            assertOrder("name", "asc", List.of("alpha", "bravo", "charlie"));
            assertOrder("name", "desc", List.of("charlie", "bravo", "alpha"));
            assertOrder("host", "asc", List.of("charlie", "alpha", "bravo"));
            assertOrder("host", "desc", List.of("bravo", "alpha", "charlie"));
            assertOrder("status", "asc", List.of("bravo", "alpha", "charlie"));
            assertOrder("status", "desc", List.of("charlie", "alpha", "bravo"));
            assertOrder("created_at", "asc", List.of("charlie", "alpha", "bravo"));
            assertOrder("created_at", "desc", List.of("bravo", "alpha", "charlie"));
            assertOrder("updated_at", "asc", List.of("alpha", "charlie", "bravo"));
            assertOrder("updated_at", "desc", List.of("bravo", "charlie", "alpha"));
        });
    }

    /** 验证排序在分页前执行，并且不同页面不重复、不遗漏。 */
    @Test
    void selectActiveServersShouldSortBeforeApplyingPagination() {
        withTestRows(() -> {
            List<String> firstPage = names(serverMapper.selectActiveServers(
                    new Page<>(1, 2), TEST_PREFIX, "name", "asc"));
            Page<ServerEntity> secondPager = new Page<>(2, 2);
            List<String> secondPage = names(serverMapper.selectActiveServers(
                    secondPager, TEST_PREFIX, "name", "asc"));

            assertEquals(List.of("alpha", "bravo"), firstPage);
            assertEquals(List.of("charlie"), secondPage);
            assertEquals(3L, secondPager.getTotal());
        });
    }

    /** 验证关键词查询与列表 count 使用相同的未软删除数据集。 */
    @Test
    void keywordSortingShouldExcludeSoftDeletedRows() {
        withTestRows(() -> {
            Page<ServerEntity> pager = new Page<>(1, 20);
            List<ServerEntity> result = serverMapper.selectActiveServers(pager, "j2-sort", "name", "asc");

            assertEquals(3L, pager.getTotal());
            assertEquals(3, result.size());
            assertTrue(result.stream().noneMatch(row -> Boolean.TRUE.equals(row.getDeleted())));
            assertFalse(result.stream().anyMatch(row -> row.getName().contains("deleted")));
        });
    }

    /**
     * 验证轮换宽限期真实 SQL 语义（2026-09-14 联调缺陷回归，
     * 见 Bug-fix/2026-09-14-agent-token-rotate-500-and-grace-column.md）：
     * prev 列必须保存旧哈希（SET 求值顺序缺陷回归防线），grace_until 必须晚于轮换时间。
     */
    @Test
    void rotateAgentTokenShouldPreservePreviousHashAndRegisterGraceWindow() {
        withTestRows(() -> {
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM servers WHERE name = ?", Long.class, TEST_PREFIX + "-alpha");
            String oldHash = "sha256:" + "a".repeat(64);
            LocalDateTime registeredAt = LocalDateTime.of(2026, 7, 23, 10, 0);
            jdbcTemplate.update(
                    "UPDATE servers SET agent_token_hash = ?, agent_token_created_at = ? WHERE id = ?",
                    oldHash, registeredAt, id);

            LocalDateTime rotatedAt = LocalDateTime.of(2026, 7, 23, 11, 0);
            String newHash = "sha256:" + "b".repeat(64);
            LocalDateTime graceUntil = rotatedAt.plusSeconds(300);

            assertEquals(1, serverMapper.rotateAgentToken(id, newHash, rotatedAt, graceUntil));

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT agent_token_hash, agent_token_hash_prev, agent_token_grace_until,"
                            + " agent_token_rotated_at, agent_token_revoked_at FROM servers WHERE id = ?", id);
            // 71 字符摘要必须完整落库（V36 列宽修复回归），且 prev 为旧哈希而非新哈希。
            assertEquals(newHash, row.get("agent_token_hash"));
            assertEquals(oldHash, row.get("agent_token_hash_prev"));
            assertEquals(graceUntil, row.get("agent_token_grace_until"));
            assertEquals(rotatedAt, row.get("agent_token_rotated_at"));
            assertNull(row.get("agent_token_revoked_at"));

            // revoke 必须同时清空宽限两列：撤销后旧 Token 立即失效。
            LocalDateTime revokedAt = rotatedAt.plusMinutes(1);
            assertEquals(1, serverMapper.revokeAgentToken(id, revokedAt));
            row = jdbcTemplate.queryForMap(
                    "SELECT agent_token_hash_prev, agent_token_grace_until, agent_token_revoked_at"
                            + " FROM servers WHERE id = ?", id);
            assertNull(row.get("agent_token_hash_prev"));
            assertNull(row.get("agent_token_grace_until"));
            assertEquals(revokedAt, row.get("agent_token_revoked_at"));
        });
    }

    /** 验证真实软删除会保留记录、写入删除字段，并在 active 条件下幂等失效。 */
    @Test
    void softDeleteShouldSetDeletionFieldsAndBeIdempotentForActiveCondition() {
        withTestRows(() -> {
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM servers WHERE name = ?", Long.class, TEST_PREFIX + "-alpha");
            LocalDateTime deletedAt = LocalDateTime.of(2026, 7, 23, 12, 0);
            String deleteToken = UUID.randomUUID().toString();

            assertEquals(1, serverMapper.softDeleteActiveServer(id, deletedAt, deleteToken));
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT id, deleted, deleted_at, delete_token FROM servers WHERE id = ?", id);
            assertEquals(id, ((Number) row.get("id")).longValue());
            assertEquals(Boolean.TRUE, row.get("deleted"));
            assertEquals(deletedAt, row.get("deleted_at"));
            assertEquals(deleteToken, row.get("delete_token"));
            assertEquals(0, serverMapper.softDeleteActiveServer(id, deletedAt.plusMinutes(1), "second-token"));
        });
    }

    /** 验证详情、状态、SSH、主机密钥和 Agent Token 的 active 查询均过滤软删除行。 */
    @Test
    void allActiveServerReadsShouldExcludeSoftDeletedRow() {
        withTestRows(() -> {
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM servers WHERE name = ?", Long.class, TEST_PREFIX + "-alpha");
            assertEquals(1, serverMapper.softDeleteActiveServer(id, CREATED_AT, UUID.randomUUID().toString()));

            assertNull(serverMapper.selectActiveServerById(id));
            assertNull(serverMapper.selectActiveServerWithCredentialsById(id));
            assertNull(serverMapper.selectActiveServerStatusById(id));
            assertNull(serverMapper.selectActiveServerHostKeyById(id));
            assertNull(serverMapper.selectActiveServerSshById(id));
            assertNull(serverMapper.selectActiveServerAgentTokenById(id));
        });
    }

    /** 在事务中准备测试数据，测试结束回滚，避免残留业务记录。 */
    private void withTestRows(Runnable assertion) {
        jdbcTemplate.execute("START TRANSACTION");
        try {
            insertRows();
            assertion.run();
        } finally {
            jdbcTemplate.execute("ROLLBACK");
        }
    }

    /** 插入三条有效记录和一条软删除记录，字段顺序故意交叉。 */
    private void insertRows() {
        insert("alpha", "10.0.0.20", "online", CREATED_AT.plusMinutes(2), CREATED_AT.plusMinutes(1), false);
        insert("bravo", "10.0.0.30", "offline", CREATED_AT.plusMinutes(3), CREATED_AT.plusMinutes(3), false);
        insert("charlie", "10.0.0.10", "unknown", CREATED_AT, CREATED_AT.plusMinutes(2), false);
        insert("deleted", "10.0.0.40", "online", CREATED_AT.plusMinutes(4), CREATED_AT.plusMinutes(4), true);
    }

    /** 插入单条最小服务器记录，避免写入 SSH 凭据和 Agent Token。 */
    private void insert(String name, String host, String status, LocalDateTime createdAt,
            LocalDateTime updatedAt, boolean deleted) {
        jdbcTemplate.update("""
                INSERT INTO servers
                (name, host, description, status, ssh_host, ssh_port, ssh_user, ssh_auth_type,
                 agent_status, deleted, deleted_at, delete_token, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 22, 'j2-test', 'password', 'offline', ?, ?, ?, ?, ?)
                """, TEST_PREFIX + "-" + name, TEST_PREFIX + "-" + host, TEST_PREFIX,
                status, host, deleted, deleted ? createdAt : null, deleted ? "j2-deleted" : "ACTIVE",
                createdAt, updatedAt);
    }

    /** 按真实 Mapper 返回的主键顺序断言排序结果。 */
    private void assertOrder(String sortBy, String sortOrder, List<String> expected) {
        assertEquals(expected, names(serverMapper.selectActiveServers(new Page<>(1, 20), TEST_PREFIX, sortBy, sortOrder)));
    }


    /** 将 Mapper 实体结果转换为测试记录名称顺序，避免依赖自增 ID。 */
    private List<String> names(List<ServerEntity> rows) {
        return rows.stream().map(ServerEntity::getName)
                .map(name -> name.substring(TEST_PREFIX.length() + 1)).toList();
    }

    /** 在 Spring 创建数据源之前校验真实 MySQL 验收目标。 */
    static final class TargetDatabaseGuard
            implements org.springframework.context.ApplicationContextInitializer<
                    org.springframework.context.ConfigurableApplicationContext> {

        @Override
        public void initialize(org.springframework.context.ConfigurableApplicationContext applicationContext) {
            if (!"true".equalsIgnoreCase(System.getenv("RUN_MYSQL_VALIDATION_TESTS"))) {
                throw new IllegalStateException("MySQL validation requires RUN_MYSQL_VALIDATION_TESTS=true");
            }
            String host = System.getenv("DB_HOST");
            String database = System.getenv("DB_NAME");
            if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))) {
                throw new IllegalStateException("MySQL validation requires a local DB_HOST");
            }
            if (database == null || database.isBlank()
                    || "susumonitor".equalsIgnoreCase(database)
                    || !database.toLowerCase().contains("validation")) {
                throw new IllegalStateException("MySQL validation requires an isolated validation DB_NAME");
            }
        }
    }
}
