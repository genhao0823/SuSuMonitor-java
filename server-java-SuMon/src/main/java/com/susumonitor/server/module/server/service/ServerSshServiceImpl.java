package com.susumonitor.server.module.server.service;

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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 编排服务器 SSH 主机公钥确认、显式轮换和凭据连接测试。
 */
// 将当前类注册为 Spring Service，隔离服务器 CRUD 与阻塞 SSH 网络操作。
@Service
public class ServerSshServiceImpl implements ServerSshService {

    private static final String PASSWORD_AUTH_TYPE = "password";
    private static final String PRIVATE_KEY_AUTH_TYPE = "private_key";
    private static final String PASSWORD_CREDENTIAL_TYPE = "ssh_password";
    private static final String PRIVATE_KEY_CREDENTIAL_TYPE = "ssh_private_key";
    private static final String PASSPHRASE_CREDENTIAL_TYPE = "ssh_private_key_passphrase";
    private static final String OPERATION_CONFIRMED = "confirmed";
    private static final String OPERATION_ROTATED = "rotated";
    private static final String OPERATION_UNCHANGED = "unchanged";
    private static final int HISTORY_LIMIT = 10;
    private static final ZoneId APPLICATION_ZONE = ZoneOffset.UTC;

    private final ServerMapper serverMapper;
    private final CredentialCipher credentialCipher;
    private final SshConnectionTester connectionTester;
    private final SshTestHistoryMapper sshTestHistoryMapper;

    /**
     * 注入服务器持久化、凭据解密、SSH 网络组件和测试历史持久化。
     *
     * @param serverMapper 服务器 Mapper
     * @param credentialCipher 凭据加解密组件
     * @param connectionTester SSH 网络组件
     * @param sshTestHistoryMapper SSH 测试历史 Mapper
     */
    public ServerSshServiceImpl(ServerMapper serverMapper, CredentialCipher credentialCipher,
            SshConnectionTester connectionTester, SshTestHistoryMapper sshTestHistoryMapper) {
        this.serverMapper = serverMapper;
        this.credentialCipher = credentialCipher;
        this.connectionTester = connectionTester;
        this.sshTestHistoryMapper = sshTestHistoryMapper;
    }

    /**
     * 握手核对带外指纹，并通过 CAS 完成首次确认或显式轮换。
     *
     * @param serverId 服务器 ID
     * @param request 主机公钥确认请求
     * @param operatorId 操作管理员 ID
     * @return 最终登记的主机公钥信息
     */
    public SshHostKeyVo updateHostKey(Long serverId, UpdateSshHostKeyRequest request, Long operatorId) {
        validateIdentifiers(serverId, operatorId);
        if (request == null || request.getExpectedFingerprint() == null
                || !request.getExpectedFingerprint().matches("^SHA256:[A-Za-z0-9+/]{43}$")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        ServerEntity server = selectHostKey(serverId);
        String previousFingerprint = server.getSshHostKeyFingerprint();
        boolean sameFingerprint = request.getExpectedFingerprint().equals(previousFingerprint);
        if (previousFingerprint == null && request.isReplace()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        if (previousFingerprint != null && !sameFingerprint && !request.isReplace()) {
            throw new BusinessException(ErrorCode.SSH_HOST_KEY_MISMATCH);
        }
        SshConnectionTester.SshHostKeyObservation observation = verifyHostKey(
                server.getSshHost(), server.getSshPort(), request.getExpectedFingerprint());
        if (sameFingerprint) {
            if (!observation.algorithm().equals(server.getSshHostKeyAlgorithm())) {
                throw new BusinessException(ErrorCode.SSH_HOST_KEY_MISMATCH);
            }
            compareAndSetHostKey(server, previousFingerprint, observation, operatorId);
            return toHostKeyVo(selectHostKey(serverId), OPERATION_UNCHANGED);
        }
        compareAndSetHostKey(server, previousFingerprint, observation, operatorId);
        ServerEntity updated = selectHostKey(serverId);
        return toHostKeyVo(updated, previousFingerprint == null ? OPERATION_CONFIRMED : OPERATION_ROTATED);
    }

    /**
     * 使用已登记主机身份和已有加密凭据完成一次无命令 SSH 认证测试。
     *
     * @param serverId 服务器 ID
     * @return SSH 认证测试结果
     */
    public SshTestVo testConnection(Long serverId) {
        validateServerId(serverId);
        ServerEntity server = selectSshSnapshot(serverId);
        if (server.getSshHostKeyAlgorithm() == null || server.getSshHostKeyFingerprint() == null) {
            throw new BusinessException(ErrorCode.SSH_HOST_KEY_NOT_CONFIRMED);
        }
        try {
            SshConnectionTester.SshConnectionResult result;
            if (PASSWORD_AUTH_TYPE.equals(server.getSshAuthType())) {
                result = connectionTester.testPassword(server.getSshHost(), server.getSshPort(), server.getSshUser(),
                        server.getSshHostKeyAlgorithm(), server.getSshHostKeyFingerprint(),
                        () -> {
                            assertSshSnapshotCurrent(server);
                            // 密码走 char[] 直解路径：全程不创建 String，缩小凭据堆驻留面（2026-09-14 加固）。
                            return credentialCipher.decryptToCharArray(server.getId(), PASSWORD_CREDENTIAL_TYPE,
                                    server.getSshPasswordEncrypted());
                        });
            } else if (PRIVATE_KEY_AUTH_TYPE.equals(server.getSshAuthType())) {
                result = connectionTester.testPrivateKey(server.getSshHost(), server.getSshPort(), server.getSshUser(),
                        server.getSshHostKeyAlgorithm(), server.getSshHostKeyFingerprint(),
                        () -> {
                            assertSshSnapshotCurrent(server);
                            // 私钥受 SSHJ loadKeys(String,…) API 限制仍以 String 解密，为已知残留（见开发日志）。
                            return decrypt(server, PRIVATE_KEY_CREDENTIAL_TYPE, server.getSshPrivateKeyEncrypted());
                        },
                        () -> {
                            assertSshSnapshotCurrent(server);
                            return server.getSshPrivateKeyPassphraseEncrypted() == null ? null
                                    // 口令与密码同口径：char[] 直解，不产生中间 String。
                                    : credentialCipher.decryptToCharArray(server.getId(), PASSPHRASE_CREDENTIAL_TYPE,
                                            server.getSshPrivateKeyPassphraseEncrypted());
                        });
            } else {
                throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
            }
            recordTestHistory(server, true, null, result.algorithm(), result.fingerprint(),
                    result.durationMillis());
            return toSshTestVo(server, result);
        } catch (SshConnectionException exception) {
            BusinessException mapped = mapConnectionException(exception);
            recordTestHistory(server, false, mapped.getErrorCode().getCode(), null, null, 0);
            throw mapped;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
        }
    }

    /**
     * 只读观察目标主机当前公钥，供管理员"一键信任"前核对，不登记、不发送凭据。
     *
     * @param serverId 服务器 ID
     * @return 观察到的远端主机公钥信息
     */
    public SshHostKeyObservationVo observeHostKey(Long serverId) {
        validateServerId(serverId);
        ServerEntity server = selectHostKey(serverId);
        SshConnectionTester.SshHostKeyObservation observation = observeHostKey(server);
        SshHostKeyObservationVo result = new SshHostKeyObservationVo();
        result.setServerId(server.getId());
        result.setHostKeyAlgorithm(observation.algorithm());
        result.setHostKeyFingerprint(observation.fingerprint());
        result.setRegisteredFingerprint(server.getSshHostKeyFingerprint());
        result.setObservedAt(OffsetDateTime.now(APPLICATION_ZONE));
        return result;
    }

    /** 调用网络层读取目标主机实际公钥，并转换稳定错误码。 */
    private SshConnectionTester.SshHostKeyObservation observeHostKey(ServerEntity server) {
        try {
            return connectionTester.observeHostKey(server.getSshHost(), server.getSshPort());
        } catch (SshConnectionException exception) {
            throw mapConnectionException(exception);
        }
    }

    /**
     * 查询某服务器最近的 SSH 连接测试历史（含成功与失败记录）。
     *
     * @param serverId 服务器 ID
     * @return 最近的测试历史列表，按测试时间倒序
     */
    public List<SshTestHistoryVo> listTestHistory(Long serverId) {
        validateServerId(serverId);
        try {
            List<SshTestHistoryEntity> records =
                    sshTestHistoryMapper.selectRecentByServerId(serverId, HISTORY_LIMIT);
            return records.stream().map(this::toTestHistoryVo).toList();
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /** 持久化一次连接测试结果到历史表，失败时 duration 记为 0。 */
    private void recordTestHistory(ServerEntity server, boolean connected, Integer errorCode,
            String hostKeyAlgorithm, String hostKeyFingerprint, long durationMs) {
        try {
            SshTestHistoryEntity entity = new SshTestHistoryEntity();
            entity.setServerId(server.getId());
            entity.setConnected(connected);
            entity.setErrorCode(errorCode);
            entity.setHostKeyAlgorithm(hostKeyAlgorithm);
            entity.setHostKeyFingerprint(hostKeyFingerprint);
            entity.setAuthType(server.getSshAuthType());
            entity.setDurationMs(durationMs);
            entity.setTestedAt(LocalDateTime.now(APPLICATION_ZONE));
            entity.setCreatedAt(LocalDateTime.now(APPLICATION_ZONE));
            sshTestHistoryMapper.insert(entity);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /** 转换测试历史实体为对外响应。 */
    private SshTestHistoryVo toTestHistoryVo(SshTestHistoryEntity entity) {
        SshTestHistoryVo result = new SshTestHistoryVo();
        result.setServerId(entity.getServerId());
        result.setConnected(Boolean.TRUE.equals(entity.getConnected()));
        result.setErrorCode(entity.getErrorCode());
        result.setHostKeyAlgorithm(entity.getHostKeyAlgorithm());
        result.setHostKeyFingerprint(entity.getHostKeyFingerprint());
        result.setAuthType(entity.getAuthType());
        result.setDurationMs(entity.getDurationMs() == null ? 0 : entity.getDurationMs());
        result.setTestedAt(toOffsetDateTime(entity.getTestedAt()));
        return result;
    }

    /** 查询不含凭据的主机公钥快照。 */
    private ServerEntity selectHostKey(Long serverId) {
        try {
            ServerEntity server = serverMapper.selectActiveServerHostKeyById(serverId);
            if (server == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return server;
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /** 查询连接测试所需的内部 SSH 快照。 */
    private ServerEntity selectSshSnapshot(Long serverId) {
        try {
            ServerEntity server = serverMapper.selectActiveServerSshById(serverId);
            if (server == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return server;
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /** 调用网络层核对远端主机公钥，并转换稳定错误码。 */
    private SshConnectionTester.SshHostKeyObservation verifyHostKey(String host, Integer port, String fingerprint) {
        try {
            return connectionTester.verifyHostKey(host, port, fingerprint);
        } catch (SshConnectionException exception) {
            throw mapConnectionException(exception);
        }
    }

    /** 使用握手前快照作为条件原子写入主机公钥。 */
    private void compareAndSetHostKey(ServerEntity server, String previousFingerprint,
            SshConnectionTester.SshHostKeyObservation observation, Long operatorId) {
        try {
            int updated = serverMapper.compareAndSetSshHostKey(server.getId(), server.getSshHost(),
                    server.getSshPort(), previousFingerprint, observation.algorithm(), observation.fingerprint(),
                    operatorId);
            if (updated != 1) {
                if (serverMapper.selectActiveServerHostKeyById(server.getId()) == null) {
                    throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
            }
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /** 使用服务器 ID 和凭据类型绑定上下文解密非空凭据。 */
    private String decrypt(ServerEntity server, String credentialType, String ciphertext) {
        if (ciphertext == null) {
            throw new IllegalStateException("SSH credential ciphertext is missing");
        }
        return credentialCipher.decrypt(server.getId(), credentialType, ciphertext);
    }

    /**
     * 在主机身份验证通过、凭据解密前重新确认数据库 SSH 快照未发生变化。
     */
    private void assertSshSnapshotCurrent(ServerEntity expected) {
        ServerEntity current;
        try {
            current = selectSshSnapshot(expected.getId());
        } catch (BusinessException exception) {
            if (ErrorCode.RESOURCE_NOT_FOUND == exception.getErrorCode()) {
                throw new SshConnectionException(SshConnectionException.Category.RESOURCE_NOT_FOUND, exception);
            }
            if (ErrorCode.DATABASE_ERROR == exception.getErrorCode()) {
                throw new SshConnectionException(SshConnectionException.Category.DATABASE_ERROR, exception);
            }
            throw exception;
        }
        if (!same(expected.getSshHost(), current.getSshHost())
                || !same(expected.getSshPort(), current.getSshPort())
                || !same(expected.getSshUser(), current.getSshUser())
                || !same(expected.getSshAuthType(), current.getSshAuthType())
                || !same(expected.getSshHostKeyAlgorithm(), current.getSshHostKeyAlgorithm())
                || !same(expected.getSshHostKeyFingerprint(), current.getSshHostKeyFingerprint())
                || !same(expected.getSshPasswordEncrypted(), current.getSshPasswordEncrypted())
                || !same(expected.getSshPrivateKeyEncrypted(), current.getSshPrivateKeyEncrypted())
                || !same(expected.getSshPrivateKeyPassphraseEncrypted(),
                        current.getSshPrivateKeyPassphraseEncrypted())) {
            throw new SshConnectionException(SshConnectionException.Category.STATE_CHANGED);
        }
    }

    /** 对可空快照字段执行稳定相等比较。 */
    private boolean same(Object expected, Object current) {
        return java.util.Objects.equals(expected, current);
    }

    /** 将网络失败分类转换为稳定 API 错误。 */
    private BusinessException mapConnectionException(SshConnectionException exception) {
        ErrorCode errorCode = switch (exception.getCategory()) {
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
        return new BusinessException(errorCode, exception);
    }

    /** 转换主机公钥响应。 */
    private SshHostKeyVo toHostKeyVo(ServerEntity server, String operation) {
        SshHostKeyVo result = new SshHostKeyVo();
        result.setServerId(server.getId());
        result.setHostKeyAlgorithm(server.getSshHostKeyAlgorithm());
        result.setHostKeyFingerprint(server.getSshHostKeyFingerprint());
        result.setOperation(operation);
        result.setVerifiedAt(toOffsetDateTime(server.getSshHostKeyVerifiedAt()));
        return result;
    }

    /** 转换 SSH 认证测试响应。 */
    private SshTestVo toSshTestVo(ServerEntity server, SshConnectionTester.SshConnectionResult connection) {
        SshTestVo result = new SshTestVo();
        result.setServerId(server.getId());
        result.setConnected(true);
        result.setHostKeyAlgorithm(connection.algorithm());
        result.setHostKeyFingerprint(connection.fingerprint());
        result.setAuthType(server.getSshAuthType());
        result.setDurationMs(connection.durationMillis());
        result.setTestedAt(OffsetDateTime.now(APPLICATION_ZONE));
        return result;
    }

    /** 校验服务器和操作人 ID 均为正数。 */
    private void validateIdentifiers(Long serverId, Long operatorId) {
        validateServerId(serverId);
        if (operatorId == null || operatorId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    /** 校验服务器 ID 为正数。 */
    private void validateServerId(Long serverId) {
        if (serverId == null || serverId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    /** 将数据库时间转换为接口时区时间。 */
    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(APPLICATION_ZONE).toOffsetDateTime();
    }
}
