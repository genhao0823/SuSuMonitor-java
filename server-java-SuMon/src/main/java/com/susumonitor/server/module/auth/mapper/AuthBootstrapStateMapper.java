package com.susumonitor.server.module.auth.mapper;

import com.susumonitor.server.module.auth.entity.AuthBootstrapStateEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 访问首管理员初始化状态，并通过行锁保证初始化判断的原子性。
 */
// 将当前接口注册为 MyBatis Mapper，使注册事务能够锁定和更新初始化状态。
@Mapper
public interface AuthBootstrapStateMapper {

    /**
     * 锁定并查询唯一初始化状态行，锁持续到当前事务提交或回滚。
     *
     * @return 初始化状态
     */
    AuthBootstrapStateEntity selectForUpdate();

    /**
     * 无锁读取唯一初始化状态行，供状态查询端点与启动令牌装配使用。
     *
     * <p>调用方不得基于本方法的结果做首管理员判定后的写操作——
     * 那类路径必须走 {@link #selectForUpdate()} 保证并发串行化。</p>
     *
     * @return 初始化状态，行不存在时返回 null
     */
    AuthBootstrapStateEntity selectState();

    /**
     * 写入（覆盖）当前一次性初始化令牌密文并清空消费时间。
     *
     * <p>仅在 admin_initialized=0 时生效，防止已初始化实例被覆写。</p>
     *
     * @param cipher 令牌 AES-256-GCM 密文信封
     * @param generatedAt 令牌生成或载入时间
     * @return 更新行数（0 表示首管理员已初始化或状态行不存在）
     */
    int saveBootstrapToken(
            // 将令牌密文信封绑定到 XML 的 cipher 参数。
            @Param("cipher") String cipher,
            // 将生成/载入时间绑定到 XML 的 generatedAt 参数。
            @Param("generatedAt") LocalDateTime generatedAt);

    /**
     * 消费一次性初始化令牌：置空密文并记录消费时间。
     *
     * <p>仅在 admin_initialized=0 时生效，与 markAdminInitialized 同事务执行，
     * 保证令牌至多被消费一次。</p>
     *
     * @param consumedAt 令牌消费时间
     * @return 更新行数（0 表示状态已变化或行不存在）
     */
    int consumeBootstrapToken(
            // 将消费时间绑定到 XML 的 consumedAt 参数。
            @Param("consumedAt") LocalDateTime consumedAt);

    /**
     * 将初始化状态标记为完成，并记录唯一首管理员。
     *
     * @param initializedUserId 首管理员用户 ID
     * @param initializedAt 初始化完成时间
     * @return 更新行数
     */
    int markAdminInitialized(
            // 将首管理员 ID 绑定到 XML 的 initializedUserId 参数。
            @Param("initializedUserId") Long initializedUserId,
            // 将初始化时间绑定到 XML 的 initializedAt 参数。
            @Param("initializedAt") LocalDateTime initializedAt);
}
