package com.susumonitor.server.module.server.service;

import com.susumonitor.server.module.server.dto.UpdateSshHostKeyRequest;
import com.susumonitor.server.module.server.vo.SshHostKeyObservationVo;
import com.susumonitor.server.module.server.vo.SshHostKeyVo;
import com.susumonitor.server.module.server.vo.SshTestHistoryVo;
import com.susumonitor.server.module.server.vo.SshTestVo;
import java.util.List;

/**
 * 定义服务器 SSH 主机身份维护与连接测试的业务契约。
 */
public interface ServerSshService {

    /** 确认或轮换服务器 SSH 主机公钥。 */
    SshHostKeyVo updateHostKey(Long serverId, UpdateSshHostKeyRequest request, Long operatorId);

    /** 只读观察目标主机当前公钥，供管理员确认信任前核对。 */
    SshHostKeyObservationVo observeHostKey(Long serverId);

    /** 使用已确认主机公钥执行 SSH 认证测试。 */
    SshTestVo testConnection(Long serverId);

    /** 查询某服务器最近的 SSH 连接测试历史。 */
    List<SshTestHistoryVo> listTestHistory(Long serverId);
}
