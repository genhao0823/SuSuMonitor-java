package com.susumonitor.server.websocket;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.server.entity.ServerEntity;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * 校验 Agent 首帧中的服务器 ID 和 Token 哈希，不记录或返回明文 Token。
 */
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
                || server.getAgentTokenRevokedAt() != null
                || !matches(server.getAgentTokenHash(), token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return server;
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
