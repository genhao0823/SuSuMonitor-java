package com.susumonitor.server.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.susumonitor.server.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 验证 HTTP 客户端 IP 解析：直连 peer、可信代理 XFF 取首个非代理地址。 */
class ClientIpResolverTests {

    /** 无可信代理配置时直接返回 peer 地址。 */
    @Test
    void resolveShouldReturnPeerWhenNoTrustedProxy() {
        AppProperties properties = new AppProperties();
        properties.getAgent().setTrustedProxyCidrs(List.of());
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");
        request.addHeader("X-Forwarded-For", "203.0.113.5");

        assertEquals("192.168.1.10", resolver.resolve(request));
    }

    /** peer 为可信代理时取 XFF 右起首个非代理地址。 */
    @Test
    void resolveShouldUseXffWhenPeerIsTrustedProxy() {
        AppProperties properties = new AppProperties();
        properties.getAgent().setTrustedProxyCidrs(List.of("127.0.0.1/32", "172.16.0.0/12"));
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.16.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 172.16.0.3");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    /** XFF 全为代理地址时回退到 peer。 */
    @Test
    void resolveShouldFallBackToPeerWhenAllXffAreProxies() {
        AppProperties properties = new AppProperties();
        properties.getAgent().setTrustedProxyCidrs(List.of("172.16.0.0/12"));
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.16.0.2");
        request.addHeader("X-Forwarded-For", "172.16.0.4, 172.16.0.3");

        assertEquals("172.16.0.2", resolver.resolve(request));
    }
}
