package com.susumonitor.server.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.susumonitor.server.config.AppProperties;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import net.schmizz.sshj.common.Buffer;
import org.junit.jupiter.api.Test;

/**
 * 验证 sshj 主机密钥 verifier 的算法选择和指纹校验行为，以及握手/连接失败阶段的超时分类。
 */
class SshConnectionTesterTests {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 2223;

    /** 验证空算法列表会让 sshj 使用默认主机密钥算法协商，不改变当前行为。 */
    @Test
    void findExistingAlgorithmsShouldKeepDefaultNegotiation() {
        SshConnectionTester.CapturingHostKeyVerifier verifier =
                new SshConnectionTester.CapturingHostKeyVerifier("SHA256:unused", null);

        assertEquals(List.of(), verifier.findExistingAlgorithms(HOST, PORT));
    }

    /** 验证正确的 RSA 公钥指纹和算法可以通过主机身份校验。 */
    @Test
    void verifyShouldAcceptMatchingRsaFingerprintAndAlgorithm() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String fingerprint = sha256Fingerprint(keyPair.getPublic());
        SshConnectionTester.CapturingHostKeyVerifier verifier =
                new SshConnectionTester.CapturingHostKeyVerifier(fingerprint, "ssh-rsa");

        assertTrue(verifier.verify(HOST, PORT, keyPair.getPublic()));
        assertTrue(verifier.matched());
        assertEquals("ssh-rsa", verifier.observedAlgorithm());
    }

    /** 验证错误指纹不能通过，即使公钥算法登记正确。 */
    @Test
    void verifyShouldRejectWrongFingerprint() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        SshConnectionTester.CapturingHostKeyVerifier verifier =
                new SshConnectionTester.CapturingHostKeyVerifier(
                        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "ssh-rsa");

        assertFalse(verifier.verify(HOST, PORT, keyPair.getPublic()));
        assertFalse(verifier.matched());
        assertEquals("ssh-rsa", verifier.observedAlgorithm());
    }

    /** 验证指纹正确但算法登记不匹配时仍拒绝主机身份。 */
    @Test
    void verifyShouldRejectAlgorithmMismatch() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        SshConnectionTester.CapturingHostKeyVerifier verifier =
                new SshConnectionTester.CapturingHostKeyVerifier(sha256Fingerprint(keyPair.getPublic()), "ED25519");

        assertFalse(verifier.verify(HOST, PORT, keyPair.getPublic()));
        assertFalse(verifier.matched());
    }

    /** 验证观察模式（指纹为 null）只记录公钥，不比对且 matched 恒为 true。 */
    @Test
    void observeModeShouldRecordKeyWithoutFingerprintComparison() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String fingerprint = sha256Fingerprint(keyPair.getPublic());
        SshConnectionTester.CapturingHostKeyVerifier verifier =
                new SshConnectionTester.CapturingHostKeyVerifier(null, null);

        assertTrue(verifier.verify(HOST, PORT, keyPair.getPublic()));
        assertTrue(verifier.matched());
        assertEquals("ssh-rsa", verifier.observedAlgorithm());
        assertEquals(fingerprint, verifier.observedFingerprint());
    }

    /** 验证握手阶段读取服务器标识超时被分类为 TIMEOUT 而非 CONNECTION_FAILED。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void handshakeReadTimeoutShouldBeClassifiedAsTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            int port = server.getLocalPort();
            SshConnectionTester tester = connectionTester(port, 1, 30, 30);

            SshConnectionException exception = assertThrows(SshConnectionException.class,
                    () -> tester.verifyHostKey(HOST, port, "SHA256:unused"));

            assertEquals(SshConnectionException.Category.TIMEOUT, exception.getCategory());
        }
    }

    /** 验证连接被拒绝时仍分类为 CONNECTION_FAILED，不误报为超时。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void connectionRefusedShouldBeClassifiedAsConnectionFailed() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            port = server.getLocalPort();
        }
        SshConnectionTester tester = connectionTester(port, 10, 15, 30);

        SshConnectionException exception = assertThrows(SshConnectionException.class,
                () -> tester.verifyHostKey(HOST, port, "SHA256:unused"));

        assertEquals(SshConnectionException.Category.CONNECTION_FAILED, exception.getCategory());
    }

    /** 验证整体连接超时取消仍分类为 TIMEOUT。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void overallTimeoutShouldBeClassifiedAsTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            int port = server.getLocalPort();
            SshConnectionTester tester = connectionTester(port, 30, 30, 1);

            SshConnectionException exception = assertThrows(SshConnectionException.class,
                    () -> tester.verifyHostKey(HOST, port, "SHA256:unused"));

            assertEquals(SshConnectionException.Category.TIMEOUT, exception.getCategory());
        }
    }

    /** 使用指定超时参数和允许端口构造不依赖 Spring 上下文的连接测试器。 */
    private SshConnectionTester connectionTester(int allowedPort, int connectTimeoutSeconds,
            int socketTimeoutSeconds, int totalTimeoutSeconds) {
        AppProperties properties = new AppProperties();
        properties.getSsh().setAllowedPorts(List.of(allowedPort));
        properties.getSsh().setAllowedCidrs(List.of("127.0.0.0/8"));
        properties.getSsh().setConnectTimeoutSeconds(connectTimeoutSeconds);
        properties.getSsh().setSocketTimeoutSeconds(socketTimeoutSeconds);
        properties.getSsh().setTotalTimeoutSeconds(totalTimeoutSeconds);
        return new SshConnectionTester(new SshOutboundPolicy(properties), properties);
    }

    /** 使用 SSH 公钥 blob 计算与 OpenSSH 和 sshj 一致的无填充 SHA-256 指纹。 */
    private String sha256Fingerprint(PublicKey key) throws Exception {
        byte[] keyBlob = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyBlob);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    }

    /** 生成测试专用 RSA 公钥，不写入磁盘或测试资源。 */
    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
