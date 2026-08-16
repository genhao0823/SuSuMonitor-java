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

    /** 告警外部通知（邮件/钉钉/Webhook）开关与发件人配置。 */
    @Valid
    private final Alert alert = new Alert();

    /** SSH 测试历史保留期与清理批次配置。 */
    @Valid
    private final SshTestHistory sshTestHistory = new SshTestHistory();

    /**
     * 获取 JWT 配置。
     *
     * @return JWT 配置
     */
    public Jwt getJwt() {
        return jwt;
    }

    /**
     * 获取安全配置。
     *
     * @return 安全配置
     */
    public Security getSecurity() {
        return security;
    }

    /**
     * 获取 Agent 配置。
     *
     * @return Agent 配置
     */
    public Agent getAgent() {
        return agent;
    }

    /**
     * 获取 SSH 配置。
     *
     * @return SSH 配置
     */
    public Ssh getSsh() {
        return ssh;
    }

    /**
     * 获取 Metrics 配置。
     *
     * @return Metrics 配置
     */
    public Metrics getMetrics() {
        return metrics;
    }

    /**
     * 获取终端会话配置。
     *
     * @return 终端会话配置
     */
    public Terminal getTerminal() {
        return terminal;
    }

    /**
     * 获取 RabbitMQ 配置。
     *
     * @return RabbitMQ 配置
     */
    public Rabbitmq getRabbitmq() {
        return rabbitmq;
    }

    /**
     * 获取 CORS 配置。
     *
     * @return CORS 配置
     */
    public Cors getCors() {
        return cors;
    }

    /**
     * 获取告警外部通知配置。
     *
     * @return 告警外部通知配置
     */
    public Alert getAlert() {
        return alert;
    }

    /**
     * 获取 SSH 测试历史保留期配置。
     *
     * @return SSH 测试历史保留期配置
     */
    public SshTestHistory getSshTestHistory() {
        return sshTestHistory;
    }

    public static class Jwt {

        // JWT 密钥必须通过本机外部配置提供，不能使用空值启动。
        @NotBlank(message = "JWT secret must not be blank")
        private String secret;

        // JWT 有效期必须为正数，默认值为 72 小时（三天）。
        @Positive(message = "JWT expiration hours must be greater than zero")
        private int expireHours = 72;

        /**
         * 获取 JWT 签名密钥。
         *
         * @return JWT 签名密钥
         */
        public String getSecret() {
            return secret;
        }

        /**
         * 设置 JWT 签名密钥。
         *
         * @param secret JWT 签名密钥
         */
        public void setSecret(String secret) {
            this.secret = secret;
        }

        /**
         * 获取 JWT 有效期（小时）。
         *
         * @return JWT 有效期小时数
         */
        public int getExpireHours() {
            return expireHours;
        }

        /**
         * 设置 JWT 有效期（小时）。
         *
         * @param expireHours JWT 有效期小时数
         */
        public void setExpireHours(int expireHours) {
            this.expireHours = expireHours;
        }
    }

    public static class Security {

        // AES-GCM 密钥必须通过外部配置提供，空值会在应用启动校验阶段被拒绝。
        @NotBlank(message = "AES-GCM key must not be blank")
        private String aesGcmKey;

        /**
         * 获取 AES-256-GCM 加密密钥。
         *
         * @return AES-256-GCM 密钥
         */
        public String getAesGcmKey() {
            return aesGcmKey;
        }

        /**
         * 设置 AES-256-GCM 加密密钥。
         *
         * @param aesGcmKey AES-256-GCM 密钥
         */
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

        /** Agent 心跳超时秒数，超过后判定离线并收口其终端会话。 */
        @Min(value = 1, message = "Agent heartbeat timeout must be at least one second")
        @Max(value = 3600, message = "Agent heartbeat timeout must not exceed 3600 seconds")
        private int heartbeatTimeoutSeconds = 90;

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

        /**
         * 获取 Agent 注册密钥。
         *
         * @return Agent 注册密钥
         */
        public String getRegisterKey() {
            return registerKey;
        }

        /**
         * 设置 Agent 注册密钥。
         *
         * @param registerKey Agent 注册密钥
         */
        public void setRegisterKey(String registerKey) {
            this.registerKey = registerKey;
        }

        /**
         * 获取单 JVM 最大 Agent 连接数。
         *
         * @return 最大连接数
         */
        public int getMaxConnections() {
            return maxConnections;
        }

        /**
         * 设置单 JVM 最大 Agent 连接数。
         *
         * @param maxConnections 最大连接数
         */
        public void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }

        /**
         * 获取未认证连接上限。
         *
         * @return 未认证连接上限
         */
        public int getMaxUnauthenticatedConnections() {
            return maxUnauthenticatedConnections;
        }

        /**
         * 设置未认证连接上限。
         *
         * @param maxUnauthenticatedConnections 未认证连接上限
         */
        public void setMaxUnauthenticatedConnections(int maxUnauthenticatedConnections) {
            this.maxUnauthenticatedConnections = maxUnauthenticatedConnections;
        }

        /**
         * 获取追踪的客户端 IP 数上限。
         *
         * @return 追踪客户端 IP 数上限
         */
        public int getMaxTrackedClientIps() {
            return maxTrackedClientIps;
        }

        /**
         * 设置追踪的客户端 IP 数上限。
         *
         * @param maxTrackedClientIps 追踪客户端 IP 数上限
         */
        public void setMaxTrackedClientIps(int maxTrackedClientIps) {
            this.maxTrackedClientIps = maxTrackedClientIps;
        }

        /**
         * 获取握手频率上限（次/分钟）。
         *
         * @return 握手频率上限
         */
        public int getHandshakeRatePerMinute() {
            return handshakeRatePerMinute;
        }

        /**
         * 设置握手频率上限（次/分钟）。
         *
         * @param handshakeRatePerMinute 握手频率上限
         */
        public void setHandshakeRatePerMinute(int handshakeRatePerMinute) {
            this.handshakeRatePerMinute = handshakeRatePerMinute;
        }

        /**
         * 获取心跳频率上限（次/分钟）。
         *
         * @return 心跳频率上限
         */
        public int getHeartbeatRatePerMinute() {
            return heartbeatRatePerMinute;
        }

        /**
         * 设置心跳频率上限（次/分钟）。
         *
         * @param heartbeatRatePerMinute 心跳频率上限
         */
        public void setHeartbeatRatePerMinute(int heartbeatRatePerMinute) {
            this.heartbeatRatePerMinute = heartbeatRatePerMinute;
        }

        /**
         * 获取心跳突发令牌数。
         *
         * @return 心跳突发令牌数
         */
        public int getHeartbeatBurst() {
            return heartbeatBurst;
        }

        /**
         * 设置心跳突发令牌数。
         *
         * @param heartbeatBurst 心跳突发令牌数
         */
        public void setHeartbeatBurst(int heartbeatBurst) {
            this.heartbeatBurst = heartbeatBurst;
        }

        /**
         * 获取心跳超时秒数。
         *
         * @return 心跳超时秒数
         */
        public int getHeartbeatTimeoutSeconds() {
            return heartbeatTimeoutSeconds;
        }

        /**
         * 设置心跳超时秒数。
         *
         * @param heartbeatTimeoutSeconds 心跳超时秒数
         */
        public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) {
            this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        }

        /**
         * 获取指标上报频率上限（次/分钟）。
         *
         * @return 指标上报频率上限
         */
        public int getMetricsRatePerMinute() {
            return metricsRatePerMinute;
        }

        /**
         * 设置指标上报频率上限（次/分钟）。
         *
         * @param metricsRatePerMinute 指标上报频率上限
         */
        public void setMetricsRatePerMinute(int metricsRatePerMinute) {
            this.metricsRatePerMinute = metricsRatePerMinute;
        }

        /**
         * 获取指标突发令牌数。
         *
         * @return 指标突发令牌数
         */
        public int getMetricsBurst() {
            return metricsBurst;
        }

        /**
         * 设置指标突发令牌数。
         *
         * @param metricsBurst 指标突发令牌数
         */
        public void setMetricsBurst(int metricsBurst) {
            this.metricsBurst = metricsBurst;
        }

        /**
         * 获取可信反向代理 CIDR 列表。
         *
         * @return 可信反向代理 CIDR 列表
         */
        public List<String> getTrustedProxyCidrs() {
            return trustedProxyCidrs;
        }

        /**
         * 设置可信反向代理 CIDR 列表。
         *
         * @param trustedProxyCidrs 可信反向代理 CIDR 列表
         */
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

        /**
         * 获取 TCP 连接超时（秒）。
         *
         * @return TCP 连接超时秒数
         */
        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        /**
         * 设置 TCP 连接超时（秒）。
         *
         * @param connectTimeoutSeconds TCP 连接超时秒数
         */
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        /**
         * 获取 SSH 握手和认证期间的 socket 读写超时（秒）。
         *
         * @return socket 超时秒数
         */
        public int getSocketTimeoutSeconds() {
            return socketTimeoutSeconds;
        }

        /**
         * 设置 SSH 握手和认证期间的 socket 读写超时（秒）。
         *
         * @param socketTimeoutSeconds socket 超时秒数
         */
        public void setSocketTimeoutSeconds(int socketTimeoutSeconds) {
            this.socketTimeoutSeconds = socketTimeoutSeconds;
        }

        /**
         * 获取单次 SSH 请求的整体等待预算（秒）。
         *
         * @return 整体超时秒数
         */
        public int getTotalTimeoutSeconds() {
            return totalTimeoutSeconds;
        }

        /**
         * 设置单次 SSH 请求的整体等待预算（秒）。
         *
         * @param totalTimeoutSeconds 整体超时秒数
         */
        public void setTotalTimeoutSeconds(int totalTimeoutSeconds) {
            this.totalTimeoutSeconds = totalTimeoutSeconds;
        }

        /**
         * 获取同时执行的 SSH 握手和认证数量上限。
         *
         * @return 最大并发连接数
         */
        public int getMaxConcurrentConnections() {
            return maxConcurrentConnections;
        }

        /**
         * 设置同时执行的 SSH 握手和认证数量上限。
         *
         * @param maxConcurrentConnections 最大并发连接数
         */
        public void setMaxConcurrentConnections(int maxConcurrentConnections) {
            this.maxConcurrentConnections = maxConcurrentConnections;
        }

        /**
         * 获取单次 DNS 解析可返回的地址数量上限。
         *
         * @return 最大解析地址数
         */
        public int getMaxResolvedAddresses() {
            return maxResolvedAddresses;
        }

        /**
         * 设置单次 DNS 解析可返回的地址数量上限。
         *
         * @param maxResolvedAddresses 最大解析地址数
         */
        public void setMaxResolvedAddresses(int maxResolvedAddresses) {
            this.maxResolvedAddresses = maxResolvedAddresses;
        }

        /**
         * 获取允许的 SSH 端口列表。
         *
         * @return 允许的端口列表
         */
        public List<Integer> getAllowedPorts() {
            return allowedPorts;
        }

        /**
         * 设置允许的 SSH 端口列表。
         *
         * @param allowedPorts 允许的端口列表
         */
        public void setAllowedPorts(List<Integer> allowedPorts) {
            this.allowedPorts = allowedPorts;
        }

        /**
         * 获取允许的 SSH 出站 CIDR 列表。
         *
         * @return 允许的 CIDR 列表
         */
        public List<String> getAllowedCidrs() {
            return allowedCidrs;
        }

        /**
         * 设置允许的 SSH 出站 CIDR 列表。
         *
         * @param allowedCidrs 允许的 CIDR 列表
         */
        public void setAllowedCidrs(List<String> allowedCidrs) {
            this.allowedCidrs = allowedCidrs;
        }

        /**
         * 获取 SSH 闲置超时（分钟）。
         *
         * @return 闲置超时分钟数
         */
        public int getIdleTimeoutMinutes() {
            return idleTimeoutMinutes;
        }

        /**
         * 设置 SSH 闲置超时（分钟）。
         *
         * @param idleTimeoutMinutes 闲置超时分钟数
         */
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

        /** 指标幂等接收记录保留天数，须覆盖消息可能重投的最大窗口。 */
        @Min(value = 1, message = "Metrics ingestion retention days must be at least one")
        @Max(value = 3650, message = "Metrics ingestion retention days must not exceed 3650")
        private int ingestionRetentionDays = 7;

        /** 指标幂等接收记录清理任务 Cron 表达式。 */
        @NotBlank(message = "Metrics ingestion cleanup cron must not be blank")
        private String ingestionCleanupCron = "0 0 3 * * ?";

        /** 指标幂等接收记录单次清理 SQL 删除的最大记录数。 */
        @Min(value = 1, message = "Metrics ingestion cleanup batch size must be at least one")
        @Max(value = 10000, message = "Metrics ingestion cleanup batch size must not exceed 10000")
        private int ingestionCleanupBatchSize = 1000;

        /** 指标幂等接收记录单次定时任务允许执行的最大批次数。 */
        @Min(value = 1, message = "Metrics ingestion cleanup max batches must be at least one")
        @Max(value = 1000, message = "Metrics ingestion cleanup max batches must not exceed 1000")
        private int ingestionCleanupMaxBatchesPerRun = 100;

        /**
         * 获取 Metrics 数据保留天数。
         *
         * @return 保留天数
         */
        public int getRetentionDays() {
            return retentionDays;
        }

        /**
         * 设置 Metrics 数据保留天数。
         *
         * @param retentionDays 保留天数
         */
        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        /**
         * 获取 Metrics 清理任务 Cron 表达式。
         *
         * @return Cron 表达式
         */
        public String getCleanupCron() {
            return cleanupCron;
        }

        /**
         * 设置 Metrics 清理任务 Cron 表达式。
         *
         * @param cleanupCron Cron 表达式
         */
        public void setCleanupCron(String cleanupCron) {
            this.cleanupCron = cleanupCron;
        }

        /**
         * 获取单次清理 SQL 删除的最大记录数。
         *
         * @return 单批清理上限
         */
        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        /**
         * 设置单次清理 SQL 删除的最大记录数。
         *
         * @param cleanupBatchSize 单批清理上限
         */
        public void setCleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
        }

        /**
         * 获取单次定时任务允许执行的最大批次数。
         *
         * @return 最大批次数
         */
        public int getCleanupMaxBatchesPerRun() {
            return cleanupMaxBatchesPerRun;
        }

        /**
         * 设置单次定时任务允许执行的最大批次数。
         *
         * @param cleanupMaxBatchesPerRun 最大批次数
         */
        public void setCleanupMaxBatchesPerRun(int cleanupMaxBatchesPerRun) {
            this.cleanupMaxBatchesPerRun = cleanupMaxBatchesPerRun;
        }

        /**
         * 获取指标幂等接收记录保留天数。
         *
         * @return 保留天数
         */
        public int getIngestionRetentionDays() {
            return ingestionRetentionDays;
        }

        /**
         * 设置指标幂等接收记录保留天数。
         *
         * @param ingestionRetentionDays 保留天数
         */
        public void setIngestionRetentionDays(int ingestionRetentionDays) {
            this.ingestionRetentionDays = ingestionRetentionDays;
        }

        /**
         * 获取指标幂等接收记录清理任务 Cron 表达式。
         *
         * @return Cron 表达式
         */
        public String getIngestionCleanupCron() {
            return ingestionCleanupCron;
        }

        /**
         * 设置指标幂等接收记录清理任务 Cron 表达式。
         *
         * @param ingestionCleanupCron Cron 表达式
         */
        public void setIngestionCleanupCron(String ingestionCleanupCron) {
            this.ingestionCleanupCron = ingestionCleanupCron;
        }

        /**
         * 获取指标幂等接收记录单次清理 SQL 删除的最大记录数。
         *
         * @return 单批清理上限
         */
        public int getIngestionCleanupBatchSize() {
            return ingestionCleanupBatchSize;
        }

        /**
         * 设置指标幂等接收记录单次清理 SQL 删除的最大记录数。
         *
         * @param ingestionCleanupBatchSize 单批清理上限
         */
        public void setIngestionCleanupBatchSize(int ingestionCleanupBatchSize) {
            this.ingestionCleanupBatchSize = ingestionCleanupBatchSize;
        }

        /**
         * 获取指标幂等接收记录单次定时任务允许执行的最大批次数。
         *
         * @return 最大批次数
         */
        public int getIngestionCleanupMaxBatchesPerRun() {
            return ingestionCleanupMaxBatchesPerRun;
        }

        /**
         * 设置指标幂等接收记录单次定时任务允许执行的最大批次数。
         *
         * @param ingestionCleanupMaxBatchesPerRun 最大批次数
         */
        public void setIngestionCleanupMaxBatchesPerRun(int ingestionCleanupMaxBatchesPerRun) {
            this.ingestionCleanupMaxBatchesPerRun = ingestionCleanupMaxBatchesPerRun;
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

        /**
         * 获取单用户最大会话数。
         *
         * @return 单用户最大会话数
         */
        public int getMaxSessionsPerUser() { return maxSessionsPerUser; }
        /**
         * 设置单用户最大会话数。
         *
         * @param value 单用户最大会话数
         */
        public void setMaxSessionsPerUser(int value) { maxSessionsPerUser = value; }
        /**
         * 获取单服务器最大会话数。
         *
         * @return 单服务器最大会话数
         */
        public int getMaxSessionsPerServer() { return maxSessionsPerServer; }
        /**
         * 设置单服务器最大会话数。
         *
         * @param value 单服务器最大会话数
         */
        public void setMaxSessionsPerServer(int value) { maxSessionsPerServer = value; }
        /**
         * 获取同时操作同一服务器的用户数上限。
         *
         * @return 操作用户数上限
         */
        public int getMaxOperatingUsers() { return maxOperatingUsers; }
        /**
         * 设置同时操作同一服务器的用户数上限。
         *
         * @param value 操作用户数上限
         */
        public void setMaxOperatingUsers(int value) { maxOperatingUsers = value; }
        /**
         * 获取会话清理 Cron 表达式。
         *
         * @return Cron 表达式
         */
        public String getCleanupCron() { return cleanupCron; }
        /**
         * 设置会话清理 Cron 表达式。
         *
         * @param value Cron 表达式
         */
        public void setCleanupCron(String value) { cleanupCron = value; }
        /**
         * 获取全局最大会话数。
         *
         * @return 全局最大会话数
         */
        public int getMaxSessions() { return maxSessions; }
        /**
         * 设置全局最大会话数。
         *
         * @param value 全局最大会话数
         */
        public void setMaxSessions(int value) { maxSessions = value; }
        /**
         * 获取会话闲置超时（分钟）。
         *
         * @return 闲置超时分钟数
         */
        public int getIdleTimeoutMinutes() { return idleTimeoutMinutes; }
        /**
         * 设置会话闲置超时（分钟）。
         *
         * @param value 闲置超时分钟数
         */
        public void setIdleTimeoutMinutes(int value) { idleTimeoutMinutes = value; }
        /**
         * 获取单会话最大持续时长（小时）。
         *
         * @return 最大持续小时数
         */
        public int getMaxSessionHours() { return maxSessionHours; }
        /**
         * 设置单会话最大持续时长（小时）。
         *
         * @param value 最大持续小时数
         */
        public void setMaxSessionHours(int value) { maxSessionHours = value; }
        /**
         * 获取打开会话频率上限（次/分钟）。
         *
         * @return 打开频率上限
         */
        public int getOpenRatePerMinute() { return openRatePerMinute; }
        /**
         * 设置打开会话频率上限（次/分钟）。
         *
         * @param value 打开频率上限
         */
        public void setOpenRatePerMinute(int value) { openRatePerMinute = value; }
        /**
         * 获取打开会话突发令牌数。
         *
         * @return 打开突发令牌数
         */
        public int getOpenBurst() { return openBurst; }
        /**
         * 设置打开会话突发令牌数。
         *
         * @param value 打开突发令牌数
         */
        public void setOpenBurst(int value) { openBurst = value; }
        /**
         * 获取输入频率上限（次/分钟）。
         *
         * @return 输入频率上限
         */
        public int getInputRatePerMinute() { return inputRatePerMinute; }
        /**
         * 设置输入频率上限（次/分钟）。
         *
         * @param value 输入频率上限
         */
        public void setInputRatePerMinute(int value) { inputRatePerMinute = value; }
        /**
         * 获取输入突发令牌数。
         *
         * @return 输入突发令牌数
         */
        public int getInputBurst() { return inputBurst; }
        /**
         * 设置输入突发令牌数。
         *
         * @param value 输入突发令牌数
         */
        public void setInputBurst(int value) { inputBurst = value; }
        /**
         * 获取调整窗口大小频率上限（次/分钟）。
         *
         * @return 调整频率上限
         */
        public int getResizeRatePerMinute() { return resizeRatePerMinute; }
        /**
         * 设置调整窗口大小频率上限（次/分钟）。
         *
         * @param value 调整频率上限
         */
        public void setResizeRatePerMinute(int value) { resizeRatePerMinute = value; }
        /**
         * 获取调整窗口大小突发令牌数。
         *
         * @return 调整突发令牌数
         */
        public int getResizeBurst() { return resizeBurst; }
        /**
         * 设置调整窗口大小突发令牌数。
         *
         * @param value 调整突发令牌数
         */
        public void setResizeBurst(int value) { resizeBurst = value; }
        /**
         * 获取关闭会话频率上限（次/分钟）。
         *
         * @return 关闭频率上限
         */
        public int getCloseRatePerMinute() { return closeRatePerMinute; }
        /**
         * 设置关闭会话频率上限（次/分钟）。
         *
         * @param value 关闭频率上限
         */
        public void setCloseRatePerMinute(int value) { closeRatePerMinute = value; }
        /**
         * 获取关闭会话突发令牌数。
         *
         * @return 关闭突发令牌数
         */
        public int getCloseBurst() { return closeBurst; }
        /**
         * 设置关闭会话突发令牌数。
         *
         * @param value 关闭突发令牌数
         */
        public void setCloseBurst(int value) { closeBurst = value; }
        /**
         * 获取输出速率上限（字节/秒）。
         *
         * @return 输出速率上限
         */
        public int getOutputRateBytesPerSecond() { return outputRateBytesPerSecond; }
        /**
         * 设置输出速率上限（字节/秒）。
         *
         * @param value 输出速率上限
         */
        public void setOutputRateBytesPerSecond(int value) { outputRateBytesPerSecond = value; }
        /**
         * 获取输出突发容量上限（字节）。
         *
         * @return 输出突发容量上限
         */
        public int getOutputBurstBytes() { return outputBurstBytes; }
        /**
         * 设置输出突发容量上限（字节）。
         *
         * @param value 输出突发容量上限
         */
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

        /** 是否启用已发布 Outbox 记录的保留期清理（只删 published 行，pending/失败行不受影响）。 */
        private boolean outboxCleanupEnabled = true;

        /** 消费失败率统计窗口（分钟），按 message_consume_records.created_at 过滤（MVP-14 监控收尾）。 */
        @Min(value = 1, message = "Consume stats window must be at least one minute")
        @Max(value = 1440, message = "Consume stats window must not exceed 1440 minutes")
        private int consumeStatsWindowMinutes = 60;

        /** 是否启用队列积压探测（MVP-14 监控收尾）。 */
        private boolean queueMonitorEnabled = true;

        /** 队列积压探测间隔（毫秒）。 */
        @Min(value = 5000, message = "Queue monitor interval must be at least 5000 ms")
        @Max(value = 3600000, message = "Queue monitor interval must not exceed 3600000 ms")
        private long queueMonitorIntervalMs = 60000;

        /** 业务队列积压告警阈值：消息数超过该值输出 backlog warn 日志。 */
        @Min(value = 1, message = "Queue backlog warn threshold must be at least one")
        @Max(value = 100000000, message = "Queue backlog warn threshold must not exceed 100000000")
        private int queueBacklogWarnThreshold = 10000;

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

        /** 是否启用消费幂等记录保留期清理。 */
        private boolean consumeRecordCleanupEnabled = true;

        /** 消费幂等记录保留天数，须覆盖事件可能重投的最大窗口。 */
        @Min(value = 1, message = "Consume record retention days must be at least one")
        @Max(value = 3650, message = "Consume record retention days must not exceed 3650")
        private int consumeRecordRetentionDays = 30;

        /** 消费幂等记录清理 cron。 */
        @NotBlank(message = "Consume record cleanup cron must not be blank")
        private String consumeRecordCleanupCron = "0 0 3 * * ?";

        /** 消费幂等记录单批清理上限。 */
        @Min(value = 1, message = "Consume record cleanup batch size must be at least one")
        @Max(value = 10000, message = "Consume record cleanup batch size must not exceed 10000")
        private int consumeRecordCleanupBatchSize = 1000;

        /** 单轮消费幂等记录清理最多执行的批次数。 */
        @Min(value = 1, message = "Consume record cleanup max batches must be at least one")
        @Max(value = 1000, message = "Consume record cleanup max batches must not exceed 1000")
        private int consumeRecordCleanupMaxBatchesPerRun = 100;

        /**
         * 返回是否启用 Outbox 发布与 RabbitMQ 就绪检查。
         *
         * @return 是否启用
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否启用 Outbox 发布与 RabbitMQ 就绪检查。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 获取 Topic Exchange 名称。
         *
         * @return Exchange 名称
         */
        public String getExchange() {
            return exchange;
        }

        /**
         * 设置 Topic Exchange 名称。
         *
         * @param exchange Exchange 名称
         */
        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        /**
         * 获取发布器轮询间隔（毫秒）。
         *
         * @return 轮询间隔毫秒数
         */
        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        /**
         * 设置发布器轮询间隔（毫秒）。
         *
         * @param pollIntervalMs 轮询间隔毫秒数
         */
        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        /**
         * 获取单轮最多选取的待发布行数。
         *
         * @return 单批大小
         */
        public int getBatchSize() {
            return batchSize;
        }

        /**
         * 设置单轮最多选取的待发布行数。
         *
         * @param batchSize 单批大小
         */
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        /**
         * 获取等待 Broker Confirm 的超时（毫秒）。
         *
         * @return 超时毫秒数
         */
        public long getPublishTimeoutMs() {
            return publishTimeoutMs;
        }

        /**
         * 设置等待 Broker Confirm 的超时（毫秒）。
         *
         * @param publishTimeoutMs 超时毫秒数
         */
        public void setPublishTimeoutMs(long publishTimeoutMs) {
            this.publishTimeoutMs = publishTimeoutMs;
        }

        /**
         * 获取指数退避封顶（秒）。
         *
         * @return 最大退避秒数
         */
        public int getMaxBackoffSeconds() {
            return maxBackoffSeconds;
        }

        /**
         * 设置指数退避封顶（秒）。
         *
         * @param maxBackoffSeconds 最大退避秒数
         */
        public void setMaxBackoffSeconds(int maxBackoffSeconds) {
            this.maxBackoffSeconds = maxBackoffSeconds;
        }

        /**
         * 返回是否启用已发布 Outbox 记录的保留期清理。
         *
         * @return 是否启用清理
         */
        public boolean isOutboxCleanupEnabled() {
            return outboxCleanupEnabled;
        }

        /**
         * 设置是否启用已发布 Outbox 记录的保留期清理。
         *
         * @param outboxCleanupEnabled 是否启用清理
         */
        public void setOutboxCleanupEnabled(boolean outboxCleanupEnabled) {
            this.outboxCleanupEnabled = outboxCleanupEnabled;
        }

        /**
         * 获取消费失败率统计窗口（分钟）。
         *
         * @return 统计窗口分钟数
         */
        public int getConsumeStatsWindowMinutes() {
            return consumeStatsWindowMinutes;
        }

        /**
         * 设置消费失败率统计窗口（分钟）。
         *
         * @param consumeStatsWindowMinutes 统计窗口分钟数
         */
        public void setConsumeStatsWindowMinutes(int consumeStatsWindowMinutes) {
            this.consumeStatsWindowMinutes = consumeStatsWindowMinutes;
        }

        /**
         * 返回是否启用队列积压探测。
         *
         * @return 是否启用探测
         */
        public boolean isQueueMonitorEnabled() {
            return queueMonitorEnabled;
        }

        /**
         * 设置队列积压探测开关。
         *
         * @param queueMonitorEnabled 是否启用探测
         */
        public void setQueueMonitorEnabled(boolean queueMonitorEnabled) {
            this.queueMonitorEnabled = queueMonitorEnabled;
        }

        /**
         * 获取队列积压探测间隔（毫秒）。
         *
         * @return 探测间隔毫秒数
         */
        public long getQueueMonitorIntervalMs() {
            return queueMonitorIntervalMs;
        }

        /**
         * 设置队列积压探测间隔（毫秒）。
         *
         * @param queueMonitorIntervalMs 探测间隔毫秒数
         */
        public void setQueueMonitorIntervalMs(long queueMonitorIntervalMs) {
            this.queueMonitorIntervalMs = queueMonitorIntervalMs;
        }

        /**
         * 获取业务队列积压告警阈值。
         *
         * @return 告警阈值
         */
        public int getQueueBacklogWarnThreshold() {
            return queueBacklogWarnThreshold;
        }

        /**
         * 设置业务队列积压告警阈值。
         *
         * @param queueBacklogWarnThreshold 告警阈值
         */
        public void setQueueBacklogWarnThreshold(int queueBacklogWarnThreshold) {
            this.queueBacklogWarnThreshold = queueBacklogWarnThreshold;
        }

        /**
         * 获取已发布 Outbox 记录的保留天数。
         *
         * @return 保留天数
         */
        public int getOutboxRetentionDays() {
            return outboxRetentionDays;
        }

        /**
         * 设置已发布 Outbox 记录的保留天数。
         *
         * @param outboxRetentionDays 保留天数
         */
        public void setOutboxRetentionDays(int outboxRetentionDays) {
            this.outboxRetentionDays = outboxRetentionDays;
        }

        /**
         * 获取已发布 Outbox 清理 Cron 表达式。
         *
         * @return Cron 表达式
         */
        public String getOutboxCleanupCron() {
            return outboxCleanupCron;
        }

        /**
         * 设置已发布 Outbox 清理 Cron 表达式。
         *
         * @param outboxCleanupCron Cron 表达式
         */
        public void setOutboxCleanupCron(String outboxCleanupCron) {
            this.outboxCleanupCron = outboxCleanupCron;
        }

        /**
         * 获取已发布 Outbox 单批清理上限。
         *
         * @return 单批清理上限
         */
        public int getOutboxCleanupBatchSize() {
            return outboxCleanupBatchSize;
        }

        /**
         * 设置已发布 Outbox 单批清理上限。
         *
         * @param outboxCleanupBatchSize 单批清理上限
         */
        public void setOutboxCleanupBatchSize(int outboxCleanupBatchSize) {
            this.outboxCleanupBatchSize = outboxCleanupBatchSize;
        }

        /**
         * 获取单轮已发布 Outbox 清理最多执行的批次数。
         *
         * @return 最大批次数
         */
        public int getOutboxCleanupMaxBatchesPerRun() {
            return outboxCleanupMaxBatchesPerRun;
        }

        /**
         * 设置单轮已发布 Outbox 清理最多执行的批次数。
         *
         * @param outboxCleanupMaxBatchesPerRun 最大批次数
         */
        public void setOutboxCleanupMaxBatchesPerRun(int outboxCleanupMaxBatchesPerRun) {
            this.outboxCleanupMaxBatchesPerRun = outboxCleanupMaxBatchesPerRun;
        }

        /**
         * 返回是否启用消费幂等记录保留期清理。
         *
         * @return 是否启用清理
         */
        public boolean isConsumeRecordCleanupEnabled() {
            return consumeRecordCleanupEnabled;
        }

        /**
         * 设置是否启用消费幂等记录保留期清理。
         *
         * @param consumeRecordCleanupEnabled 是否启用清理
         */
        public void setConsumeRecordCleanupEnabled(boolean consumeRecordCleanupEnabled) {
            this.consumeRecordCleanupEnabled = consumeRecordCleanupEnabled;
        }

        /**
         * 获取消费幂等记录保留天数。
         *
         * @return 保留天数
         */
        public int getConsumeRecordRetentionDays() {
            return consumeRecordRetentionDays;
        }

        /**
         * 设置消费幂等记录保留天数。
         *
         * @param consumeRecordRetentionDays 保留天数
         */
        public void setConsumeRecordRetentionDays(int consumeRecordRetentionDays) {
            this.consumeRecordRetentionDays = consumeRecordRetentionDays;
        }

        /**
         * 获取消费幂等记录清理 Cron 表达式。
         *
         * @return Cron 表达式
         */
        public String getConsumeRecordCleanupCron() {
            return consumeRecordCleanupCron;
        }

        /**
         * 设置消费幂等记录清理 Cron 表达式。
         *
         * @param consumeRecordCleanupCron Cron 表达式
         */
        public void setConsumeRecordCleanupCron(String consumeRecordCleanupCron) {
            this.consumeRecordCleanupCron = consumeRecordCleanupCron;
        }

        /**
         * 获取消费幂等记录单批清理上限。
         *
         * @return 单批清理上限
         */
        public int getConsumeRecordCleanupBatchSize() {
            return consumeRecordCleanupBatchSize;
        }

        /**
         * 设置消费幂等记录单批清理上限。
         *
         * @param consumeRecordCleanupBatchSize 单批清理上限
         */
        public void setConsumeRecordCleanupBatchSize(int consumeRecordCleanupBatchSize) {
            this.consumeRecordCleanupBatchSize = consumeRecordCleanupBatchSize;
        }

        /**
         * 获取单轮消费幂等记录清理最多执行的批次数。
         *
         * @return 最大批次数
         */
        public int getConsumeRecordCleanupMaxBatchesPerRun() {
            return consumeRecordCleanupMaxBatchesPerRun;
        }

        /**
         * 设置单轮消费幂等记录清理最多执行的批次数。
         *
         * @param consumeRecordCleanupMaxBatchesPerRun 最大批次数
         */
        public void setConsumeRecordCleanupMaxBatchesPerRun(int consumeRecordCleanupMaxBatchesPerRun) {
            this.consumeRecordCleanupMaxBatchesPerRun = consumeRecordCleanupMaxBatchesPerRun;
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

        /**
         * 获取允许的前端 Origin 列表。
         *
         * @return 允许的 Origin 列表
         */
        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        /**
         * 设置允许的前端 Origin 列表。
         *
         * @param allowedOrigins 允许的 Origin 列表
         */
        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        /**
         * 获取允许的 HTTP 方法列表。
         *
         * @return 允许的 HTTP 方法列表
         */
        public List<String> getAllowedMethods() {
            return allowedMethods;
        }

        /**
         * 设置允许的 HTTP 方法列表。
         *
         * @param allowedMethods 允许的 HTTP 方法列表
         */
        public void setAllowedMethods(List<String> allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        /**
         * 获取允许的请求头列表。
         *
         * @return 允许的请求头列表
         */
        public List<String> getAllowedHeaders() {
            return allowedHeaders;
        }

        /**
         * 设置允许的请求头列表。
         *
         * @param allowedHeaders 允许的请求头列表
         */
        public void setAllowedHeaders(List<String> allowedHeaders) {
            this.allowedHeaders = allowedHeaders;
        }

        /**
         * 获取预检缓存时间（秒）。
         *
         * @return 预检缓存秒数
         */
        public long getMaxAgeSeconds() {
            return maxAgeSeconds;
        }

        /**
         * 设置预检缓存时间（秒）。
         *
         * @param maxAgeSeconds 预检缓存秒数
         */
        public void setMaxAgeSeconds(long maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
        }
    }

    public static class Alert {

        /** 外部通知总开关；关闭时不为任何渠道排程发送（默认关闭，兼容旧部署）。 */
        private boolean notificationEnabled = false;

        /** 邮件发件人地址，仅在启用邮件通知时使用。 */
        private String mailFrom = "noreply@susumonitor.local";

        /** 是否启用告警记录保留期清理。 */
        private boolean recordCleanupEnabled = true;

        /** 告警记录保留天数。 */
        @Min(value = 1, message = "Alert record retention days must be at least one")
        @Max(value = 3650, message = "Alert record retention days must not exceed 3650")
        private int recordRetentionDays = 90;

        /** 告警记录清理 cron。 */
        @NotBlank(message = "Alert record cleanup cron must not be blank")
        private String recordCleanupCron = "0 0 3 * * ?";

        /** 告警记录单批清理上限。 */
        @Min(value = 1, message = "Alert record cleanup batch size must be at least one")
        @Max(value = 10000, message = "Alert record cleanup batch size must not exceed 10000")
        private int recordCleanupBatchSize = 1000;

        /** 单轮告警记录清理最多执行的批次数。 */
        @Min(value = 1, message = "Alert record cleanup max batches must be at least one")
        @Max(value = 1000, message = "Alert record cleanup max batches must not exceed 1000")
        private int recordCleanupMaxBatchesPerRun = 100;

        /** 是否启用通知投递记录保留期清理（补齐 V21 通知表只增不删的增长风险）。 */
        private boolean notificationCleanupEnabled = true;

        /** 通知投递记录保留天数。 */
        @Min(value = 1, message = "Alert notification retention days must be at least one")
        @Max(value = 3650, message = "Alert notification retention days must not exceed 3650")
        private int notificationRetentionDays = 90;

        /** 通知投递记录清理 cron。 */
        @NotBlank(message = "Alert notification cleanup cron must not be blank")
        private String notificationCleanupCron = "0 0 3 * * ?";

        /** 通知投递记录单批清理上限。 */
        @Min(value = 1, message = "Alert notification cleanup batch size must be at least one")
        @Max(value = 10000, message = "Alert notification cleanup batch size must not exceed 10000")
        private int notificationCleanupBatchSize = 1000;

        /** 单轮通知投递清理最多执行的批次数。 */
        @Min(value = 1, message = "Alert notification cleanup max batches must be at least one")
        @Max(value = 1000, message = "Alert notification cleanup max batches must not exceed 1000")
        private int notificationCleanupMaxBatchesPerRun = 100;

        /**
         * 返回外部通知是否启用。
         *
         * @return 是否启用通知
         */
        public boolean isNotificationEnabled() {
            return notificationEnabled;
        }

        /**
         * 设置外部通知开关。
         *
         * @param notificationEnabled 是否启用通知
         */
        public void setNotificationEnabled(boolean notificationEnabled) {
            this.notificationEnabled = notificationEnabled;
        }

        /**
         * 获取邮件发件人地址。
         *
         * @return 邮件发件人地址
         */
        public String getMailFrom() {
            return mailFrom;
        }

        /**
         * 设置邮件发件人地址。
         *
         * @param mailFrom 邮件发件人地址
         */
        public void setMailFrom(String mailFrom) {
            this.mailFrom = mailFrom;
        }

        /**
         * 返回是否启用告警记录保留期清理。
         *
         * @return 是否启用清理
         */
        public boolean isRecordCleanupEnabled() {
            return recordCleanupEnabled;
        }

        /**
         * 设置是否启用告警记录保留期清理。
         *
         * @param recordCleanupEnabled 是否启用清理
         */
        public void setRecordCleanupEnabled(boolean recordCleanupEnabled) {
            this.recordCleanupEnabled = recordCleanupEnabled;
        }

        /**
         * 获取告警记录保留天数。
         *
         * @return 保留天数
         */
        public int getRecordRetentionDays() {
            return recordRetentionDays;
        }

        /**
         * 设置告警记录保留天数。
         *
         * @param recordRetentionDays 保留天数
         */
        public void setRecordRetentionDays(int recordRetentionDays) {
            this.recordRetentionDays = recordRetentionDays;
        }

        /**
         * 获取告警记录清理 Cron 表达式。
         *
         * @return Cron 表达式
         */
        public String getRecordCleanupCron() {
            return recordCleanupCron;
        }

        /**
         * 设置告警记录清理 Cron 表达式。
         *
         * @param recordCleanupCron Cron 表达式
         */
        public void setRecordCleanupCron(String recordCleanupCron) {
            this.recordCleanupCron = recordCleanupCron;
        }

        /**
         * 获取告警记录单批清理上限。
         *
         * @return 单批清理上限
         */
        public int getRecordCleanupBatchSize() {
            return recordCleanupBatchSize;
        }

        /**
         * 设置告警记录单批清理上限。
         *
         * @param recordCleanupBatchSize 单批清理上限
         */
        public void setRecordCleanupBatchSize(int recordCleanupBatchSize) {
            this.recordCleanupBatchSize = recordCleanupBatchSize;
        }

        /**
         * 获取单轮告警记录清理最多执行的批次数。
         *
         * @return 最大批次数
         */
        public int getRecordCleanupMaxBatchesPerRun() {
            return recordCleanupMaxBatchesPerRun;
        }

        /**
         * 设置单轮告警记录清理最多执行的批次数。
         *
         * @param recordCleanupMaxBatchesPerRun 最大批次数
         */
        public void setRecordCleanupMaxBatchesPerRun(int recordCleanupMaxBatchesPerRun) {
            this.recordCleanupMaxBatchesPerRun = recordCleanupMaxBatchesPerRun;
        }

        /**
         * 返回通知投递清理是否启用。
         *
         * @return 是否启用清理
         */
        public boolean isNotificationCleanupEnabled() {
            return notificationCleanupEnabled;
        }

        /**
         * 设置通知投递清理开关。
         *
         * @param notificationCleanupEnabled 是否启用清理
         */
        public void setNotificationCleanupEnabled(boolean notificationCleanupEnabled) {
            this.notificationCleanupEnabled = notificationCleanupEnabled;
        }

        /**
         * 获取通知投递记录保留天数。
         *
         * @return 保留天数
         */
        public int getNotificationRetentionDays() {
            return notificationRetentionDays;
        }

        /**
         * 设置通知投递记录保留天数。
         *
         * @param notificationRetentionDays 保留天数
         */
        public void setNotificationRetentionDays(int notificationRetentionDays) {
            this.notificationRetentionDays = notificationRetentionDays;
        }

        /**
         * 获取通知投递清理 cron。
         *
         * @return cron 表达式
         */
        public String getNotificationCleanupCron() {
            return notificationCleanupCron;
        }

        /**
         * 设置通知投递清理 cron。
         *
         * @param notificationCleanupCron cron 表达式
         */
        public void setNotificationCleanupCron(String notificationCleanupCron) {
            this.notificationCleanupCron = notificationCleanupCron;
        }

        /**
         * 获取通知投递单批清理上限。
         *
         * @return 单批上限
         */
        public int getNotificationCleanupBatchSize() {
            return notificationCleanupBatchSize;
        }

        /**
         * 设置通知投递单批清理上限。
         *
         * @param notificationCleanupBatchSize 单批上限
         */
        public void setNotificationCleanupBatchSize(int notificationCleanupBatchSize) {
            this.notificationCleanupBatchSize = notificationCleanupBatchSize;
        }

        /**
         * 获取单轮通知投递清理最多执行的批次数。
         *
         * @return 最大批次数
         */
        public int getNotificationCleanupMaxBatchesPerRun() {
            return notificationCleanupMaxBatchesPerRun;
        }

        /**
         * 设置单轮通知投递清理最多执行的批次数。
         *
         * @param notificationCleanupMaxBatchesPerRun 最大批次数
         */
        public void setNotificationCleanupMaxBatchesPerRun(int notificationCleanupMaxBatchesPerRun) {
            this.notificationCleanupMaxBatchesPerRun = notificationCleanupMaxBatchesPerRun;
        }
    }

    /**
     * SSH 测试历史（ssh_test_history）保留期与分批清理配置。
     */
    public static class SshTestHistory {

        /** 是否启用 SSH 测试历史保留期清理。 */
        private boolean cleanupEnabled = true;

        /** SSH 测试历史保留天数。 */
        @Min(value = 1, message = "SSH test history retention days must be at least one")
        @Max(value = 3650, message = "SSH test history retention days must not exceed 3650")
        private int retentionDays = 90;

        /** SSH 测试历史清理 cron。 */
        @NotBlank(message = "SSH test history cleanup cron must not be blank")
        private String cleanupCron = "0 0 3 * * ?";

        /** SSH 测试历史单批清理上限。 */
        @Min(value = 1, message = "SSH test history cleanup batch size must be at least one")
        @Max(value = 10000, message = "SSH test history cleanup batch size must not exceed 10000")
        private int cleanupBatchSize = 1000;

        /** 单轮 SSH 测试历史清理最多执行的批次数。 */
        @Min(value = 1, message = "SSH test history cleanup max batches must be at least one")
        @Max(value = 1000, message = "SSH test history cleanup max batches must not exceed 1000")
        private int cleanupMaxBatchesPerRun = 100;

        /**
         * 返回 SSH 测试历史清理是否启用。
         *
         * @return 是否启用清理
         */
        public boolean isCleanupEnabled() {
            return cleanupEnabled;
        }

        /**
         * 设置 SSH 测试历史清理是否启用。
         *
         * @param cleanupEnabled 是否启用清理
         */
        public void setCleanupEnabled(boolean cleanupEnabled) {
            this.cleanupEnabled = cleanupEnabled;
        }

        /**
         * 返回 SSH 测试历史保留天数。
         *
         * @return 保留天数
         */
        public int getRetentionDays() {
            return retentionDays;
        }

        /**
         * 设置 SSH 测试历史保留天数。
         *
         * @param retentionDays 保留天数
         */
        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        /**
         * 返回 SSH 测试历史清理 cron。
         *
         * @return 清理 cron
         */
        public String getCleanupCron() {
            return cleanupCron;
        }

        /**
         * 设置 SSH 测试历史清理 cron。
         *
         * @param cleanupCron 清理 cron
         */
        public void setCleanupCron(String cleanupCron) {
            this.cleanupCron = cleanupCron;
        }

        /**
         * 返回 SSH 测试历史单批清理上限。
         *
         * @return 单批清理上限
         */
        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        /**
         * 设置 SSH 测试历史单批清理上限。
         *
         * @param cleanupBatchSize 单批清理上限
         */
        public void setCleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
        }

        /**
         * 返回单轮 SSH 测试历史清理最多执行的批次数。
         *
         * @return 最大批次数
         */
        public int getCleanupMaxBatchesPerRun() {
            return cleanupMaxBatchesPerRun;
        }

        /**
         * 设置单轮 SSH 测试历史清理最多执行的批次数。
         *
         * @param cleanupMaxBatchesPerRun 最大批次数
         */
        public void setCleanupMaxBatchesPerRun(int cleanupMaxBatchesPerRun) {
            this.cleanupMaxBatchesPerRun = cleanupMaxBatchesPerRun;
        }
    }
}
