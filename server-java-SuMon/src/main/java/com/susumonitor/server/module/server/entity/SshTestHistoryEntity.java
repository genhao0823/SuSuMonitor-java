package com.susumonitor.server.module.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 映射 V23 创建的 ssh_test_history 表：每次 SSH 连接测试一行，成功与失败均记录。
 *
 * <p>connected 标记测试结果；error_code 仅在失败时非空（50002/50003/50400 等业务错误码）。
 * 成功时保留观察到的主机公钥算法与指纹，失败时为空。</p>
 */
@Data
@TableName("ssh_test_history")
public class SshTestHistoryEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("server_id")
    private Long serverId;
    // 连接测试是否成功。
    private Boolean connected;
    // 失败时的业务错误码，成功为 NULL。
    @TableField("error_code")
    private Integer errorCode;
    // 成功时观察到的远端主机公钥算法。
    @TableField("host_key_algorithm")
    private String hostKeyAlgorithm;
    // 成功时观察到的远端主机公钥指纹。
    @TableField("host_key_fingerprint")
    private String hostKeyFingerprint;
    @TableField("auth_type")
    private String authType;
    // 测试总耗时毫秒。
    @TableField("duration_ms")
    private Long durationMs;
    @TableField("tested_at")
    private LocalDateTime testedAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
