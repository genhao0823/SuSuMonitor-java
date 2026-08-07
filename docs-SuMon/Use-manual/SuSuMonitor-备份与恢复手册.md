# SuSuMonitor 备份与恢复手册（MVP-8）

**适用范围**：数据库、密钥、配置的备份策略与恢复操作。
**配套脚本**：`server-java-SuMon/deploy/backup.sh`（备份）、`server-java-SuMon/deploy/restore.sh`（恢复）。

---

## 一、备份内容清单与优先级

| # | 内容 | 优先级 | 说明 |
|---|---|---|---|
| 1 | **数据库**（`susumonitor` 全库含 Flyway 历史） | 必须 | 业务数据、告警记录、outbox 事件、迁移历史 |
| 2 | **密钥文件 `/etc/susumonitor/server.env`** | **必须（最高风险）** | `JWT_SECRET`/`AES_GCM_KEY` 丢失 = 所有 SSH 凭据密文**永久不可解密**，且 JWT 无法续签校验 |
| 3 | 配置（nginx vhost / systemd unit） | 建议 | 宝塔面板自带快照可替代 |
| 4 | 发布包归档（`releases/` 历史） | 建议 | 支持快速回滚 |
| 5 | RabbitMQ 配置（vhost/用户清单） | 建议 | 拓扑自动声明无需备份；用户清单需记录（见《RabbitMQ 运维手册》§二） |

## 二、备份操作

```bash
# 标准备份（root 执行）
sudo bash /opt/susumonitor/deploy/backup.sh --dir /var/backups/susumonitor --keep 7

# 输出示例
# OK: /var/backups/susumonitor/susumonitor-20260731-120000.tar.gz (12M)
```

脚本行为（详见脚本头注释）：
1. `mysqldump --single-transaction`（InnoDB 一致性快照，**不停服、不加锁**）
2. 复制 `server.env`（密钥）
3. tar.gz 归档 + `gzip -t` 校验 + 非空校验，失败即非零退出
4. 按 `--keep` 轮换旧备份（默认保留 7 份）

**异地/离线留存**：备份包必须额外留存一份到异地（云对象存储/离线盘）——本机磁盘损坏时密钥与数据同时丢失。

**一键异地备份**（本机 Git Bash 运行，云端执行 backup.sh → 拉回本机 → AES-256 加密落盘）：
```bash
ENCRYPT_PASS='你的加密口令' bash scripts/remote-backup.sh --keep 7
# 输出: OK: D:/SuSuMonitor-Backups/susumonitor-20260807-213825.tar.gz.enc (16M)
```
- 脚本会自动同步仓库 `server-java-SuMon/deploy/backup.sh` 到云端执行（云端保留 1 份中间产物），再拉回本机 `D:/SuSuMonitor-Backups/` 加密（AES-256-CBC + pbkdf2），并生成 `.sha256` 校验
- 恢复：`export ENCRYPT_PASS=口令 && openssl enc -d -aes-256-cbc -pbkdf2 -pass pass:"$ENCRYPT_PASS" -in xxx.tar.gz.enc -out backup.tar.gz && tar -xzf backup.tar.gz`
- ⚠️ 加密口令与备份文件**分开存放**，口令丢失则备份不可恢复

**调度建议**（crontab，root）：
```cron
# 每日 02:00 备份，保留 14 份
0 2 * * * /usr/bin/bash /opt/susumonitor/deploy/backup.sh --dir /var/backups/susumonitor --keep 14 >> /var/log/susumonitor-backup.log 2>&1
```

**备份验证**：每月至少一次在验证库执行恢复演练（见 §五），确认备份可用——"备份不可恢复等于没有备份"。

## 三、恢复操作

> ⚠️ 恢复前置：**必须停止应用**（`systemctl stop susumonitor-server`），否则应用写入与恢复互相干扰。

```bash
# 1) 停止应用
sudo systemctl stop susumonitor-server

# 2) 恢复（交互确认；自动化可用 --yes）
sudo bash /opt/susumonitor/deploy/restore.sh --backup /var/backups/susumonitor/susumonitor-20260731-120000.tar.gz

# 3) 启动并验证（脚本输出验证清单）
sudo systemctl start susumonitor-server
sudo journalctl -u susumonitor-server -n 50 --no-pager    # Flyway 回放到备份时点
curl -s http://127.0.0.1:18080/api/health
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/api/ready   # 200
# 抽查：服务器列表 / 告警记录 / 用户账号 与备份时点一致
```

脚本行为：校验备份包（gzip + sql 非空）→ 覆盖式恢复数据库（mysqldump 自带 `DROP TABLE IF EXISTS`）→ 恢复 `server.env`（原文件先备份为 `server.env.bak-时间戳`，权限 0600）→ 输出验证清单。

## 四、场景化恢复指引

| 场景 | 操作 |
|---|---|
| **全量恢复**（误删/损坏/回滚） | §三 标准流程 |
| **仅密钥恢复**（server.env 丢失/损坏） | 从备份包解出 `server.env` → `install -m 0600 -o root -g root` 覆盖 → 重启应用。**若备份也没有密钥**：SSH 凭据密文不可解密，需逐台服务器重新录入凭据（`POST /api/servers/{id}` 更新） |
| **新机迁移**（换服务器） | 新机按《部署安装手册》从零安装 → 用备份包执行 §三 恢复 → 迁移 nginx 配置 → 重新签发 Agent Token 或保留数据库中的 token 哈希（数据库已恢复则无需重新签发） |
| **单表恢复**（如误删 alert_records） | 解包备份 → `mysql < (sed 提取单表语句)` 或手工导入；**风险自担**，建议全量恢复 |

## 五、恢复演练（建议每月）

1. 在**隔离验证库**（`susumonitor_metrics_validation`）执行：`restore.sh` 指向验证库（`DB_NAME` 环境变量覆盖）
2. 验证：Flyway 版本回放到备份时点、服务器/告警记录抽查一致
3. 演练记录写入 Develop-log（时间/备份包/结果/异常）

## 六、与发布流程的配合

- **升级前必备份**（《升级与回滚手册》§一）：Flyway 迁移只进不退，数据库回滚的唯一途径是备份恢复
- 备份脚本与发布脚本同仓维护（`deploy/`），版本随仓库演进
