# SuSuMonitor Server 部署安装手册（MVP-8）

**适用范围**：Java 后端 + 依赖组件（MySQL / RabbitMQ / Nginx / Vue 静态资源）从零到上线的安装与启动操作。
**配套资产**：`server-java-SuMon/deploy/`（install.sh、systemd unit、nginx 片段、env 模板）、`docs-SuMon/Handoff-SuMon/20260727-云端T5部署交接.md`、`server-java-SuMon/deploy/DEPLOYMENT.md`（资产说明与边界）。
**Go Agent 安装**：见 `docs-SuMon/Use-manual/Go-Agent 部署使用手册.md`。

---

## 一、组件拓扑与职责

```text
浏览器 (Web 控制台)
  │ HTTPS/WSS（域名备案后） / 明文 HTTP（当前阶段）
  ▼
Nginx (宝塔托管，80/443)
  ├─ / → Vue 静态资源（web-vue-SuMon/dist）
  ├─ /api → 127.0.0.1:18080（后端 REST + /ws/monitor）
  └─ /ws/agent → 127.0.0.1:18080（Agent 通道）

Java 后端 (susumonitor-server, systemd, 127.0.0.1:18080)
  ├─ MySQL 8.4（127.0.0.1:3306，Flyway 管理迁移 V1-V27）
  └─ RabbitMQ（127.0.0.1:5672，vhost susumonitor，MVP-10 Outbox 发布）

Go Agent（部署到被监控服务器）
  └─ ws://<域名或IP>/ws/agent（Token 鉴权 + 指标上报 + 终端中继）
```

## 二、前置条件与版本要求

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | 21 LTS | 云上使用 Tencent Kona JDK 21 验证通过 |
| MySQL | 8.4 | Flyway V1-V27；`utf8mb4`；专用账号最小权限 |
| RabbitMQ | 4.x + Erlang 27 | **版本匹配约束**：RabbitMQ 4.3 与 Erlang 29 不兼容（horus 机制），生产必须用官方支持的 Erlang 27.x（本机验收实测 4.3.4 + 27.3.4.13） |
| Node.js | ≥18.18（仅构建前端时需要） | 构建 Vue 静态资源 |
| Nginx | 宝塔 1.30+（云上现状） | 反代 + SPA fallback + WS Upgrade |

## 三、从零安装流程

### 3.1 MySQL 初始化与账号

```sql
-- 用 root 执行（或按服务器既有策略）
CREATE DATABASE IF NOT EXISTS susumonitor DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'susumonitor'@'localhost' IDENTIFIED BY '<强密码>';
GRANT ALL PRIVILEGES ON susumonitor.* TO 'susumonitor'@'localhost';
FLUSH PRIVILEGES;
```

> 安全要求：账号仅限 `localhost`；数据库密码通过 `server.env` 注入，不入库、不落日志。密码策略恢复 MEDIUM 见《安全检查手册》。

### 3.2 RabbitMQ vhost 与用户（MVP-10 依赖）

```bash
rabbitmqctl add_vhost susumonitor
rabbitmqctl add_user susumonitor '<强密码>'
rabbitmqctl set_permissions -p susumonitor susumonitor '.*' '.*' '.*'
rabbitmqctl set_user_tags susumonitor administrator   # 管理台/验收脚本需要 management 角色
```

> 拓扑（8 件：2 exchange `susumonitor.events`/`susumonitor.dlx` + 6 队列 `susumonitor.alert.metrics`/`susumonitor.alert.triggered`/`susumonitor.alert.resolved` 及其 `.dlq`）由后端启动时幂等自动声明，无需手工建。详见《RabbitMQ 运维手册》。

### 3.3 构建发布包

```bash
# 后端（在开发机）
cd server-java-SuMon && ./mvnw clean package -DskipTests   # 产出 target/server-java-SuMon-0.0.1-SNAPSHOT.jar

# 前端（在开发机）
cd web-vue-SuMon && npm install && npm run build            # 产出 dist/（上传到 Nginx 站点根目录）
```

### 3.4 上传与首次安装

```bash
# 1) 上传 JAR 到版本目录（发布目录不可由 susumonitor 用户写入）
sudo install -d -o root -g root /opt/susumonitor/releases/RELEASE_ID
# 上传 server-java-SuMon-0.0.1-SNAPSHOT.jar 到该目录

# 2) 首次安装（或按 DEPLOYMENT.md 的手动等价步骤）
sudo bash /opt/susumonitor/releases/RELEASE_ID/install.sh
# 或手动：ln -sfn 软链 + systemctl daemon-reload + enable --now

# 3) 配置环境文件（模板见 deploy/susumonitor-server.env.example）
sudo install -m 0600 -o root -g root server.env /etc/susumonitor/server.env
```

`/etc/susumonitor/server.env` 关键变量（**密钥不得入库、不得落日志**）：

| 变量 | 说明 |
|---|---|
| `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` | 数据库连接 |
| `JWT_SECRET` / `AES_GCM_KEY` | **必须备份**（丢失 = SSH 凭据密文永久不可解密，见《备份与恢复手册》） |
| `SERVER_ADDRESS=127.0.0.1` `SERVER_PORT=18080` | 生产仅回环监听，由 Nginx 反代 |
| `SPRING_RABBITMQ_*` | RabbitMQ 连接（host/port/virtual-host/username/password） |
| `AGENT_TRUSTED_PROXY_CIDRS` | 反向代理信任列表（生产默认仅本机） |
| `CORS_ALLOWED_ORIGINS` | 前端 Origin 白名单（域名备案后为 https://域名） |
| `ALERT_NOTIFICATION_ENABLED` | 告警外部通知总开关，默认 `false`；`true` 时按规则配置的渠道发送（邮件/钉钉/Webhook，其中邮件另需 SMTP 配置） |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | SMTP 服务器（邮件告警通道；不配置则邮件通道自动跳过，钉钉/Webhook 不受影响） |
| `ALERT_MAIL_FROM` | 告警邮件发件人，默认 `noreply@susumonitor.local` |
| `ALERT_CONSUMER_CONCURRENCY` / `ALERT_CONSUMER_PREFETCH` | 告警消费并发（默认 `1` 单消费者；>1 启用多消费者，幂等由 V15 唯一键保障，2026-08-12 落地）与每消费者预取数 |
| `ALERT_CONSUME_MAX_ATTEMPTS` | 消费重试上限，默认 `3` 次（耗尽转 DLQ） |
| `ALERT_NOTIFICATION_CLEANUP_ENABLED` / `ALERT_NOTIFICATION_RETENTION_DAYS` | 通知历史清理开关（默认 `true`）/ 保留天数（默认 `90`） |
| `ALERT_NOTIFICATION_CLEANUP_CRON` / `ALERT_NOTIFICATION_CLEANUP_BATCH_SIZE` / `ALERT_NOTIFICATION_CLEANUP_MAX_BATCHES_PER_RUN` | 通知清理调度 cron（默认每天 03:00）/ 每批条数（默认 1000）/ 单次最多批数（默认 100） |

> 完整环境变量清单以 `server-java-SuMon/src/main/resources/application.yml` 为准（含 `ALERT_RECORD_CLEANUP_*`、`SSH_TEST_HISTORY_*` 等）。

> **通知重试（2026-08-05）**：每个渠道在 `alert_notifications` 表记一行，首次失败按 2^attempts 秒退避（上限 60s）重试，最多 5 次；达上限标记 `failed` 并在告警记录页显示"发送失败"。失败原因截断 500 字符留痕。

### 3.5 Nginx 合并

按 `deploy/nginx-susumonitor.conf.example` 合并到宝塔站点 server 块：
- SPA fallback（`try_files ... /index.html`）
- `/api/` 反代 127.0.0.1:18080
- `/ws/agent`、`/ws/monitor` 反代（`Upgrade`/`Connection` 头 + 3600s 超时）
- **HTTPS/WSS 由宝塔托管证书，域名备案后启用**（见《安全检查手册》TLS 计划）

## 四、启动 / 停止 / 重启 / 状态

```bash
sudo systemctl enable --now susumonitor-server   # 开机自启 + 启动
sudo systemctl start susumonitor-server
sudo systemctl stop susumonitor-server
sudo systemctl restart susumonitor-server
sudo systemctl status susumonitor-server --no-pager
sudo journalctl -u susumonitor-server -f          # 实时日志（journald）
```

> 日志要求：不得提高生产日志等级以记录 WebSocket 内容；日志不得含密码/Token/密钥/SSH 凭据。

## 五、健康检查

| 端点 | 语义 | 期望 |
|---|---|---|
| `GET /api/health` | 进程存活 | `{"status":"UP",...}` |
| `GET /api/ready` | 就绪（DB + RabbitMQ） | `{"status":"UP","database":"ok"}` |
| `GET /api/ready` | **存活但未就绪**：RabbitMQ 不可达 | HTTP 503 `50301 rabbitmq unavailable`（应用不退出，Outbox 退避重试，恢复后自动补发） |

```bash
curl -s http://127.0.0.1:18080/api/health
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/api/ready   # 期望 200
```

## 六、首次验收清单（部署后必须执行）

| # | 检查项 | 命令/资产 |
|---|---|---|
| 1 | 后端启动 + Flyway V1-V27 迁移成功 | `journalctl -u susumonitor-server` 无 ERROR |
| 2 | health/ready 正常 | 见 §五 |
| 3 | RabbitMQ 拓扑已声明 | `rabbitmqctl list_queues` 见 `susumonitor.alert.metrics`（durable） |
| 4 | 告警全链路 24 项 | `node api-test/verify-alert-ws.mjs`（需验证库 + 管理员账号） |
| 5 | Outbox 三阶段（含停机恢复） | `node api-test/verify-outbox.mjs`（需管理 API 账号） |
| 6 | 前端页面可访问（登录/服务器列表/告警页） | 浏览器冒烟 |
| 7 | Agent 连接与指标上报 | `Go-Agent 部署使用手册` §七 验证 |

## 六点五、Docker Compose 部署（2026-08-16 实机验证通过）

容器化部署为本手册 systemd 路径之外的第二种部署形态（mysql + rabbitmq + server + web 四服务，agent 可选 profile），已在本机 WSL2 Docker 29.4.2 上完成三镜像构建与全栈实跑验收（详见 `docs-SuMon/Develop-log/20260816-Docker镜像实机构建.md`）。

### 6.5.1 前置

- Docker Engine ≥ 26 + Compose v2 插件；国内网络建议配置 registry-mirror（本机用 `docker.m.daocloud.io`，见 dev-log）。
- 镜像由 `docker compose build` 现场构建（server 构建走 `.docker/settings.xml` 阿里云 Maven 镜像；npm 依赖需可访问 npmjs.org）。

### 6.5.2 环境文件

```bash
cp .env.example .env   # 或放非仓库路径并 --env-file 引用
# 必填：MYSQL_ROOT_PASSWORD / DB_PASSWORD / RABBITMQ_PASSWORD
#       JWT_SECRET(≥32B base64) / AES_GCM_KEY(恰32B base64) / CORS_ALLOWED_ORIGINS
# 生成：openssl rand -base64 32（JWT/AES）；openssl rand -hex 16（数据库口令）
# 多实例化（2026-08-17/18）：REDIS_ENABLED=true 启用 Redis 能力（compose 自带 redis 服务；
#   Redis 需 ≥6.2 以支持 GETDEL）——Monitor ticket 共享、JWT 黑名单（logout 真实失效，
#   TTL=剩余有效期，跨实例生效）、登录防爆破（LOGIN_LIMIT_MAX_ATTEMPTS 默认 10 /
#   LOGIN_LIMIT_WINDOW_SECONDS 默认 60，超限 429 + Retry-After）
```

### 6.5.3 构建与启动

```bash
docker compose build                                    # server + web
docker compose --profile agent build agent              # 可选：agent 镜像
docker compose up -d                                    # 四服务，healthcheck 门控启动顺序
docker compose ps                                       # 应全部 (healthy)
```

- 入口：`http://<主机>:${WEB_PORT:-8080}`（nginx 容器反代 `/api`、`/ws/agent`、`/ws/monitor` → server:18080）。
- 数据：命名卷 `mysql-data`/`rabbitmq-data` 持久化；`docker compose down -v` 清空重置。
- 验收：`node api-test/verify-docker-compose.mjs`（P0 首管理员 bootstrap + C1 健康/C2 建服务器/C3 WS 订阅/C4 agent token/C5 agent 容器上报闭环，2026-08-16 PASS）。
- 注意：容器与 systemd 部署不可在同一主机混用端口（3306/5672/18080 均被容器占用时 systemd 路径需改端口）。

## 七、相关手册

- 升级与回滚：《SuSuMonitor-升级与回滚手册.md》
- 备份与恢复：《SuSuMonitor-备份与恢复手册.md》
- 安全检查：《SuSuMonitor-安全检查手册.md》
- RabbitMQ：《SuSuMonitor-RabbitMQ-运维手册.md》
