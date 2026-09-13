package com.susumonitor.server.module.ai.provider;

import com.susumonitor.server.config.AppProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * AI 个人 Provider 出站地址策略（SSRF 防护，2026-09-14 安全评审三件套之一）。
 *
 * <p>管理员个人配置的 baseUrl 可指向任意地址，若不校验，失陷的管理员账户可借
 * 服务端出站探测内网拓扑或云 metadata。本策略在 {@code deny-private}（默认）下
 * 拒绝解析结果落在环回/私网/链路本地/组播/云 metadata/IPv6 ULA 的 endpoint；
 * {@code allow-private} 时放行——保留"内网网关"合法场景（与 allow-insecure-http
 * 同一口径，见复查结论"不能一刀切禁私网，需配置开关区分"）。</p>
 *
 * <p>作用域：仅约束按个人配置构造的 provider（保存/测试时与每次出站前双重校验）；
 * 全局系统 Provider 的 baseUrl 由运维配置，不经过本策略。校验在出站前即时解析 DNS，
 * 校验与真实连接之间残余的极小 TOCTOU 窗口（受 OS 正向 DNS 缓存影响）为已接受的
 * 已知限制，记录于开发日志。</p>
 */
// 注册为普通 Bean：AI 未启用时不影响装配，启用方按需注入。
@Component
public class AiEgressPolicy {

    /** 单次解析允许返回的地址上限：超过视为解析异常，直接拒绝（与 SSH 出站口径一致）。 */
    private static final int MAX_RESOLVED_ADDRESSES = 8;

    /** 云厂商 metadata 服务地址（v4/v6、EC2/ECS/腾讯云），内网探测的最高价值目标。 */
    private static final Set<String> CLOUD_METADATA_ADDRESSES = Set.of(
            "169.254.169.254", "169.254.170.2", "100.100.100.200",
            "fd00:ec2::254", "fd00:ec2:0:0:0:0:0:254");

    private final AppProperties appProperties;

    /** 注入全局配置以读取 {@code susumonitor.ai.user-provider-egress-policy} 开关。 */
    public AiEgressPolicy(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 出站地址校验结论：allowed=false 时 reason 给出拦截类别（面向日志与错误面，
     * 不回显完整解析地址，避免把内网拓扑泄漏到接口响应）。
     */
    public record Verdict(boolean allowed, String reason) {

        /** 放行结论。 */
        static Verdict allow() {
            return new Verdict(true, null);
        }

        /** 拦截结论与类别说明。 */
        static Verdict block(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * 校验个人 Provider endpoint 的出站合法性。
     *
     * <p>调用方需保证 baseUrl 已通过 scheme/长度校验；本方法对格式非法输入按
     * fail-closed 处理（视为拦截），不做二次宽容。</p>
     *
     * @param baseUrl 个人配置的 endpoint（未归一化原文即可）
     * @return 放行或拦截结论
     */
    public Verdict check(String baseUrl) {
        // 开关在 allow-private 时直接放行：内网网关场景由运维显式选择承担内网可达性。
        if ("allow-private".equals(appProperties.getAi().getUserProviderEgressPolicy())) {
            return Verdict.allow();
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            return Verdict.block("endpoint 不是合法 URI");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Verdict.block("endpoint 缺少主机名");
        }
        // IP 字面量快速路径：不经过 DNS 直接判定（getByName 对纯数字/冒号串不会发起解析，
        // 但显式预判可避免"0177.0.0.1"等非常规写法绕过校验语义）。
        InetAddress literal = parseIpLiteral(host);
        if (literal != null) {
            return verdictFor(literal);
        }
        InetAddress[] resolved;
        try {
            resolved = resolveAllByName(host);
        } catch (UnknownHostException exception) {
            return Verdict.block("endpoint 主机名无法解析");
        }
        if (resolved.length == 0 || resolved.length > MAX_RESOLVED_ADDRESSES) {
            return Verdict.block("endpoint 解析地址数量异常");
        }
        for (InetAddress address : resolved) {
            Verdict verdict = verdictFor(address);
            if (!verdict.allowed()) {
                return verdict;
            }
        }
        return Verdict.allow();
    }

    /**
     * DNS 解析钩子：默认走系统解析器；测试可覆写以注入确定性结果——
     * 本机/内网 DNS 存在 NXDOMAIN 劫持（任意域名都可能返回地址），依赖真实解析
     * 会使"解析失败被拒"的用例不可复现。
     *
     * @param host 主机名
     * @return 解析结果数组
     * @throws UnknownHostException 解析失败
     */
    InetAddress[] resolveAllByName(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    /**
     * 单地址判定：命中任一受限类别即拦截。
     *
     * @param address 解析（或字面量）得到的地址
     * @return 放行或拦截结论
     */
    private static Verdict verdictFor(InetAddress address) {
        String normalized = normalize(address);
        boolean forbidden = address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || address.isLinkLocalAddress()
                || address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || isUniqueLocalIpv6(address)
                || CLOUD_METADATA_ADDRESSES.contains(normalized);
        return forbidden
                ? Verdict.block("endpoint 解析到受限地址（环回/私网/链路本地/云 metadata）")
                : Verdict.allow();
    }

    /**
     * 解析 IP 字面量（含 IPv6 方括号形式）；非字面量返回 null 交由 DNS 路径处理。
     *
     * @param host URI 提取的主机名（IPv6 字面量带方括号）
     * @return 字面量地址，非字面量为 null
     */
    private static InetAddress parseIpLiteral(String host) {
        String value = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        // 仅数字/点/冒号组合才可能是字面量；其余字符直接按域名处理，避免误触发解析。
        if (value.isEmpty() || !value.matches("^[0-9A-Fa-f:.]+$")) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    /**
     * 判断 IPv6 ULA（fc00::/7）：Java 的 isSiteLocalAddress 只覆盖已废弃的 fec0::/10，
     * 现代私网 IPv6 使用 ULA，需要按首字节前 7 位单独判定。
     *
     * @param address 待判定地址
     * @return 是否为 ULA 地址
     */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * 去掉 IPv6 本地链路作用域后缀（如 fe80::1%eth0），得到可查表的规范文本。
     *
     * @param address 待规范化地址
     * @return 规范地址文本
     */
    private static String normalize(InetAddress address) {
        String normalized = address.getHostAddress();
        int scopeIndex = normalized.indexOf('%');
        return scopeIndex >= 0 ? normalized.substring(0, scopeIndex) : normalized;
    }
}
