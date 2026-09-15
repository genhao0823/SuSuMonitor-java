package com.susumonitor.server.module.auth.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.susumonitor.server.module.auth.entity.AuthBootstrapStateEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 在显式隔离 MySQL 库中验证 V37 首管理员一次性初始化令牌列的 DDL、
 * saveBootstrapToken / consumeBootstrapToken 的写入语义与 admin_initialized 守卫。
 *
 * <p>H2 单测不承担「令牌密文列读写 + 条件守卫 UPDATE」在 MySQL 方言下的
 * 行为验证职责；本 IT 仅验证 Mapper 与迁移，不做端到端注册（并发不变量由
 * 行锁事务与既有首管理员并发验收保证）。</p>
 */
@ActiveProfiles("test")
@SpringBootTest
@ContextConfiguration(initializers = AuthBootstrapTokenMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class AuthBootstrapTokenMySqlValidationIT {

    private static final String SAMPLE_CIPHER =
            "v1:dGVzdC1jaXBoZXItc2FtcGxlLWZvci1pdC1vbmx5LW5vdC1hLXJlYWwtdG9rZW4";

    @Autowired
    private AuthBootstrapStateMapper authBootstrapStateMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 每个用例前把状态行重置为待初始化并清空令牌列，保证用例互不依赖。 */
    @BeforeEach
    void resetStateRow() {
        jdbcTemplate.update("""
                UPDATE auth_bootstrap_state
                SET admin_initialized = 0,
                    initialized_user_id = NULL,
                    initialized_at = NULL,
                    bootstrap_token_cipher = NULL,
                    bootstrap_token_generated_at = NULL,
                    bootstrap_token_consumed_at = NULL
                WHERE id = 1
                """);
    }

    /** 验证 V37 已执行且三个令牌列均已存在。 */
    @Test
    void v37ShouldAddBootstrapTokenColumns() {
        Integer migrationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '37' AND success = 1
                """, Integer.class);
        Integer cipherColumn = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'auth_bootstrap_state'
                  AND column_name = 'bootstrap_token_cipher'
                """, Integer.class);
        Integer generatedAtColumn = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'auth_bootstrap_state'
                  AND column_name = 'bootstrap_token_generated_at'
                """, Integer.class);
        Integer consumedAtColumn = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'auth_bootstrap_state'
                  AND column_name = 'bootstrap_token_consumed_at'
                """, Integer.class);

        assertEquals(1, migrationCount);
        assertEquals(1, cipherColumn);
        assertEquals(1, generatedAtColumn);
        assertEquals(1, consumedAtColumn);
    }

    /** 验证待初始化行上令牌保存与消费的完整往返：写密文 → 读取 → 消费置空并记录时间。 */
    @Test
    void saveAndConsumeShouldRoundTripOnPendingRow() {
        assertEquals(1, authBootstrapStateMapper.saveBootstrapToken(SAMPLE_CIPHER, LocalDateTime.now()));

        AuthBootstrapStateEntity saved = authBootstrapStateMapper.selectState();
        assertEquals(SAMPLE_CIPHER, saved.getBootstrapTokenCipher());
        assertNotNull(saved.getBootstrapTokenGeneratedAt());
        assertNull(saved.getBootstrapTokenConsumedAt());

        LocalDateTime consumedAt = LocalDateTime.now();
        assertEquals(1, authBootstrapStateMapper.consumeBootstrapToken(consumedAt));

        AuthBootstrapStateEntity consumed = authBootstrapStateMapper.selectState();
        assertNull(consumed.getBootstrapTokenCipher());
        assertNotNull(consumed.getBootstrapTokenConsumedAt());
        assertNotNull(consumed.getBootstrapTokenGeneratedAt());
    }

    /** 验证 admin_initialized=1 时令牌写入被守卫拒绝，存量数据不被覆写。 */
    @Test
    void saveShouldGuardAgainstInitializedRow() {
        assertEquals(1, authBootstrapStateMapper.saveBootstrapToken(SAMPLE_CIPHER, LocalDateTime.now()));
        jdbcTemplate.update("UPDATE auth_bootstrap_state SET admin_initialized = 1 WHERE id = 1");

        assertEquals(0, authBootstrapStateMapper.saveBootstrapToken("v1:other", LocalDateTime.now()));
        assertEquals(SAMPLE_CIPHER, authBootstrapStateMapper.selectState().getBootstrapTokenCipher());
    }

    /** 验证 admin_initialized=1 时令牌消费被守卫拒绝，密文保持原样。 */
    @Test
    void consumeShouldGuardAgainstInitializedRow() {
        assertEquals(1, authBootstrapStateMapper.saveBootstrapToken(SAMPLE_CIPHER, LocalDateTime.now()));
        jdbcTemplate.update("UPDATE auth_bootstrap_state SET admin_initialized = 1 WHERE id = 1");

        assertEquals(0, authBootstrapStateMapper.consumeBootstrapToken(LocalDateTime.now()));
        assertEquals(SAMPLE_CIPHER, authBootstrapStateMapper.selectState().getBootstrapTokenCipher());
        // 附带验证补齐新列后的行锁查询在 MySQL 方言下可正常执行。
        assertNotNull(authBootstrapStateMapper.selectForUpdate());
    }

    /**
     * 隔离库守卫：仅允许本机回环地址 + 名称含 validation 的隔离库，
     * 防止误将验证负载指向开发/生产库（与既有 IT 守卫口径一致）。
     */
    static final class TargetDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
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
