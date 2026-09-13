package com.susumonitor.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.server.entity.ServerEntity;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 Agent 认证的 Token 哈希匹配与轮换宽限窗口语义（2026-09-14 加固）：
 * 当前 Token 优先；旧 Token 仅在宽限窗口内放行；撤销后旧 Token 立即失效。
 */
class AgentAuthenticationServiceTests {

    private static final long SERVER_ID = 11L;

    private ServerMapper serverMapper;

    private AgentAuthenticationServiceImpl service;

    /** 初始化被测服务。 */
    @BeforeEach
    void setUp() {
        serverMapper = mock(ServerMapper.class);
        service = new AgentAuthenticationServiceImpl(serverMapper);
    }

    /** 计算相对当前 UTC 时间的宽限截止。 */
    private LocalDateTime graceUntil(long offsetSeconds) {
        return LocalDateTime.now(ZoneOffset.UTC).plusSeconds(offsetSeconds);
    }

    /** 当前 Token 命中主哈希时认证通过，不触发宽限逻辑。 */
    @Test
    void currentTokenShouldAuthenticate() {
        when(serverMapper.selectActiveServerAgentTokenById(SERVER_ID))
                .thenReturn(server(hashOf("current-token"), null, null, null));

        ServerEntity result = service.authenticate(SERVER_ID, "current-token");

        assertEquals(SERVER_ID, result.getId());
    }

    /** 旧 Token 在宽限窗口内放行：轮换瞬间的在线 Agent 可完成重连。 */
    @Test
    void prevTokenWithinGraceShouldAuthenticate() {
        when(serverMapper.selectActiveServerAgentTokenById(SERVER_ID))
                .thenReturn(server(hashOf("current-token"), hashOf("old-token"), graceUntil(300), null));

        ServerEntity result = service.authenticate(SERVER_ID, "old-token");

        assertEquals(SERVER_ID, result.getId());
    }

    /** 旧 Token 超过宽限截止后被拒绝。 */
    @Test
    void prevTokenAfterGraceShouldBeRejected() {
        when(serverMapper.selectActiveServerAgentTokenById(SERVER_ID))
                .thenReturn(server(hashOf("current-token"), hashOf("old-token"), graceUntil(-1), null));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.authenticate(SERVER_ID, "old-token"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    /** 撤销后（revoked_at 非空）即使宽限未到期旧 Token 也立即失效。 */
    @Test
    void prevTokenShouldBeRejectedAfterRevoke() {
        when(serverMapper.selectActiveServerAgentTokenById(SERVER_ID))
                .thenReturn(server(hashOf("current-token"), hashOf("old-token"), graceUntil(300),
                        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.authenticate(SERVER_ID, "old-token"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    /** 既非当前 Token 也非宽限内旧 Token 时拒绝。 */
    @Test
    void unknownTokenShouldBeRejected() {
        when(serverMapper.selectActiveServerAgentTokenById(SERVER_ID))
                .thenReturn(server(hashOf("current-token"), hashOf("old-token"), graceUntil(300), null));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.authenticate(SERVER_ID, "other-token"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    /** 生成与生产一致的 sha256: 前缀十六进制摘要，供哈希匹配使用。 */
    private String hashOf(String token) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 构造含 Token 生命周期字段的服务器快照。 */
    private ServerEntity server(String hash, String prevHash, LocalDateTime graceUntil,
            LocalDateTime revokedAt) {
        ServerEntity server = new ServerEntity();
        server.setId(SERVER_ID);
        server.setAgentTokenHash(hash);
        server.setAgentTokenHashPrev(prevHash);
        server.setAgentTokenGraceUntil(graceUntil);
        server.setAgentTokenRevokedAt(revokedAt);
        return server;
    }
}
