package com.susumonitor.server.config;

import com.susumonitor.server.websocket.TerminalProtocolValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-level typed configuration for SuSuMonitor.
 */
// 启用配置属性校验，使无效安全配置在应用启动阶段直接失败。
@Validated
// 将 susumonitor 前缀下的配置绑定到当前类型。
@ConfigurationProperties(prefix = "susumonitor")
public class AppProperties {

    // 递归校验 JWT 密钥和有效期配置。
    @Valid
    private final Jwt jwt = new Jwt();

    // 递归校验 AES-GCM 密钥，使缺失的加密配置在应用启动阶段直接失败。
    @Valid
    private final Security security = new Security();

    private final Agent agent = new Agent();

    // 递归校验 SSH 出站访问、超时和并发限制配置。
    @Valid
    private final Ssh ssh = new Ssh();

    // 递归校验 Metrics 保留周期和清理批次配置。
    @Valid
    private final Metrics metrics = new Metrics();

    /** 终端会话的单 JVM 资源和超时边界。 */
    @Valid
    private final Terminal terminal = new Terminal();

    /** 递归校验 Outbox 发布器与 RabbitMQ 拓扑配置。 */
    @Valid
    private final Rabbitmq rabbitmq = new Rabbitmq();

    // 递归校验 CORS 允许的前端 Origin 白名单。
    @Valid
    private final Cors cors = new Cors();

    public Jwt getJwt() {
        return jwt;
    }

    public Security getSecurity() {
        return security;
    }

    public Agent getAgent() {
        return agent;
    }

    public Ssh getSsh() {
        return ssh;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public Rabbitmq getRabbitmq() {
        return rabbitmq;
    }

    public Cors getCors() {
        return cors;
    }

    public static class Jwt {

        // JWT 密钥必须通过本机外部配置提供，不能使用空值启动。
        @NotBlank(message = "JWT secret must not be blank")
        private String secret;

        // JWT 有效期必须为正数，默认值为 72 小时（三天）。
        @Positive(message = "JWT expiration hours must be greater than zero")
        private int expireHours = 72;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getExpireHours() {
            return expireHours;
        }

        public void setExpireHours(int expireHours) {
            this.expireHours = expireHours;
        }
    }

    public static class Security {

        // AES-GCM 密钥必须通过外部配置提供，空值会在应用启动校验阶段被拒绝。
        @NotBlank(message = "AES-GCM key must not be blank")
        private String aesGcmKey;

        public String getAesGcmKey() {
            return aesGcmKey;
        }

        public void setAesGcmKey(String aesGcmKey) {
            this.aesGcmKey = aesGcmKey;
        }
    }

    public static class Agent {

        private String registerKey;

        /** 限制单 JVM 已接纳的 Agent WebSocket 总连接数，防止异常建连耗尽内存。 */
        @Min(value = 1, message = "Agent connection limit must be at least one")
        @Max(value = 1024, message = "Agent connection limit must not exceed 1024")
        private int maxConnections = 128;

        /** 限制等待首帧认证的连接数，降低未认证连接占用资源的风险。 */
        @Min(value = 1, message = "Agent unauthenticated connection limit must be at least one")
        @Max(value = 1024, message = "Agent unauthenticated connection limit must not exceed 1024")
        private int maxUnauthenticatedConnections = 32;

        /** 限制单 JVM 内保留的客户端 IP 限流状态数，避免随机 IP 使状态表无界增长。 */
        @Min(value = 1, message = "Agent tracked client IP limit must be at least one")
        @Max(value = 100000, message = "Agent tracked client IP limit must not exceed 100000")
        private int maxTrackedClientIps = 4096;

        /** 限制同一客户端 IP 每分钟可发起的 WebSocket 握手次数。 */
        @Min(value = 1, message = "Agent handshake rate must be at least one per minute")
        @Max(value = 10000, message = "Agent handshake rate must not exceed 10000 per minute")
        private int handshakeRatePerMinute = 10;

        /** 限制每个已认证会话每分钟可发送的心跳数。 */
        @Min(value = 1, message = "Agent heartbeat rate must be at least one per minute")
        @Max(value = 10000, message = "Agent heartbeat rate must not exceed 10000 per minute")
        private int heartbeatRatePerMinute = 12;

        /** 允许短时心跳抖动的突发令牌数。 */
        @Min(value = 1, message = "Agent heartbeat burst must be at least one")
        @Max(value = 10000, message = "Agent heartbeat burst must not exceed 10000")
        private int heartbeatBurst = 3;

        /** 限制每个已认证会话每分钟可发送的指标消息数。 */
        @Min(value = 1, message = "Agent metrics rate must be at least one per minute")
        @Max(value = 10000, message = "Agent metrics rate must not exceed 10000 per minute")
        private int metricsRatePerMinute = 24;

        /** 允许短时 Metrics 采集抖动的突发令牌数。 */
        @Min(value = 1, message = "Agent metrics burst must be at least one")
        @Max(value = 10000, message = "Agent metrics burst must not exceed 10000")
        private int metricsBurst = 6;

        /** 仅信任列表内反向代理转发的客户端 IP，空列表时始终使用 TCP peer IP。 */
        private List<String> trustedProxyCidrs = new ArrayList<>();

        public String getRegisterKey() {
            return registerKey;
        }

        public void setRegisterKey(String registerKey) {
            this.registerKey = registerKey;
        }

        public int getMaxConnections() {
            return maxConnections;
        }

        public void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }

        public int getMaxUnauthenticatedConnections() {
            return maxUnauthenticatedConnections;
        }

        public void setMaxUnauthenticatedConnections(int maxUnauthenticatedConnections) {
            this.maxUnauthenticatedConnections = maxUnauthenticatedConnections;
        }

        public int getMaxTrackedClientIps() {
            return maxTrackedClientIps;
        }

        public void setMaxTrackedClientIps(int maxTrackedClientIps) {
            this.maxTrackedClientIps = maxTrackedClientIps;
        }

        public int getHandshakeRatePerMinute() {
            return handshakeRatePerMinute;
        }

        public void setHandshakeRatePerMinute(int handshakeRatePerMinute) {
            this.handshakeRatePerMinute = handshakeRatePerMinute;
        }

        public int getHeartbeatRatePerMinute() {
            return heartbeatRatePerMinute;
        }

        public void setHeartbeatRatePerMinute(int heartbeatRatePerMinute) {
            this.heartbeatRatePerMinute = heartbeatRatePerMinute;
        }

        public int getHeartbeatBurst() {
            return heartbeatBurst;
        }

        public void setHeartbeatBurst(int heartbeatBurst) {
            this.heartbeatBurst = heartbeatBurst;
        }

        public int getMetricsRatePerMinute() {
            return metricsRatePerMinute;
        }

        public void setMetricsRatePerMinute(int metricsRatePerMinute) {
            this.metricsRatePerMinute = metricsRatePerMinute;
        }

        public int getMetricsBurst() {
            return metricsBurst;
        }

        public void setMetricsBurst(int metricsBurst) {
            this.metricsBurst = metricsBurst;
        }

        public List<String> getTrustedProxyCidrs() {
            return trustedProxyCidrs;
        }

        public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
            this.trustedProxyCidrs = trustedProxyCidrs;
        }
    }

    public static class Ssh {

        // 限制 TCP 连接超时在可控范围内，避免过短抖动或长时间占用资源。
        @Min(value = 1, message = "SSH connect timeout must be at least one second")
        @Max(value = 60, message = "SSH connect timeout must not exceed 60 seconds")
        private int connectTimeoutSeconds = 10;

        // 限制 SSH 握手和认证期间的 socket 读写超时。
        @Min(value = 1, message = "SSH socket timeout must be at least one second")
        @Max(value = 120, message = "SSH socket timeout must not exceed 120 seconds")
        private int socketTimeoutSeconds = 15;

        // 限制一次 SSH 请求的整体等待预算。
        @Min(value = 1, message = "SSH total timeout must be at least one second")
        @Max(value = 180, message = "SSH total timeout must not exceed 180 seconds")
        private int totalTimeoutSeconds = 30;

        // 限制同时执行的 SSH 握手和认证数量。
        @Min(value = 1, message = "SSH connection limit must be at least one")
        @Max(value = 100, message = "SSH connection limit must not exceed 100")
        private int maxConcurrentConnections = 8;

        // 限制一次 DNS 解析可返回的地址数量，避免异常解析结果放大校验和连接成本。
        @Min(value = 1, message = "SSH resolved address limit must be at least one")
        @Max(value = 32, message = "SSH resolved address limit must not exceed 32")
        private int maxResolvedAddresses = 8;

        // 至少配置一个允许的 SSH 端口，空列表会导致配置校验失败。
        @NotEmpty(message = "SSH allowed ports must not be empty")
        private List<Integer> allowedPorts = new ArrayList<>(List.of(22));

        private List<String> allowedCidrs = new ArrayList<>();

        private int idleTimeoutMinutes = 20;

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getSocketTimeoutSeconds() {
            return socketTimeoutSeconds;
        }

        public void setSocketTimeoutSeconds(int socketTimeoutSeconds) {
            this.socketTimeoutSeconds = socketTimeoutSeconds;
        }

        public int getTotalTimeoutSeconds() {
            return totalTimeoutSeconds;
        }

        public void setTotalTimeoutSeconds(int totalTimeoutSeconds) {
            this.totalTimeoutSeconds = totalTimeoutSeconds;
        }

        public int getMaxConcurrentConnections() {
            return maxConcurrentConnections;
        }

        public void setMaxConcurrentConnections(int maxConcurrentConnections) {
            this.maxConcurrentConnections = maxConcurrentConnections;
        }

        public int getMaxResolvedAddresses() {
            return maxResolvedAddresses;
        }

        public void setMaxResolvedAddresses(int maxResolvedAddresses) {
            this.maxResolvedAddresses = maxResolvedAddresses;
        }

        public List<Integer> getAllowedPorts() {
            return allowedPorts;
        }

        public void setAllowedPorts(List<Integer> allowedPorts) {
            this.allowedPorts = allowedPorts;
        }

        public List<String> getAllowedCidrs() {
            return allowedCidrs;
        }

        public void setAllowedCidrs(List<String> allowedCidrs) {
            this.allowedCidrs = allowedCidrs;
        }

        public int getIdleTimeoutMinutes() {
            return idleTimeoutMinutes;
        }

        public void setIdleTimeoutMinutes(int idleTimeoutMinutes) {
            this.idleTimeoutMinutes = idleTimeoutMinutes;
        }
    }

    public static class Metrics {

        /** Metrics 数据保留天数，必须至少保留一天。 */
        @Min(value = 1, message = "Metrics retention days must be at least one")
        private int retentionDays = 10;

        /** Metrics 清理任务 Cron 表达式。 */
        @NotBlank(message = "Metrics cleanup cron must not be blank")
        private String cleanupCron = "0 0 3 * * ?";

        /** 单次清理 SQL 删除的最大记录数。 */
        @Min(value = 1, message = "Metrics cleanup batch size must be at least one")
        @Max(value = 10000, message = "Metrics cleanup batch size must not exceed 10000")
        private int cleanupBatchSize = 1000;

        /** 单次定时任务允许执行的最大批次数。 */
        @Min(value = 1, message = "Metrics cleanup max batches must be at least one")
        @Max(value = 1000, message = "Metrics cleanup max batches must not exceed 1000")
        private int cleanupMaxBatchesPerRun = 100;

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        public String getCleanupCron() {
            return cleanupCron;
        }

        public void setCleanupCron(String cleanupCron) {
            this.cleanupCron = cleanupCron;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public int getCleanupMaxBatchesPerRun() {
            return cleanupMaxBatchesPerRun;
        }

        public void setCleanupMaxBatchesPerRun(int cleanupMaxBatchesPerRun) {
            this.cleanupMaxBatchesPerRun = cleanupMaxBatchesPerRun;
        }
    }

    /** 终端会话资源限制，首版仅在单 JVM 中生效。 */
    public static class Terminal {

        @Min(value = 1, message = "Terminal per-user session limit must be at least one")
        @Max(value = 20, message = "Terminal per-user session limit must not exceed 20")
        private int maxSessionsPerUser = 2;
        @Min(value = 1, message = "Terminal per-server session limit must be at least one")
        @Max(value = 100, message = "Terminal per-server session limit must not exceed 100")
        private int maxSessionsPerServer = 4;
        @Min(value = 1, message = "Terminal operating user limit must be at least one")
        @Max(value = 100, message = "Terminal operating user limit must not exceed 100")
        private int maxOperatingUsers = 5;
        private String cleanupCron = "0 0 * * * ?";
        @Min(value = 1, message = "Terminal global session limit must be at least one")
        @Max(value = 1024, message = "Terminal global session limit must not exceed 1024")
        private int maxSessions = 16;
        @Min(value = 1, message = "Terminal idle timeout must be at least one minute")
        @Max(value = 1440, message = "Terminal idle timeout must not exceed one day")
        private int idleTimeoutMinutes = 20;
        @Min(value = 1, message = "Terminal maximum session duration must be at least one hour")
        @Max(value = 168, message = "Terminal maximum session duration must not exceed one week")
        private int maxSessionHours = 8;
        @Min(value = 1, message = "Terminal open rate must be at least one per minute")
        @Max(value = 10000, message = "Terminal open rate must not exceed 10000 per minute")
        private int openRatePerMinute = 6;
        @Min(value = 1, message = "Terminal open burst must be at least one")
        @Max(value = 10000, message = "Terminal open burst must not exceed 10000")
        private int openBurst = 2;
        @Min(value = 1, message = "Terminal input rate must be at least one per minute")
        @Max(value = 100000, message = "Terminal input rate must not exceed 100000 per minute")
        private int inputRatePerMinute = 600;
        @Min(value = 1, message = "Terminal input burst must be at least one")
        @Max(value = 100000, message = "Terminal input burst must not exceed 100000")
        private int inputBurst = 120;
        @Min(value = 1, message = "Terminal resize rate must be at least one per minute")
        @Max(value = 10000, message = "Terminal resize rate must not exceed 10000 per minute")
        private int resizeRatePerMinute = 60;
        @Min(value = 1, message = "Terminal resize burst must be at least one")
        @Max(value = 10000, message = "Terminal resize burst must not exceed 10000")
        private int resizeBurst = 20;
        @Min(value = 1, message = "Terminal close rate must be at least one per minute")
        @Max(value = 10000, message = "Terminal close rate must not exceed 10000 per minute")
        private int closeRatePerMinute = 30;
        @Min(value = 1, message = "Terminal close burst must be at least one")
        @Max(value = 10000, message = "Terminal close burst must not exceed 10000")
        private int closeBurst = 10;
        // 输出速率必须为正数，避免令牌桶永远无法为 Agent 输出补充容量。
        @Positive(message = "Terminal output rate must be greater than zero")
        private int outputRateBytesPerSecond = 256 * 1024;
        // 输出突发容量必须为正数，避免新会话无法接收第一段 Agent 输出。
        @Positive(message = "Terminal output burst must be greater than zero")
        // 输出突发容量至少容纳协议允许的单条最大输出，避免合法单帧被配置永久拒绝。
        @Min(value = TerminalProtocolValidator.MAX_DATA_BYTES,
                message = "Terminal output burst must be at least the maximum terminal data bytes")
        private int outputBurstBytes = 512 * 1024;
        // 限制浏览器 Monitor 会话单次发送的最长占用时间，防止慢消费者长期阻塞出站写入。
        @Min(value = 1, message = "Terminal monitor send time limit must be at least one millisecond")
        @Max(value = 60000, message = "Terminal monitor send time limit must not exceed 60000 milliseconds")
        private int monitorSendTimeLimitMillis = 5000;
        // 限制浏览器 Monitor 会话的待发送缓冲，超出后由 Spring 终止不可靠连接。
        @Min(value = 1024, message = "Terminal monitor buffer size must be at least 1024 bytes")
        @Max(value = 16 * 1024 * 1024, message = "Terminal monitor buffer size must not exceed 16777216 bytes")
        private int monitorBufferSizeBytes = 256 * 1024;

        public int getMaxSessionsPerUser() { return maxSessionsPerUser; }
        public void setMaxSessionsPerUser(int value) { maxSessionsPerUser = value; }
        public int getMaxSessionsPerServer() { return maxSessionsPerServer; }
        public void setMaxSessionsPerServer(int value) { maxSessionsPerServer = value; }
        public int getMaxOperatingUsers() { return maxOperatingUsers; }
        public void setMaxOperatingUsers(int value) { maxOperatingUsers = value; }
        public String getCleanupCron() { return cleanupCron; }
        public void setCleanupCron(String value) { cleanupCron = value; }
        public int getMaxSessions() { return maxSessions; }
        public void setMaxSessions(int value) { maxSessions = value; }
        public int getIdleTimeoutMinutes() { return idleTimeoutMinutes; }
        public void setIdleTimeoutMinutes(int value) { idleTimeoutMinutes = value; }
        public int getMaxSessionHours() { return maxSessionHours; }
        public void setMaxSessionHours(int value) { maxSessionHours = value; }
        public int getOpenRatePerMinute() { return openRatePerMinute; }
        public void setOpenRatePerMinute(int value) { openRatePerMinute = value; }
        public int getOpenBurst() { return openBurst; }
        public void setOpenBurst(int value) { openBurst = value; }
        public int getInputRatePerMinute() { return inputRatePerMinute; }
        public void setInputRatePerMinute(int value) { inputRatePerMinute = value; }
        public int getInputBurst() { return inputBurst; }
        public void setInputBurst(int value) { inputBurst = value; }
        public int getResizeRatePerMinute() { return resizeRatePerMinute; }
        public void setResizeRatePerMinute(int value) { resizeRatePerMinute = value; }
        public int getResizeBurst() { return resizeBurst; }
        public void setResizeBurst(int value) { resizeBurst = value; }
        public int getCloseRatePerMinute() { return closeRatePerMinute; }
        public void setCloseRatePerMinute(int value) { closeRatePerMinute = value; }
        public int getCloseBurst() { return closeBurst; }
        public void setCloseBurst(int value) { closeBurst = value; }
        public int getOutputRateBytesPerSecond() { return outputRateBytesPerSecond; }
        public void setOutputRateBytesPerSecond(int value) { outputRateBytesPerSecond = value; }
        public int getOutputBurstBytes() { return outputBurstBytes; }
        public void setOutputBurstBytes(int value) { outputBurstBytes = value; }
        /** 返回浏览器 Monitor 会话的发送超时限制。 */
        public int getMonitorSendTimeLimitMillis() { return monitorSendTimeLimitMillis; }
        /** 设置浏览器 Monitor 会话的发送超时限制。 */
        public void setMonitorSendTimeLimitMillis(int value) { monitorSendTimeLimitMillis = value; }
        /** 返回浏览器 Monitor 会话的待发送缓冲上限。 */
        public int getMonitorBufferSizeBytes() { return monitorBufferSizeBytes; }
        /** 设置浏览器 Monitor 会话的待发送缓冲上限。 */
        public void setMonitorBufferSizeBytes(int value) { monitorBufferSizeBytes = value; }
    }

    /**
     * Outbox 发布器与 RabbitMQ 拓扑配置（MVP-10）。
     *
     * <p>连接参数（host/port/username/password/virtual-host）走标准
     * {@code spring.rabbitmq.*}，由 spring-boot-starter-amqp 自动配置；
     * 本嵌套类只承载业务侧参数。enabled=false 时不加载发布器调度、
     * 拓扑声明与就绪检查（测试与降级场景使用）。</p>
     */
    public static class Rabbitmq {

        /** 是否启用 Outbox 发布与 RabbitMQ 就绪检查。 */
        private boolean enabled = true;

        /** 冻结的 Topic Exchange 名（rabbitmq-topology-v1.md §二）。 */
        private String exchange = "susumonitor.events";

        /** 冻结的 Routing Key（metrics.reported.v1）。 */
        private String routingKey = "metrics.reported.v1";

        /** 发布器轮询间隔（毫秒）。 */
        @Min(value = 100, message = "Outbox poll interval must be at least 100 ms")
        @Max(value = 60000, message = "Outbox poll interval must not exceed 60000 ms")
        private long pollIntervalMs = 1000;

        /** 单轮最多选取的待发布行数。 */
        @Min(value = 1, message = "Outbox batch size must be at least one")
        @Max(value = 10000, message = "Outbox batch size must not exceed 10000")
        private int batchSize = 100;

        /** 等待 Broker Confirm 的超时（毫秒）。 */
        @Min(value = 100, message = "Outbox publish timeout must be at least 100 ms")
        @Max(value = 60000, message = "Outbox publish timeout must not exceed 60000 ms")
        private long publishTimeoutMs = 5000;

        /** 指数退避封顶（秒），实际间隔为 min(2^attempts, 该值)。 */
        @Min(value = 1, message = "Outbox max backoff must be at least one second")
        @Max(value = 86400, message = "Outbox max backoff must not exceed 86400 seconds")
        private int maxBackoffSeconds = 300;

        /** 是否启用已发布 Outbox 记录的保留期清理，默认关闭以避免升级即删除历史证据。 */
        private boolean outboxCleanupEnabled;

        /** 已发布 Outbox 记录的保留天数。 */
        @Min(value = 1, message = "Outbox retention days must be at least one")
        @Max(value = 3650, message = "Outbox retention days must not exceed 3650")
        private int outboxRetentionDays = 30;

        /** 已发布 Outbox 清理 cron。 */
        @NotBlank(message = "Outbox cleanup cron must not be blank")
        private String outboxCleanupCron = "0 30 3 * * ?";

        /** 已发布 Outbox 单批清理上限。 */
        @Min(value = 1, message = "Outbox cleanup batch size must be at least one")
        @Max(value = 10000, message = "Outbox cleanup batch size must not exceed 10000")
        private int outboxCleanupBatchSize = 1000;

        /** 单轮已发布 Outbox 清理最多执行的批次数。 */
        @Min(value = 1, message = "Outbox cleanup max batches must be at least one")
        @Max(value = 1000, message = "Outbox cleanup max batches must not exceed 1000")
        private int outboxCleanupMaxBatchesPerRun = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public String getRoutingKey() {
            return routingKey;
        }

        public void setRoutingKey(String routingKey) {
            this.routingKey = routingKey;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getPublishTimeoutMs() {
            return publishTimeoutMs;
        }

        public void setPublishTimeoutMs(long publishTimeoutMs) {
            this.publishTimeoutMs = publishTimeoutMs;
        }

        public int getMaxBackoffSeconds() {
            return maxBackoffSeconds;
        }

        public void setMaxBackoffSeconds(int maxBackoffSeconds) {
            this.maxBackoffSeconds = maxBackoffSeconds;
        }

        public boolean isOutboxCleanupEnabled() {
            return outboxCleanupEnabled;
        }

        public void setOutboxCleanupEnabled(boolean outboxCleanupEnabled) {
            this.outboxCleanupEnabled = outboxCleanupEnabled;
        }

        public int getOutboxRetentionDays() {
            return outboxRetentionDays;
        }

        public void setOutboxRetentionDays(int outboxRetentionDays) {
            this.outboxRetentionDays = outboxRetentionDays;
        }

        public String getOutboxCleanupCron() {
            return outboxCleanupCron;
        }

        public void setOutboxCleanupCron(String outboxCleanupCron) {
            this.outboxCleanupCron = outboxCleanupCron;
        }

        public int getOutboxCleanupBatchSize() {
            return outboxCleanupBatchSize;
        }

        public void setOutboxCleanupBatchSize(int outboxCleanupBatchSize) {
            this.outboxCleanupBatchSize = outboxCleanupBatchSize;
        }

        public int getOutboxCleanupMaxBatchesPerRun() {
            return outboxCleanupMaxBatchesPerRun;
        }

        public void setOutboxCleanupMaxBatchesPerRun(int outboxCleanupMaxBatchesPerRun) {
            this.outboxCleanupMaxBatchesPerRun = outboxCleanupMaxBatchesPerRun;
        }
    }

    /**
     * CORS 跨域配置，控制 REST API 允许的前端 Origin、方法和请求头。
     */
    public static class Cors {

        // 允许的前端 Origin 列表，至少配置一个；空列表在启动校验时失败。
        @NotEmpty(message = "CORS allowed origins must not be empty")
        private List<String> allowedOrigins = new ArrayList<>(
                List.of("http://localhost:5173", "http://127.0.0.1:5173"));

        // 允许的 HTTP 方法，覆盖 REST API 全部操作。
        private List<String> allowedMethods = new ArrayList<>(
                List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));

        // 允许的请求头，包含认证、内容和追踪头。
        private List<String> allowedHeaders = new ArrayList<>(
                List.of("Authorization", "Content-Type", "X-Correlation-ID"));

        // 预检缓存时间（秒），减少浏览器重复 OPTIONS 请求。
        @Min(value = 0, message = "CORS max age must not be negative")
        private long maxAgeSeconds = 3600;

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public List<String> getAllowedMethods() {
            return allowedMethods;
        }

        public void setAllowedMethods(List<String> allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        public List<String> getAllowedHeaders() {
            return allowedHeaders;
        }

        public void setAllowedHeaders(List<String> allowedHeaders) {
            this.allowedHeaders = allowedHeaders;
        }

        public long getMaxAgeSeconds() {
            return maxAgeSeconds;
        }

        public void setMaxAgeSeconds(long maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
        }
    }
}
