# SuSuMonitor Agent (Go)

SuSuMonitor 监控采集 Agent，Go 实现。

> **当前状态（2026-08-03）**：WebSocket 鉴权、心跳、指数退避重连、gopsutil 指标采集、`metrics.report` 上报、`metrics.ack` 入口确认、有界本地 FIFO 缓冲和 Linux PTY 终端链路已实现。正式生产部署应使用 HTTPS/WSS。

- Go 1.23（`go.mod` 要求 1.23）
- gopsutil（跨平台系统指标采集）
- coder/websocket（WebSocket 客户端）

## 当前阶段

核心 Agent MVP 已完成，当前工作重点是 Linux/WSS 正式部署验收、PTY 异常生命周期验证、WebSocket 客户端可靠性测试和发布运维。

## 目录结构

```text
agent-go-SuMon/
├── go.mod
├── README.md
├── .env.example
├── Makefile
├── config/
│   └── agent.example.yml
├── cmd/
│   └── susumonitor-agent/
│       └── main.go
├── internal/
│   ├── config/       配置加载与校验
│   ├── wsclient/     WebSocket 连接、鉴权、重连
│   ├── collector/    系统指标采集
│   ├── metricbuffer/ 未确认指标的持久化 FIFO
│   └── reporter/     metrics.report 构造、入队与确认后发送
└── bin/              构建产物（不提交 Git）
```

## 构建

```powershell
# 编译
go build -o bin/susumonitor-agent.exe cmd/susumonitor-agent/main.go

# 或用 Makefile
make build
```

## 配置

当前正式运行方式仅从环境变量加载；`config/agent.example.yml` 仅作为字段参考，不会被 Agent 自动读取。

```powershell
# 从 .env.example 复制并填入真实值
cp .env.example .env
```

关键配置项：

| 配置项 | 说明 |
|--------|------|
| `SUSUMONITOR_BACKEND_URL` | 后端 WebSocket 地址，如 `ws://localhost:18080` |
| `SUSUMONITOR_SERVER_ID` | 服务器 ID（admin 预建后获得） |
| `SUSUMONITOR_AGENT_TOKEN` | Agent Token（admin 通过 REST 预发放，明文仅一次性返回） |
| `SUSUMONITOR_METRICS_BUFFER_PATH` | 未确认指标 FIFO 文件，默认 `/var/lib/susumonitor/metrics-buffer.json` |
| `SUSUMONITOR_METRICS_BUFFER_MAX_ENTRIES` | 待确认指标最大条数，默认 720（约 1 小时的 5 秒采集） |
| `SUSUMONITOR_METRICS_ACK_TIMEOUT_SECONDS` | 一次指标写入后等待 `metrics.ack` 的最长时间，默认 15 秒 |
| `SUSUMONITOR_METRICS_RETRY_INITIAL_SECONDS` | ACK 超时后的首次重传等待，默认 2 秒 |
| `SUSUMONITOR_METRICS_RETRY_MAX_SECONDS` | ACK 超时重传退避上限，默认 60 秒 |
| `SUSUMONITOR_METRICS_REPLAY_MIN_INTERVAL_MILLIS` | 积压 FIFO 相邻发送的最小间隔，默认 2500ms（约 24 条/分钟） |
| `SUSUMONITOR_METRICS_RETRY_JITTER_ENABLED` | ACK 超时重传是否使用 equal jitter，默认 `true`（实际等待为退避值的 1/2 至 1 倍） |

## 指标上报边界

- Agent 启动后会立即采集一次，之后按 `SUSUMONITOR_COLLECT_INTERVAL_SECONDS` 周期采集。
- 每条 `metrics.report` 在网络发送前先以完整协议帧、固定 UUID 和采集时间落入本地严格 FIFO；仅收到同一 `message_id` 的 `metrics.ack` 才删除。
- 写入失败、断线或 ACK 丢失时，下一次认证会重放队首的**原 UUID**；Server 对重复 ID 的入口幂等接受可避免重复指标与重复事件。
- 已认证连接中若 `SUSUMONITOR_METRICS_ACK_TIMEOUT_SECONDS` 内未收到 ACK，Agent 保留队首并按 `SUSUMONITOR_METRICS_RETRY_INITIAL_SECONDS` 至 `SUSUMONITOR_METRICS_RETRY_MAX_SECONDS` 的指数退避重发；重发仍使用原完整帧和 UUID。
- 为保持 Server 对 `collected_at` 的严格递增规则，最多只有一条指标处于 in-flight 状态，后续记录等待前一条 ACK；若 ACK 后仍有积压，下一条会等待 `SUSUMONITOR_METRICS_REPLAY_MIN_INTERVAL_MILLIS`，避免恢复时触发服务端指标限流。
- 缓冲满时拒绝最新采样并记录错误，保留既有 FIFO；缓存文件损坏、版本不兼容或 `server_id` 不匹配时启动失败，避免静默丢弃未确认数据。
- Agent 部署前必须先升级 Server 至支持 `metrics.ack` 的版本（`773fc4d` 或后续）；旧 Server 不会确认，队首将按可靠语义持续保留。
- 当前未实现队列字节上限、恢复期节流、`metrics.nack` 策略及管理端积压展示；真实断网/重启联合 E2E 仍待隔离环境执行。

## 平台支持

- 指标采集支持 Go/gopsutil 可运行的平台；温度和 load average 在部分平台可能为 `null`。
- 远程 PTY 终端当前仅支持 Linux；非 Linux 构建会拒绝终端请求并返回 unsupported。

## 验证

```bash
# 在 agent-go-SuMon 目录执行
go test ./...
go test -count=20 ./internal/wsclient
go vet ./...
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o bin/susumonitor-agent-linux-amd64 ./cmd/susumonitor-agent
```

`go test -race ./...` 需要可用的 C 编译器。本机已验证 MSYS2 UCRT64 工具链：

```bash
export PATH="/c/msys64/ucrt64/bin:$PATH"   # 或已持久化到用户 PATH
CGO_ENABLED=1 go test -race ./...          # 全仓 race 测试通过
```

生产部署还需要域名、TLS 证书和正确代理 WebSocket Upgrade 的 Nginx；Agent 应只配置 `wss://` 后端地址，禁止在不受信任网络中使用明文 `ws://`。

## 重连与错误处理

- 连接或鉴权失败后按指数退避重连，每次等待加入 equal jitter（实际等待为退避值的 [1/2, 1]），避免多 Agent 同步重连形成惊群；每次认证成功后退避重置为初始值。
- 服务端 error 帧按 `code` 分支：`40100`（认证失效，如 token 被 rotate/revoke）是终态错误，Agent 停止重连并以错误退出；其他错误码（如限流 `42902`）只记录结构化日志，等待连接关闭后由退避自然限速。
- 正常关停（SIGINT/SIGTERM）时不触发断连回调，终端清理由 Agent 进程统一执行；已认证连接异常断开时才触发断连回调，且回调 panic 会被捕获，不影响重连。

## 协议

Agent 通过 `ws(s)://<backend>/ws/agent` 连接后端，首帧发送 `agent.authenticate`，鉴权成功后按配置周期上报 `metrics.report` 和发送 `heartbeat`。

详见 `docs-SuMon/Protocol-SuMon/websocket-protocol.md`。

## 安全

- `agent_token` 不写日志、不出现在命令行和错误信息中。
- 配置文件（`.env`、`config/agent.yml`）不入 Git。
- 日志不输出密钥、密码、私钥。
