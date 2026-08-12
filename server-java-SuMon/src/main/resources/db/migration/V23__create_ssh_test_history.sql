-- SSH 连接测试历史表：记录每次 POST /api/servers/{id}/ssh/test 的结果（成功与失败均记录）。
CREATE TABLE ssh_test_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    -- 关联的服务器 ID（无外键约束，遵循项目表规范）。
    server_id BIGINT UNSIGNED NOT NULL COMMENT '测试的服务器 ID',
    -- 连接测试是否成功。
    connected TINYINT(1) NOT NULL COMMENT '连接测试是否成功',
    -- 失败时的业务错误码(50002/50003/50400 等)，成功为 NULL。
    error_code INT NULL COMMENT '失败时的业务错误码，成功为 NULL',
    -- 成功时观察到的远端主机公钥算法。
    host_key_algorithm VARCHAR(50) NULL COMMENT '成功时的远端主机公钥算法',
    -- 成功时观察到的远端主机公钥指纹。
    host_key_fingerprint VARCHAR(200) NULL COMMENT '成功时的远端主机公钥指纹',
    -- 使用的认证方式: password / private_key。
    auth_type VARCHAR(20) NOT NULL COMMENT '认证方式: password / private_key',
    -- 测试总耗时毫秒。
    duration_ms BIGINT NOT NULL COMMENT '测试耗时毫秒',
    -- 测试发生时间。
    tested_at DATETIME NOT NULL COMMENT '测试发生时间',
    -- 记录创建时间。
    created_at DATETIME NOT NULL COMMENT '记录创建时间',
    PRIMARY KEY (id),
    KEY idx_ssh_test_history_server_tested (server_id, tested_at)
) COMMENT 'SSH 连接测试历史';
