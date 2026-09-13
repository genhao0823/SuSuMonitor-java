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
import com.susumonitor.server.module.server.entity.ServerEntity;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.server.vo.AgentTokenVo;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    /** 初始化 Token Service。 */
    @BeforeEach
    void setUp() {
        service = new AgentTokenServiceImpl(serverMapper);
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
        when(serverMapper.selectActiveServerAgentTokenById(11L)).thenReturn(server("sha256:old"));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.register(11L));

        assertEquals(ErrorCode.RESOURCE_CONFLICT, exception.getErrorCode());
    }

    /** 验证撤销会调用数据库状态更新。 */
    @Test
    void revokeShouldUpdateRevokedAt() {
        when(serverMapper.selectActiveServerAgentTokenById(11L)).thenReturn(server("sha256:old"));
        when(serverMapper.revokeAgentToken(eq(11L), any(LocalDateTime.class))).thenReturn(1);

        service.revoke(11L);

        verify(serverMapper).revokeAgentToken(eq(11L), any(LocalDateTime.class));
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
