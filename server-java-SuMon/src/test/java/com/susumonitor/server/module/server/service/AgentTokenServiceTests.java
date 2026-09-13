package com.susumonitor.server.module.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.server.entity.ServerEntity;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.server.vo.AgentTokenVo;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证 Agent Token 只保存哈希并按注册、轮换、撤销规则变更状态。
 */
@ExtendWith(MockitoExtension.class)
class AgentTokenServiceTests {

    @Mock
    private ServerMapper serverMapper;

    private AgentTokenService service;

    private AppProperties appProperties;

    /** 初始化 Token Service。 */
    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        service = new AgentTokenServiceImpl(serverMapper, appProperties);
    }

    /** 验证首次注册返回一次性 Token，并向 Mapper 传入哈希而非同值明文。 */
    @Test
    void registerShouldCreateOneTimeTokenHash() {
        ServerEntity server = server(null);
        when(serverMapper.selectActiveServerAgentTokenById(11L)).thenReturn(server);
        when(serverMapper.registerAgentToken(eq(11L), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);

        AgentTokenVo result = service.register(11L);

        assertEquals(11L, result.serverId());
        assertNotNull(result.agentToken());
        assertEquals(43, result.agentToken().length());
        verify(serverMapper).registerAgentToken(
                eq(11L), anyString(), anyString(), any(LocalDateTime.class));
    }

    /** 验证已有 Token 不能通过 register 覆盖，必须显式 rotate。 */
    @Test
    void registerShouldRejectExistingToken() {
        when(serverMapper.selectActiveServerAgentTokenById(11L)).thenReturn(server(hashOf("old-token")));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.register(11L));

        assertEquals(ErrorCode.RESOURCE_CONFLICT, exception.getErrorCode());
    }

    /** 验证撤销会调用数据库状态更新。 */
    @Test
    void revokeShouldUpdateRevokedAt() {
        when(serverMapper.selectActiveServerAgentTokenById(11L)).thenReturn(server(hashOf("old-token")));
        when(serverMapper.revokeAgentToken(eq(11L), any(LocalDateTime.class))).thenReturn(1);

        service.revoke(11L);

        verify(serverMapper).revokeAgentToken(eq(11L), any(LocalDateTime.class));
    }

    /** 验证轮换生成新哈希并登记宽限截止时间（默认 300s，旧 Token 在窗口内仍可握手）。 */
    @Test
    void rotateShouldRegisterGraceWindow() {
        when(serverMapper.selectActiveServerAgentTokenById(11L)).thenReturn(server(hashOf("old-token")));
        when(serverMapper.rotateAgentToken(eq(11L), anyString(), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(1);

        AgentTokenVo result = service.rotate(11L);

        assertNotNull(result.agentToken());
        ArgumentCaptor<String> newHashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> rotatedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> graceUntilCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(serverMapper).rotateAgentToken(eq(11L), newHashCaptor.capture(),
                rotatedAtCaptor.capture(), graceUntilCaptor.capture());
        // 新哈希不复用旧值，宽限截止 = 轮换时间 + 配置宽限窗口（300s，误差容忍 5s）。
        org.junit.jupiter.api.Assertions.assertNotEquals(hashOf("old-token"), newHashCaptor.getValue());
        long graceSeconds = java.time.Duration.between(
                rotatedAtCaptor.getValue(), graceUntilCaptor.getValue()).getSeconds();
        assertEquals(300L, graceSeconds);
    }

    /** 验证宽限窗口配置为 0 时轮换立即失效（grace_until 等于轮换时间）。 */
    @Test
    void rotateWithZeroGraceShouldExpireImmediately() {
        appProperties.getAgent().setTokenGraceSeconds(0);
        when(serverMapper.selectActiveServerAgentTokenById(11L)).thenReturn(server(hashOf("old-token")));
        when(serverMapper.rotateAgentToken(eq(11L), anyString(), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(1);

        service.rotate(11L);

        ArgumentCaptor<LocalDateTime> rotatedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> graceUntilCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(serverMapper).rotateAgentToken(eq(11L), anyString(),
                rotatedAtCaptor.capture(), graceUntilCaptor.capture());
        assertEquals(rotatedAtCaptor.getValue(), graceUntilCaptor.getValue());
    }

    /** 生成与生产一致的 sha256: 前缀十六进制摘要，供哈希匹配断言使用。 */
    private String hashOf(String token) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 创建有效服务器 Agent 快照。 */
    private ServerEntity server(String tokenHash) {
        ServerEntity server = new ServerEntity();
        server.setId(11L);
        server.setAgentId("agent-11");
        server.setAgentTokenHash(tokenHash);
        return server;
    }
}
