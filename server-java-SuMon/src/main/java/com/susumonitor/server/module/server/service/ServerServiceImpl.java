package com.susumonitor.server.module.server.service;

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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理服务器基础信息、SSH 凭据密文、分页读取、软删除和状态快照。
 */
// 将当前类注册为 Spring Service Bean，作为服务器管理业务和事务边界。
@Service
public class ServerServiceImpl implements ServerService {

    private static final String PASSWORD_AUTH_TYPE = "password";
    private static final String PRIVATE_KEY_AUTH_TYPE = "private_key";
    private static final String PASSWORD_CREDENTIAL_TYPE = "ssh_password";
    private static final String PRIVATE_KEY_CREDENTIAL_TYPE = "ssh_private_key";
    private static final String PASSPHRASE_CREDENTIAL_TYPE = "ssh_private_key_passphrase";
    private static final String AGENT_STATUS_ONLINE = "online";
    private static final int MAX_PASSWORD_LENGTH = 1024;
    private static final int MAX_PRIVATE_KEY_LENGTH = 65535;
    private static final int MAX_PASSPHRASE_LENGTH = 1024;
    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String DEFAULT_SORT_BY = "id";
    private static final String DEFAULT_SORT_ORDER = "desc";
    private static final Set<String> SORT_BY_WHITELIST = Set.of(
            "id", "name", "host", "status", "created_at", "updated_at");
    private static final Set<String> SORT_ORDER_WHITELIST = Set.of("asc", "desc");
    private static final ZoneId APPLICATION_ZONE = ZoneOffset.UTC;

    private final ServerMapper serverMapper;
    private final CredentialCipher credentialCipher;

    /**
     * 注入服务器持久化和凭据加密依赖。
     *
     * @param serverMapper 服务器数据访问组件
     * @param credentialCipher SSH 凭据加密组件
     */
    public ServerServiceImpl(ServerMapper serverMapper, CredentialCipher credentialCipher) {
        this.serverMapper = serverMapper;
        this.credentialCipher = credentialCipher;
    }

    /**
     * 先创建基础记录取得 ID，再使用该 ID 作为 AAD 上下文加密并写入凭据。
     *
     * @param request 创建参数
     * @return 新建服务器的安全公开信息
     */
    // 创建基础记录和密文更新必须处于同一事务，任一步失败都会整体回滚。
    @Transactional
    public ServerVo create(CreateServerRequest request) {
        validateCreateRequest(request);
        ServerEntity server = toServerEntity(request);
        try {
            if (serverMapper.insertServerBase(server) != 1 || server.getId() == null || server.getId() <= 0) {
                throw new BusinessException(ErrorCode.DATABASE_ERROR);
            }
            encryptCreateCredentials(request, server);
            if (serverMapper.updateCredentialCiphertexts(
                    server.getId(), server.getSshPasswordEncrypted(), server.getSshPrivateKeyEncrypted(),
                    server.getSshPrivateKeyPassphraseEncrypted()) != 1) {
                throw new BusinessException(ErrorCode.DATABASE_ERROR);
            }
            ServerEntity created = serverMapper.selectActiveServerById(server.getId());
            if (created == null) {
                throw new BusinessException(ErrorCode.DATABASE_ERROR);
            }
            return toServerVo(created);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, exception);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, exception);
        }
    }

    /**
     * 按白名单分页条件查询有效服务器，并返回统一分页结果。
     *
     * @param request 分页查询参数
     * @return 服务器安全公开信息分页结果
     */
    // 只读事务避免读方法意外参与写入，并允许数据库应用只读优化。
    @Transactional(readOnly = true)
    public PageResult<ServerVo> list(ServerQueryRequest request) {
        QueryValues query = validateQuery(request);
        try {
            // 分页由 MyBatis-Plus 拦截器承担：COUNT 自动执行，total 回写 Page。
            Page<ServerEntity> pager = new Page<>(query.page(), query.pageSize());
            List<ServerEntity> rows = serverMapper.selectActiveServers(
                    pager, query.keyword(), query.sortBy(), query.sortOrder());
            PageResult<ServerVo> result = new PageResult<>();
            result.setItems(rows.stream().map(this::toServerVo).toList());
            result.setTotal(pager.getTotal());
            result.setPage(query.page());
            result.setPageSize(query.pageSize());
            return result;
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /**
     * 按 ID 查询一条未软删除服务器的安全公开信息。
     *
     * @param serverId 服务器 ID
     * @return 服务器安全公开信息
     */
    // 详情读取使用只读事务，不读取任何凭据列。
    @Transactional(readOnly = true)
    public ServerVo get(Long serverId) {
        validateServerId(serverId);
        try {
            ServerEntity server = serverMapper.selectActiveServerById(serverId);
            if (server == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return toServerVo(server);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /**
     * 判断服务器是否存在且未被软删除，供更新入口在请求体校验前进行资源预检。
     *
     * @param serverId 服务器 ID
     * @return 服务器存在且有效时返回 true
     */
    @Transactional(readOnly = true)
    public boolean existsActive(Long serverId) {
        validateServerId(serverId);
        try {
            return serverMapper.selectActiveServerById(serverId) != null;
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /**
     * 判断服务器是否存在且未被软删除（FOR UPDATE 行锁语义）。
     *
     * <p>供指标接收等跨模块强一致校验使用：在调用方事务内锁定服务器行，
     * 防止指标写入期间服务器被软删除。锁语义封装在本模块，指标模块不直接访问 Mapper。</p>
     *
     * @param serverId 服务器 ID
     * @return 服务器存在且有效时返回 true
     */
    @Override
    public boolean existsActiveForUpdate(Long serverId) {
        try {
            return serverMapper.selectActiveServerForUpdateById(serverId) != null;
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /**
     * 判断服务器是否存在且 Agent 在线，供终端等跨模块能力校验使用。
     *
     * @param serverId 服务器 ID
     * @return 服务器存在且 agent_status=online 时返回 true
     */
    @Override
    public boolean isAgentOnline(Long serverId) {
        try {
            ServerEntity server = serverMapper.selectActiveServerStatusById(serverId);
            return server != null && AGENT_STATUS_ONLINE.equals(server.getAgentStatus());
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /**
     * 全量更新基础字段，并按认证方式变化决定保留、替换或清除凭据密文。
     *
     * @param serverId 服务器 ID
     * @param request 全量更新参数
     * @return 更新后的服务器安全公开信息
     */
    // 凭据读取、最终密文计算和条件更新必须处于同一事务。
    @Transactional
    public ServerVo update(Long serverId, UpdateServerRequest request) {
        validateServerId(serverId);
        validateUpdateBasicFields(request);
        validateCredentialLengths(request.getSshPassword(), request.getSshPrivateKey(),
                request.getSshPrivateKeyPassphrase());
        validateNoBlankCredential(request.getSshPassword(), request.getSshPrivateKey(),
                request.getSshPrivateKeyPassphrase());
        try {
            ServerEntity existing = serverMapper.selectActiveServerWithCredentialsById(serverId);
            if (existing == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            ServerEntity updated = buildUpdatedServer(serverId, existing, request);
            if (serverMapper.updateActiveServer(updated) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
            }
            ServerEntity publicServer = serverMapper.selectActiveServerById(serverId);
            if (publicServer == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return toServerVo(publicServer);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, exception);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER, exception);
        }
    }

    /**
     * 使用 UUID 释放唯一约束并软删除指定有效服务器。
     *
     * @param serverId 服务器 ID
     */
    // 软删除是单个事务写操作，条件更新失败表示目标已不存在。
    @Transactional
    public void delete(Long serverId) {
        validateServerId(serverId);
        try {
            if (serverMapper.softDeleteActiveServer(
                    serverId, LocalDateTime.now(ZoneOffset.UTC), UUID.randomUUID().toString()) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, exception);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /**
     * 查询数据库中的服务器状态快照，并记录本次调用检查时间。
     *
     * @param serverId 服务器 ID
     * @return 状态快照
     */
    // 状态读取不会触发实时探测，仅返回数据库快照。
    @Transactional(readOnly = true)
    public ServerStatusVo status(Long serverId) {
        validateServerId(serverId);
        try {
            ServerEntity server = serverMapper.selectActiveServerStatusById(serverId);
            if (server == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            ServerStatusVo result = new ServerStatusVo();
            result.setServerId(server.getId());
            result.setStatus(server.getStatus());
            result.setAgentStatus(server.getAgentStatus());
            result.setLastHeartbeatAt(toOffsetDateTime(server.getLastHeartbeatAt()));
            result.setDeliveryPendingCount(server.getDeliveryPendingCount());
            result.setDeliveryPendingBytes(server.getDeliveryPendingBytes());
            result.setDeliveryOldestCollectedAt(toOffsetDateTime(server.getDeliveryOldestCollectedAt()));
            result.setDeliveryDropCount(server.getDeliveryDropCount());
            result.setDeliveryDeadLetterCount(server.getDeliveryDeadLetterCount());
            result.setDeliveryDeadLetterBytes(server.getDeliveryDeadLetterBytes());
            result.setCheckedAt(OffsetDateTime.now(APPLICATION_ZONE));
            return result;
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
    }

    /** 校验创建参数及其认证方式对应的唯一主凭据。 */
    private void validateCreateRequest(CreateServerRequest request) {
        if (request == null || invalidBasicFields(request.getName(), request.getHost(), request.getDescription(),
                request.getSshHost(), request.getSshPort(), request.getSshUser(), request.getSshAuthType(), false)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        validateCredentialLengths(request.getSshPassword(), request.getSshPrivateKey(),
                request.getSshPrivateKeyPassphrase());
        validateNoBlankCredential(request.getSshPassword(), request.getSshPrivateKey(),
                request.getSshPrivateKeyPassphrase());
        boolean hasPassword = request.getSshPassword() != null;
        boolean hasPrivateKey = request.getSshPrivateKey() != null;
        boolean hasPassphrase = request.getSshPrivateKeyPassphrase() != null;
        if (hasPassword == hasPrivateKey
                || PASSWORD_AUTH_TYPE.equals(request.getSshAuthType()) && hasPassphrase
                || PASSWORD_AUTH_TYPE.equals(request.getSshAuthType()) && !hasPassword
                || PRIVATE_KEY_AUTH_TYPE.equals(request.getSshAuthType()) && (!hasPrivateKey || hasPassword)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    /** 校验 PUT 必填基础字段。 */
    private void validateUpdateBasicFields(UpdateServerRequest request) {
        if (request == null || invalidBasicFields(request.getName(), request.getHost(), request.getDescription(),
                request.getSshHost(), request.getSshPort(), request.getSshUser(), request.getSshAuthType(), true)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    /** 校验基础字段的必填、长度、端口和认证方式范围。 */
    private boolean invalidBasicFields(String name, String host, String description, String sshHost,
            Integer sshPort, String sshUser, String authType, boolean descriptionRequired) {
        return blank(name) || name.length() > 100 || blank(host) || host.length() > 255
                || descriptionRequired && description == null || description != null && description.length() > 500
                || blank(sshHost) || sshHost.length() > 255 || sshPort == null || sshPort < 1 || sshPort > 65535
                || blank(sshUser) || sshUser.length() > 100
                || !PASSWORD_AUTH_TYPE.equals(authType) && !PRIVATE_KEY_AUTH_TYPE.equals(authType);
    }

    /** 校验所有传入凭据均非空白字符串。 */
    private void validateNoBlankCredential(String password, String privateKey, String passphrase) {
        if (password != null && password.isBlank() || privateKey != null && privateKey.isBlank()
                || passphrase != null && passphrase.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    /** 校验凭据明文最大长度，防止绕过 Controller 校验直接调用 Service。 */
    private void validateCredentialLengths(String password, String privateKey, String passphrase) {
        if (password != null && password.length() > MAX_PASSWORD_LENGTH
                || privateKey != null && privateKey.length() > MAX_PRIVATE_KEY_LENGTH
                || passphrase != null && passphrase.length() > MAX_PASSPHRASE_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    /** 校验并规范化分页查询参数。 */
    private QueryValues validateQuery(ServerQueryRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        int page = request.getPage() == null ? DEFAULT_PAGE : request.getPage();
        int pageSize = request.getPageSize() == null ? DEFAULT_PAGE_SIZE : request.getPageSize();
        String sortBy = request.getSortBy() == null ? DEFAULT_SORT_BY : request.getSortBy();
        String sortOrder = request.getSortOrder() == null ? DEFAULT_SORT_ORDER : request.getSortOrder();
        String keyword = request.getKeyword();
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE
                || keyword != null && keyword.length() > MAX_KEYWORD_LENGTH
                || !SORT_BY_WHITELIST.contains(sortBy) || !SORT_ORDER_WHITELIST.contains(sortOrder)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        return new QueryValues(page, pageSize, keyword, sortBy, sortOrder);
    }

    /** 将创建请求转换为不含凭据明文的基础实体。 */
    private ServerEntity toServerEntity(CreateServerRequest request) {
        ServerEntity server = new ServerEntity();
        server.setName(request.getName());
        server.setHost(request.getHost());
        server.setDescription(request.getDescription());
        server.setSshHost(request.getSshHost());
        server.setSshPort(request.getSshPort());
        server.setSshUser(request.getSshUser());
        server.setSshAuthType(request.getSshAuthType());
        return server;
    }

    /** 使用新服务器 ID 对创建请求中的凭据进行上下文绑定加密。 */
    private void encryptCreateCredentials(CreateServerRequest request, ServerEntity server) {
        if (PASSWORD_AUTH_TYPE.equals(request.getSshAuthType())) {
            server.setSshPasswordEncrypted(credentialCipher.encrypt(
                    server.getId(), PASSWORD_CREDENTIAL_TYPE, request.getSshPassword()));
            return;
        }
        server.setSshPrivateKeyEncrypted(credentialCipher.encrypt(
                server.getId(), PRIVATE_KEY_CREDENTIAL_TYPE, request.getSshPrivateKey()));
        if (request.getSshPrivateKeyPassphrase() != null) {
            server.setSshPrivateKeyPassphraseEncrypted(credentialCipher.encrypt(
                    server.getId(), PASSPHRASE_CREDENTIAL_TYPE, request.getSshPrivateKeyPassphrase()));
        }
    }

    /** 计算更新后的完整基础字段和最终凭据密文。 */
    private ServerEntity buildUpdatedServer(Long serverId, ServerEntity existing, UpdateServerRequest request) {
        boolean sameAuthType = request.getSshAuthType().equals(existing.getSshAuthType());
        ServerEntity updated = new ServerEntity();
        updated.setId(serverId);
        updated.setName(request.getName());
        updated.setHost(request.getHost());
        updated.setDescription(request.getDescription());
        updated.setSshHost(request.getSshHost());
        updated.setSshPort(request.getSshPort());
        updated.setSshUser(request.getSshUser());
        updated.setSshAuthType(request.getSshAuthType());
        if (PASSWORD_AUTH_TYPE.equals(request.getSshAuthType())) {
            applyPasswordCredentials(existing, request, updated, sameAuthType);
        } else {
            applyPrivateKeyCredentials(existing, request, updated, sameAuthType);
        }
        return updated;
    }

    /** 应用 password 认证的保留、更新或切换规则。 */
    private void applyPasswordCredentials(ServerEntity existing, UpdateServerRequest request,
            ServerEntity updated, boolean sameAuthType) {
        if (request.getSshPrivateKey() != null || request.getSshPrivateKeyPassphrase() != null
                || !sameAuthType && request.getSshPassword() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        updated.setSshPasswordEncrypted(request.getSshPassword() == null
                ? existing.getSshPasswordEncrypted()
                : credentialCipher.encrypt(updated.getId(), PASSWORD_CREDENTIAL_TYPE, request.getSshPassword()));
        if (!sameAuthType) {
            updated.setSshPrivateKeyEncrypted(null);
            updated.setSshPrivateKeyPassphraseEncrypted(null);
        }
    }

    /** 应用 private_key 认证的保留、更新、可选口令更新或切换规则。 */
    private void applyPrivateKeyCredentials(ServerEntity existing, UpdateServerRequest request,
            ServerEntity updated, boolean sameAuthType) {
        if (request.getSshPassword() != null || !sameAuthType && request.getSshPrivateKey() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        updated.setSshPrivateKeyEncrypted(request.getSshPrivateKey() == null
                ? existing.getSshPrivateKeyEncrypted()
                : credentialCipher.encrypt(updated.getId(), PRIVATE_KEY_CREDENTIAL_TYPE, request.getSshPrivateKey()));
        updated.setSshPrivateKeyPassphraseEncrypted(request.getSshPrivateKeyPassphrase() == null
                ? sameAuthType ? existing.getSshPrivateKeyPassphraseEncrypted() : null
                : credentialCipher.encrypt(updated.getId(), PASSPHRASE_CREDENTIAL_TYPE,
                        request.getSshPrivateKeyPassphrase()));
        if (!sameAuthType) {
            updated.setSshPasswordEncrypted(null);
        }
    }

    /** 将持久化实体转换为不含凭据的公开 VO。 */
    private ServerVo toServerVo(ServerEntity server) {
        ServerVo result = new ServerVo();
        result.setId(server.getId());
        result.setName(server.getName());
        result.setHost(server.getHost());
        result.setDescription(server.getDescription());
        result.setStatus(server.getStatus());
        result.setSshHost(server.getSshHost());
        result.setSshPort(server.getSshPort());
        result.setSshUser(server.getSshUser());
        result.setSshAuthType(server.getSshAuthType());
        result.setAgentId(server.getAgentId());
        result.setAgentStatus(server.getAgentStatus());
        result.setLastHeartbeatAt(toOffsetDateTime(server.getLastHeartbeatAt()));
        result.setCreatedAt(toOffsetDateTime(server.getCreatedAt()));
        result.setUpdatedAt(toOffsetDateTime(server.getUpdatedAt()));
        return result;
    }

    /** 校验服务器 ID 是正数。 */
    private void validateServerId(Long serverId) {
        if (serverId == null || serverId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    /** 判断必填字符串是否为空白。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 按应用默认时区将数据库时间转换为接口时间。 */
    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(APPLICATION_ZONE).toOffsetDateTime();
    }

    /** 保存校验后的分页查询值，避免后续重新读取可变 DTO。 */
    private record QueryValues(int page, int pageSize, String keyword, String sortBy, String sortOrder) {
    }
}
