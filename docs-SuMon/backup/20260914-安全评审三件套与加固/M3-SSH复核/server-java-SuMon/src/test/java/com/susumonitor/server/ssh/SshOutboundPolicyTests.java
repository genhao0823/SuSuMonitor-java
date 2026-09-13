package com.susumonitor.server.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.susumonitor.server.config.AppProperties;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 SSH 出站端口、CIDR 和云元数据地址安全边界。
 */
class SshOutboundPolicyTests {

    private static final int ALLOWED_PORT = 22;
    private static final int FORBIDDEN_PORT = 23;

    /** 验证白名单端口和 CIDR 内的数字 IP 可被解析并返回。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void allowedPortAndCidrShouldReturnAddress() {
        SshOutboundPolicy policy = policy(List.of(ALLOWED_PORT), List.of("192.0.2.0/24"));

        List<InetAddress> result = policy.resolveAndValidate("192.0.2.10", ALLOWED_PORT);

        assertEquals(1, result.size());
        assertEquals("192.0.2.10", result.getFirst().getHostAddress());
    }

    /** 验证不在端口白名单内的请求在地址解析前被拒绝。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void forbiddenPortShouldBeRejected() {
        SshOutboundPolicy policy = policy(List.of(ALLOWED_PORT), List.of("192.0.2.0/24"));

        assertCategory(SshConnectionException.Category.TARGET_FORBIDDEN,
                () -> policy.resolveAndValidate("192.0.2.10", FORBIDDEN_PORT));
    }

    /** 验证允许端口上的 CIDR 外地址仍被拒绝。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void addressOutsideAllowedCidrShouldBeRejected() {
        SshOutboundPolicy policy = policy(List.of(ALLOWED_PORT), List.of("192.0.2.0/24"));

        assertCategory(SshConnectionException.Category.TARGET_FORBIDDEN,
                () -> policy.resolveAndValidate("198.51.100.10", ALLOWED_PORT));
    }

    /** 验证云元数据地址即使落入宽泛允许 CIDR 也始终被拒绝。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void cloudMetadataAddressShouldAlwaysBeRejected() {
        SshOutboundPolicy policy = policy(List.of(ALLOWED_PORT), List.of("0.0.0.0/0"));

        assertCategory(SshConnectionException.Category.TARGET_FORBIDDEN,
                () -> policy.resolveAndValidate("169.254.169.254", ALLOWED_PORT));
    }

    /** 验证 CIDR 配置不能使用会触发 DNS 的主机名。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void hostnameCidrShouldFailDuringPolicyCreation() {
        AppProperties properties = new AppProperties();
        properties.getSsh().setAllowedCidrs(List.of("localhost/32"));

        assertThrows(IllegalArgumentException.class, () -> new SshOutboundPolicy(properties));
    }

    /** 使用指定端口和 CIDR 创建不依赖 Spring 上下文的出站策略。 */
    private SshOutboundPolicy policy(List<Integer> ports, List<String> cidrs) {
        AppProperties properties = new AppProperties();
        properties.getSsh().setAllowedPorts(ports);
        properties.getSsh().setAllowedCidrs(cidrs);
        return new SshOutboundPolicy(properties);
    }

    /** 执行策略调用并断言稳定 SSH 失败分类。 */
    private void assertCategory(SshConnectionException.Category category, Runnable action) {
        SshConnectionException exception = assertThrows(SshConnectionException.class, action::run);
        assertEquals(category, exception.getCategory());
    }
}
