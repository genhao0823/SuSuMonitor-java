package com.susumonitor.server.module.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.server.dto.CreateServerRequest;
import com.susumonitor.server.module.server.dto.ServerQueryRequest;
import com.susumonitor.server.module.server.dto.UpdateServerRequest;
import com.susumonitor.server.module.server.entity.ServerEntity;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import com.susumonitor.server.module.server.vo.ServerVo;
import com.susumonitor.server.security.CredentialCipher;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

/**
 * 验证服务器 Service 的凭据状态机、分页、异常映射和安全响应边界。
 */
// 启用 Mockito 扩展，隔离数据库和真实 AES 加密实现。
@ExtendWith(MockitoExtension.class)
class ServerServiceTests {

    // 使用 Mapper 替身精确验证数据访问参数和异常分支。
    @Mock
    private ServerMapper serverMapper;

    // 使用密码器替身避免测试依赖随机 IV 和真实密钥。
    @Mock
    private CredentialCipher credentialCipher;

    private ServerService serverService;

    /** 在每个测试前创建待测服务器服务。 */
    @BeforeEach
    void setUp() {
        serverService = new ServerServiceImpl(serverMapper, credentialCipher);
    }

    /** 验证密码认证创建先取得 ID，再使用 ID 加密并更新密码密文。 */
    @Test
    void createWithPasswordShouldEncryptAfterBaseInsert() {
        CreateServerRequest request = createRequest("password");
        request.setSshPassword("secret");
        arrangeSuccessfulCreate();
        when(credentialCipher.encrypt(7L, "ssh_password", "secret")).thenReturn("password-cipher");

        ServerVo result = serverService.create(request);

        assertEquals(7L, result.getId());
        verify(credentialCipher).encrypt(7L, "ssh_password", "secret");
        verify(serverMapper).updateCredentialCiphertexts(7L, "password-cipher", null, null);
    }

    /** 验证私钥认证创建分别加密私钥和可选口令。 */
    @Test
    void createWithPrivateKeyShouldEncryptKeyAndPassphrase() {
        CreateServerRequest request = createRequest("private_key");
        request.setSshPrivateKey("private-key");
        request.setSshPrivateKeyPassphrase("phrase");
        arrangeSuccessfulCreate();
        when(credentialCipher.encrypt(7L, "ssh_private_key", "private-key")).thenReturn("key-cipher");
        when(credentialCipher.encrypt(7L, "ssh_private_key_passphrase", "phrase")).thenReturn("phrase-cipher");

        serverService.create(request);

        verify(serverMapper).updateCredentialCiphertexts(7L, null, "key-cipher", "phrase-cipher");
    }

    /** 验证创建同时提交两类主凭据返回参数错误。 */
    @Test
    void createWithBothPrimaryCredentialsShouldFail() {
        CreateServerRequest request = createRequest("password");
        request.setSshPassword("secret");
        request.setSshPrivateKey("private-key");

        assertError(ErrorCode.INVALID_REQUEST_PARAMETER, () -> serverService.create(request));
        verify(serverMapper, never()).insertServerBase(any());
    }

    /** 验证密码认证提交无关私钥口令返回参数错误。 */
    @Test
    void createPasswordWithPassphraseShouldFail() {
        CreateServerRequest request = createRequest("password");
        request.setSshPassword("secret");
        request.setSshPrivateKeyPassphrase("phrase");

        assertError(ErrorCode.INVALID_REQUEST_PARAMETER, () -> serverService.create(request));
    }

    /** 验证创建缺少认证方式对应的主凭据返回参数错误。 */
    @Test
    void createWithoutPrimaryCredentialShouldFail() {
        assertError(ErrorCode.INVALID_REQUEST_PARAMETER,
                () -> serverService.create(createRequest("private_key")));
    }

    /** 验证任意传入的空字符串凭据返回参数错误。 */
    @Test
    void createWithBlankCredentialShouldFail() {
        CreateServerRequest request = createRequest("password");
        request.setSshPassword("");

        assertError(ErrorCode.INVALID_REQUEST_PARAMETER, () -> serverService.create(request));
    }

    /** 验证超过冻结上限的密码在 Service 边界被拒绝。 */
    @Test
    void createWithOversizedPasswordShouldFail() {
        CreateServerRequest request = createRequest("password");
        request.setSshPassword("x".repeat(1025));

        assertError(ErrorCode.INVALID_REQUEST_PARAMETER, () -> serverService.create(request));
    }

    /** 验证同为密码认证且省略凭据时保留原密码密文。 */
    @Test
    void updateSamePasswordAuthShouldRetainCiphertext() {
        UpdateServerRequest request = updateRequest("password");
        arrangeSuccessfulUpdate(existingPasswordServer());

        serverService.update(7L, request);

        ServerEntity updated = captureUpdatedServer();
        assertEquals("old-password-cipher", updated.getSshPasswordEncrypted());
        verify(credentialCipher, never()).encrypt(anyLong(), anyString(), anyString());
    }

    /** 验证同为密码认证且提交非空新密码时替换密文。 */
    @Test
    void updateSamePasswordAuthShouldReplaceCiphertext() {
        UpdateServerRequest request = updateRequest("password");
        request.setSshPassword("new-secret");
        arrangeSuccessfulUpdate(existingPasswordServer());
        when(credentialCipher.encrypt(7L, "ssh_password", "new-secret")).thenReturn("new-password-cipher");

        serverService.update(7L, request);

        assertEquals("new-password-cipher", captureUpdatedServer().getSshPasswordEncrypted());
    }

    /** 验证从密码切换到私钥时要求并写入新私钥，同时清空旧密码密文。 */
    @Test
    void updatePasswordToPrivateKeyShouldClearPasswordCiphertext() {
        UpdateServerRequest request = updateRequest("private_key");
        request.setSshPrivateKey("new-key");
        arrangeSuccessfulUpdate(existingPasswordServer());
        when(credentialCipher.encrypt(7L, "ssh_private_key", "new-key")).thenReturn("new-key-cipher");

        serverService.update(7L, request);

        ServerEntity updated = captureUpdatedServer();
        assertNull(updated.getSshPasswordEncrypted());
        assertEquals("new-key-cipher", updated.getSshPrivateKeyEncrypted());
    }

    /** 验证从私钥切换到密码时清空私钥及口令密文。 */
    @Test
    void updatePrivateKeyToPasswordShouldClearPrivateKeyCiphertexts() {
        UpdateServerRequest request = updateRequest("password");
        request.setSshPassword("new-secret");
        arrangeSuccessfulUpdate(existingPrivateKeyServer());
        when(credentialCipher.encrypt(7L, "ssh_password", "new-secret")).thenReturn("new-password-cipher");

        serverService.update(7L, request);

        ServerEntity updated = captureUpdatedServer();
        assertEquals("new-password-cipher", updated.getSshPasswordEncrypted());
        assertNull(updated.getSshPrivateKeyEncrypted());
        assertNull(updated.getSshPrivateKeyPassphraseEncrypted());
    }

    /** 验证同为私钥认证且只更新口令时保留私钥并替换口令密文。 */
    @Test
    void updatePrivateKeyPassphraseShouldRetainKeyAndReplacePassphrase() {
        UpdateServerRequest request = updateRequest("private_key");
        request.setSshPrivateKeyPassphrase("new-phrase");
        arrangeSuccessfulUpdate(existingPrivateKeyServer());
        when(credentialCipher.encrypt(7L, "ssh_private_key_passphrase", "new-phrase"))
                .thenReturn("new-phrase-cipher");

        serverService.update(7L, request);

        ServerEntity updated = captureUpdatedServer();
        assertEquals("old-key-cipher", updated.getSshPrivateKeyEncrypted());
        assertEquals("new-phrase-cipher", updated.getSshPrivateKeyPassphraseEncrypted());
    }

    /** 验证认证方式切换但未提交新主凭据时返回参数错误且不更新。 */
    @Test
    void updateAuthTypeWithoutNewPrimaryCredentialShouldFail() {
        when(serverMapper.selectActiveServerWithCredentialsById(7L)).thenReturn(existingPasswordServer());

        assertError(ErrorCode.INVALID_REQUEST_PARAMETER,
                () -> serverService.update(7L, updateRequest("private_key")));
        verify(serverMapper, never()).updateActiveServer(any());
    }

    /** 验证更新时提交无关凭据返回参数错误。 */
    @Test
    void updateWithUnrelatedCredentialShouldFail() {
        UpdateServerRequest request = updateRequest("password");
        request.setSshPrivateKey("unrelated-key");
        when(serverMapper.selectActiveServerWithCredentialsById(7L)).thenReturn(existingPasswordServer());

        assertError(ErrorCode.INVALID_REQUEST_PARAMETER, () -> serverService.update(7L, request));
    }

    /** 验证列表透传白名单排序、分页参数进入 Page 并映射分页 VO（total 由拦截器回写）。 */
    @Test
    void listShouldReturnMappedPage() {
        ServerQueryRequest request = new ServerQueryRequest();
        request.setPage(2);
        request.setPageSize(10);
        request.setKeyword("prod");
        request.setSortBy("created_at");
        request.setSortOrder("asc");
        when(serverMapper.selectActiveServers(any(), eq("prod"), eq("created_at"), eq("asc")))
                .thenAnswer(invocation -> {
                    Page<ServerEntity> pager = invocation.getArgument(0);
                    pager.setTotal(21L);
                    return List.of(publicServer());
                });

        PageResult<ServerVo> result = serverService.list(request);

        assertEquals(21L, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals(10, result.getPageSize());
        assertEquals(1, result.getItems().size());
        ArgumentCaptor<Page<ServerEntity>> captor = pageCaptor();
        verify(serverMapper).selectActiveServers(captor.capture(),
                eq("prod"), eq("created_at"), eq("asc"));
        assertEquals(2, captor.getValue().getCurrent());
        assertEquals(10, captor.getValue().getSize());
    }

    /** 验证可选分页和排序字段为空时使用服务端默认值。 */
    @Test
    void listWithNullOptionalValuesShouldUseDefaults() {
        ServerQueryRequest request = new ServerQueryRequest();
        request.setPage(null);
        request.setPageSize(null);
        request.setSortBy(null);
        request.setSortOrder(null);
        when(serverMapper.selectActiveServers(any(), isNull(), eq("id"), eq("desc")))
                .thenReturn(List.of());

        PageResult<ServerVo> result = serverService.list(request);

        assertEquals(1, result.getPage());
        assertEquals(20, result.getPageSize());
        ArgumentCaptor<Page<ServerEntity>> captor = pageCaptor();
        verify(serverMapper).selectActiveServers(captor.capture(), isNull(), eq("id"), eq("desc"));
        assertEquals(1, captor.getValue().getCurrent());
        assertEquals(20, captor.getValue().getSize());
    }

    /** 验证非法排序方向在访问任何 Mapper 查询前失败。 */
    @Test
    void listWithInvalidSortOrderShouldFailBeforeMapperAccess() {
        ServerQueryRequest request = new ServerQueryRequest();
        request.setSortOrder("ascending");

        assertError(ErrorCode.INVALID_REQUEST_PARAMETER, () -> serverService.list(request));
        verify(serverMapper, never()).selectActiveServers(any(), any(), anyString(), anyString());
    }

    /** 验证所有排序字段和方向均完整透传到真实 Mapper 方法签名。 */
    @ParameterizedTest
    @MethodSource("sortValues")
    void listShouldPassWhitelistedSortValuesToMapper(String sortBy, String sortOrder) {
        ServerQueryRequest request = new ServerQueryRequest();
        request.setSortBy(sortBy);
        request.setSortOrder(sortOrder);
        when(serverMapper.selectActiveServers(any(), isNull(), eq(sortBy), eq(sortOrder)))
                .thenReturn(List.of());

        serverService.list(request);

        verify(serverMapper).selectActiveServers(any(), isNull(), eq(sortBy), eq(sortOrder));
    }

    /** 提供服务层允许的排序字段和方向组合。 */
    private static Stream<Arguments> sortValues() {
        return Stream.of("id", "name", "host", "status", "created_at", "updated_at")
                .flatMap(sortBy -> Stream.of(Arguments.of(sortBy, "asc"), Arguments.of(sortBy, "desc")));
    }

    /** 验证非法排序字段在访问 Mapper 前返回参数错误。 */
    @Test
    void listWithInvalidSortShouldFail() {
        ServerQueryRequest request = new ServerQueryRequest();
        request.setSortBy("name desc");

        assertError(ErrorCode.INVALID_REQUEST_PARAMETER, () -> serverService.list(request));
        verify(serverMapper, never()).selectActiveServers(any(), any(), anyString(), anyString());
    }

    /** 构造分页参数捕获器（泛型数组限制下的常规绕行写法）。 */
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Page<ServerEntity>> pageCaptor() {
        return (ArgumentCaptor<Page<ServerEntity>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Page.class);
    }

    /** 验证详情不存在时返回 40400。 */
    @Test
    void getMissingServerShouldReturnNotFound() {
        when(serverMapper.selectActiveServerById(99L)).thenReturn(null);

        assertError(ErrorCode.RESOURCE_NOT_FOUND, () -> serverService.get(99L));
    }

    /** 验证有效服务器存在性查询只读取未软删除的公开快照。 */
    @Test
    void existsActiveShouldReturnTrueForActiveServer() {
        when(serverMapper.selectActiveServerById(7L)).thenReturn(publicServer());

        assertTrue(serverService.existsActive(7L));

        verify(serverMapper).selectActiveServerById(7L);
    }

    /** 验证不存在或已软删除服务器的存在性查询返回 false。 */
    @Test
    void existsActiveShouldReturnFalseForMissingServer() {
        when(serverMapper.selectActiveServerById(99L)).thenReturn(null);

        assertTrue(!serverService.existsActive(99L));

        verify(serverMapper).selectActiveServerById(99L);
    }

    /** 验证更新不存在目标时返回 40400。 */
    @Test
    void updateMissingServerShouldReturnNotFound() {
        when(serverMapper.selectActiveServerWithCredentialsById(99L)).thenReturn(null);

        assertError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> serverService.update(99L, updateRequest("password")));
    }

    /** 验证软删除使用合法 UUID 作为删除唯一标识。 */
    @Test
    void deleteShouldUseUuidToken() {
        when(serverMapper.softDeleteActiveServer(eq(7L), any(), anyString())).thenReturn(1);

        serverService.delete(7L);

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(serverMapper).softDeleteActiveServer(eq(7L), any(), tokenCaptor.capture());
        assertNotNull(UUID.fromString(tokenCaptor.getValue()));
    }

    /** 验证软删除条件未命中时返回 40400。 */
    @Test
    void deleteMissingServerShouldReturnNotFound() {
        when(serverMapper.softDeleteActiveServer(eq(99L), any(), anyString())).thenReturn(0);

        assertError(ErrorCode.RESOURCE_NOT_FOUND, () -> serverService.delete(99L));
    }

    /** 验证删除方法拒绝非法 ID 且不访问数据库。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void deleteInvalidIdShouldFailBeforeMapperAccess(Long serverId) {
        assertError(ErrorCode.INVALID_REQUEST_PARAMETER, () -> serverService.delete(serverId));

        verify(serverMapper, never()).softDeleteActiveServer(anyLong(), any(), anyString());
    }

    /** 验证软删除数据库异常统一映射为数据库错误。 */
    @Test
    void deleteDatabaseFailureShouldReturnDatabaseError() {
        when(serverMapper.softDeleteActiveServer(eq(7L), any(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertError(ErrorCode.DATABASE_ERROR, () -> serverService.delete(7L));
    }

    /** 验证软删除唯一键冲突统一映射为资源冲突。 */
    @Test
    void deleteDuplicateKeyShouldReturnConflict() {
        when(serverMapper.softDeleteActiveServer(eq(7L), any(), anyString()))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertError(ErrorCode.RESOURCE_CONFLICT, () -> serverService.delete(7L));
    }

    /** 验证创建唯一键冲突映射为 40900。 */
    @Test
    void createDuplicateShouldReturnConflict() {
        CreateServerRequest request = createRequest("password");
        request.setSshPassword("secret");
        when(serverMapper.insertServerBase(any())).thenThrow(new DuplicateKeyException("duplicate"));

        assertError(ErrorCode.RESOURCE_CONFLICT, () -> serverService.create(request));
    }

    /** 验证 Mapper 数据访问异常映射为 50001。 */
    @Test
    void listDatabaseFailureShouldReturnDatabaseError() {
        when(serverMapper.selectActiveServers(any(), isNull(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertError(ErrorCode.DATABASE_ERROR, () -> serverService.list(new ServerQueryRequest()));
    }

    /** 验证加密基础设施异常映射为 50000。 */
    @Test
    void createEncryptionFailureShouldReturnInternalError() {
        CreateServerRequest request = createRequest("password");
        request.setSshPassword("secret");
        when(serverMapper.insertServerBase(any())).thenAnswer(invocation -> {
            invocation.<ServerEntity>getArgument(0).setId(7L);
            return 1;
        });
        when(credentialCipher.encrypt(7L, "ssh_password", "secret"))
                .thenThrow(new IllegalStateException("encryption failed"));

        assertError(ErrorCode.INTERNAL_SERVER_ERROR, () -> serverService.create(request));
    }

    /** 验证状态快照映射 Agent 与投递遥测字段并生成当前检查时间。 */
    @Test
    void statusShouldReturnSnapshotAndCheckedTime() {
        ServerEntity snapshot = new ServerEntity();
        snapshot.setId(7L);
        snapshot.setStatus("online");
        snapshot.setAgentStatus("online");
        snapshot.setLastHeartbeatAt(LocalDateTime.now().minusMinutes(1));
        snapshot.setDeliveryPendingCount(3L);
        snapshot.setDeliveryPendingBytes(456L);
        snapshot.setDeliveryOldestCollectedAt(LocalDateTime.now().minusHours(1));
        snapshot.setDeliveryDropCount(2L);
        snapshot.setDeliveryDeadLetterCount(1L);
        snapshot.setDeliveryDeadLetterBytes(789L);
        when(serverMapper.selectActiveServerStatusById(7L)).thenReturn(snapshot);
        OffsetDateTime before = OffsetDateTime.now();

        ServerStatusVo result = serverService.status(7L);

        assertEquals(7L, result.getServerId());
        assertEquals("online", result.getAgentStatus());
        assertNotNull(result.getLastHeartbeatAt());
        assertTrue(!result.getCheckedAt().isBefore(before.minusSeconds(1)));
        assertEquals(3L, result.getDeliveryPendingCount());
        assertEquals(456L, result.getDeliveryPendingBytes());
        assertNotNull(result.getDeliveryOldestCollectedAt());
        assertEquals(2L, result.getDeliveryDropCount());
        assertEquals(1L, result.getDeliveryDeadLetterCount());
        assertEquals(789L, result.getDeliveryDeadLetterBytes());
    }

    /** 安排创建基础记录、密文更新和公开记录回读均成功。 */
    private void arrangeSuccessfulCreate() {
        when(serverMapper.insertServerBase(any())).thenAnswer(invocation -> {
            invocation.<ServerEntity>getArgument(0).setId(7L);
            return 1;
        });
        when(serverMapper.updateCredentialCiphertexts(eq(7L), any(), any(), any())).thenReturn(1);
        when(serverMapper.selectActiveServerById(7L)).thenReturn(publicServer());
    }

    /** 安排更新条件写和公开记录回读均成功。 */
    private void arrangeSuccessfulUpdate(ServerEntity existing) {
        when(serverMapper.selectActiveServerWithCredentialsById(7L)).thenReturn(existing);
        when(serverMapper.updateActiveServer(any())).thenReturn(1);
        when(serverMapper.selectActiveServerById(7L)).thenReturn(publicServer());
    }

    /** 捕获传给单次条件更新的最终服务器实体。 */
    private ServerEntity captureUpdatedServer() {
        ArgumentCaptor<ServerEntity> captor = ArgumentCaptor.forClass(ServerEntity.class);
        verify(serverMapper).updateActiveServer(captor.capture());
        return captor.getValue();
    }

    /** 创建合法的服务器创建请求基础字段。 */
    private CreateServerRequest createRequest(String authType) {
        CreateServerRequest request = new CreateServerRequest();
        request.setName("production");
        request.setHost("prod.example.com");
        request.setDescription("production server");
        request.setSshHost("10.0.0.7");
        request.setSshPort(22);
        request.setSshUser("deploy");
        request.setSshAuthType(authType);
        return request;
    }

    /** 创建合法的服务器 PUT 全量更新请求基础字段。 */
    private UpdateServerRequest updateRequest(String authType) {
        UpdateServerRequest request = new UpdateServerRequest();
        request.setName("production-updated");
        request.setHost("prod-new.example.com");
        request.setDescription("");
        request.setSshHost("10.0.0.8");
        request.setSshPort(2222);
        request.setSshUser("operator");
        request.setSshAuthType(authType);
        return request;
    }

    /** 创建带密码密文的现有服务器内部记录。 */
    private ServerEntity existingPasswordServer() {
        ServerEntity server = new ServerEntity();
        server.setId(7L);
        server.setSshAuthType("password");
        server.setSshPasswordEncrypted("old-password-cipher");
        return server;
    }

    /** 创建带私钥和口令密文的现有服务器内部记录。 */
    private ServerEntity existingPrivateKeyServer() {
        ServerEntity server = new ServerEntity();
        server.setId(7L);
        server.setSshAuthType("private_key");
        server.setSshPrivateKeyEncrypted("old-key-cipher");
        server.setSshPrivateKeyPassphraseEncrypted("old-phrase-cipher");
        return server;
    }

    /** 创建 Mapper 公开查询会返回的服务器记录。 */
    private ServerEntity publicServer() {
        ServerEntity server = new ServerEntity();
        server.setId(7L);
        server.setName("production");
        server.setHost("prod.example.com");
        server.setDescription("production server");
        server.setStatus("online");
        server.setSshHost("10.0.0.7");
        server.setSshPort(22);
        server.setSshUser("deploy");
        server.setSshAuthType("password");
        server.setCreatedAt(LocalDateTime.now().minusDays(1));
        server.setUpdatedAt(LocalDateTime.now());
        return server;
    }

    /** 执行业务调用并断言稳定错误码。 */
    private void assertError(ErrorCode errorCode, Runnable action) {
        BusinessException exception = assertThrows(BusinessException.class, action::run);
        assertEquals(errorCode, exception.getErrorCode());
    }
}
