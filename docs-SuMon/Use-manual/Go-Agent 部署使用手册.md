# SuSuMonitor Go-Agent 部署使用手册

**适用范围**: 被监控 Linux 主机（含家庭内网 / NAT 主机、WSL 调试机）部署 Go Agent 接入云端后端  
**Agent 工程目录**: `agent-go-SuMon/`  
**后端接入地址**: `ws://SERVER_IP_OR_DOMAIN/ws/agent`  
**协议参考**: `docs-SuMon/Protocol-SuMon/websocket-protocol.md`

## 一、Agent 定位与架构

SuSuMonitor 采用 **Agent 主动出站** 模式，不依赖后端反向连接主机：

- **Agent（本手册）**：部署在被监控主机上，主动出站 WebSocket 连接云端后端 `ws://SERVER_IP_OR_DOMAIN/ws/agent`，鉴权后每 N 秒上报 `metrics.report`，并可选提供基于本地 PTY 的 Web 终端。
- **方向**：Agent → 后端（出站）。被监控主机只需能访问公网 80 端口即可，**无需公网 IP、无需开放入站端口、无需后端能 SSH 进来**。
- **适用场景**：家庭内网主机、NAT 后主机、云服务器、本机 WSL 调试机，均适用。

> 与"服务器详情页 → 测试连接（SSH）"的区别：SSH 测试是后端**主动 SSH 连** `ssh_host` 做连通性测试，要求后端能连到主机，对家庭内网主机无效。Agent 监控 / Web 终端**不依赖** SSH 字段，全部走 WebSocket。创建服务器时 SSH 字段在 Agent 模式下为冗余，填占位值即可（见第四章）。

## 二、前置条件

| 项 | 要求 |
|----|------|
| 后端 | 已部署且运行，nginx 80 反代 `/ws/agent` → 后端 18080 |
| 管理员账号 | 一个 admin 角色账号（首个注册用户自动成为 admin），用于预建 server 与发放 token |
| 编译机 | Go 1.23+（`go.mod` 要求 1.23，用于交叉编译 Linux 二进制），或目标机自带 Go |
| 目标机 | Linux x86_64（PTY 终端功能仅 Linux 支持） |
| 网络 | 目标机能出站访问 `SERVER_IP_OR_DOMAIN:80` |

验证目标机网络可达：

```bash
curl -i http://SERVER_IP_OR_DOMAIN/api/health
# 期望 HTTP 200, "status":"UP"
```

## 三、构建

### 方式 A：Makefile 交叉编译（推荐，Windows / 任意机均可）

```bash
# 在 agent-go-SuMon/ 目录执行
make build-linux
# 产物: bin/susumonitor-agent-linux-amd64（静态二进制,CGO_ENABLED=0）
```

等价命令：

```bash
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o bin/susumonitor-agent-linux-amd64 ./cmd/susumonitor-agent
```

> 产物为静态链接 ELF，不依赖目标机 glibc 版本，可直接丢到任意 Linux x86_64 运行。

### 方式 B：目标机原生编译

若目标机已装 Go 1.23+ 且版本 ≥ go.mod 声明版本：

```bash
cd agent-go-SuMon
go build -o bin/susumonitor-agent ./cmd/susumonitor-agent
```

## 四、预建 server 与发放 Agent Token

Agent 启动需要 `server_id` 与 `agent_token`，由管理员通过后端 REST 接口获取。`agent_token` 明文**仅一次性返回**，后端只存哈希，务必当场记下。

> 以下命令中 `SERVER`、`ADMIN_USER`、`ADMIN_PASS` 为占位，请替换为真实值。token 切勿外泄、不入版本库。

### 1. 登录获取 JWT

```bash
SERVER="http://SERVER_IP_OR_DOMAIN"
# 用变量传密码,避免明文进 history
read -rsp "admin password: " ADMIN_PASS; echo
LOGIN=$(curl -s -X POST "$SERVER/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")
JWT=$(echo "$LOGIN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
[ -n "$JWT" ] && echo "login ok" || { echo "login failed: $LOGIN"; exit 1; }
```

### 2. 预建 server

```bash
# SSH 字段在 Agent 模式下不用于监控/终端,填占位值即可通过校验
curl -s -X POST "$SERVER/api/servers" \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{
    "name":"my-host",
    "host":"my-host",
    "description":"Agent 模式主机",
    "ssh_host":"127.0.0.1","ssh_port":22,"ssh_user":"agent",
    "ssh_auth_type":"password","ssh_password":"placeholder-not-used"
  }'
# 响应 data.id 即 server_id,记为 SERVER_ID
```

> 字段 `name/host` 是展示标识，可自定义；`ssh_*` 全部占位。若该 name 已存在会报错，可改名重试或在前端删除旧记录。

### 3. 发放 Agent Token（明文一次性）

```bash
curl -s -X POST "$SERVER/api/servers/$SERVER_ID/agent/register" \
  -H "Authorization: Bearer $JWT"
# 响应 data.agent_token 即 Agent Token,立即记下
```

> 若该 server 已有 token，`register` 会失败，改用轮换接口 `POST /api/servers/{id}/agent/rotate`。
> 清空变量：`unset JWT ADMIN_PASS`。

## 五、配置 agent.env

Agent 只读环境变量（不自动加载 `.env` 文件，systemd 用 `EnvironmentFile` 注入）。模板见 `agent-go-SuMon/.env.example`。

部署文件路径：`/etc/susumonitor/agent.env`（权限 0600，root 只读）。

```ini
SUSUMONITOR_BACKEND_URL=ws://SERVER_IP_OR_DOMAIN
SUSUMONITOR_SERVER_ID=<上一步 server_id>
SUSUMONITOR_AGENT_TOKEN=<上一步 agent_token>
SUSUMONITOR_COLLECT_INTERVAL_SECONDS=5
SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS=30
SUSUMONITOR_RECONNECT_INITIAL_SECONDS=5
SUSUMONITOR_RECONNECT_MAX_SECONDS=60
SUSUMONITOR_LOG_LEVEL=info
SUSUMONITOR_TERMINAL_ENABLED=false
SUSUMONITOR_TERMINAL_SHELL=/bin/bash
SUSUMONITOR_TERMINAL_MAX_SESSIONS=4
SUSUMONITOR_TERMINAL_MAX_INPUT_BYTES=16384
SUSUMONITOR_TERMINAL_MAX_OUTPUT_BYTES=16384
SUSUMONITOR_TERMINAL_OUTPUT_RATE_BYTES_PER_SECOND=262144
SUSUMONITOR_TERMINAL_OUTPUT_BURST_BYTES=524288
SUSUMONITOR_TERMINAL_OUTPUT_QUEUE_SIZE=64
SUSUMONITOR_TERMINAL_IDLE_TIMEOUT_SECONDS=1200
SUSUMONITOR_TERMINAL_MAX_LIFETIME_SECONDS=28800
# --- 指标可靠投递(默认值即生产推荐,可省略) ---
SUSUMONITOR_METRICS_BUFFER_PATH=/var/lib/susumonitor/metrics-buffer.json
SUSUMONITOR_METRICS_BUFFER_MAX_ENTRIES=720
# SUSUMONITOR_METRICS_BUFFER_MAX_BYTES=0     # 字节上限，0 = 不限制（默认），最小 1024
SUSUMONITOR_METRICS_ACK_TIMEOUT_SECONDS=15
SUSUMONITOR_METRICS_RETRY_INITIAL_SECONDS=2
SUSUMONITOR_METRICS_RETRY_MAX_SECONDS=60
SUSUMONITOR_METRICS_RETRY_JITTER_ENABLED=true
SUSUMONITOR_METRICS_REPLAY_MIN_INTERVAL_MILLIS=2500
```

关键配置项：

| 配置项 | 说明 |
|--------|------|
| `SUSUMONITOR_BACKEND_URL` | 后端 WebSocket 地址，**必须** `ws://` 或 `wss://` 开头。走 nginx 公网入口，**不要**直连后端 18080（后端通常只绑 127.0.0.1） |
| `SUSUMONITOR_SERVER_ID` | 预建 server 的 id（正整数，必填） |
| `SUSUMONITOR_AGENT_TOKEN` | 预发的 Agent Token（必填，不写日志） |
| `SUSUMONITOR_COLLECT_INTERVAL_SECONDS` | 指标采集间隔，默认 5 秒，≥1 |
| `SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS` | 心跳间隔，默认 30 秒，≥1 |
| `SUSUMONITOR_RECONNECT_INITIAL_SECONDS` / `_MAX_SECONDS` | 重连退避初始 / 上限秒，max ≥ initial |
| `SUSUMONITOR_LOG_LEVEL` | `info` / `debug` / `warn` / `error`，排障时用 `debug` 可见 `metrics sent/reported` |
| `SUSUMONITOR_TERMINAL_ENABLED` | 是否接受 Web 终端协议帧，默认 `false`。开启终端功能设 `true` |
| `SUSUMONITOR_TERMINAL_SHELL` | PTY 启动 shell，须干净绝对路径，默认 `/bin/bash` |
| `SUSUMONITOR_METRICS_BUFFER_PATH` | 未确认指标 FIFO 快照文件，必须是 clean absolute path（含本地死信，v2 格式，v1 自动迁移），默认 `/var/lib/susumonitor/metrics-buffer.json` |
| `SUSUMONITOR_METRICS_BUFFER_MAX_ENTRIES` | 待确认指标与死信共用的条数上限，默认 720（约 1 小时 5 秒采集；死信超限丢最旧） |
| `SUSUMONITOR_METRICS_BUFFER_MAX_BYTES` | 待确认队列字节上限，默认 `0`（不限制）；非 0 时必须 ≥ 1024。超限拒绝最新采样并计 drop，与条数上限独立生效 |
| `SUSUMONITOR_METRICS_ACK_TIMEOUT_SECONDS` | 写入后等待 `metrics.ack` 的最长时间，默认 15；超时保留队首并按退避重传原 UUID |
| `SUSUMONITOR_METRICS_RETRY_INITIAL_SECONDS` / `_MAX_SECONDS` | ACK 超时重传的指数退避初始 / 上限，默认 2 / 60；max ≥ initial |
| `SUSUMONITOR_METRICS_RETRY_JITTER_ENABLED` | 重传是否使用 equal jitter，默认 `true`（实际等待为退避值的 1/2 至 1 倍） |
| `SUSUMONITOR_METRICS_REPLAY_MIN_INTERVAL_MILLIS` | ACK/NACK 后积压相邻发送的最小间隔，默认 2500ms |
| `SUSUMONITOR_LOG_LEVEL` | `info` / `debug` / `warn` / `error`，排障时用 `debug` 可见 `metrics sent/reported`；NACK 死信处置为 warn 级 |

> **可靠投递语义（2026-08-05）**：仅收到同一 `message_id` 的 `metrics.ack` 才删除队首；`metrics.nack`（永久拒绝：载荷非法 / 乱序 / 服务器不存在）把队首移入本地死信且不重试；泛化 `error` 帧不删队首。心跳携带投递遥测（pending/bytes、最旧采样、丢弃、死信），后端落库后可在服务器详情页"状态快照"查看。

## 六、systemd 部署

部署资产在 `agent-go-SuMon/deploy/`：

```
deploy/
├── install.sh                    # 一键安装脚本
├── susumonitor-agent.service     # systemd 单元
├── agent.env                     # 配置模板
└── logrotate.conf                # 日志轮转
```

### 方式 A：一键安装（推荐）

云端已托管二进制 + 脚本，目标机一行命令下载部署：

```bash
curl --fail --silent --show-error --location \
  https://genhaosan.online/agent/install-agent.sh | \
  sudo -E env \
    AGENT_BASE_URL=https://genhaosan.online \
    AGENT_TERMINAL_ENABLED=true \
    AGENT_VERSION=1.0.0 \
    bash
```

> 生产环境必须 HTTPS。脚本会把 `AGENT_BASE_URL` 的 `https:` 自动转成 `wss:` 写入 `SUSUMONITOR_BACKEND_URL`（agent 连后端地址），无需再传 `AGENT_ALLOW_INSECURE_HTTP`。终端功能需要 `AGENT_TERMINAL_ENABLED=true`（默认关闭）。

脚本自动完成：下载二进制（sha256 校验）→ 交互输入 admin 账密 → 登录 → 预建 server → 发 token → 写配置 → 装 systemd → 启动验证。安装失败自动回滚。

> ⚠️ **已装过 agent 的机器重跑不会更新旧地址**：脚本只在 `/etc/susumonitor/agent.env` 不存在时才写入 `SUSUMONITOR_BACKEND_URL`，已存在则保留原值。HTTPS 迁移后旧机器（指向 `ws://82.156.245.102` 等）需先手动改：`sed -i 's|SUSUMONITOR_BACKEND_URL=ws://.*|SUSUMONITOR_BACKEND_URL=wss://genhaosan.online|' /etc/susumonitor/agent.env && systemctl restart susumonitor-agent`，或 `rm /etc/susumonitor/agent.env` 后重跑脚本（会重新注册新 server）。

**环境变量**：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_BASE_URL` | `https://monitor.example.com` | 后端 HTTPS 地址，`https:` 自动转 `wss:` 写 agent 配置 |
| `AGENT_VERSION` | `1.0.0` | release 版本号 |
| `AGENT_NAME` | `hostname -s` | 主机显示名 |
| `AGENT_SERVER_ID` | 空（新建） | 已有 server 时复用，跳过预建 |
| `AGENT_TOKEN` | 空 | 已有 server 时配合 `AGENT_SERVER_ID` 复用，免交互 |
| `AGENT_TERMINAL_ENABLED` | `false` | 设 `true` 开启 Web 终端 |
| `AGENT_ALLOW_INSECURE_HTTP` | `false` | 仅历史 IPv4 明文测试用，生产勿设 |

> 已有 `/etc/susumonitor/agent.env` 时脚本读取现有 `AGENT_SERVER_ID` + token，不重复预建。

### 方式 B：手动部署

```bash
sudo install -m 0755 susumonitor-agent-linux-amd64 /usr/local/bin/susumonitor-agent
sudo mkdir -p /etc/susumonitor /var/log/susumonitor
sudo install -m 0600 -o root -g root agent.env /etc/susumonitor/agent.env   # 填好真实值
sudo install -m 0644 susumonitor-agent.service /etc/systemd/system/
sudo install -m 0644 logrotate.conf /etc/logrotate.d/susumonitor-agent
sudo systemctl daemon-reload
sudo systemctl enable --now susumonitor-agent
```

systemd 单元要点：

- `User=root`（PTY 需要 root 或对应权限）
- `EnvironmentFile=/etc/susumonitor/agent.env`
- `Restart=always` / `RestartSec=5`（断线自动重连拉起）
- `StandardOutput=append:/var/log/susumonitor/agent.log`

## 七、验证

### 1. 服务状态

```bash
systemctl is-active susumonitor-agent   # active
```

### 2. 日志确认鉴权

```bash
tail -f /var/log/susumonitor/agent.log
# 或
sudo journalctl -u susumonitor-agent -f
```

期望关键行：

```
"msg":"susumonitor agent starting","backend_url":"ws://...","server_id":N,...
"msg":"agent authenticated","server_id":N
```

- 出现 `agent authenticated` = 鉴权成功，Agent 已连上后端。
- 出现 `connect or authenticate failed, reconnecting` = 鉴权失败，检查 `AGENT_TOKEN` / `SERVER_ID` 是否正确、是否用了 `register` 而非 `rotate`。
- `debug` 级别下可见 `metrics sent` / `metrics reported` = 采集上报成功。

### 3. 后端落库确认（管理员）

登录后端数据库，确认 `metrics` 表有该 server 记录：

```bash
mysql -h127.0.0.1 -u susumonitor -p susumonitor -e \
  "SELECT id, server_id, cpu_percent, memory_percent, collected_at FROM metrics WHERE server_id=N ORDER BY id DESC LIMIT 3;"
```

期望每 `COLLECT_INTERVAL_SECONDS` 一条新记录。

### 4. 前端验证

- 登录前端，进 Dashboard，对应 server 应出现实时 CPU / 内存 / 磁盘 / 网络指标。
- 进服务器详情页，点右上角"**Web 终端**"（需 `TERMINAL_ENABLED=true`），应连到 Agent 本机 PTY。

## 八、运维命令

```bash
sudo systemctl status susumonitor-agent     # 状态
sudo systemctl restart susumonitor-agent   # 重启（改配置后）
sudo systemctl stop susumonitor-agent      # 停止
sudo journalctl -u susumonitor-agent -f     # 实时日志（journald）
tail -f /var/log/susumonitor/agent.log     # 实时日志（文件）
```

- **轮换 Token**：`POST /api/servers/{id}/agent/rotate` 后，更新 `/etc/susumonitor/agent.env` 的 `AGENT_TOKEN`，再 `restart`。
- **改采集间隔**：改 `COLLECT_INTERVAL_SECONDS` 后 `restart`。
- **调日志**：`LOG_LEVEL=debug` 后 `restart`，可定位上报 / 终端问题。

## 九、Web 终端

Web 终端走 WebSocket（**不是 SSH**）：前端 `/ws/monitor` → 后端中继 → `/ws/agent` → Agent 本地 PTY。

开启条件：

1. `SUSUMONITOR_TERMINAL_ENABLED=true`
2. 目标机为 Linux（PTY 仅 Linux 支持）
3. `SUSUMONITOR_TERMINAL_SHELL` 指向存在的 shell（如 `/bin/bash`）
4. 前端用户为 `approved`（详情页才显示"Web 终端"按钮）

> 终端关闭时（`TERMINAL_ENABLED=false`），`terminal.open` 会被 Agent 拒绝，前端报终端错误。这是常见排障点。

## 十、常见问题

| 现象 | 原因 / 处理 |
|------|------|
| `connect or authenticate failed, reconnecting` | token 错或 server_id 错；确认用 `register` 首次发 token，已存在则用 `rotate` |
| 前端"测试连接"按钮失败 | 这是 SSH 测试，后端主动连 `ssh_host`，对内网主机无效。Agent 模式下忽略，不影响监控/终端 |
| 创建 server 必填 SSH | 当前 `CreateServerRequest` DTO 强制要求 SSH 字段，Agent 模式填占位值即可通过 |
| Web 终端报终端错误（40903 等） | `TERMINAL_ENABLED=false`，设 `true` 后 `restart` |
| 前端 dashboard 无数据 | 先看 `agent.log` 是否 `authenticated` + `metrics reported`，再看后端 `metrics` 表是否落库 |
| `BACKEND_URL must start with ws:// or wss://` | 地址没带协议头；注意走 nginx 公网入口，别直连后端 18080 |
| WSL 里 `nohup &` 后台进程消失 | WSL 命令退出会回收后台进程。WSL 需用 systemd 部署（WSL2 需 `/etc/wsl.conf` 设 `[boot] systemd=true`） |

### 家庭内网主机部署要点

家庭内网主机无公网 IP、在 NAT 后，后端无法主动连入。Agent 模式正好适配：

1. 路由器无需端口映射 / 公网 IP。
2. 主机装 Agent，`BACKEND_URL` 指向云端公网地址，Agent 出站连接。
3. 监控指标、Web 终端均经此出站通道，不依赖 SSH 字段。

### WSL 调试机部署要点

1. 确认 `/etc/wsl.conf` 含 `[boot] systemd=true`（Windows 侧 `wsl --shutdown` 后重启 WSL 生效）。
2. 用 systemd 部署（第六章），`Restart=always` 保证常驻。
3. 交叉编译产物可直接在 WSL 运行（静态二进制）。

## 十一、协议参考

Agent 与后端的 WebSocket 消息协议（`agent.authenticate` / `metrics.report` / `heartbeat` / `terminal.*`）详见：

```
docs-SuMon/Protocol-SuMon/websocket-protocol.md
```
