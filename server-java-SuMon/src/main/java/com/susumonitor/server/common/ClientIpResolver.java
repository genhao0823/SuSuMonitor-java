package com.susumonitor.server.common;

import com.susumonitor.server.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 从 HTTP 请求解析客户端 IP（登录防爆破用，Redis 安全加固一期，2026-08-18）。
 *
 * <p>仅对显式可信代理（复用 {@code AGENT_TRUSTED_PROXY_CIDRS} 配置）接受 X-Forwarded-For，
 * 从右向左取首个非代理地址；与 Agent 握手场景的 {@code AgentClientIpResolver} 同逻辑
 * （入参类型不同不复用，后续可考虑合并抽取）。</p>
 */
@Component
public class ClientIpResolver {

    private final List<CidrRange> trustedProxies;

    /** 在应用启动期解析可信代理 CIDR，非法配置会阻止服务以不安全方式启动。 */
    public ClientIpResolver(AppProperties appProperties) {
        trustedProxies = appProperties.getAgent().getTrustedProxyCidrs().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(CidrRange::parse).toList();
    }

    /**
     * 返回客户端 IP：peer 为可信代理时取 XFF 右起首个非代理地址，否则直接返回 peer。
     *
     * @param request HTTP 请求
     * @return 客户端 IP 字符串
     */
    public String resolve(HttpServletRequest request) {
        InetAddress peer = numericAddress(request.getRemoteAddr());
        if (peer == null) {
            throw new IllegalArgumentException("HTTP client remote address is unavailable");
        }
        if (trustedProxies.stream().noneMatch(range -> range.contains(peer))) {
            return peer.getHostAddress();
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
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
        if (value == null || value.isBlank()
                || !(value.matches("^[0-9.]+$") || value.matches("^[0-9A-Fa-f:.]+$"))) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (Exception exception) {
            return null;
        }
    }

    /** 保存已解析的 IPv4 或 IPv6 CIDR（与 AgentClientIpResolver 同逻辑）。 */
    private record CidrRange(byte[] network, int prefixLength) {

        /** 从 "网络地址/前缀长度" 字符串解析 CIDR 段。 */
        private static CidrRange parse(String value) {
            String[] parts = value.trim().split("/", -1);
            InetAddress address = parts.length == 2 ? numericAddress(parts[0]) : null;
            if (address == null) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            }
            try {
                int prefix = Integer.parseInt(parts[1]);
                int maximum = address.getAddress().length * Byte.SIZE;
                if (prefix < 0 || prefix > maximum) {
                    throw new IllegalArgumentException("Invalid trusted proxy CIDR prefix");
                }
                byte[] network = address.getAddress().clone();
                clearHostBits(network, prefix);
                return new CidrRange(network, prefix);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR", exception);
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
