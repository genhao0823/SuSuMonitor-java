# SuSuMonitor 安全检查手册（MVP-8）

**适用范围**：部署环境安全基线、上线前检查清单。
**原则**：最小暴露、密钥隔离、日志脱敏、依赖补丁。生产必须使用 HTTPS/WSS；本文不固化具体域名，部署时以 `SERVER_IP_OR_DOMAIN` 或实际受控域名替换。

---

## 一、网络与端口

| 检查项 | 要求 | 检查命令 |
|---|---|---|
| 后端监听 | 仅 `127.0.0.1:18080`（prod 默认），**不暴露公网** | `ss -tlnp \| grep 18080` |
| 公网端口 | 仅 80/443（Nginx）+ 22（SSH）+ 云安全组最小化 | 云控制台安全组核对 |
| MySQL | 仅 `127.0.0.1:3306`，禁止公网 | `ss -tlnp \| grep 3306` |
| RabbitMQ | 5672/15672 仅内网（云安全组不开放公网） | `ss -tlnp \| grep -E '5672\|15672'` |
| SSH | 建议禁用 root 密码登录、启用密钥登录 | `grep PermitRootLogin /etc/ssh/sshd_config` |
| TLS | 生产 HTTPS/WSS 已启用；证书、域名和 HSTS 等按现场部署核对 | `curl -I https://SERVER_IP_OR_DOMAIN/api/health` |

## 二、数据库

| 检查项 | 要求 |
|---|---|
| 账号 | `susumonitor`@localhost 专用账号，仅授权 `susumonitor.*`；**禁用 root 远程** |
| 密码策略 | **恢复 MEDIUM**（`validate_password` 组件）：长度 ≥8、含大小写/数字/特殊字符（20260729 云端巡检遗留待办） |
| 备份文件 | `/var/backups/susumonitor/` 权限 0700，异地留存 |

```sql
-- 检查密码策略（期望 MEDIUM 或 STRONG）
SHOW VARIABLES LIKE 'validate_password%';
```

## 三、RabbitMQ

| 检查项 | 要求 |
|---|---|
| 专用用户 | `susumonitor` 用户 + 独立 vhost，**不得使用 guest 连业务**（guest 仅限 localhost 且仅默认 vhost） |
| 权限最小化 | `set_permissions -p susumonitor susumonitor '.*' '.*' '.*'`（当前 vhost 内全权限）；管理角色按需（`administrator` 仅在需要管理台/验收时授予，建议生产收回为 monitoring） |
| 管理台 | 15672 仅内网可达 |

```bash
rabbitmqctl list_users          # 核对用户与 tag
rabbitmqctl list_permissions -p susumonitor
```

## 四、密钥与凭据

| 检查项 | 要求 | 检查命令 |
|---|---|---|
| `server.env` | root:root + 0600，密钥**不入库、不提交、不落日志** | `ls -l /etc/susumonitor/server.env` |
| JWT/AES 密钥 | 长度合规（AES 32 字节 Base64；JWT ≥32 字节）；已随备份异地留存 | 见《备份与恢复手册》 |
| 日志脱敏 | 应用日志不含密码/Token/SSH 私钥/密钥；错误响应不含敏感字段 | `journalctl -u susumonitor-server \| grep -iE 'password\|secret\|token' `（应仅命中脱敏提示） |
| SSH 凭据 | 数据库仅存 AES-GCM 密文，接口不返回明文 | 抽查 `servers.ssh_password_encrypted` 无明文 |

## 五、TLS（生产必需）

生产入口必须使用 HTTPS，Agent 和 Monitor WebSocket 必须使用 WSS。将 `SERVER_IP_OR_DOMAIN` 替换为实际受控域名后，核对证书链、有效期、反代 Upgrade 头和 HTTP 到 HTTPS 的跳转策略。

1. ~~域名备案完成后：宝塔签发 CA 证书（Let's Encrypt）→ 站点启用 HTTPS → `nginx-susumonitor.conf.example` 合并到 443 server 块~~（历史记录：2026-08-07 已完成；当前部署仍须按实际域名复核）
2. ~~切换后更新：`CORS_ALLOWED_ORIGINS=https://SERVER_IP_OR_DOMAIN`、`AGENT_TRUSTED_PROXY_CIDRS` 保持本机、Agent 端 `SUSUMONITOR_BACKEND_URL=wss://SERVER_IP_OR_DOMAIN`~~（配置模板；上线前按实际域名复核）
3. ~~启用后验收：浏览器锁标、`/ws/monitor` 与 `/ws/agent` WSS 握手、Agent 长连接保活~~（历史记录：2026-08-07 验收通过；当前环境必须重新核验）
4. 生产 CA 证书轮换、TLS 1.3、HSTS/OCSP 按需加固（**待办**，当前宝塔托管默认即可）

**历史记录（2026-07-30，风险已消除）**：原明文 HTTP + WS 阶段存在运营商劫持风险。该记录仅用于追溯，明文公网 HTTP/WS **禁止执行**；生产必须使用 HTTPS/WSS。

## 六、依赖与补丁

| 项 | 要求 |
|---|---|
| JDK | 21 LTS 最新补丁（当前 Temurin 21.0.11） |
| Spring Boot | 3.4.x 补丁版本跟进 |
| MySQL | 8.4 小版本补丁 |
| RabbitMQ/Erlang | 官方支持版本组合（4.3 + Erlang 27），跟进安全公告 |

## 七、上线前检查表（可勾选）

```text
□ 后端仅回环监听 18080，公网仅 80/443/22
□ MySQL 仅本机、密码策略 MEDIUM、专用账号最小权限
□ RabbitMQ 专用 vhost/用户、guest 未用于业务、管理台不暴露公网
□ server.env 0600 root-only，密钥已异地备份
□ 备份脚本已配置 crontab 且演练过恢复
□ 日志抽查无明文凭据
□ Nginx 反代 /api 与 /ws 路径正确（SPA fallback + Upgrade 头）
□ 验收清单通过（《部署安装手册》§六 7 项）
□ TLS 已启用（HTTPS/WSS，域名以 `SERVER_IP_OR_DOMAIN` 替换并现场核验；HSTS/OCSP 等加固按需执行）
```
