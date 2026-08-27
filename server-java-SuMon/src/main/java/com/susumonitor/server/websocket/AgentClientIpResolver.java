package com.susumonitor.server.websocket;

import com.susumonitor.server.config.AppProperties;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;

/** 从握手请求解析客户端 IP，仅对显式可信代理接受 X-Forwarded-For。 */
@Component
public class AgentClientIpResolver {

    private final List<CidrRange> trustedProxies;

    /** 在应用启动期解析可信代理 CIDR，非法配置会阻止服务以不安全方式启动。 */
    public AgentClientIpResolver(AppProperties appProperties) {
        trustedProxies = appProperties.getAgent().getTrustedProxyCidrs().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(CidrRange::parse).toList();
    }

    /** 返回 peer IP；仅当 peer 为可信代理时，从 XFF 右向左选择首个非代理地址。 */
    public String resolve(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            throw new IllegalArgumentException("Agent WebSocket remote address is unavailable");
        }
        InetAddress peer = remoteAddress.getAddress();
        if (trustedProxies.stream().noneMatch(range -> range.contains(peer))) {
            return peer.getHostAddress();
        }
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return peer.getHostAddress();
        }
        String[] values = forwardedFor.split(",");
        for (int index = values.length - 1; index >= 0; index--) {
            InetAddress candidate = numericAddress(values[index].trim());
            if (candidate == null) {
                return peer.getHostAddress();
            }
            if (trustedProxies.stream().noneMatch(range -> range.contains(candidate))) {
                return candidate.getHostAddress();
            }
        }
        return peer.getHostAddress();
    }

    /** 将纯数字格式的 IP 地址字符串解析为 InetAddress，非法格式返回 null。 */
    private static InetAddress numericAddress(String value) {
        if (value.isBlank() || !(value.matches("^[0-9.]+$") || value.matches("^[0-9A-Fa-f:.]+$"))) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (Exception exception) {
            return null;
        }
    }

    /** 保存已解析的 IPv4 或 IPv6 CIDR。 */
    private record CidrRange(byte[] network, int prefixLength) {

        /** 从 "网络地址/前缀长度" 字符串解析 CIDR 段。 */
        private static CidrRange parse(String value) {
            String[] parts = value.trim().split("/", -1);
            InetAddress address = parts.length == 2 ? numericAddress(parts[0]) : null;
            if (address == null) {
                throw new IllegalArgumentException("Invalid Agent trusted proxy CIDR");
            }
            try {
                int prefix = Integer.parseInt(parts[1]);
                int maximum = address.getAddress().length * Byte.SIZE;
                if (prefix < 0 || prefix > maximum) {
                    throw new IllegalArgumentException("Invalid Agent trusted proxy CIDR prefix");
                }
                byte[] network = address.getAddress().clone();
                clearHostBits(network, prefix);
                return new CidrRange(network, prefix);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid Agent trusted proxy CIDR", exception);
            }
        }

        /** 判断目标 IP 地址是否在此 CIDR 范围内。 */
        private boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress().clone();
            if (candidate.length != network.length) {
                return false;
            }
            clearHostBits(candidate, prefixLength);
            return Arrays.equals(network, candidate);
        }

        /** 将地址中超出前缀长度的主机位置零。 */
        private static void clearHostBits(byte[] address, int prefixLength) {
            int fullBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            if (remainingBits > 0) {
                int mask = 0xFF << (Byte.SIZE - remainingBits);
                address[fullBytes] = (byte) (address[fullBytes] & mask);
                fullBytes++;
            }
            Arrays.fill(address, fullBytes, address.length, (byte) 0);
        }
    }
}
