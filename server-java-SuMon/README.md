# SuSuMonitor Java Backend

SuSuMonitor Java 后端工程，基于 Java 21、Spring Boot 3.4.x 和 Maven。

> **文档进度对齐说明（2026-07-25 修订，仅文档层）**
>
> 本节由本次"文档与实际开发进度对齐"修订追加；未修改原文档正文、未删除任何段落、未改动任何代码或配置。**修订时间：2026-07-25。**
>
> **当前对齐状态**：本节原"七、当前阶段"标"已完成 Java 后端工程骨架、统一响应、基础错误码、全局异常处理、request_id、`/api/health` 和 `/api/ready`。认证、管理员审核、服务器 CRUD、SSH 凭据加密和 SSH 测试将在后续阶段实现" — 该"后续阶段"内容**当前已全部实现**（详见 `项目需求与规范.md`）。本节追加仅做事实对齐提示，原"后续阶段"描述保留作历史快照。

## 一、技术栈

- Java 21 LTS
- Spring Boot 3.4.x
- Spring Web MVC
- Spring Security
- Spring WebSocket
- Spring AMQP（spring-boot-starter-amqp，RabbitMQ）
- Spring Mail（告警邮件通知）
- Hibernate Validator
- MyBatis-Plus
- MySQL 8.4
- Flyway
- springdoc-openapi
- JUnit 5

Java 代码必须遵循《阿里巴巴 Java 开发手册》。

## 二、本机配置

本机 MySQL 初始化脚本：

```text
../scripts/local-mysql-init.sql
```

当前本机开发数据库约定：

| 项 | 值 |
|----|----|
| 数据库地址 | `127.0.0.1:3306` |
| 数据库名 | `susumonitor` |
| 数据库用户 | `susumonitor` |
| 本机开发密码 | `732682` |

该密码仅用于本机开发，生产环境必须替换。

## 三、环境变量

参考 `.env.example`。真实 `.env` 不提交 Git。

关键密钥必须通过本机环境或部署平台注入：

- `JWT_SECRET`
- `AES_GCM_KEY`
- `AGENT_REGISTER_KEY`

> 注意：工程启动**不会自动加载** `.env` 文件；本机运行需手动 `export`（或写入 IDE 运行配置），容器化部署由仓库根 `docker-compose.yml` 读取根目录 `.env` 注入。

## 四、构建和测试

```bash
mvn test
```

## 五、启动

基础系统接口已实现，可使用：

```bash
mvn spring-boot:run
```

目标验证：

```bash
curl http://localhost:18080/api/health
curl http://localhost:18080/api/ready
```

`/api/health` 不依赖数据库，`/api/ready` 会检查数据库连接。

## 六、Apifox 接口文档

OpenAPI 3.0 文档共 5 个文件，位于：

```text
../docs-SuMon/OpenApi-SuMon/openapi-system.json
../docs-SuMon/OpenApi-SuMon/openapi-auth.json
../docs-SuMon/OpenApi-SuMon/openapi-admin.json
../docs-SuMon/OpenApi-SuMon/openapi-server.json
../docs-SuMon/OpenApi-SuMon/openapi-alert.json
```

在 Apifox 中分别导入后，使用本机环境：

```text
baseUrl = http://localhost:18080
```

5 个文件覆盖 system/auth/admin/server/alert 全部 REST 接口（29 路径 / 34 端点操作），例如：

- `GET /api/health`
- `GET /api/ready`
- 统一成功响应
- 统一错误响应
- `X-Request-ID` 响应头

## 七、当前阶段

当前已完成：Java 后端工程骨架、统一响应、基础错误码、全局异常处理、request_id、`/api/health`、`/api/ready`、认证（注册/登录/登出/当前用户）、管理员审核（待审核列表/通过/拒绝）、服务器 CRUD（含软删除和排序）、SSH 凭据加密和连接测试（含主机指纹确认）、Agent Token 生命周期（注册/轮换/撤销）、Metrics 接收/存储/查询/清理、Agent 和 Monitor 双 WebSocket 通道（含 Ticket 握手和实时推送）、CORS 跨域配置、WebSocket 错误契约冻结、MVP-6 告警业务闭环（规则 CRUD、状态机去重、恢复、记录查询、标记已读和 alert.push 推送）、终端会话生命周期（创建/状态转换/超时清理）、终端单 JVM 配额（用户 2、服务器 4、操作用户 5、全局 16、并发幂等）、**MVP-10 Metrics Outbox（V14 同事务写入 + RabbitMQ 发布器 Confirm/Return/指数退避 + ready 50301）**、**MVP-11 Alert 消费侧（V15 消费幂等表 + AUTO 确认 + 3 次有限重试 + DLQ 分类 + 字段级契约校验）**、**Agent 指标可靠投递（`metrics.ack` 入口确认；`metrics.nack` 永久拒绝分类 invalid_metrics_payload/stale_collected_at/server_not_found；心跳携带投递遥测落库 `servers.delivery_*` 并经 `GET /api/servers/{id}/status` 返回）**、Flyway V1-V19（V13 告警活跃规则唯一索引 / V14 outbox / V15 消费幂等 / V16 outbox 清理 / V17 心跳微秒精度 / V18 告警确认窗口 / V19 投递统计列）、**告警通知链路（V20/V21：邮件+钉钉+Webhook 通知渠道、消息驱动排程、退避重试与失败留痕、`GET /api/alerts/records/{id}/notifications` 通知历史）**、**alert.resolved 恢复事件链路（V25/V26 + alert-resolved-notifier 消费收口，`alert.triggered.v1` / `alert.resolved.v1` 出站事件）**、**保留期清理全集（V22 告警记录 / V24 SSH 测试历史 / V27 通知清理，outbox 清理默认开启，共 9 个调度器）**、**SSH 测试历史（V23，`GET /api/servers/{id}/ssh/test/history` 成功与失败均留痕）**、**心跳超时终端收口修复（`AGENT_HEARTBEAT_TIMEOUT_SECONDS`）**、部署资产（`application-prod.yml`、systemd unit、Nginx 反代示例、环境变量模板、`DEPLOYMENT.md`、本机 IPv4 smoke 脚本）。

资源限制语义：

- 单用户最多 2 个活动终端会话。
- 单服务器最多 4 个活动终端会话。
- 活动操作用户（`COUNT(DISTINCT user_id)`）最多 5 个。
- 全局活动终端会话最多 16 个。
- 用户可配置和拥有不限量主机。
- 10 台同时在线 Agent 主机限制暂未纳入 `main` 跟踪代码。
- 单 JVM 一致性保证；多实例部署前需评估分布式协调方案。

状态区分：

- 已合入 `main`：上述所有能力（含 `mvnw`/`mvnw.cmd` 与 `deploy/` 部署资产）。
- 本机 IPv4 可验证：`/api/health`、`/api/ready`、`127.0.0.1:18080` 监听、Nginx 示例配置静态检查。
- 真实云环境已验证：云端明文 HTTP 部署端到端（2026-07-31，见 `docs-SuMon/Handoff-SuMon/20260731-云端部署调试交接.md`）；HTTPS/WSS 已上线（2026-08-07，域名 genhaosan.online）。
- 真实云环境未验证：MySQL 备份恢复演练、家庭 Linux 主机（T6）部署。

未纳入 `main` 跟踪的代码或计划：

- 多消费者并发（双实例）验收、DLQ JSON Schema 校验（本地 broker 并发验收已于 2026-08-12 通过）。
- 性能 p50/p95/p99 数字基线（见 `docs-SuMon/Develop-log/20260728-MVP-9-Java后端性能基线.md`）。

## 八、模块清单

| 包/模块 | 职责（一句话） |
|---|---|
| `module/admin` | 用户审核：待审核列表、单个/批量通过与拒绝。 |
| `module/alert` | 告警管理：规则 CRUD、状态机评估、MQ 消费（幂等/重试/DLQ）、通知投递与恢复事件。 |
| `module/auth` | 认证：注册/登录/登出/当前用户、JWT 签发、首管理员行锁。 |
| `module/metrics` | 指标：接收（幂等/乱序拒绝）、宽表存储与查询、过期清理、Outbox 可靠发布。 |
| `module/server` | 服务器资产：CRUD（软删除）、SSH 凭据加密、主机指纹、SSH 测试、Agent Token 生命周期。 |
| `module/system` | 健康检查：`/api/health` 恒 UP + `/api/ready` 探活（"存活但未就绪"语义）。 |
| `module/terminal` | 终端会话：元数据、配额与生命周期管理（内容不落库）。 |
| `websocket` | 双通道：`/ws/agent`（认证/心跳/指标/中继）与 `/ws/monitor`（ticket/订阅/推送/背压）。 |
| `security` | 安全横切：JWT 过滤器、BCrypt、AES-256-GCM 凭据加密、出站 SSRF 策略。 |
| `ssh` | SSH 连接：sshj 连接测试、指纹捕获、虚拟线程 + Semaphore 并发限流。 |
| `scheduler` | 定时任务：9 个调度器（指标/接收/消费/告警/通知/SSH 历史清理、outbox 发布与清理、终端清理、心跳扫描、ticket 清理等）。 |
| `common` | 统一响应/错误码/业务异常/全局异常处理/RequestId 过滤器。 |
| `config` | 配置装配：RabbitMQ 拓扑幂等声明、AppProperties 校验、统一 Clock。 |
