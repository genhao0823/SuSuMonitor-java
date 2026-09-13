package com.susumonitor.server.module.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.susumonitor.server.module.server.entity.ServerEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 访问服务器持久化数据，并统一限制业务操作只能作用于未软删除记录。
 */
// 将当前接口注册为 MyBatis Mapper，使 Spring 能够注入服务器数据访问实现。
@Mapper
public interface ServerMapper extends BaseMapper<ServerEntity> {

    /**
     * 插入不含 SSH 凭据的服务器基础记录，并回写数据库生成的主键。
     *
     * @param server 待插入的服务器基础记录
     * @return 插入行数
     */
    int insertServerBase(
            // 将服务器记录绑定到 XML 的 server 参数，并承接自增主键回写。
            @Param("server") ServerEntity server);

    /**
     * 更新有效服务器的三种 SSH 凭据密文。
     *
     * @param serverId 服务器 ID
     * @param sshPasswordEncrypted SSH 密码密文
     * @param sshPrivateKeyEncrypted SSH 私钥密文
     * @param sshPrivateKeyPassphraseEncrypted SSH 私钥口令密文
     * @return 更新行数
     */
    int updateCredentialCiphertexts(
            // 将服务器 ID 绑定到 XML 的 serverId 参数。
            @Param("serverId") Long serverId,
            // 将 SSH 密码密文绑定到 XML 的 sshPasswordEncrypted 参数。
            @Param("sshPasswordEncrypted") String sshPasswordEncrypted,
            // 将 SSH 私钥密文绑定到 XML 的 sshPrivateKeyEncrypted 参数。
            @Param("sshPrivateKeyEncrypted") String sshPrivateKeyEncrypted,
            // 将 SSH 私钥口令密文绑定到 XML 的 sshPrivateKeyPassphraseEncrypted 参数。
            @Param("sshPrivateKeyPassphraseEncrypted") String sshPrivateKeyPassphraseEncrypted);

    /**
     * 分页查询有效服务器公开字段，不读取凭据和 Agent Token 哈希。
     *
     * <p>分页由 MyBatis-Plus 分页拦截器承担：携带 IPage 参数自动执行 COUNT
     * 并追加 LIMIT，total 回写到传入的 Page 对象。</p>
     *
     * @param page 分页参数（页码与每页数量，承载回写的 total）
     * @param keyword 匹配名称、地址或描述的关键词
     * @param sortBy 排序字段白名单值
     * @param sortOrder 排序方向白名单值
     * @return 当前页有效服务器列表
     */
    List<ServerEntity> selectActiveServers(
            // 承载分页参数与回写的 total，由分页拦截器消费。
            IPage<ServerEntity> page,
            // 将搜索关键词绑定到 XML 的 keyword 参数。
            @Param("keyword") String keyword,
            // 将排序字段标识绑定到 XML 的 sortBy 参数，XML 仅通过固定分支使用该值。
            @Param("sortBy") String sortBy,
            // 将排序方向标识绑定到 XML 的 sortOrder 参数，XML 仅通过固定分支使用该值。
            @Param("sortOrder") String sortOrder);

    /**
     * 按 ID 查询有效服务器公开字段，不读取任何凭据。
     *
     * @param serverId 服务器 ID
     * @return 有效服务器，不存在时返回 null
     */
    ServerEntity selectActiveServerById(
            // 将服务器 ID 绑定到 XML 的 serverId 参数。
            @Param("serverId") Long serverId);

    /**
     * 锁定有效服务器行，使同一服务器的指标乱序判定与写入在一个事务内串行执行。
     *
     * @param serverId 服务器 ID
     * @return 已锁定的有效服务器，不存在时返回 null
     */
    ServerEntity selectActiveServerForUpdateById(@Param("serverId") Long serverId);

    /**
     * 按 ID 查询内部更新所需业务字段和 SSH 凭据密文，不读取 Agent Token 哈希。
     *
     * @param serverId 服务器 ID
     * @return 可供内部更新的有效服务器，不存在时返回 null
     */
    ServerEntity selectActiveServerWithCredentialsById(
            // 将服务器 ID 绑定到 XML 的 serverId 参数。
            @Param("serverId") Long serverId);

    /**
     * 更新有效服务器基础字段及由 Service 计算完成的最终凭据密文。
     *
     * @param server 待更新的服务器记录
     * @return 更新行数
     */
    int updateActiveServer(
            // 将完整更新结果绑定到 XML 的 server 参数。
            @Param("server") ServerEntity server);

    /**
     * 软删除有效服务器，并设置删除时间和释放 host 唯一约束所需的删除标识。
     *
     * @param serverId 服务器 ID
     * @param deletedAt 删除时间
     * @param deleteToken 删除唯一标识
     * @return 更新行数
     */
    int softDeleteActiveServer(
            // 将服务器 ID 绑定到 XML 的 serverId 参数。
            @Param("serverId") Long serverId,
            // 将删除时间绑定到 XML 的 deletedAt 参数。
            @Param("deletedAt") LocalDateTime deletedAt,
            // 将删除唯一标识绑定到 XML 的 deleteToken 参数。
            @Param("deleteToken") String deleteToken);

    /**
     * 查询有效服务器的最小状态快照，不读取业务详情、凭据或 Agent Token 哈希。
     *
     * @param serverId 服务器 ID
     * @return 服务器状态快照，不存在时返回 null
     */
    ServerEntity selectActiveServerStatusById(
            // 将服务器 ID 绑定到 XML 的 serverId 参数。
            @Param("serverId") Long serverId);

    /**
     * 查询主机公钥确认所需的 SSH 目标和当前登记状态，不读取任何登录凭据。
     *
     * @param serverId 服务器 ID
     * @return SSH 主机公钥状态，不存在时返回 null
     */
    ServerEntity selectActiveServerHostKeyById(
            // 将服务器 ID 绑定到主机公钥状态查询。
            @Param("serverId") Long serverId);

    /**
     * 查询 SSH 连接测试所需的目标、已确认主机密钥和凭据密文。
     *
     * @param serverId 服务器 ID
     * @return SSH 连接测试快照，不存在时返回 null
     */
    ServerEntity selectActiveServerSshById(
            // 将服务器 ID 绑定到 SSH 连接测试查询。
            @Param("serverId") Long serverId);

    /**
     * 使用 SSH 目标和旧指纹作为 CAS 条件确认或轮换主机公钥。
     *
     * @param serverId 服务器 ID
     * @param expectedSshHost 握手前读取的 SSH 地址
     * @param expectedSshPort 握手前读取的 SSH 端口
     * @param previousFingerprint 握手前读取的指纹，首次确认时为 null
     * @param hostKeyAlgorithm 远端主机公钥算法
     * @param newFingerprint 已核对的新指纹
     * @param operatorId 操作管理员 ID
     * @return 更新行数
     */
    int compareAndSetSshHostKey(
            @Param("serverId") Long serverId,
            @Param("expectedSshHost") String expectedSshHost,
            @Param("expectedSshPort") Integer expectedSshPort,
            @Param("previousFingerprint") String previousFingerprint,
            @Param("hostKeyAlgorithm") String hostKeyAlgorithm,
            @Param("newFingerprint") String newFingerprint,
            @Param("operatorId") Long operatorId);

    /** 查询 Agent Token 生命周期和哈希校验所需的有效服务器字段。 */
    ServerEntity selectActiveServerAgentTokenById(@Param("serverId") Long serverId);

    /** 首次生成 Agent Token，只有未登记 Token 的服务器才能更新成功。 */
    int registerAgentToken(
            @Param("serverId") Long serverId,
            @Param("agentId") String agentId,
            @Param("tokenHash") String tokenHash,
            @Param("createdAt") LocalDateTime createdAt);

    /** 显式轮换 Agent Token：旧摘要移入宽限列并登记宽限截止，窗口过后旧 Token 不再被接受。 */
    int rotateAgentToken(
            @Param("serverId") Long serverId,
            @Param("tokenHash") String tokenHash,
            @Param("rotatedAt") LocalDateTime rotatedAt,
            @Param("graceUntil") LocalDateTime graceUntil);

    /** 撤销当前 Agent Token 并将 Agent 标记为离线。 */
    int revokeAgentToken(
            @Param("serverId") Long serverId,
            @Param("revokedAt") LocalDateTime revokedAt);

    /** 仅在 Agent 当前不在线时更新心跳并标记在线，用于识别在线状态转换。 */
    int markAgentOnlineAndHeartbeat(
            @Param("serverId") Long serverId,
            @Param("heartbeatAt") LocalDateTime heartbeatAt);

    /** 更新已认证 Agent 的心跳时间和在线状态。 */
    int updateAgentHeartbeat(
            @Param("serverId") Long serverId,
            @Param("heartbeatAt") LocalDateTime heartbeatAt);

    /** 更新 Agent 心跳上报的投递遥测统计；统计缺失字段时保持原值。 */
    int updateDeliveryStats(
            @Param("serverId") Long serverId,
            @Param("oldestCollectedAt") LocalDateTime oldestCollectedAt,
            @Param("pendingCount") Long pendingCount,
            @Param("pendingBytes") Long pendingBytes,
            @Param("dropCount") Long dropCount,
            @Param("deadLetterCount") Long deadLetterCount,
            @Param("deadLetterBytes") Long deadLetterBytes);

    /** 仅在心跳仍为预期值时将 Agent 标记离线，避免旧连接覆盖新连接。 */
    int markAgentOffline(
            @Param("serverId") Long serverId,
            @Param("expectedHeartbeatAt") LocalDateTime expectedHeartbeatAt);
}
