package com.susumonitor.server.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证应用 JWT、AES-GCM 和 SSH 配置的基础 Bean Validation 规则。
 */
class AppPropertiesTests {

    private static final String VALID_TEST_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static final String VALID_AES_GCM_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private Validator validator;

    /**
     * 在每个测试前创建 Jakarta Validation 校验器。
     */
    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    /**
     * 验证空 JWT 密钥不能通过配置校验。
     */
    @Test
    void blankJwtSecretShouldFailValidation() {
        AppProperties properties = properties("", 24);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /**
     * 验证非正数 JWT 有效期不能通过配置校验。
     */
    @Test
    void nonPositiveJwtExpirationShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 0);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /**
     * 验证合法 JWT 配置可以通过基础配置校验。
     */
    @Test
    void validJwtConfigurationShouldPassValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);

        assertTrue(validator.validate(properties).isEmpty());
    }

    /**
     * 验证空 AES-GCM 密钥不能通过启动配置校验。
     */
    @Test
    void blankAesGcmKeyShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getSecurity().setAesGcmKey(" ");

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 验证非正数 SSH 连接超时不能通过配置校验。 */
    @Test
    void nonPositiveSshConnectTimeoutShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getSsh().setConnectTimeoutSeconds(0);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 验证空 SSH 端口白名单不能通过配置校验。 */
    @Test
    void emptySshAllowedPortsShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getSsh().setAllowedPorts(java.util.List.of());

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 验证非正 Metrics 保留天数不能通过配置校验。 */
    @Test
    void nonPositiveMetricsRetentionDaysShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getMetrics().setRetentionDays(0);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 验证非正 Metrics 清理批次大小不能通过配置校验。 */
    @Test
    void nonPositiveMetricsCleanupBatchSizeShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getMetrics().setCleanupBatchSize(0);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 验证非正 Metrics 单轮批次数不能通过配置校验。 */
    @Test
    void nonPositiveMetricsCleanupMaxBatchesShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getMetrics().setCleanupMaxBatchesPerRun(0);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 验证终端清理调度配置的默认值。 */
    @Test
    void terminalCleanupConfigurationShouldHaveHourlyDefault() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);

        assertEquals("0 0 * * * ?", properties.getTerminal().getCleanupCron());
    }

    @Test
    void nonPositiveTerminalInputRateShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getTerminal().setInputRatePerMinute(0);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 验证非正终端输出字节速率不能通过配置校验。 */
    @Test
    void nonPositiveTerminalOutputRateShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getTerminal().setOutputRateBytesPerSecond(0);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 验证终端输出突发容量必须容纳协议允许的最大单帧。 */
    @Test
    void terminalOutputBurstBelowProtocolMaximumShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getTerminal().setOutputBurstBytes(16 * 1024 - 1);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 验证非正 Monitor 发送时限不能通过配置校验。 */
    @Test
    void nonPositiveMonitorSendTimeLimitShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getTerminal().setMonitorSendTimeLimitMillis(0);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /** 验证过小 Monitor 缓冲不能通过配置校验。 */
    @Test
    void tooSmallMonitorBufferShouldFailValidation() {
        AppProperties properties = properties(VALID_TEST_SECRET, 24);
        properties.getTerminal().setMonitorBufferSizeBytes(1023);

        assertFalse(validator.validate(properties).isEmpty());
    }

    /**
     * 创建测试所需的应用配置。
     *
     * @param secret JWT 测试密钥
     * @param expireHours JWT 有效小时数
     * @return 应用配置
     */
    private AppProperties properties(String secret, int expireHours) {
        AppProperties properties = new AppProperties();
        properties.getJwt().setSecret(secret);
        properties.getJwt().setExpireHours(expireHours);
        properties.getSecurity().setAesGcmKey(VALID_AES_GCM_KEY);
        return properties;
    }
}
