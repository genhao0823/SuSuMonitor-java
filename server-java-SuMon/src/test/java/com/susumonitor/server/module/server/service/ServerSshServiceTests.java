package com.susumonitor.server.module.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.server.dto.UpdateSshHostKeyRequest;
import com.susumonitor.server.module.server.entity.ServerEntity;
import com.susumonitor.server.module.server.entity.SshTestHistoryEntity;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.server.mapper.SshTestHistoryMapper;
import com.susumonitor.server.module.server.vo.SshHostKeyObservationVo;
import com.susumonitor.server.module.server.vo.SshHostKeyVo;
import com.susumonitor.server.module.server.vo.SshTestHistoryVo;
import com.susumonitor.server.module.server.vo.SshTestVo;
import com.susumonitor.server.security.CredentialCipher;
import com.susumonitor.server.ssh.SshConnectionException;
import com.susumonitor.server.ssh.SshConnectionTester;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证 SSH 主机公钥状态机、凭据延迟解密和稳定异常映射。
 */
// 启用 Mockito 扩展，使测试不访问数据库、网络或真实凭据密码器。
@ExtendWith(MockitoExtension.class)
class ServerSshServiceTests {

    private static final long SERVER_ID = 7L;
    private static final long OPERATOR_ID = 11L;
    private static final String HOST = "192.0.2.7";
    private static final int PORT = 22;
    private static final String ALGORITHM = "ssh-ed25519";
    private static final String FIRST_FINGERPRINT = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String SECOND_FINGERPRINT = "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    // 使用 Mapper 替身控制主机公钥快照、凭据快照和 CAS 结果。
    @Mock
    private ServerMapper serverMapper;

    // 使用密码器替身验证凭据只在网络组件请求时解密。
    @Mock
    private CredentialCipher credentialCipher;

    // 使用网络组件替身避免建立任何真实 SSH 连接。
    @Mock
    private SshConnectionTester connectionTester;

    // 使用历史 Mapper 替身验证测试结果留痕行为。
    @Mock
    private SshTestHistoryMapper sshTestHistoryMapper;

    private ServerSshService serverSshService;

    /** 在每个测试前创建待测 SSH 业务服务。 */
    // 将当前方法注册为 JUnit 5 的测试初始化方法。
    @BeforeEach
    void setUp() {
        serverSshService = new ServerSshServiceImpl(
                serverMapper, credentialCipher, connectionTester, sshTestHistoryMapper);
    }

    /** 验证未登记公钥时完成握手、CAS 首次确认并回读最终状态。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void firstConfirmationShouldPersistObservedHostKey() {
        ServerEntity initial = hostKeyServer(null, null);
        ServerEntity confirmed = hostKeyServer(ALGORITHM, FIRST_FINGERPRINT);
        when(serverMapper.selectActiveServerHostKeyById(SERVER_ID)).thenReturn(initial, confirmed);
        when(connectionTester.verifyHostKey(HOST, PORT, FIRST_FINGERPRINT))
                .thenReturn(new SshConnectionTester.SshHostKeyObservation(ALGORITHM, FIRST_FINGERPRINT));
        when(serverMapper.compareAndSetSshHostKey(
                SERVER_ID, HOST, PORT, null, ALGORITHM, FIRST_FINGERPRINT, OPERATOR_ID)).thenReturn(1);

        SshHostKeyVo result = serverSshService.updateHostKey(
                SERVER_ID, hostKeyRequest(FIRST_FINGERPRINT, false), OPERATOR_ID);

        assertEquals("confirmed", result.getOperation());
        assertEquals(FIRST_FINGERPRINT, result.getHostKeyFingerprint());
        verify(serverMapper).compareAndSetSshHostKey(
                SERVER_ID, HOST, PORT, null, ALGORITHM, FIRST_FINGERPRINT, OPERATOR_ID);
    }

    /** 验证已有指纹发生变化时必须显式提交 replace，且拒绝前不进行握手。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void changedFingerprintWithoutReplaceShouldFail() {
        when(serverMapper.selectActiveServerHostKeyById(SERVER_ID))
                .thenReturn(hostKeyServer(ALGORITHM, FIRST_FINGERPRINT));

        assertError(ErrorCode.SSH_HOST_KEY_MISMATCH,
                () -> serverSshService.updateHostKey(
                        SERVER_ID, hostKeyRequest(SECOND_FINGERPRINT, false), OPERATOR_ID));

        verify(connectionTester, never()).verifyHostKey(anyString(), eq(PORT), anyString());
        verify(serverMapper, never()).compareAndSetSshHostKey(
                any(), anyString(), eq(PORT), any(), anyString(), anyString(), any());
    }

    /** 验证显式 replace 允许将已登记指纹原子轮换为握手观察值。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void changedFingerprintWithReplaceShouldRotate() {
        ServerEntity initial = hostKeyServer(ALGORITHM, FIRST_FINGERPRINT);
        ServerEntity rotated = hostKeyServer(ALGORITHM, SECOND_FINGERPRINT);
        when(serverMapper.selectActiveServerHostKeyById(SERVER_ID)).thenReturn(initial, rotated);
        when(connectionTester.verifyHostKey(HOST, PORT, SECOND_FINGERPRINT))
                .thenReturn(new SshConnectionTester.SshHostKeyObservation(ALGORITHM, SECOND_FINGERPRINT));
        when(serverMapper.compareAndSetSshHostKey(
                SERVER_ID, HOST, PORT, FIRST_FINGERPRINT, ALGORITHM, SECOND_FINGERPRINT, OPERATOR_ID)).thenReturn(1);

        SshHostKeyVo result = serverSshService.updateHostKey(
                SERVER_ID, hostKeyRequest(SECOND_FINGERPRINT, true), OPERATOR_ID);

        assertEquals("rotated", result.getOperation());
        assertEquals(SECOND_FINGERPRINT, result.getHostKeyFingerprint());
    }

    /** 验证相同指纹及算法握手成功时通过 CAS 复核并返回 unchanged。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void sameFingerprintShouldBeIdempotent() {
        ServerEntity existing = hostKeyServer(ALGORITHM, FIRST_FINGERPRINT);
        when(serverMapper.selectActiveServerHostKeyById(SERVER_ID)).thenReturn(existing, existing);
        when(connectionTester.verifyHostKey(HOST, PORT, FIRST_FINGERPRINT))
                .thenReturn(new SshConnectionTester.SshHostKeyObservation(ALGORITHM, FIRST_FINGERPRINT));
        when(serverMapper.compareAndSetSshHostKey(
                SERVER_ID, HOST, PORT, FIRST_FINGERPRINT, ALGORITHM, FIRST_FINGERPRINT, OPERATOR_ID)).thenReturn(1);

        SshHostKeyVo result = serverSshService.updateHostKey(
                SERVER_ID, hostKeyRequest(FIRST_FINGERPRINT, false), OPERATOR_ID);

        assertEquals("unchanged", result.getOperation());
        verify(serverMapper).compareAndSetSshHostKey(
                SERVER_ID, HOST, PORT, FIRST_FINGERPRINT, ALGORITHM, FIRST_FINGERPRINT, OPERATOR_ID);
    }

    /** 验证握手后的 CAS 未命中且目标仍存在时返回资源冲突。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void compareAndSetMissShouldReturnConflict() {
        ServerEntity initial = hostKeyServer(null, null);
        when(serverMapper.selectActiveServerHostKeyById(SERVER_ID)).thenReturn(initial, initial);
        when(connectionTester.verifyHostKey(HOST, PORT, FIRST_FINGERPRINT))
                .thenReturn(new SshConnectionTester.SshHostKeyObservation(ALGORITHM, FIRST_FINGERPRINT));
        when(serverMapper.compareAndSetSshHostKey(
                SERVER_ID, HOST, PORT, null, ALGORITHM, FIRST_FINGERPRINT, OPERATOR_ID)).thenReturn(0);

        assertError(ErrorCode.RESOURCE_CONFLICT,
                () -> serverSshService.updateHostKey(
                        SERVER_ID, hostKeyRequest(FIRST_FINGERPRINT, false), OPERATOR_ID));
    }

    /** 验证未确认主机公钥时 POST 测试在解密和网络调用前返回 40901。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void unconfirmedHostKeyShouldRejectConnectionTest() {
        when(serverMapper.selectActiveServerSshById(SERVER_ID)).thenReturn(passwordServer(false));

        assertError(ErrorCode.SSH_HOST_KEY_NOT_CONFIRMED,
                () -> serverSshService.testConnection(SERVER_ID));

        verify(credentialCipher, never()).decrypt(any(), anyString(), anyString());
        verify(connectionTester, never()).testPassword(
                anyString(), eq(PORT), anyString(), anyString(), anyString(), any());
    }

    /** 验证密码密文直到 SSH 网络组件调用 Supplier 时才解密并转换连接结果。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void passwordShouldBeDecryptedLazily() {
        ServerEntity server = passwordServer(true);
        when(serverMapper.selectActiveServerSshById(SERVER_ID)).thenReturn(server);
        when(credentialCipher.decrypt(SERVER_ID, "ssh_password", "password-cipher"))
                .thenReturn("test-password");
        when(connectionTester.testPassword(
                eq(HOST), eq(PORT), eq("operator"), eq(ALGORITHM), eq(FIRST_FINGERPRINT), any()))
                .thenAnswer(invocation -> {
                    verify(credentialCipher, never()).decrypt(any(), anyString(), anyString());
                    Supplier<char[]> passwordSupplier = invocation.getArgument(5);
                    char[] password = passwordSupplier.get();
                    assertEquals("test-password", new String(password));
                    return new SshConnectionTester.SshConnectionResult(ALGORITHM, FIRST_FINGERPRINT, 25L);
                });

        SshTestVo result = serverSshService.testConnection(SERVER_ID);

        assertEquals(SERVER_ID, result.getServerId());
        assertEquals("password", result.getAuthType());
        assertEquals(25L, result.getDurationMs());
        assertNotNull(result.getTestedAt());
    }

    /** 验证主机身份通过后数据库 SSH 快照变化会在解密前阻止发送旧凭据。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void changedSnapshotShouldRejectBeforePasswordDecryption() {
        ServerEntity initial = passwordServer(true);
        ServerEntity changed = passwordServer(true);
        changed.setSshUser("changed-operator");
        when(serverMapper.selectActiveServerSshById(SERVER_ID)).thenReturn(initial, changed);
        when(connectionTester.testPassword(
                eq(HOST), eq(PORT), eq("operator"), eq(ALGORITHM), eq(FIRST_FINGERPRINT), any()))
                .thenAnswer(invocation -> {
                    Supplier<char[]> passwordSupplier = invocation.getArgument(5);
                    passwordSupplier.get();
                    return new SshConnectionTester.SshConnectionResult(ALGORITHM, FIRST_FINGERPRINT, 25L);
                });

        assertError(ErrorCode.RESOURCE_CONFLICT, () -> serverSshService.testConnection(SERVER_ID));

        verify(credentialCipher, never()).decrypt(any(), anyString(), anyString());
    }

    /** 验证 private_key 路径分别延迟取得私钥和可选口令并返回认证结果。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void privateKeyShouldUseKeyAndPassphraseSuppliers() {
        ServerEntity server = sshServer("private_key", true);
        server.setSshPrivateKeyEncrypted("key-cipher");
        server.setSshPrivateKeyPassphraseEncrypted("passphrase-cipher");
        when(serverMapper.selectActiveServerSshById(SERVER_ID)).thenReturn(server);
        when(credentialCipher.decrypt(SERVER_ID, "ssh_private_key", "key-cipher"))
                .thenReturn("test-private-key");
        when(credentialCipher.decrypt(SERVER_ID, "ssh_private_key_passphrase", "passphrase-cipher"))
                .thenReturn("test-passphrase");
        when(connectionTester.testPrivateKey(
                eq(HOST), eq(PORT), eq("operator"), eq(ALGORITHM), eq(FIRST_FINGERPRINT), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<String> keySupplier = invocation.getArgument(5);
                    Supplier<char[]> passphraseSupplier = invocation.getArgument(6);
                    assertEquals("test-private-key", keySupplier.get());
                    assertEquals("test-passphrase", new String(passphraseSupplier.get()));
                    return new SshConnectionTester.SshConnectionResult(ALGORITHM, FIRST_FINGERPRINT, 30L);
                });

        SshTestVo result = serverSshService.testConnection(SERVER_ID);

        assertEquals("private_key", result.getAuthType());
        assertEquals(30L, result.getDurationMs());
        verify(connectionTester, never()).testPassword(
                anyString(), eq(PORT), anyString(), anyString(), anyString(), any());
    }

    /** 验证每一种 SSH 网络异常分类都映射为对应稳定业务错误码。 */
    // 使用枚举参数源对所有 SSH 异常分类执行同一映射断言。
    @EnumSource(SshConnectionException.Category.class)
    // 将当前方法注册为 JUnit 5 参数化测试用例。
    @ParameterizedTest
    void connectionExceptionShouldMapToStableError(SshConnectionException.Category category) {
        when(serverMapper.selectActiveServerSshById(SERVER_ID)).thenReturn(passwordServer(true));
        when(connectionTester.testPassword(
                eq(HOST), eq(PORT), eq("operator"), eq(ALGORITHM), eq(FIRST_FINGERPRINT), any()))
                .thenThrow(new SshConnectionException(category));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> serverSshService.testConnection(SERVER_ID));

        assertEquals(expectedError(category), exception.getErrorCode());
    }

    /** 验证观察模式返回目标主机当前公钥，不涉及凭据与登记。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void observeHostKeyShouldReturnObservedKey() {
        ServerEntity server = hostKeyServer(ALGORITHM, FIRST_FINGERPRINT);
        when(serverMapper.selectActiveServerHostKeyById(SERVER_ID)).thenReturn(server);
        when(connectionTester.observeHostKey(HOST, PORT))
                .thenReturn(new SshConnectionTester.SshHostKeyObservation(ALGORITHM, FIRST_FINGERPRINT));

        SshHostKeyObservationVo result = serverSshService.observeHostKey(SERVER_ID);

        assertEquals(SERVER_ID, result.getServerId());
        assertEquals(ALGORITHM, result.getHostKeyAlgorithm());
        assertEquals(FIRST_FINGERPRINT, result.getHostKeyFingerprint());
        assertEquals(FIRST_FINGERPRINT, result.getRegisteredFingerprint());
        assertNotNull(result.getObservedAt());
        verify(connectionTester, never()).testPassword(anyString(), eq(PORT), anyString(), anyString(), anyString(), any());
    }

    /** 验证未确认过主机密钥的服务器观察时 registered_fingerprint 为 null。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void observeHostKeyOnUnconfirmedServerShouldReportNullRegistered() {
        ServerEntity server = hostKeyServer(null, null);
        when(serverMapper.selectActiveServerHostKeyById(SERVER_ID)).thenReturn(server);
        when(connectionTester.observeHostKey(HOST, PORT))
                .thenReturn(new SshConnectionTester.SshHostKeyObservation(ALGORITHM, FIRST_FINGERPRINT));

        SshHostKeyObservationVo result = serverSshService.observeHostKey(SERVER_ID);

        assertEquals(null, result.getRegisteredFingerprint());
    }

    /** 验证观察目标不存在的服务器返回 40400。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void observeHostKeyOnMissingServerShouldReturnNotFound() {
        when(serverMapper.selectActiveServerHostKeyById(SERVER_ID)).thenReturn(null);

        assertError(ErrorCode.RESOURCE_NOT_FOUND, () -> serverSshService.observeHostKey(SERVER_ID));

        verify(connectionTester, never()).observeHostKey(anyString(), eq(PORT));
    }

    /** 验证成功连接测试会留痕一条成功历史记录。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void successfulTestShouldRecordHistory() {
        ServerEntity server = passwordServer(true);
        when(serverMapper.selectActiveServerSshById(SERVER_ID)).thenReturn(server);
        when(connectionTester.testPassword(
                eq(HOST), eq(PORT), eq("operator"), eq(ALGORITHM), eq(FIRST_FINGERPRINT), any()))
                .thenReturn(new SshConnectionTester.SshConnectionResult(ALGORITHM, FIRST_FINGERPRINT, 25L));

        SshTestVo result = serverSshService.testConnection(SERVER_ID);

        assertEquals(true, result.isConnected());
        ArgumentCaptor<SshTestHistoryEntity> captor = ArgumentCaptor.forClass(SshTestHistoryEntity.class);
        verify(sshTestHistoryMapper).insert(captor.capture());
        SshTestHistoryEntity history = captor.getValue();
        assertEquals(SERVER_ID, history.getServerId());
        assertEquals(true, history.getConnected());
        assertEquals(null, history.getErrorCode());
        assertEquals(ALGORITHM, history.getHostKeyAlgorithm());
        assertEquals(FIRST_FINGERPRINT, history.getHostKeyFingerprint());
        assertEquals("password", history.getAuthType());
        assertEquals(25L, history.getDurationMs());
        assertNotNull(history.getTestedAt());
    }

    /** 验证连接失败会留痕失败历史（含错误码）后继续抛出映射后的业务异常。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void failedTestShouldRecordFailureHistoryWithErrorCode() {
        ServerEntity server = passwordServer(true);
        when(serverMapper.selectActiveServerSshById(SERVER_ID)).thenReturn(server);
        when(connectionTester.testPassword(
                eq(HOST), eq(PORT), eq("operator"), eq(ALGORITHM), eq(FIRST_FINGERPRINT), any()))
                .thenThrow(new SshConnectionException(SshConnectionException.Category.TIMEOUT));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> serverSshService.testConnection(SERVER_ID));

        assertEquals(ErrorCode.SSH_CONNECTION_TIMEOUT, exception.getErrorCode());
        ArgumentCaptor<SshTestHistoryEntity> captor = ArgumentCaptor.forClass(SshTestHistoryEntity.class);
        verify(sshTestHistoryMapper).insert(captor.capture());
        SshTestHistoryEntity history = captor.getValue();
        assertEquals(false, history.getConnected());
        assertEquals(50400, history.getErrorCode());
        assertEquals(null, history.getHostKeyAlgorithm());
        assertEquals(0L, history.getDurationMs());
    }

    /** 验证历史查询将实体转换为对外 VO，保持时间与成功标志。 */
    // 将当前方法注册为 JUnit 5 测试用例。
    @Test
    void listTestHistoryShouldConvertEntities() {
        SshTestHistoryEntity entity = new SshTestHistoryEntity();
        entity.setId(1L);
        entity.setServerId(SERVER_ID);
        entity.setConnected(true);
        entity.setErrorCode(null);
        entity.setHostKeyAlgorithm(ALGORITHM);
        entity.setHostKeyFingerprint(FIRST_FINGERPRINT);
        entity.setAuthType("password");
        entity.setDurationMs(25L);
        entity.setTestedAt(LocalDateTime.now());
        when(sshTestHistoryMapper.selectRecentByServerId(SERVER_ID, 10)).thenReturn(List.of(entity));

        List<SshTestHistoryVo> result = serverSshService.listTestHistory(SERVER_ID);

        assertEquals(1, result.size());
        assertEquals(SERVER_ID, result.getFirst().getServerId());
        assertEquals(true, result.getFirst().isConnected());
        assertEquals(25L, result.getFirst().getDurationMs());
        assertEquals(ALGORITHM, result.getFirst().getHostKeyAlgorithm());
        assertNotNull(result.getFirst().getTestedAt());
    }

    /** 创建主机公钥确认请求。 */
    private UpdateSshHostKeyRequest hostKeyRequest(String fingerprint, boolean replace) {
        UpdateSshHostKeyRequest request = new UpdateSshHostKeyRequest();
        request.setExpectedFingerprint(fingerprint);
        request.setReplace(replace);
        return request;
    }

    /** 创建主机公钥查询使用的最小服务器快照。 */
    private ServerEntity hostKeyServer(String algorithm, String fingerprint) {
        ServerEntity server = sshServer("password", fingerprint != null);
        server.setSshHostKeyAlgorithm(algorithm);
        server.setSshHostKeyFingerprint(fingerprint);
        server.setSshHostKeyVerifiedAt(LocalDateTime.now());
        return server;
    }

    /** 创建密码认证测试使用的服务器快照。 */
    private ServerEntity passwordServer(boolean confirmed) {
        ServerEntity server = sshServer("password", confirmed);
        server.setSshPasswordEncrypted("password-cipher");
        return server;
    }

    /** 创建包含目标和可选已确认主机身份的 SSH 快照。 */
    private ServerEntity sshServer(String authType, boolean confirmed) {
        ServerEntity server = new ServerEntity();
        server.setId(SERVER_ID);
        server.setSshHost(HOST);
        server.setSshPort(PORT);
        server.setSshUser("operator");
        server.setSshAuthType(authType);
        if (confirmed) {
            server.setSshHostKeyAlgorithm(ALGORITHM);
            server.setSshHostKeyFingerprint(FIRST_FINGERPRINT);
        }
        return server;
    }

    /** 将 SSH 网络分类转换为测试预期的业务错误码。 */
    private ErrorCode expectedError(SshConnectionException.Category category) {
        return switch (category) {
            case TARGET_FORBIDDEN -> ErrorCode.SSH_TARGET_FORBIDDEN;
            case HOST_KEY_MISMATCH -> ErrorCode.SSH_HOST_KEY_MISMATCH;
            case STATE_CHANGED -> ErrorCode.RESOURCE_CONFLICT;
            case RESOURCE_NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case DATABASE_ERROR -> ErrorCode.DATABASE_ERROR;
            case CONNECTION_LIMIT -> ErrorCode.SSH_CONNECTION_LIMIT_REACHED;
            case TIMEOUT -> ErrorCode.SSH_CONNECTION_TIMEOUT;
            case AUTHENTICATION_FAILED -> ErrorCode.SSH_AUTHENTICATION_FAILED;
            case CONNECTION_FAILED -> ErrorCode.SSH_CONNECTION_FAILED;
        };
    }

    /** 执行业务调用并断言稳定错误码。 */
    private void assertError(ErrorCode errorCode, Runnable action) {
        BusinessException exception = assertThrows(BusinessException.class, action::run);
        assertEquals(errorCode, exception.getErrorCode());
    }
}
