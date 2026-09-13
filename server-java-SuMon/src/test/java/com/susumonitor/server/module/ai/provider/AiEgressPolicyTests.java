package com.susumonitor.server.module.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.susumonitor.server.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 个人 Provider 出站地址策略（SSRF 防护）：IP 字面量/域名解析的受限地址
 * 全量拦截，allow-private 开关放行内网网关场景，解析失败按 fail-closed 处理。
 */
class AiEgressPolicyTests {

    private AppProperties appProperties;
    private AiEgressPolicy policy;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        // 默认即 deny-private：显式赋值以表达用例前置，避免依赖字段默认值。
        appProperties.getAi().setUserProviderEgressPolicy("deny-private");
        policy = new AiEgressPolicy(appProperties);
    }

    /** 公网 IP 字面量直接放行（不经过 DNS）。 */
    @Test
    void publicIpLiteralShouldBeAllowed() {
        assertTrue(policy.check("https://93.184.216.34/v1").allowed());
    }

    /** 环回/私网/云 metadata/ULA/组播/未指定地址的 IPv4 字面量全部拦截。 */
    @Test
    void restrictedIpv4LiteralsShouldBeBlocked() {
        assertTrue(policy.check("https://127.0.0.1/v1").reason().contains("受限地址"));
        assertTrue(policy.check("https://10.1.2.3/v1").reason().contains("受限地址"));
        assertTrue(policy.check("https://172.16.0.9/v1").reason().contains("受限地址"));
        assertTrue(policy.check("https://192.168.1.1/v1").reason().contains("受限地址"));
        assertTrue(policy.check("https://0.0.0.0/v1").reason().contains("受限地址"));
        assertTrue(policy.check("https://169.254.169.254/latest/meta-data").reason().contains("受限地址"));
        assertTrue(policy.check("https://100.100.100.200/v1").reason().contains("受限地址"));
        // 非常规进制写法（0177.0.0.1 等）的解析结果随 JDK 版本语义变化，但 Java 校验与
        // 连接使用同一 getByName 结果，语义自洽，不在此做平台相关断言。
    }

    /** IPv6 环回、ULA 与云 metadata v6 字面量全部拦截。 */
    @Test
    void restrictedIpv6LiteralsShouldBeBlocked() {
        assertTrue(policy.check("https://[::1]/v1").reason().contains("受限地址"));
        assertTrue(policy.check("https://[fd00::1]/v1").reason().contains("受限地址"));
        assertTrue(policy.check("https://[fe80::1]/v1").reason().contains("受限地址"));
        assertTrue(policy.check("https://[fd00:ec2::254]/v1").reason().contains("受限地址"));
    }

    /** localhost 域名经解析落在环回地址，必须拦截。 */
    @Test
    void localhostHostnameShouldBeBlocked() {
        assertFalse(policy.check("https://localhost/v1").allowed());
    }

    /** 无法解析的域名按 fail-closed 拦截（注入确定性失败解析器：本机 DNS 存在 NXDOMAIN 劫持）。 */
    @Test
    void unresolvableHostnameShouldBeBlocked() {
        AiEgressPolicy failingResolverPolicy = new AiEgressPolicy(appProperties) {
            @Override
            java.net.InetAddress[] resolveAllByName(String host) throws java.net.UnknownHostException {
                throw new java.net.UnknownHostException(host);
            }
        };
        assertTrue(failingResolverPolicy.check("https://nonexistent.invalid/v1").reason().contains("无法解析"));
    }

    /** allow-private 开关直接放行私网地址：内网网关场景由运维显式选择。 */
    @Test
    void allowPrivatePolicyShouldPermitPrivateAddresses() {
        appProperties.getAi().setUserProviderEgressPolicy("allow-private");
        assertTrue(policy.check("https://10.0.0.5:8443/v1").allowed());
        assertTrue(policy.check("https://192.168.10.20/v1").allowed());
    }
}
