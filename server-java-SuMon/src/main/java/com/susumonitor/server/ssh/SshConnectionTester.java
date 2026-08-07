package com.susumonitor.server.ssh;

import com.susumonitor.server.config.AppProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 使用 sshj 完成受限出站 SSH 握手、严格主机公钥校验和凭据认证。
 */
// 将 SSH 网络组件注册为 Spring Bean，并集中执行连接资源限制。
@Component
public class SshConnectionTester implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SshConnectionTester.class);

    private final SshOutboundPolicy outboundPolicy;
    private final int connectTimeoutMillis;
    private final int socketTimeoutMillis;
    private final long totalTimeoutSeconds;
    private final Semaphore connectionPermits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 使用类型化配置建立 SSH 超时和并发限制。
     *
     * @param outboundPolicy 出站访问策略
     * @param appProperties 应用配置
     */
    public SshConnectionTester(SshOutboundPolicy outboundPolicy, AppProperties appProperties) {
        this.outboundPolicy = outboundPolicy;
        AppProperties.Ssh ssh = appProperties.getSsh();
        this.connectTimeoutMillis = Math.toIntExact(Duration.ofSeconds(ssh.getConnectTimeoutSeconds()).toMillis());
        this.socketTimeoutMillis = Math.toIntExact(Duration.ofSeconds(ssh.getSocketTimeoutSeconds()).toMillis());
        this.totalTimeoutSeconds = ssh.getTotalTimeoutSeconds();
        this.connectionPermits = new Semaphore(ssh.getMaxConcurrentConnections());
    }

    /**
     * 只执行 SSH 握手并核对带外取得的指纹，不读取或发送登录凭据。
     *
     * @param host SSH 主机
     * @param port SSH 端口
     * @param expectedFingerprint 管理员预期 SHA-256 指纹
     * @return 已核对的远端主机密钥
     */
    public SshHostKeyObservation verifyHostKey(String host, int port, String expectedFingerprint) {
        return execute(host, port, expectedFingerprint, null, (sshClient, cancelled) -> {
        });
    }

    /**
     * 严格验证主机身份后，按需取得密码并完成认证。
     *
     * @param host SSH 主机
     * @param port SSH 端口
     * @param username SSH 用户名
     * @param expectedAlgorithm 已登记主机公钥算法
     * @param expectedFingerprint 已登记主机公钥指纹
     * @param passwordSupplier 指纹验证通过后才调用的密码提供器
     * @return SSH 连接测试结果
     */
    public SshConnectionResult testPassword(String host, int port, String username, String expectedAlgorithm,
            String expectedFingerprint, Supplier<char[]> passwordSupplier) {
        long startedAt = System.nanoTime();
        SshHostKeyObservation observation = execute(host, port, expectedFingerprint, expectedAlgorithm,
                (sshClient, cancelled) -> {
            ensureActive(cancelled);
            char[] password = passwordSupplier.get();
            try {
                sshClient.authPassword(username, password);
            } finally {
                PasswordUtils.blankOut(password);
            }
        });
        return new SshConnectionResult(observation.algorithm(), observation.fingerprint(), elapsedMillis(startedAt));
    }

    /**
     * 严格验证主机身份后，从内存加载私钥和可选口令完成认证。
     *
     * @param host SSH 主机
     * @param port SSH 端口
     * @param username SSH 用户名
     * @param expectedAlgorithm 已登记主机公钥算法
     * @param expectedFingerprint 已登记主机公钥指纹
     * @param privateKeySupplier 指纹验证通过后才调用的私钥提供器
     * @param passphraseSupplier 指纹验证通过后才调用的可选口令提供器
     * @return SSH 连接测试结果
     */
    public SshConnectionResult testPrivateKey(String host, int port, String username, String expectedAlgorithm,
            String expectedFingerprint, Supplier<String> privateKeySupplier, Supplier<char[]> passphraseSupplier) {
        long startedAt = System.nanoTime();
        SshHostKeyObservation observation = execute(host, port, expectedFingerprint, expectedAlgorithm,
                (sshClient, cancelled) -> {
            ensureActive(cancelled);
            String privateKey = privateKeySupplier.get();
            ensureActive(cancelled);
            char[] passphrase = passphraseSupplier.get();
            try {
                PasswordFinder passwordFinder = passphrase == null ? null : PasswordUtils.createOneOff(passphrase);
                KeyProvider keyProvider = sshClient.loadKeys(privateKey, null, passwordFinder);
                sshClient.authPublickey(username, keyProvider);
            } finally {
                PasswordUtils.blankOut(passphrase);
                privateKey = null;
            }
        });
        return new SshConnectionResult(observation.algorithm(), observation.fingerprint(), elapsedMillis(startedAt));
    }

    /**
     * 在应用关闭时停止虚拟线程执行器，防止后台任务继续持有连接。
     */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 在整体超时和并发限制内执行一次 SSH 操作。
     *
     * @param host SSH 主机
     * @param port SSH 端口
     * @param expectedFingerprint 预期主机公钥指纹
     * @param expectedAlgorithm 预期主机公钥算法
     * @param operation SSH 操作回调
     * @return 已核对的主机密钥观察结果
     */
    private SshHostKeyObservation execute(String host, int port, String expectedFingerprint,
            String expectedAlgorithm, SshOperation operation) {
        if (!connectionPermits.tryAcquire()) {
            throw new SshConnectionException(SshConnectionException.Category.CONNECTION_LIMIT);
        }
        AtomicReference<SSHClient> activeClient = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Future<SshHostKeyObservation> future;
        try {
            future = executor.submit(() -> {
                try {
                    return connect(host, port, expectedFingerprint, expectedAlgorithm, operation, activeClient,
                            cancelled);
                } finally {
                    connectionPermits.release();
                }
            });
        } catch (RuntimeException exception) {
            connectionPermits.release();
            throw exception;
        }
        try {
            return future.get(totalTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            cancelled.set(true);
            future.cancel(true);
            closeQuietly(activeClient.get());
            throw new SshConnectionException(SshConnectionException.Category.TIMEOUT, exception);
        } catch (InterruptedException exception) {
            cancelled.set(true);
            Thread.currentThread().interrupt();
            future.cancel(true);
            closeQuietly(activeClient.get());
            throw new SshConnectionException(SshConnectionException.Category.CONNECTION_FAILED, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SshConnectionException sshException) {
                throw sshException;
            }
            throw new SshConnectionException(SshConnectionException.Category.CONNECTION_FAILED, cause);
        }
    }

    /**
     * 依次尝试已校验地址，并确保每个 SSHClient 都被关闭。
     *
     * @param host SSH 主机
     * @param port SSH 端口
     * @param expectedFingerprint 预期主机公钥指纹
     * @param expectedAlgorithm 预期主机公钥算法
     * @param operation SSH 操作回调
     * @param activeClient 当前活跃的 SSHClient 引用
     * @param cancelled 取消标记
     * @return 已核对的主机密钥观察结果
     */
    private SshHostKeyObservation connect(String host, int port, String expectedFingerprint,
            String expectedAlgorithm, SshOperation operation, AtomicReference<SSHClient> activeClient,
            AtomicBoolean cancelled) {
        List<InetAddress> addresses = outboundPolicy.resolveAndValidate(host, port);
        ensureActive(cancelled);
        SshConnectionException lastFailure = null;
        for (InetAddress address : addresses) {
            ensureActive(cancelled);
            SSHClient sshClient = new SSHClient();
            activeClient.set(sshClient);
            CapturingHostKeyVerifier verifier = new CapturingHostKeyVerifier(expectedFingerprint, expectedAlgorithm);
            sshClient.addHostKeyVerifier(verifier);
            sshClient.setConnectTimeout(connectTimeoutMillis);
            sshClient.setTimeout(socketTimeoutMillis);
            try {
                sshClient.connect(address, port);
                LOGGER.debug("SSH transport connected: host={}, port={}, negotiatedHostKeyAlgorithm={}",
                        address.getHostAddress(), port, negotiatedHostKeyAlgorithm(sshClient));
                ensureActive(cancelled);
                operation.run(sshClient, cancelled);
                return verifier.observation();
            } catch (SshConnectionException exception) {
                throw exception;
            } catch (UserAuthException exception) {
                LOGGER.debug("SSH authentication failed after verified host key: host={}, port={}, "
                                + "exceptionType={}, negotiatedHostKeyAlgorithm={}, observedAlgorithm={}",
                        address.getHostAddress(), port, exception.getClass().getSimpleName(),
                        negotiatedHostKeyAlgorithm(sshClient), verifier.observedAlgorithm());
                throw new SshConnectionException(SshConnectionException.Category.AUTHENTICATION_FAILED, exception);
            } catch (IOException | RuntimeException exception) {
                ensureActive(cancelled);
                LOGGER.debug("SSH transport failed: host={}, port={}, exceptionType={}, "
                                + "negotiatedHostKeyAlgorithm={}, observed={}, matched={}, observedAlgorithm={}",
                        address.getHostAddress(), port, exception.getClass().getSimpleName(),
                        negotiatedHostKeyAlgorithm(sshClient), verifier.observed(), verifier.matched(),
                        verifier.observedAlgorithm());
                if (verifier.observed() && !verifier.matched()) {
                    throw new SshConnectionException(SshConnectionException.Category.HOST_KEY_MISMATCH, exception);
                }
                lastFailure = new SshConnectionException(SshConnectionException.Category.CONNECTION_FAILED, exception);
            } finally {
                closeQuietly(sshClient);
                activeClient.compareAndSet(sshClient, null);
            }
        }
        throw lastFailure == null
                ? new SshConnectionException(SshConnectionException.Category.CONNECTION_FAILED)
                : lastFailure;
    }

    /**
     * 读取 sshj 实际协商的主机密钥算法，连接尚未建立时返回 null。
     *
     * @param sshClient SSH 客户端
     * @return 协商的主机密钥算法，或 null
     */
    private String negotiatedHostKeyAlgorithm(SSHClient sshClient) {
        if (sshClient.getTransport() == null || sshClient.getTransport().getHostKeyAlgorithm() == null) {
            return null;
        }
        return sshClient.getTransport().getHostKeyAlgorithm().getKeyAlgorithm();
    }

    /**
     * 在超时或调用线程中断后阻止继续尝试地址或取得凭据。
     *
     * @param cancelled 取消标记
     */
    private void ensureActive(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new SshConnectionException(SshConnectionException.Category.TIMEOUT);
        }
    }

    /**
     * 不传播关闭异常，避免覆盖更重要的主机身份或认证失败。
     *
     * @param sshClient SSH 客户端
     */
    private void closeQuietly(SSHClient sshClient) {
        if (sshClient == null) {
            return;
        }
        try {
            sshClient.close();
        } catch (IOException ignored) {
            // 连接结果已经确定，关闭失败不能替换原始安全结果。
        }
    }

    /**
     * 计算单次连接测试耗时。
     *
     * @param startedAt 起始纳秒时间
     * @return 耗时毫秒数
     */
    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /**
     * SSHClient 建连后的可选认证操作，由 execute 方法在连接建立后回调。
     */
    @FunctionalInterface
    private interface SshOperation {
        void run(SSHClient sshClient, AtomicBoolean cancelled) throws IOException;
    }

    /**
     * 捕获远端公钥并使用 sshj SHA-256 verifier 完成恒定内容比较。
     */
    static final class CapturingHostKeyVerifier implements HostKeyVerifier {

        private final HostKeyVerifier fingerprintVerifier;
        private final String expectedAlgorithm;
        private volatile SshHostKeyObservation observation;
        private volatile boolean matched;

        /**
         * 构造捕获型主机密钥验证器。
         *
         * @param expectedFingerprint 预期主机公钥指纹
         * @param expectedAlgorithm 预期主机公钥算法
         */
        CapturingHostKeyVerifier(String expectedFingerprint, String expectedAlgorithm) {
            this.fingerprintVerifier = FingerprintVerifier.getInstance(expectedFingerprint);
            this.expectedAlgorithm = expectedAlgorithm;
        }

        /** 计算算法和 SHA-256 指纹，并同时校验登记算法与指纹。 */
        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            String algorithm = KeyType.fromKey(key).toString();
            String fingerprint = sha256Fingerprint(key);
            this.observation = new SshHostKeyObservation(algorithm, fingerprint);
            boolean algorithmMatches = expectedAlgorithm == null || expectedAlgorithm.equals(algorithm);
            boolean fingerprintMatches = fingerprintVerifier.verify(hostname, port, key);
            this.matched = algorithmMatches && fingerprintMatches;
            LOGGER.debug("SSH host key observed: host={}, port={}, algorithm={}, fingerprint={}, "
                            + "algorithmMatched={}, fingerprintMatched={}",
                    hostname, port, algorithm, fingerprint, algorithmMatches, fingerprintMatches);
            return matched;
        }

        /** 不提示 sshj 放宽或替换算法，只接受正常协商结果。 */
        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            LOGGER.debug("SSH host key algorithm lookup: host={}, port={}, algorithms=[]", hostname, port);
            return List.of();
        }

        /** 返回已观察到的算法，仅用于连接失败时的诊断日志和同包测试。 */
        String observedAlgorithm() {
            return observation == null ? null : observation.algorithm();
        }

        /** 返回已观察且通过校验的主机密钥。 */
        private SshHostKeyObservation observation() {
            if (!matched || observation == null) {
                throw new SshConnectionException(SshConnectionException.Category.HOST_KEY_MISMATCH);
            }
            return observation;
        }

        /**
         * 返回是否已观察到主机公钥。
         *
         * @return 是否已观察到
         */
        private boolean observed() {
            return observation != null;
        }

        /**
         * 返回算法和指纹是否完全匹配预期。
         *
         * @return 是否完全匹配
         */
        boolean matched() {
            return matched;
        }

        /** 按 OpenSSH 公钥 blob 计算无填充 SHA-256 指纹。 */
        private static String sha256Fingerprint(PublicKey key) {
            try {
                byte[] keyBlob = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyBlob);
                return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
            } catch (GeneralSecurityException exception) {
                throw new SshConnectionException(SshConnectionException.Category.CONNECTION_FAILED, exception);
            }
        }
    }

    /** 保存经过握手验证的主机公钥算法和指纹。 */
    public record SshHostKeyObservation(String algorithm, String fingerprint) {
    }

    /** 保存认证成功后的主机身份和耗时。 */
    public record SshConnectionResult(String algorithm, String fingerprint, long durationMillis) {
    }
}
