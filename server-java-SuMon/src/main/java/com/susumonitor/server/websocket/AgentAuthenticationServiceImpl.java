package com.susumonitor.server.websocket;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.server.entity.ServerEntity;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 校验 Agent 首帧中的服务器 ID 和 Token 哈希，不记录或返回明文 Token。
 *
 * <p>轮换宽限（2026-09-14 安全评审"加固"项）：Token 轮换后旧摘要移入
 * {@code agent_token_hash_prev} 并登记截止时间 {@code agent_token_grace_until}；
 * 宽限窗口内旧 Token 仍可完成握手，避免在线 Agent 因轮换瞬断被拒。窗口外或
 * revoke 后旧 Token 一律失效。</p>
 */
@Slf4j
@Service
public class AgentAuthenticationServiceImpl implements AgentAuthenticationService {

    private static final String HASH_PREFIX = "sha256:";
    private final ServerMapper serverMapper;

    /** 注入服务器 Mapper。 */
    public AgentAuthenticationServiceImpl(ServerMapper serverMapper) {
        this.serverMapper = serverMapper;
    }

    /** 校验 Agent Token 并返回有效服务器快照。 */
    public ServerEntity authenticate(Long serverId, String token) {
        if (serverId == null || serverId <= 0 || token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        ServerEntity server = serverMapper.selectActiveServerAgentTokenById(serverId);
        if (server == null || server.getAgentTokenHash() == null
                || server.getAgentTokenRevokedAt() != null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (matches(server.getAgentTokenHash(), token)) {
            return server;
        }
        // 旧 Token 仅在宽限窗口内放行：命中记 info 留痕，窗口外与撤销后立即拒绝。
        if (matchesWithinGrace(server, token)) {
            log.info("agent authenticated with prev token within grace window, serverId={}", serverId);
            return server;
        }
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    /**
     * 判断 Token 是否命中宽限期内旧摘要。
     *
     * @param server 服务器快照（含旧摘要与宽限截止）
     * @param token Agent 明文 Token
     * @return 旧 Token 且宽限未过期时为 true
     */
    private boolean matchesWithinGrace(ServerEntity server, String token) {
        String prevHash = server.getAgentTokenHashPrev();
        LocalDateTime graceUntil = server.getAgentTokenGraceUntil();
        if (prevHash == null || graceUntil == null) {
            return false;
        }
        // 宽限截止使用 UTC 与库内 DATETIME 口径一致；revoked_at 非空已在上游统一拦截。
        boolean graceActive = LocalDateTime.now(ZoneOffset.UTC).isBefore(graceUntil);
        return graceActive && matches(prevHash, token);
    }

    /** 比较存储的 Token 哈希与明文 Token 的 SHA-256 摘要。 */
    private boolean matches(String storedHash, String token) {
        if (!storedHash.startsWith(HASH_PREFIX)) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(storedHash.substring(HASH_PREFIX.length()));
        byte[] actual = sha256(token);
        return MessageDigest.isEqual(expected, actual);
    }

    /** 计算字符串的 SHA-256 摘要。 */
    private byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
