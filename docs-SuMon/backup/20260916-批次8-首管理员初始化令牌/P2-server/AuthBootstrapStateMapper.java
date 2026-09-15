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
