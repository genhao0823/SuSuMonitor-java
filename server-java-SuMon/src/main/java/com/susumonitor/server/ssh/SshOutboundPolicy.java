package com.susumonitor.server.ssh;

import com.susumonitor.server.config.AppProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 解析并校验 SSH 出站目标，确保连接只使用显式允许的端口和 CIDR。
 */
// 将出站策略注册为 Spring Bean，供 SSH 网络组件统一复用。
@Component
public class SshOutboundPolicy {

    private static final Set<String> CLOUD_METADATA_ADDRESSES = Set.of(
            "169.254.169.254", "169.254.170.2", "100.100.100.200",
            "fd00:ec2::254", "fd00:ec2:0:0:0:0:0:254");

    private final Set<Integer> allowedPorts;
    private final List<CidrRange> allowedRanges;
    private final int maxResolvedAddresses;

    /**
     * 预解析配置中的端口和 CIDR，使非法安全配置在应用启动阶段失败。
     *
     * @param appProperties 应用配置
     */
    public SshOutboundPolicy(AppProperties appProperties) {
        AppProperties.Ssh ssh = appProperties.getSsh();
        this.allowedPorts = Set.copyOf(ssh.getAllowedPorts());
        this.allowedRanges = ssh.getAllowedCidrs().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(CidrRange::parse)
                .toList();
        this.maxResolvedAddresses = ssh.getMaxResolvedAddresses();
        if (allowedPorts.stream().anyMatch(port -> port == null || port < 1 || port > 65535)) {
            throw new IllegalArgumentException("SSH allowed ports contain an invalid value");
        }
    }

    /**
     * 解析主机并确保所有结果均满足安全策略，返回后续可直接连接的地址。
     *
     * @param host SSH 主机名或地址
     * @param port SSH 端口
     * @return 已校验且不会再次解析的 IP 地址列表
     */
    public List<InetAddress> resolveAndValidate(String host, int port) {
        if (host == null || host.isBlank() || !allowedPorts.contains(port)) {
            throw new SshConnectionException(SshConnectionException.Category.TARGET_FORBIDDEN);
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new SshConnectionException(SshConnectionException.Category.CONNECTION_FAILED, exception);
        }
        if (resolved.length == 0 || resolved.length > maxResolvedAddresses) {
            throw new SshConnectionException(SshConnectionException.Category.TARGET_FORBIDDEN);
        }
        List<InetAddress> addresses = new ArrayList<>(resolved.length);
        for (InetAddress address : resolved) {
            validateAddress(address);
            addresses.add(address);
        }
        return List.copyOf(addresses);
    }

    /**
     * 校验单个解析地址不是特殊地址且位于显式允许的 CIDR。
     *
     * @param address 待校验的 IP 地址
     */
    private void validateAddress(InetAddress address) {
        String normalized = address.getHostAddress();
        int scopeIndex = normalized.indexOf('%');
        if (scopeIndex >= 0) {
            normalized = normalized.substring(0, scopeIndex);
        }
        if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()
                || CLOUD_METADATA_ADDRESSES.contains(normalized)
                || allowedRanges.stream().noneMatch(range -> range.contains(address))) {
            throw new SshConnectionException(SshConnectionException.Category.TARGET_FORBIDDEN);
        }
    }

    /**
     * 保存一个已验证格式的 IPv4 或 IPv6 CIDR。
     *
     * @param network 网络地址字节数组
     * @param prefixLength 前缀长度
     */
    private record CidrRange(byte[] network, int prefixLength) {

        /** 将文本 CIDR 转换为网络字节和前缀长度。 */
        private static CidrRange parse(String value) {
            String[] parts = value.trim().split("/", -1);
            if (parts.length != 2 || !numericAddress(parts[0])) {
                throw new IllegalArgumentException("Invalid SSH allowed CIDR");
            }
            try {
                InetAddress address = InetAddress.getByName(parts[0]);
                int prefix = Integer.parseInt(parts[1]);
                int maxPrefix = address.getAddress().length * Byte.SIZE;
                if (prefix < 0 || prefix > maxPrefix) {
                    throw new IllegalArgumentException("Invalid SSH allowed CIDR prefix");
                }
                byte[] network = address.getAddress().clone();
                clearHostBits(network, prefix);
                return new CidrRange(network, prefix);
            } catch (UnknownHostException | NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid SSH allowed CIDR", exception);
            }
        }

        /** 只允许数字 IPv4/IPv6，避免解析 CIDR 配置时访问 DNS。 */
        private static boolean numericAddress(String address) {
            return address.contains(":")
                    ? address.matches("^[0-9A-Fa-f:.]+$")
                    : address.matches("^[0-9.]+$");
        }

        /** 判断地址是否位于当前 CIDR。 */
        private boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress().clone();
            if (candidate.length != network.length) {
                return false;
            }
            clearHostBits(candidate, prefixLength);
            return Arrays.equals(network, candidate);
        }

        /** 将前缀之外的主机位清零，得到可比较的网络地址。 */
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
