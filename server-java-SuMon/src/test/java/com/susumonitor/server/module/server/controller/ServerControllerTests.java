package com.susumonitor.server.module.server.controller;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.server.dto.CreateServerRequest;
import com.susumonitor.server.module.server.dto.ServerQueryRequest;
import com.susumonitor.server.module.server.dto.UpdateServerRequest;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.module.server.service.ServerService;
import com.susumonitor.server.module.server.service.ServerSshService;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import com.susumonitor.server.module.server.vo.ServerVo;
import com.susumonitor.server.module.server.vo.SshHostKeyVo;
import com.susumonitor.server.module.server.vo.SshTestVo;
import com.susumonitor.server.security.JwtTokenService;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.terminal.mapper.TerminalSessionMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 验证服务器 Controller 的八个接口契约、权限边界、参数校验和统一异常响应。
 */
// 激活独立测试配置，避免 Controller 测试读取本机敏感配置。
@ActiveProfiles("test")
// 启动完整 Spring Boot Web 上下文，并排除真实数据源和 Flyway 初始化。
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
// 启用 MockMvc，使测试无需启动真实 HTTP 端口即可验证安全链和 Controller。
@AutoConfigureMockMvc
class ServerControllerTests {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final String AUTHORIZATION = "Authorization";
    private static final String ADMIN_BEARER = "Bearer " + ADMIN_TOKEN;
    private static final String USER_BEARER = "Bearer " + USER_TOKEN;
    private static final String JSON_CONTENT_TYPE = "application/json";

    // 注入 MockMvc 以通过真实过滤器链调用服务器接口。
    @Autowired
    private MockMvc mockMvc;

    // 使用模拟服务器服务，隔离 Controller 测试与业务实现和真实数据库。
    @MockitoBean
    private ServerService serverService;

    // 使用模拟 SSH 服务隔离 Controller 测试与网络、凭据解密和数据库写入。
    @MockitoBean
    private ServerSshService serverSshService;

    // 提供服务器 Mapper 替身，避免测试上下文创建真实 MyBatis 会话工厂。
    @MockitoBean
    private ServerMapper serverMapper;

    @MockitoBean
    private MetricsMapper metricsMapper;

    @MockitoBean
    private MetricsCleanupMapper metricsCleanupMapper;

    // 使用模拟告警 Mapper，避免告警模块 Mapper 扫描后创建真实 MyBatis 会话依赖。
    @MockitoBean
    private AlertRuleMapper alertRuleMapper;

    @MockitoBean
    private AlertRecordMapper alertRecordMapper;
    @MockitoBean
    private AlertNotificationMapper alertNotificationMapper;

    @MockitoBean
    private AlertStateMapper alertStateMapper;

    // 使用模拟终端 Mapper，避免 V12 Mapper 扫描后创建真实 MyBatis 会话依赖。
    @MockitoBean
    private TerminalSessionMapper terminalSessionMapper;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    // 提供用户 Mapper 替身，使 JWT 过滤器可回查可控的认证用户。
    @MockitoBean
    private UserMapper userMapper;

    // 提供初始化状态 Mapper 替身，避免测试上下文加载真实 MyBatis 依赖。
    @MockitoBean
    private AuthBootstrapStateMapper authBootstrapStateMapper;

    // 提供系统就绪检查所需的数据源替身，避免排除数据库自动配置后缺少 Bean。
    @MockitoBean
    private DataSource dataSource;

    // 提供 JWT 服务替身，使每个权限场景可控制 Token 解析结果。
    @MockitoBean
    private JwtTokenService jwtTokenService;
    @MockitoBean
    private OutboxMapper outboxMapper;
    @MockitoBean
    private ConsumeRecordMapper consumeRecordMapper;



    /**
     * 验证管理员可成功调用创建、列表、详情、更新、删除和状态六个接口。
     */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void adminShouldAccessAllSixEndpoints() throws Exception {
        authenticateAdmin();
        ServerVo server = serverVo();
        PageResult<ServerVo> pageResult = pageResult(server);
        ServerStatusVo statusVo = statusVo();
        when(serverService.create(any(CreateServerRequest.class))).thenReturn(server);
        when(serverService.list(any(ServerQueryRequest.class))).thenReturn(pageResult);
        when(serverService.get(1L)).thenReturn(server);
        when(serverService.existsActive(1L)).thenReturn(true);
        when(serverService.update(any(Long.class), any(UpdateServerRequest.class))).thenReturn(server);
        when(serverService.status(1L)).thenReturn(statusVo);

        mockMvc.perform(post("/api/servers")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ssh_host").value("10.0.0.1"))
                .andExpect(jsonPath("$.data.ssh_port").value(22))
                .andExpect(jsonPath("$.data.ssh_auth_type").value("password"))
                .andExpect(jsonPath("$.data.ssh_password").doesNotExist())
                .andExpect(jsonPath("$.data.ssh_private_key").doesNotExist())
                .andExpect(jsonPath("$.data.ssh_private_key_passphrase").doesNotExist());

        mockMvc.perform(get("/api/servers")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .header("X-Correlation-ID", "server-list-1")
                        .param("page", "2")
                        .param("page_size", "10")
                        .param("keyword", "production")
                        .param("sort_by", "created_at")
                        .param("sort_order", "asc"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(header().string("X-Correlation-ID", "server-list-1"))
                .andExpect(jsonPath("$.data.page_size").value(10))
                .andExpect(jsonPath("$.data.items[0].agent_status").value("online"))
                .andExpect(jsonPath("$.data.items[0].sshPasswordEncrypted").doesNotExist());

        mockMvc.perform(get("/api/servers/1").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));

        mockMvc.perform(put("/api/servers/1")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content(updateBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("production"));

        mockMvc.perform(delete("/api/servers/1").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/api/servers/1/status").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.server_id").value(1))
                .andExpect(jsonPath("$.data.checked_at").isNotEmpty());

        ArgumentCaptor<CreateServerRequest> createCaptor = ArgumentCaptor.forClass(CreateServerRequest.class);
        verify(serverService).create(createCaptor.capture());
        assertEquals("10.0.0.1", createCaptor.getValue().getSshHost());
        assertEquals("secret", createCaptor.getValue().getSshPassword());

        ArgumentCaptor<ServerQueryRequest> queryCaptor = ArgumentCaptor.forClass(ServerQueryRequest.class);
        verify(serverService).list(queryCaptor.capture());
        assertEquals(2, queryCaptor.getValue().getPage());
        assertEquals(10, queryCaptor.getValue().getPageSize());
        assertEquals("production", queryCaptor.getValue().getKeyword());
        assertEquals("created_at", queryCaptor.getValue().getSortBy());
        assertEquals("asc", queryCaptor.getValue().getSortOrder());
        verify(serverService).get(1L);
        verify(serverService).update(any(Long.class), any(UpdateServerRequest.class));
        verify(serverService).delete(1L);
        verify(serverService).status(1L);
    }

    /**
     * 验证已审核普通用户可读取列表、详情和状态三个 GET 接口。
     */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void approvedUserShouldAccessThreeGetEndpoints() throws Exception {
        authenticateUser();
        ServerVo server = serverVo();
        when(serverService.list(any(ServerQueryRequest.class))).thenReturn(pageResult(server));
        when(serverService.get(1L)).thenReturn(server);
        when(serverService.status(1L)).thenReturn(statusVo());

        mockMvc.perform(get("/api/servers").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/servers/1").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/servers/1/status").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isOk());
    }

    /**
     * 验证普通用户的创建、更新和删除请求均由安全链拒绝为 403。
     */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void approvedUserShouldReceiveForbiddenForAllWrites() throws Exception {
        authenticateUser();

        mockMvc.perform(post("/api/servers")
                        .header(AUTHORIZATION, USER_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content(createBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(put("/api/servers/1")
                        .header(AUTHORIZATION, USER_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content(updateBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(delete("/api/servers/1").header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    /**
     * 验证六个服务器接口缺少 Token 时均返回统一 401 响应。
     */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void allEndpointsWithoutTokenShouldReturnUnauthorized() throws Exception {
        RequestBuilder[] requests = {
                post("/api/servers").contentType(JSON_CONTENT_TYPE).content(createBody()),
                get("/api/servers"),
                get("/api/servers/1"),
                put("/api/servers/1").contentType(JSON_CONTENT_TYPE).content(updateBody()),
                delete("/api/servers/1"),
                get("/api/servers/1/status")
        };

        for (RequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(40100));
        }
    }

    /**
     * 验证非正数路径 ID 在进入 Service 前返回统一参数错误。
     */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void invalidIdShouldReturnBadRequest() throws Exception {
        authenticateAdmin();

        mockMvc.perform(get("/api/servers/0").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /** 验证 DELETE 路径上的非正数 ID 在进入 Service 前返回参数错误。 */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void deletingInvalidIdShouldReturnBadRequest(String serverId) throws Exception {
        authenticateAdmin();

        mockMvc.perform(delete("/api/servers/" + serverId)
                        .header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002))
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())));

        verify(serverService, never()).delete(any(Long.class));
    }

    /** 验证删除不存在资源时返回统一 404 响应。 */
    @Test
    void deletingMissingServerShouldReturnNotFound() throws Exception {
        authenticateAdmin();
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(serverService).delete(99L);

        mockMvc.perform(delete("/api/servers/99")
                        .header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())));
    }

    /**
     * 验证不在白名单中的排序字段返回统一参数错误。
     */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void invalidSortShouldReturnBadRequest() throws Exception {
        authenticateAdmin();

        mockMvc.perform(get("/api/servers")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .param("sort_by", "ssh_password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /** 验证非法排序方向在进入 Service 前返回统一参数错误。 */
    @Test
    void invalidSortOrderShouldReturnBadRequest() throws Exception {
        authenticateAdmin();

        mockMvc.perform(get("/api/servers")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .param("sort_order", "ascending"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(serverService, never()).list(any(ServerQueryRequest.class));
    }

    /** 验证未传排序参数时使用稳定的 id 降序默认值。 */
    @Test
    void missingSortParametersShouldUseDefaults() throws Exception {
        authenticateAdmin();
        when(serverService.list(any(ServerQueryRequest.class))).thenReturn(pageResult(serverVo()));

        mockMvc.perform(get("/api/servers").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk());

        ArgumentCaptor<ServerQueryRequest> captor = ArgumentCaptor.forClass(ServerQueryRequest.class);
        verify(serverService).list(captor.capture());
        assertEquals("id", captor.getValue().getSortBy());
        assertEquals("desc", captor.getValue().getSortOrder());
    }

    /** 验证所有公开排序字段均能从 HTTP 参数传递到 Service。 */
    @ParameterizedTest
    @MethodSource("sortFields")
    void whitelistedSortFieldsShouldReachService(String sortBy) throws Exception {
        authenticateAdmin();
        when(serverService.list(any(ServerQueryRequest.class))).thenReturn(pageResult(serverVo()));

        mockMvc.perform(get("/api/servers")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .param("sort_by", sortBy)
                        .param("sort_order", "asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<ServerQueryRequest> captor = ArgumentCaptor.forClass(ServerQueryRequest.class);
        verify(serverService).list(captor.capture());
        assertEquals(sortBy, captor.getValue().getSortBy());
        assertEquals("asc", captor.getValue().getSortOrder());
    }

    /** 提供 REST 契约声明的排序字段白名单。 */
    private static Stream<Arguments> sortFields() {
        return Stream.of("id", "name", "host", "status", "created_at", "updated_at")
                .map(Arguments::of);
    }

    /**
     * 验证无法解析的创建 JSON 返回统一参数错误。
     */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void malformedJsonShouldReturnBadRequest() throws Exception {
        authenticateAdmin();

        mockMvc.perform(post("/api/servers")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content("{malformed-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /**
     * 验证 Service 的资源不存在异常映射为统一 404 响应。
     */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void missingServerShouldReturnNotFound() throws Exception {
        authenticateAdmin();
        when(serverService.get(99L)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/servers/99").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    /** 验证更新接口先检查资源存在性，再决定是否校验请求体。 */
    @Test
    void updateMissingServerShouldReturnNotFoundBeforeBodyValidation() throws Exception {
        authenticateAdmin();
        when(serverService.existsActive(99L)).thenReturn(false);

        mockMvc.perform(put("/api/servers/99")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content("{\"description\":\"probe\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        verify(serverService).existsActive(99L);
        verify(serverService, never()).update(any(Long.class), any(UpdateServerRequest.class));
    }

    /** 验证有效服务器的非法更新请求仍返回参数错误。 */
    @Test
    void updateActiveServerWithInvalidBodyShouldReturnBadRequest() throws Exception {
        authenticateAdmin();
        when(serverService.existsActive(1L)).thenReturn(true);

        mockMvc.perform(put("/api/servers/1")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content("{\"description\":\"probe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(serverService).existsActive(1L);
        verify(serverService, never()).update(any(Long.class), any(UpdateServerRequest.class));
    }

    /**
     * 验证 Service 的资源冲突异常映射为统一 409 响应。
     */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void conflictingDeleteShouldReturnConflict() throws Exception {
        authenticateAdmin();
        doThrow(new BusinessException(ErrorCode.RESOURCE_CONFLICT)).when(serverService).delete(1L);

        mockMvc.perform(delete("/api/servers/1").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    /** 验证管理员可调用两个 SSH 接口，身份 ID 被透传且响应包含请求追踪头。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void adminShouldAccessBothSshEndpoints() throws Exception {
        authenticateAdmin();
        when(serverSshService.updateHostKey(eq(1L), any(), eq(1L))).thenReturn(sshHostKeyVo());
        when(serverSshService.testConnection(1L)).thenReturn(sshTestVo());

        mockMvc.perform(put("/api/servers/1/ssh/host-key")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content(hostKeyBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.operation").value("confirmed"))
                .andExpect(jsonPath("$.data.server_id").value(1))
                .andExpect(jsonPath("$.data.host_key_algorithm").value("ssh-ed25519"))
                .andExpect(jsonPath("$.data.verified_at").isNotEmpty());

        mockMvc.perform(post("/api/servers/1/ssh/test")
                        .header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.connected").value(true))
                .andExpect(jsonPath("$.data.server_id").value(1))
                .andExpect(jsonPath("$.data.auth_type").value("password"))
                .andExpect(jsonPath("$.data.duration_ms").value(12))
                .andExpect(jsonPath("$.data.tested_at").isNotEmpty());

        verify(serverSshService).updateHostKey(eq(1L), any(), eq(1L));
        verify(serverSshService).testConnection(1L);
    }

    /** 验证已审核普通用户调用两个 SSH 管理接口均返回 40300。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void approvedUserShouldReceiveForbiddenForBothSshEndpoints() throws Exception {
        authenticateUser();
        RequestBuilder[] requests = {
                put("/api/servers/1/ssh/host-key")
                        .header(AUTHORIZATION, USER_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content(hostKeyBody()),
                post("/api/servers/1/ssh/test").header(AUTHORIZATION, USER_BEARER)
        };

        for (RequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));
        }
    }

    /** 验证两个 SSH 接口缺少 Token 时均返回统一 40100。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void sshEndpointsWithoutTokenShouldReturnUnauthorized() throws Exception {
        RequestBuilder[] requests = {
                put("/api/servers/1/ssh/host-key")
                        .contentType(JSON_CONTENT_TYPE)
                        .content(hostKeyBody()),
                post("/api/servers/1/ssh/test")
        };

        for (RequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(40100));
        }
    }

    /** 验证不符合 OpenSSH SHA-256 格式的指纹返回参数错误且不调用 Service。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void invalidSshFingerprintShouldReturnBadRequest() throws Exception {
        authenticateAdmin();

        mockMvc.perform(put("/api/servers/1/ssh/host-key")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content("{\"expected_fingerprint\":\"MD5:invalid\",\"replace\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        verify(serverSshService, never()).updateHostKey(any(), any(), any());
    }

    /** 验证 SSH 测试接口拒绝契约未定义的 JSON 请求体且不调用 Service。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void sshTestRequestBodyShouldReturnBadRequest() throws Exception {
        authenticateAdmin();

        mockMvc.perform(post("/api/servers/1/ssh/test")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(40002));

        verify(serverSshService, never()).testConnection(any());
    }

    /** 验证 SSH 凭据认证失败映射为 50003 和 HTTP 502。 */
    @Test
    void sshAuthenticationFailureShouldReturnBadGateway() throws Exception {
        authenticateAdmin();
        doThrow(new BusinessException(ErrorCode.SSH_AUTHENTICATION_FAILED))
                .when(serverSshService).testConnection(1L);

        mockMvc.perform(post("/api/servers/1/ssh/test")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType(JSON_CONTENT_TYPE)
                        .content(""))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(50003));
    }

    /** 配置管理员 JWT 和数据库最新状态回查。 */
    private void authenticateAdmin() {
        when(jwtTokenService.parseToken(ADMIN_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "admin-token-id"));
        when(userMapper.selectAuthenticationUserById(1L)).thenReturn(authenticationUser(1L, "admin", "admin"));
    }

    /** 配置已审核普通用户 JWT 和数据库最新状态回查。 */
    private void authenticateUser() {
        when(jwtTokenService.parseToken(USER_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(2L, "approved_user", "user-token-id"));
        when(userMapper.selectAuthenticationUserById(2L))
                .thenReturn(authenticationUser(2L, "approved_user", "user"));
    }

    /** 创建安全过滤器回查使用的已审核用户实体。 */
    private UserEntity authenticationUser(Long id, String username, String role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setReviewStatus("approved");
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    /** 创建不含凭据字段的服务器公开响应。 */
    private ServerVo serverVo() {
        ServerVo server = new ServerVo();
        server.setId(1L);
        server.setName("production");
        server.setHost("10.0.0.1");
        server.setDescription("production server");
        server.setStatus("online");
        server.setSshHost("10.0.0.1");
        server.setSshPort(22);
        server.setSshUser("root");
        server.setSshAuthType("password");
        server.setAgentId("agent-1");
        server.setAgentStatus("online");
        server.setLastHeartbeatAt(OffsetDateTime.now());
        server.setCreatedAt(OffsetDateTime.now());
        server.setUpdatedAt(OffsetDateTime.now());
        return server;
    }

    /** 创建服务器列表分页响应。 */
    private PageResult<ServerVo> pageResult(ServerVo server) {
        PageResult<ServerVo> result = new PageResult<>();
        result.setItems(List.of(server));
        result.setTotal(1L);
        result.setPage(2);
        result.setPageSize(10);
        return result;
    }

    /** 创建服务器状态快照响应。 */
    private ServerStatusVo statusVo() {
        ServerStatusVo result = new ServerStatusVo();
        result.setServerId(1L);
        result.setStatus("online");
        result.setAgentStatus("online");
        result.setLastHeartbeatAt(OffsetDateTime.now());
        result.setCheckedAt(OffsetDateTime.now());
        return result;
    }

    /** 创建主机公钥确认接口使用的非敏感响应。 */
    private SshHostKeyVo sshHostKeyVo() {
        SshHostKeyVo result = new SshHostKeyVo();
        result.setServerId(1L);
        result.setHostKeyAlgorithm("ssh-ed25519");
        result.setHostKeyFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        result.setOperation("confirmed");
        result.setVerifiedAt(OffsetDateTime.now());
        return result;
    }

    /** 创建 SSH 连接测试接口使用的非敏感响应。 */
    private SshTestVo sshTestVo() {
        SshTestVo result = new SshTestVo();
        result.setServerId(1L);
        result.setConnected(true);
        result.setHostKeyAlgorithm("ssh-ed25519");
        result.setHostKeyFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        result.setAuthType("password");
        result.setDurationMs(12L);
        result.setTestedAt(OffsetDateTime.now());
        return result;
    }

    /** 返回使用 snake_case 字段的合法创建 JSON。 */
    private String createBody() {
        return """
                {
                  "name": "production",
                  "host": "10.0.0.1",
                  "description": "production server",
                  "ssh_host": "10.0.0.1",
                  "ssh_port": 22,
                  "ssh_user": "root",
                  "ssh_auth_type": "password",
                  "ssh_password": "secret"
                }
                """;
    }

    /** 返回使用 snake_case 字段的合法全量更新 JSON。 */
    private String updateBody() {
        return """
                {
                  "name": "production",
                  "host": "10.0.0.1",
                  "description": "production server",
                  "ssh_host": "10.0.0.1",
                  "ssh_port": 22,
                  "ssh_user": "root",
                  "ssh_auth_type": "password"
                }
                """;
    }

    /** 返回使用 snake_case 字段的合法主机公钥确认 JSON。 */
    private String hostKeyBody() {
        return """
                {
                  "expected_fingerprint": "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                  "replace": false
                }
                """;
    }
}
