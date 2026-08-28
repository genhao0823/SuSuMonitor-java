# Go Agent WSS 公网验收手册

> 适用对象：Go Agent（`agent-go-SuMon`）的正式公网部署与验收。
> 本文档只描述验收步骤与判据；执行前需先具备域名、TLS 证书和可用的云端主机。
> 关联文档：`agent-go-SuMon/deploy/RELEASE.md`、`docs-SuMon/Use-manual/Go-Agent 部署使用手册.md`、`docs-SuMon/Handoff-SuMon/20260731-云端部署调试交接.md`。

## 1. 验收前置条件

| 项 | 要求 | 检查 |
|---|---|---|
| 域名与证书 | 正式域名 + 有效 TLS 证书，证书链完整 | `openssl s_client -connect <域名>:443 -servername <域名> </dev/null 2>/dev/null | openssl x509 -noout -subject -dates` |
| Nginx | 同域代理 `/api/`、`/ws/agent`、`/ws/monitor`，正确配置 Upgrade 头 | 见第 2 节示例 |
| 后端服务 | Java `18080`（仅回环/内网）、MySQL、RabbitMQ 正常，Flyway 迁移完成 | `/api/ready` 返回 200，且包含 rabbitmq 就绪字段 |
| 账号与 Server | admin 账号；已建 server 记录 | REST `GET /api/servers` 可见目标 server |
| Agent Token | 通过 register/rotate API 一次性获取 | `POST /api/servers/{id}/agent/register`，明文只返回一次 |
| 家庭主机 | Linux amd64；具有运行服务所需的 systemd 权限；可出站访问 443 | 无入站端口要求；PTY 进程权限继承 Agent 服务用户，是否使用 root 由现场 systemd 单元明确配置 |

## 2. Nginx 关键配置（参考）

```nginx
server {
    listen 443 ssl;
    server_name <域名>;
    ssl_certificate     /etc/nginx/ssl/<域名>.crt;
    ssl_certificate_key /etc/nginx/ssl/<域名>.key;

    location /api/ {
        proxy_pass http://127.0.0.1:18080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /ws/ {
        proxy_pass http://127.0.0.1:18080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

验收要点：

- `/ws/agent` 与 `/ws/monitor` 必须走 Upgrade 代理，禁止被运营商/CDN 剥离 Upgrade 头。
- 明文 HTTP 下已验证存在宽带运营商劫持（剥离 Upgrade 并注入脚本）风险；**正式验收一律使用 WSS**，手机热点仅作为临时绕过手段。

## 3. 发布与安装

```bash
# 在 agent-go-SuMon 目录构建（本机）
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o bin/susumonitor-agent-linux-amd64 ./cmd/susumonitor-agent
sha256sum bin/susumonitor-agent-linux-amd64   # 记录并核对

# 上传到家庭主机后按 install-agent.sh 安装
sudo bash deploy/install-agent.sh
```

配置 `/etc/susumonitor/agent.env`（权限必须 0600）：

```bash
SUSUMONITOR_BACKEND_URL=wss://<域名>
SUSUMONITOR_SERVER_ID=<server_id>
SUSUMONITOR_AGENT_TOKEN=<一次性 token>
SUSUMONITOR_LOG_LEVEL=info
# 如需远程终端：SUSUMONITOR_TERMINAL_ENABLED=true
```

```bash
sudo systemctl daemon-reload && sudo systemctl restart susumonitor-agent
sudo systemctl status susumonitor-agent
journalctl -u susumonitor-agent -f --no-pager   # 观察 JSON 日志
```

## 4. 验收矩阵

### 4.1 WSS 连接与在线状态

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 启动 Agent，观察日志 | `agent authenticated`，无 error |
| 2 | 前端服务器列表 | “状态”和“Agent”两列均为 online |
| 3 | `GET /api/servers/{id}` | `status=online`、`agent_status=online` |
| 4 | 停掉 Agent | 两列在离线阈值（默认 90s）内变为 offline |
| 5 | 重启 Agent | 再次变回 online |

回归项（2026-07-30 修复的两个状态缺陷）：

- 心跳/鉴权成功时必须同时更新 `status` 与 `agent_status`。
- 旧连接断开时不得覆盖新连接的 online 状态（后端已用 `last_heartbeat_at` 乐观锁，验收时验证新连接心跳完成后旧连接关闭不会误标 offline）。

### 4.2 指标链路

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 启动 Agent 后等待 2 个采集周期 | 启动即有首个样本，随后按周期（默认 5s）上报 |
| 2 | `GET /api/servers/{id}/metrics/latest` | 返回最新指标，`collected_at` 为 UTC |
| 3 | `GET /api/servers/{id}/metrics?start_time=&end_time=` | 历史曲线连续、时间严格递增 |
| 4 | 前端监控页打开 | `metrics.update` 实时推送，无空白期超过一个采集周期 |
| 5 | 检查日志 | `metrics report failed` 不应出现 |

### 4.3 Token 生命周期

| # | 步骤 | 预期 |
|---|---|---|
| 1 | rotate：`POST /api/servers/{id}/agent/rotate` | 旧连接失效（Agent 重连失败或收到 40100 后停止重连）；新 token 可连接 |
| 2 | revoke：`DELETE /api/servers/{id}/agent/revoke` | 使用该 token 的 Agent 无法再认证，不会无限重连 |
| 3 | 全流程日志 | 日志、命令行、错误信息中均无明文 token |

参考脚本：`api-test/verify-agent-api.ps1`、`api-test/verify-go-agent-recovery.mjs`（Java 重启 + token rotate 恢复）。

### 4.4 断线与恢复

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 断开家庭网络 30s 后恢复 | Agent 指数退避重连（带 jitter，等待为退避的 [1/2, 1]），恢复后重新认证并上报 |
| 2 | 重启云端 Java 服务 | Agent 感知断线、退避、后端恢复后自动重连 |
| 3 | 观察重连日志 | `connect or authenticate failed, reconnecting` 的 backoff 序列 1s→2s→4s…，且不形成紧密循环 |
| 4 | 长时间断线再恢复 | 恢复后先按 FIFO 顺序受控回放已持久化且未确认的指标，再继续上报新样本；不跳过队首、不无限制突发发送 |

参考脚本：`api-test/verify-go-agent-reconnect.mjs`、`api-test/verify-go-agent-recovery.mjs`。

### 4.5 PTY 终端全链路（终端开启时）

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 浏览器打开终端 | `open → opened`，shell 提示符出现 |
| 2 | 输入命令 | `input → output` 正常回显 |
| 3 | 调整窗口大小 | `resize` 生效 |
| 4 | 关闭终端 | `close → closed`，`terminal.closed` 回送 |
| 5 | 终端内 `exit` / Ctrl+D | Shell 正常退出，`closed` 回送 |
| 6 | Ctrl+C | 只中断当前进程，终端仍可用 |
| 7 | 关闭浏览器/断网 | 会话关闭，后端无悬挂会话 |
| 8 | 杀掉 Agent 进程 | 子进程组被回收，无孤儿 Shell/PTY：`ps -ef | grep -v grep` 无残留 |
| 9 | Agent 重连后 | 旧会话不恢复 |

参考脚本：`agent-go-SuMon/cmd/terminal-integration`（成功标志 `PTY_RELAY_INTEGRATION_OK`）、`agent-go-SuMon/cmd/terminal-flow-control-integration`。

### 4.6 流控与限流

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 终端输出洪峰 | Go 侧令牌桶限流生效，超限按 `output_rate_exceeded` 关闭 |
| 2 | 输出队列满 | 会话按既定策略关闭，不无限缓存 |
| 3 | 慢消费者 | 触发 Monitor 背压（关闭码 `1011`，`CloseStatus.SESSION_NOT_RELIABLE`），记录关闭原因；WSL loopback 无法稳定触发，公网慢网下验收 |
| 4 | 超频发送（压测脚本） | 服务端返回错误码 `42901`/`42902`，随后关闭连接，Agent 退避重连 |
| 5 | 控制帧洪泛 | `42904`，Monitor 被断连 |

参考脚本：`api-test/verify-p2-agent-limits.mjs`（需低限流配置的隔离环境）、`api-test/verify-monitor-ws.mjs`、`api-test/verify-alert-ws.mjs`。

### 4.7 systemd 运维

| # | 步骤 | 预期 |
|---|---|---|
| 1 | `systemctl restart susumonitor-agent` | 秒级恢复，重新认证 |
| 2 | `kill -9` 模拟崩溃 | systemd `Restart=always` 自动拉起 |
| 3 | 日志轮转 | `logrotate` 生效，日志不无限增长 |
| 4 | 升级 | 替换二进制 → restart → 新版本日志确认 |
| 5 | 回滚 | 保留旧版本目录 → 恢复旧二进制 → restart |

### 4.8 安全与配置

| # | 步骤 | 预期 |
|---|---|---|
| 1 | `ls -l /etc/susumonitor/agent.env` | 权限 `0600`；属主为运行 Agent 的专用服务用户或 root（按部署方案），token 不可被无关用户读取 |
| 2 | 抓包/日志 | 无明文 token；传输全部为 TLS |
| 3 | `AGENT_ALLOW_INSECURE_HTTP` | 生产环境未设置 |
| 4 | 家庭主机 | 仅出站 443，无入站端口开放 |

## 5. 相关验证脚本索引

| 脚本 | 用途 |
|---|---|
| `api-test/verify-agent-api.ps1` | Token register/rotate/revoke 与哈希落库 |
| `api-test/verify-agent-ws.mjs` | WS 认证、heartbeat ACK、metrics、token rotate 关闭 |
| `api-test/verify-go-agent-reconnect.mjs` | 退避序列 1s→2s→4s（jitter 下限 1/2） |
| `api-test/verify-go-agent-recovery.mjs` | Java 重启、token rotate 后的恢复 |
| `api-test/verify-monitor-ws.mjs` | Monitor ticket、`metrics.update` 推送 |
| `api-test/verify-alert-ws.mjs` | 告警 Trigger/Continue/Resolve 推送 |
| `api-test/verify-outbox.mjs` | Outbox 正常/停机/恢复补发三阶段 |
| `api-test/verify-mvp11.mjs` | Alert 消费侧正常/幂等/DLQ |
| `scripts/run-wsl-pty-integration.sh` | WSL PTY 链路集成 |

## 6. MVP-11 外部验收项（2026-08-16 更新：三项均已完成）

原登记为"待验"的三项已全部执行完成，Go Agent 作为指标生产者配合验证：

1. ~~`api-test/verify-mvp11-concurrency.mjs`：并发消费幂等。~~ 已完成（2026-08-12，Polish-7 M6）：多消费者并发消费落地，`ALERT_CONSUMER_CONCURRENCY`/`ALERT_CONSUMER_PREFETCH` 可配，幂等由 V15 唯一键 + 业务事务保障。见 `Develop-log/20260812-Polish7-M6-告警消费多消费者并发.md`。
2. ~~`api-test/replay-mvp11-dlq.mjs`：DLQ 受控重放。~~ 已完成：字段级消息契约 DLQ 验收 2026-08-01（`Develop-log/20260801-字段级消息契约DLQ验收.md`）；重放工具 2026-08-12 泛化至 `--event metrics|alert`（`api-test/replay-dlq.mjs` / `replay-mvp11-dlq.mjs`），见 `Develop-log/20260812-RabbitMQ全链路收口.md`。
3. ~~`api-test/verify-mvp11-broker-recovery.md`：Broker 停机/恢复补发。~~ 已完成（2026-08-01）：Broker 停机消费侧重连验收通过，见 `Develop-log/20260801-MVP11-收口.md`（三、Broker 停机消费侧重连验收）。

执行环境（历史记录，验收已通过）：MySQL 验证库 + RabbitMQ（`susumonitor` vhost）+ 隔离 Java 实例 + 管理员账号。

## 7. 已知限制

- `/ws/agent`、`/ws/monitor` 的连接注册表、ticket、订阅和终端中继均为单 JVM 内存状态，多实例横向扩容未验证。
- 明文 `ws://` 已被运营商劫持验证（历史记录，2026-07-30）；公网一律使用 `wss://`。
- 慢消费者 `1011`（`CloseStatus.SESSION_NOT_RELIABLE`）背压需要受控慢网环境，WSL loopback 无法稳定触发。
