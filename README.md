# SuSuMonitor

> 涂山苏苏主题的服务器监控平台 — 前后端 + Agent 全栈

[![Branch](https://img.shields.io/badge/branch-main-blue)](https://github.com/genhao0823/SuSuMonitor-jvav-)
[![Tag](https://img.shields.io/badge/tag-v0.5.0--cloud-green)](https://github.com/genhao0823/SuSuMonitor-jvav-/releases/tag/v0.5.0-cloud)
[![Status](https://img.shields.io/badge/Polish--6%20%E5%AE%8C%E6%88%90-brightgreen)](#%E5%BD%93%E5%89%8D%E8%BF%9B%E5%BA%A6)
[![Docs](https://img.shields.io/badge/docs--alignment-2026--07--25-blue)](docs-SuMon/Bug-fix/2026-07-25-文档对齐性修复.md)

## 🚀 当前进度快照（2026-08-12）

| 项 | 状态 / 值 |
|---|---|
| GitHub 仓库 | <https://github.com/genhao0823/SuSuMonitor-jvav-> |
| 当前基线 | `main` HEAD（Polish-7 收口，2026-08-12） |
| 认证方式 | Git Credential Manager(Windows 凭据管理器缓存,无需明文 token) |
| 基本功能闭环 | 鉴权(JWT 72h)/服务器 CRUD/SSH 测试/Web 终端/Dashboard 真实指标/告警评估(MVP-6)/Metrics Outbox(MVP-10)/告警消息消费(MVP-11)/Agent 指标可靠投递 M1-M4（ACK/FIFO/退避重传 + NACK 死信 + 投递遥测）/告警外部通知（邮件+钉钉+Webhook，含退避重试）/监控页 ECharts 图表+阈值线/Agent 队列字节上限/增长表保留期清理（幂等接收记录 7 天、消费记录 30 天、告警记录 90 天、SSH 测试历史 90 天，Flyway V22/V24）/告警消费多消费者并发（V15 唯一键幂等，本地 broker 验收并发 4 消费 100 条零重复）/Agent nack 有限重试（retriable_server_error + snapshot v3 持久化预算）/告警事件 Broker 发布（alert.triggered.v1，Outbox 按行路由 V25，消费者待接入）/Docker 资产 已实现；既有本机与真实 Broker 验收通过 |
| Android App | **阶段一+阶段二+Polish-7 已完整实现（2026-08-12）**：`app-kt-SuMon/`（Kotlin + Compose）登录/注册、服务器列表/详情/排序、实时指标（WS + OkHttp pingInterval 心跳）、告警记录（通知深链到告警 Tab）、DataStore 通知开关持久化、前台服务告警通知、SSH 终端（实际尺寸 resize + 功能键行 + 物理键盘 Ctrl + **自研 ANSI 终端模拟器**：增量 CSI 解析/双屏/滚动回退/256 色/备用屏，支持 top/htop 类 TUI + **断线自动重连**：指数退避自动重开）；`gradlew assembleDebug` + 85 单测全绿；云端全链路手测待真机 |
| 首次上云端部署 | 腾讯云 OpenCloudOS 公网明文 HTTP 已跑通(2026-07-31,前端 5173 + 后端 18080 + Agent 8089 + RabbitMQ 5672,端到端联调 PASS),详见 `docs-SuMon/Handoff-SuMon/20260731-云端部署调试交接.md` |
| 已知遗留 | 真实 Agent+Server 断网/重启/ACK 联合 E2E、首管理员空库并发脚本、真实 SMTP 发送均待 DB 管理员凭据/真实邮箱运行（脚本已备）；Android App 云端全链路手测（待设备）、数据库异地备份定时调度（crontab）、Docker 镜像实机构建、多 JVM 实例仍属后续阶段；Android 终端 vi 全指令集/DEC 私有光标样式/OSC 超链接为自研模拟器边界外 |

> 本节反映 2026-08-05 的当前仓库基线；下方带日期的“对齐说明”和“收口”段落均为历史记录。

> **文档进度对齐说明（2026-07-25 修订，仅文档层）**
>
> 本节由本次“文档与实际开发进度对齐”修订添加，仅追加、未删除原文段落、未改动任何代码或配置。**修订时间：2026-07-25。** 状态标签定义：
>
> | 标签 | 含义 |
> |---|---|
> | **已实现** | 代码已合入并经过单元/集成/MockMvc 验证 |
> | **已验证** | 在真实本机环境中跑过并记录通过用例 |
> | **未实现** | 仅有规划/表已建但无业务/目录为空 |
> | **未验证** | 代码已实现但未在真实环境跑过 |
> | **计划中** | 仅出现在 plan 文档，未进入开发 |
> | **outdated** | 文档陈旧但保留作历史快照 |
>
> **总体状态（2026-08-01 文档对齐修订，仅文档层，逐项核对代码事实）**：
> 本次修订把"仓库结构/启动指南/协议工具"等历史段与代码事实对齐：OpenAPI 5 文件 31 端点（含 `openapi-alert.json`）、WS 协议 v1.2、views 13 个页面组件、dev-log 90+、Flyway V1-V18、JWT 默认 72h；Agent 启动改为纯环境变量方式（无 `--config` 命令行参数）；MVP-10/MVP-11 已合入 `main` 跟踪。
>
> **总体状态（2026-07-31 对齐说明，详见 `docs-SuMon/Use-manual/README.md`）**：
> **MVP-8 运维文档完成（2026-07-31）**：Use-manual 手册系列落地（Server 部署安装 / 升级与回滚 / 备份与恢复 / 安全检查 / RabbitMQ 运维 + Go-Agent 手册索引）；配套 `deploy/backup.sh` 与 `deploy/restore.sh`（补齐 DEPLOYMENT.md 明言的"无备份脚本"空白）；恢复与安全检查两大空白已系统化；TLS/HTTPS 仍待域名备案（手册含计划与边界）。
>
> **总体状态（2026-08-01 对齐说明，详见 `docs-SuMon/Develop-log/20260801-MVP11-收口.md`）**：
> **MVP-11 收口完成（2026-08-01）**：outbox 轮询 1000→200ms（S5 alert.push p50 835→242ms，-71%）；DLQ 受控重放工具落地（`replay-dlq.mjs`，幂等重放 + 防循环）；Broker 停机消费侧重连三阶段真实验收 PASS（停机后端存活/ready 50301、恢复后发布器补发 + 消费者自动重连补消费、状态机正确、6/6 消息无丢失）。386 测试全绿。
>
> **总体状态（2026-07-31 对齐说明，详见 `docs-SuMon/Develop-log/20260731-MVP11-Alert-消费侧.md`）**：
> **MVP-11 告警消费侧完成（2026-07-31）**：告警评估从本地事务事件**切换**到 RabbitMQ 消息通道——`AlertMessageConsumer` 幂等消费 `metrics.reported.v1`（V15 `message_consume_records` 唯一键），AUTO 确认模式（业务事务提交后 ACK）+ 容器级有限重试（3 次指数退避）+ 不可重试数据错误零重试进 DLQ；真实 broker 验收：`verify-alert-ws` 24/24、`verify-mvp11` 17 项检查 PASS（正常链路/重复投递幂等/DLQ 分类），373 测试全绿；修复真实验收发现的 MANUAL ack 滞留坑（改 AUTO + 自定义容器工厂）。队列不再堆积。
>
> **总体状态（2026-07-31 对齐说明，详见 `docs-SuMon/Develop-log/20260731-MVP10-Metrics-Outbox.md`）**：
> **MVP-10 Metrics Outbox 完成（2026-07-31）**：指标入库与 `message_outbox` 同事务写入，发布器经 RabbitMQ（Publisher Confirm/Return + 指数退避）可靠投递；真实 Broker 三阶段验收 PASS（正常/停机/恢复补发）；`/api/ready` 增加 RabbitMQ 检查（存活但未就绪语义，50301）；本地事件链路保留，告警零中断；消费侧（MVP-11）未启动，队列消息堆积为预期。
>
> **总体状态（2026-07-31 对齐说明，详见 `docs-SuMon/Develop-log/20260731-MVP9-数据所有权与依赖审计.md`、`20260731-MVP9-契约冻结评审与出口条件核对.md`）**：
> **MVP-9 收口完成（2026-07-31）**：性能基线 7 场景实测 PASS（`api-test/bench-alert-chain.mjs`，p50/p95/p99 见 `20260728-MVP-9-Java后端性能基线.md` §九~§十）；数据所有权审计完成并收口 3 处跨模块 Mapper 访问（admin/metrics/terminal 改走 Service 契约）；RabbitMQ 消息契约与拓扑完成评审冻结。过程中修复 1 个真实 bug：告警恢复后不再触发（`handleResolve` 改为删除 state 行，commit `f7dba69`，verify-alert-ws 24/24 复核通过）。
>
> **总体状态（2026-07-27 对齐说明，详见 `docs-SuMon/Develop-log/20260727-项目状态与契约最终收口.md`）**：
> 本段保留 `7b01a60` 阶段的历史收口背景：当时 MVP-6 后端业务闭环已实现，前端告警页面尚未实现。后续前端告警、MVP-7 T4、明文云端部署、MVP-10/MVP-11 与 Agent M1-M3 的当前状态以本文顶部快照和后续带日期的对齐说明为准。
>
> **总体状态（2026-07-31 文档对齐修订，仅文档层，逐项核对代码事实）**：
> 本次修订把下方”未验证 / 未实现 / 部署资产缺口”清单与代码事实逐项对齐，已闭环项见各清单内的划线注明：**公网明文 HTTP 云端部署已跑通**（2026-07-31，腾讯云 OpenCloudOS，前端+后端+Agent 端到端，见 `docs-SuMon/Handoff-SuMon/20260731-云端部署调试交接.md`；HTTPS/WSS 待域名备案）；**MVP-6 告警前端已收口**（2026-07-27 Sprint 0-7，真实 HTTP/WS 端到端链路 2026-07-28 验收通过）；**MVP-7 T4 xterm.js 前端已完成**（2026-07-28 最小可用版本）；**T5 部署资产已补齐**（Maven Wrapper / `application-prod.yml` / systemd Unit / Nginx 站点配置，2026-07-27）；**B-005/B-006/B-007 已闭环**（Agent 采集上报接入 + `build-linux` + Linux amd64 二进制实测）；**B-037/B-038 已收口**（`openapi-system.json:info.title` 补齐 + 前端 4 端点契约封装与真实浏览器联调，2026-07-29）；**后端限流 / CORS 已实现**；**Android App 核心监控版 MVP 已实现**（2026-08-05，`app-kt-SuMon/`，见 `docs-SuMon/Develop-log/20260805-Android-App-核心监控版MVP.md`）。仍属”未验证/未实现”：首管理员空库并发、多 JVM AFTER_COMMIT 事件跨实例、Monitor 1012 真实背压、HTTPS/WSS、数据库备份脚本、告警外部通知渠道、Android App 云端全链路手测（待设备）。

## 项目简介

SuSuMonitor 是一套**前后端 + Agent 全栈**的服务器监控系统,主题采用涂山苏苏(狐妖小红娘)IP。包含 Web 端控制台(基于 Vue 3 + Element Plus)、Java 后端(Spring Boot + MyBatis + WebSocket)、Go Agent(采集器 + WS 上报)。

## 当前进度(2026-07-22 收口)

> 本节是对齐修订保留的原文表格，未做删改。

| 阶段 | 状态 | 内容 |
|---|---|---|
| **M1-M6**(MVP) | ✅ 完成 | 鉴权 / 主布局 / CRUD / 审核 / 监控 |
| **Sprint 1** | ✅ 完成 | SSH 测试按钮接真实后端 (`/api/servers/{id}/ssh/test`) |
| **Sprint 2** | ✅ 完成 | ServerListView spark line 接真实 metrics 历史 |
| **Sprint 3** | ✅ 完成 | DashboardView spark 接真实 + 通用 `ServerSparkLine` 组件化 |
| **Polish 1-5** | ✅ 完成 | dirty 清理 / e2e 选择器 / LONG_FILE 拆分 / audit 收口 / GitHub 推送 |
| **总计** | **65+ commits / 67 dev-log / 37 单元测试 / 4 道防线** | |

### 4 道测试防线

| 工具 | 命令 | 数量 |
|---|---|---|
| Vitest 单元测试 | `npm run test` | 37 测试 / 7 文件 |
| `audit:catchup`(11 规则) | `npm run audit:catchup` | 0 ERROR / 0 WARN / 0 INFO |
| `api:e2e`(HTTP 13 路径) | `npm run api:e2e` | 13 路径 |
| `ui:e2e`(浏览器 17 路径) | `npm run ui:e2e` | 17 路径 |

---

## 当前进度与实际开发进度对齐（2026-07-25 修订，仅文档层）

> 本节与上方“当前进度(2026-07-22 收口)”并存；前者是历史里程碑收口记录，后者是与代码事实对齐后的当前状态。两者均不做删改。**本次修订时间：2026-07-25。**

### 已实现 + 本机已验证（直接调用证据保留在原表格）

- Java 工程骨架、统一响应、错误码、异常处理、`X-Request-ID`、`X-Correlation-ID`（`项目需求与规范.md`、本仓库 `Develop-log`）。
- 认证（注册/登录/me/logout）+ 管理员审核 + 服务器 CRUD + SSH 凭据 AES-256-GCM 加密。
- Flyway V1-V9 + MySQL 8.4 隔离库迁移。
- Go Agent 配置加载、WebSocket 鉴权、心跳、断线重连。
- Metrics 接收/存储/最新/历史查询、Metrics 过期清理（独立 MySQL 已验证）。
- Monitor Ticket + 实时指标推送（事务提交后 AFTER_COMMIT 推送）。
- Vue M2-M6、Vitest、ESLint、构建、`audit:catchup`、`api:e2e`、`ui:e2e`、`openapi:check`。

### 未验证（必须留痕，未来安排独立验证）

1. **首管理员空库并发注册** — V7 行锁已实现但真实空库并发未跑。
2. _(已通过：真实 SSH `50003`/`50002` 分类已于 2026-07-25 通过 Apifox 受控 SSHD 真实验收，详见 `docs-SuMon/Develop-log/20260725-Apifox-SSH-50003-真实验收.md`、 `20260725-Apifox-SSH-50002-真实验收.md`)_
3. _(已通过：公网明文 HTTP 云端部署已于 2026-07-31 跑通（前端+后端+Agent 端到端），见 `docs-SuMon/Handoff-SuMon/20260731-云端部署调试交接.md`；HTTPS/WSS 待域名备案后验证)_
4. **多 JVM 实例下 AFTER_COMMIT 事件跨实例推送** — `MonitorTicketService` / `AgentConnectionRegistry` / `MetricsCleanupService` 全部为单 JVM 内存状态。
5. _(已通过：`Makefile:build-linux` 已补（B-007 关闭），`agent-go-SuMon/bin/susumonitor-agent-linux-amd64` 已交叉构建并在云端真实运行（明文 WS，2026-07-31 端到端验证）；HTTPS/WSS 长连接仍待域名备案)_
6. _(已通过：`PUT /api/servers/{id}/ssh/host-key` 首次确认/轮换已实现，通过 Apifox 9 用例 27 断言（2026-07-20）+ 前端真实浏览器联调（2026-07-29，B-038 收口）；"自动化强制校验"策略仍待评估)_
7. _(已通过：spark line 已接真实 metrics 历史（Sprint 2/3 收口）；仅"SSH 测试历史卡"（`DashboardSshCard`）仍为占位，等待 SSH test history 接口)_
8. _(已通过：前端已补齐 4 个未签端点契约封装并以真实浏览器联调收口（2026-07-29，B-038 关闭）；`openapi-system.json:info.title` 已补齐（B-037 关闭）)_

### 未实现（计划中或结构性缺口）

1. _(已补齐：`server-java-SuMon/mvnw` + `mvnw.cmd`，2026-07-27 T5，`mvnw test` 326 全过)_
2. _(已补齐：`server-java-SuMon/src/main/resources/application-prod.yml`，2026-07-27 T5)_
3. **Dockerfile / docker-compose** — 全仓 0 命中。
4. _(已补齐：`server-java-SuMon/deploy/susumonitor-server.service`，2026-07-27 T5)_
5. _(已补齐：`server-java-SuMon/deploy/nginx-susumonitor.conf.example` + `susumonitor-vhost.conf`，2026-07-27 T5，云端已生效)_
6. **数据库备份脚本** — `scripts/` 仅本地开发脚本。
7. _(已实现：2026-07-27 Sprint 0-7 收口，告警记录页 `/alerts/records`、告警规则页 `/alerts/rules`、菜单挂载、`alert.push` WS 消费；真实端到端链路 2026-07-28 验收通过)_
8. _(已实现：T4 xterm.js 前端最小可用版本 2026-07-28，路由 `/terminal/:serverId`；T5 云端部署已验证（明文 HTTP）、T6 家庭 Linux 主机部署仍待验)_
9. _(MVP-9 已于 2026-07-31 收口：性能基线 7 场景 PASS + 数据所有权收口 + RabbitMQ 契约冻结；MVP-10~14 仍规划中)_
10. _(已实现：**Android App 核心监控版 MVP** 2026-08-05，`app-kt-SuMon/`——登录/注册、服务器列表/详情、实时指标（WS）、告警记录、前台服务告警通知；`gradlew assembleDebug` + 18 单测全绿；云端全链路手测待设备，见 `Develop-log/20260805-Android-App-核心监控版MVP.md`)_
11. _(限流 / CORS 已实现：`AgentConnectionLimiter` / `AgentMessageRateLimiter` / Monitor 背压 + `config/CorsConfig.java`；WebSocket Origin 白名单策略仍待评估)_

### 部署资产缺口清单（必须在新分支中补齐后，才可上线公网）

> 本表为 2026-07-25 快照；其中 6 项已于 T5（2026-07-27）补齐并云端实测，见"现状"列标注。

| 缺口 | 现状 | 阻碍 |
|---|---|---|
| Maven Wrapper | ~~缺失~~ → **已补齐**（2026-07-27 T5，`server-java-SuMon/mvnw`） | CI / 跨机器构建可复现（`mvnw test` 326 全过） |
| `application-prod.yml` | ~~缺失~~ → **已补齐**（2026-07-27 T5，`src/main/resources/application-prod.yml`） | 生产配置已就绪 |
| Dockerfile + docker-compose | 缺失 | 没有容器化交付路径 |
| Java systemd Unit | ~~缺失~~ → **已补齐**（2026-07-27 T5，`server-java-SuMon/deploy/susumonitor-server.service`） | 进程监管和优雅停机已就绪 |
| 完整宝塔 Nginx 站点配置 | ~~缺失~~ → **已补齐**（2026-07-27 T5，`deploy/nginx-susumonitor.conf.example` + `susumonitor-vhost.conf`） | 云端明文 HTTP 已跑通；TLS 待域名备案 |
| 数据库备份脚本 | 缺失 | 没有任何异地归档策略 |
| `agent-go-SuMon/Makefile:build-linux` target | ~~缺失（B-007）~~ → **已补齐**（`Makefile:6`；`bin/susumonitor-agent-linux-amd64` 已实测 + 云端真实运行） | Linux 二进制可构建 |
| `ssh_host_key_fingerprint` 自动化 | V8 字段 + `PUT /api/servers/{id}/ssh/host-key` 接口已实现并通过 Apifox 9 用例 27 断言（2026-07-20）+ 前端真实浏览器联调（2026-07-29） | SSH 测试已闭环；终端已接入 xterm.js（T4，2026-07-28） |

### 文档与代码事实不一致项（本次对齐已修正或留痕）

| 项 | 修订 |
|---|---|
| JWT 有效期 24 vs 72 小时 | 以代码 `application.yml:36` 与 `JwtKeyConfig` 的 72 小时为准；同步 `.env.example:14` 与 `本机开发环境配置.md:94` |
| `ErrorCode:50003` 语义 | 代码 `ErrorCode.java:25` 为 `SSH_AUTHENTICATION_FAILED`；`项目需求与规范.md:176` 早期写为 `agent offline`，以代码为准 |
| `openapi-system.json:info.title` | 已补齐（B-037 关闭，2026-07-29） |
| 6 个 OpenAPI 端点缺失 | 已收口（B-038 关闭，2026-07-29：前端 4 端点契约封装 + 真实浏览器联调） |

## 技术栈

### 前端 (`web-vue-SuMon/`)
- **框架**:Vue 3.5 + `<script setup>` + Composition API
- **状态**:Pinia 3 + persistedstate
- **HTTP**:axios 1.7 + 自封装 `api/client`
- **UI**:Element Plus 2
- **测试**:Vitest 1 + @vue/test-utils + jsdom
- **E2E**:puppeteer-core(驱动系统 Chrome)

### 后端 (`server-java-SuMon/`)
- **框架**:Spring Boot
- **持久层**:MyBatis + MySQL
- **实时**:WebSocket(Agent 上报 + Monitor 监控通道)
- **认证**:JWT + BCrypt
- **安全**:AES-GCM(AES-GCM-256 主机密钥加密)

### Agent (`agent-go-SuMon/`)
- **语言**:Go
- **协议**:WebSocket + 指数退避重连
- **采集**:gopsutil(CPU / 内存 / 磁盘 / 网络)
- **心跳**:独立心跳服务,server 端校验

## 仓库结构

```
SuSuMonitor(Jvav)/
├── web-vue-SuMon/          # 前端 SPA(5173 端口 dev server)
│   ├── src/
│   │   ├── views/         # 13 个页面组件(含 AuthLayout / Login / Register / Dashboard / ServerList / ServerDetail / Metrics / AdminUsers / Forbidden / NotFound / AlertRecords / AlertRules / Terminal)
│   │   ├── components/    # 15+ 个 SFC(含 PageHeader / ServerSparkLine / ServerFormDialog / Dashboard* 系列等)
│   │   ├── api/           # 9 个 HTTP 模块(auth / system / server / agent-token / metrics / alert / admin / websocket 等)
│   │   ├── stores/        # Pinia stores + 2 个 spec
│   │   ├── services/      # WebSocket 客户端(Monitor 通道 + 终端复用)
│   │   ├── composables/   # useRouterLoading + 1 spec
│   │   ├── utils/         # format + animate + 2 spec
│   │   ├── types/         # API 类型 + error-code + metrics
│   │   └── router/        # index + guards
│   ├── scripts/           # 工具(openapi / audit / api-e2e / ui-e2e)
│   └── vitest.config.ts
├── server-java-SuMon/       # Java 后端(18080 端口)
│   └── src/main/java/com/susumonitor/server/
│       ├── module/auth/         # register / login / me / logout
│       ├── module/server/       # CRUD + SSH test + status
│       ├── module/metrics/      # latest + history
│       ├── module/admin/        # users page/search / batch approve-reject / single approve-reject
│       ├── module/system/       # health / ready
│       ├── websocket/           # Agent / Monitor / Ticket
│       ├── security/           # JWT + AES-GCM + SSH outbound
│       └── ssh/                # SSH connection tester
├── agent-go-SuMon/          # Go Agent(独立进程,部署到目标服务器)
│   ├── cmd/susumonitor-agent/main.go
│   └── internal/
│       ├── collector/      # gopsutil 采集
│       ├── reporter/       # WS 上报
│       ├── wsclient/       # 客户端连接(重连 + 心跳)
│       └── config/         # 配置加载
├── app-kt-SuMon/            # Android App(Kotlin + Compose,核心监控版 MVP)
│   └── app/src/main/java/com/susumonitor/
│       ├── api/            # Retrofit 接口 + WsClient + 拦截器
│       ├── data/           # DTO + Repository + SessionStore + 帧解析
│       ├── di/             # Hilt 模块
│       ├── service/        # 前台监控服务 + 通知
│       ├── ui/             # Compose 页面(login/dashboard/servers/alerts)
│       └── util/           # 格式化/错误码/常量
├── docs-SuMon/              # 项目文档
│   ├── Develop-log/         # 110+ dev-log(实施记录)
│   ├── Develop-plans/       # 16 plan(规划)
│   ├── OpenApi-SuMon/       # 5 个 OpenAPI 契约 JSON(auth / server / system / admin / alert),共 32 个 endpoint
│   ├── Protocol-SuMon/     # WebSocket 协议(v1.3)
│   ├── Bug-fix/             # 9 篇 bug 修复记录 + README 索引
│   ├── 本机开发环境配置.md   # 本机开发约定(JWT/AES/SSH/Metrics/Outbox 环境变量)
│   └── Summary-Technology/  # 项目架构总览
├── api-test/                # API 集成测试(Apifox + curl + WS 验证)
├── scripts/                 # PowerShell 脚本(初始化 / 测试 / 数据库)
├── server-java-SuMon/pom.xml
└── package.json
```

## 启动指南

### 前置

- Node.js >= 18.18
- npm >= 9
- JDK 17+(server)
- Go 1.23+(agent，`go.mod` 要求 1.23)
- MySQL 8.0(server)

### 前端(`web-vue-SuMon/`)

```bash
npm install
npm run dev                # http://127.0.0.1:5173
npm run test               # 单元测试
npm run audit:catchup      # 11 条规则静态扫描
npm run api:e2e            # HTTP 13 路径
npm run ui:e2e             # 浏览器 17 路径
```

### 后端(`server-java-SuMon/`)

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local    # http://localhost:18080
```

### Agent(`agent-go-SuMon/`)

```bash
go build -o susumonitor-agent ./cmd/susumonitor-agent
./susumonitor-agent    # 纯环境变量加载，无命令行参数（配置项见 agent-go-SuMon/README.md）
```

## 涂山 IP 使用范围

本项目使用涂山苏苏(狐妖小红娘)IP 形象,**仅供内部学习与 demo 用途,非官方同人作品,不用于商业用途**。公开展示或商业化前请替换为自有素材或已获授权的版本。详见各页面引言池、`/docs-SuMon/Bug-fix/` 目录与各 dev-log。

## 关键 commit + tag

| 引用 | 说明 |
|---|---|
| `v0.4.0-sprint4` | Sprint 1-4 收口里程碑 |
| `cd85f53` | BACKUP_REPO_README(仓库结构说明) |
| `f1399f4` | Merge feat/agent-monitoring into main(Polish 5 收口)|
| `cacf131` | docs(web): Sprint 4 dev-log + README 同步 |
| `170577d` | chore(audit): Polish 6 LONG_FILE 阈值 500→600 + CRLF 兼容 |
| `3dab5c8` | feat(web): Sprint 3 Dashboard spark 接真实 + ServerSparkLine 复用 + 4 单测 |
| `c99fa03` | feat(web): Sprint 2 ServerListView spark line 接真实 |
| `8b5dcc0` | feat(web): Sprint 1 SSH 测试按钮接真实后端 |
| `005e655` | chore(web): catch-up B1 工程配置 + 类型常量 |
| `e17a78d` | docs(web): catch-up B1 工程配置 dev-log |

## 后续计划

- **后端**:~~管理员批量审核 / 用户搜索接口~~（**Sprint 5+ 已完成 2026-08-02**：pending 分页+keyword 搜索 + batch-approve/reject，openapi-admin 3→5 端点，见 `Develop-log/20260802-Sprint5-用户管理增强与逃逸窗口控件.md`）
- **前端**:~~Sprint 5+（搜索 / 批量审核）~~（**已完成 2026-08-02**：用户审核页远端搜索/分页/批量选择 + 状态 Tabs + 批量失败明细 + 告警逃逸窗口 confirm_count 控件，vitest 118 全绿）；剩余候选：按状态管理增强、ECharts 图表启用、T4 Web SSH 终端增强
- **隔离环境验收**（**已完成 2026-08-02**）：verify-mvp11（C1-C4 + 失败留痕 DB 断言）、verify-admin-batch（18 项）、verify-go-agent-reconnect（6 checks）、verify-mvp11-broker-down（B1-B7）四脚本全 PASS；RabbitMQ 凭据已重置（susumonitor/732682，建议登记 local/server.env），见 `Develop-log/20260802-隔离环境端到端验收.md`
- **Agent 可靠投递**（**M1-M4 已完成 2026-08-05**）：M1 Server `metrics.ack` 入口确认 + M2 Agent 完整帧持久化 FIFO + M3 同 UUID 断线/ACK 超时重传、指数退避/equal jitter、积压重放节流 + **M4** Server 永久拒绝分类 `metrics.nack`（invalid_metrics_payload / stale_collected_at / server_not_found）、Agent 本地持久化死信（snapshot v2，v1 自动迁移）、心跳投递遥测（pending/bytes、oldest、drop、dead-letter）→ Flyway V19 → 状态接口 → 服务器详情页展示；loopback fixture 15 项 PASS，真实 Agent + Java/MySQL 独立 schema 场景待 DB 管理员凭据运行；队列字节上限仍属后续独立模块，见 `Develop-log/20260805-Agent指标可靠投递（NACK死信与投递遥测）.md`
- **协作**:在 GitHub 上创建 PR / 提 issue
- **Polish-6 三端收尾**（**已完成 2026-08-07**，commit `75b41e5`→`29effc0`）：HTTPS 迁移后 agent 配置核对与全库明文 URL 清扫；Android 告警通知深链到告警 Tab / 通知开关 DataStore 持久化（前台服务联动）/ 服务器列表排序选择器 / WS 半开连接检测（OkHttp pingInterval）/ 终端基础增强（实际尺寸 resize + 功能键行 + 物理键盘 Ctrl）；数据库异地备份脚本 `scripts/remote-backup.sh`（云端备份→拉回→AES-256 加密落盘，含 backup.sh CRLF 修复）；Web 告警通知投递历史详情弹窗（新增 `GET /api/alerts/records/{id}/notifications`，OpenAPI alert 6→7 端点）；顺带恢复 Web lint 零警告基线。详见 `docs-SuMon/Develop-plans/20260807-Polish-6-三端全面收尾.md` 与各模块 Dev-log（`20260807-Polish6-M*.md`）
- **Polish-7 遗留收尾**（**已完成 2026-08-12**，commit `c2672c8`→收口）：20260810 WIP checkpoint（SSH 测试历史 V23 / 主机密钥一键信任 / 超时分类 50400 / Swagger 放行）；SSH 测试历史保留期清理（V24 + CleanupService/Scheduler）；Android 自研 ANSI 终端模拟器（增量 CSI 解析/双屏/滚动回退/256 色/备用屏 + 逐格渲染）；Android 终端断线自动重连（指数退避重开 + UI 提示）；Agent metrics.nack 有限重试（retriable_server_error + snapshot v3 持久化预算）；告警消费多消费者并发（本地 broker 验收：并发 4 消费 100 条零重复 + DLQ 分类正确）。详见 `docs-SuMon/Develop-plans/20260812-Polish-7-遗留收尾.md` 与各模块 Dev-log（`20260812-Polish7-M*.md`）

## 协议 / 工具

- **OpenAPI 契约**:`docs-SuMon/OpenApi-SuMon/{openapi-auth,server,system,admin,alert}.json`(5 个文件 / 32 个 endpoint)
- **WebSocket 协议**:`docs-SuMon/Protocol-SuMon/websocket-protocol.md`(v1.3)
- **后端 OpenAPI 自动化**:`web-vue-SuMon/scripts/check-openapi.mjs`(pre-commit 钩子)
- **代码质量门**:`web-vue-SuMon/scripts/audit-catchup.mjs`(11 条规则)
- **API E2E**:`web-vue-SuMon/scripts/api-e2e-test.mjs`
- **UI E2E**:`web-vue-SuMon/scripts/ui-e2e-test.mjs`

## 已知遗留(留作未来 polish)

- 当前工作区在本次文档审查开始时干净；历史 dirty 文件计数仅适用于此前快照，不再作为当前遗留。
- 1 个 ui:e2e WARN(搜索 v-model 传递,可微调)
- 3 个 LONG_FILE 实际超 500 行(525-565)但 INFO 严重度,阈值 600
- ~~`openapi-system.json` 缺 `info.title` 字段~~（已补齐 2026-07-29，B-037 关闭；pre-commit 钩子相关 dirty 处理见上一条）
- 详细文档对齐性检查与修正记录:见 `docs-SuMon/Bug-fix/2026-07-25-文档对齐性修复.md`
- **Polish-6 之后仍遗留（2026-08-07）**：真实 SMTP 发送验收、真实 Agent→Java/MySQL 联合 E2E、首管理员空库真实并发、Android approved 用户云端全链路手测（均需凭据/设备，脚本已备）；Android 终端 ANSI 光标/滚屏/TUI（top/vi）仍为简化渲染；多消费者并发消费、Docker 镜像实机构建、数据库异地备份的定时调度（crontab）未配置
- **Polish-7 之后仍遗留（2026-08-12）**：真实 SMTP 发送验收、真实 Agent→Java/MySQL 联合 E2E、首管理员空库真实并发、Android approved 用户云端全链路手测（均需凭据/设备，脚本已备）；Android 终端 vi 全指令集/DEC 私有光标样式/OSC 超链接（自研模拟器边界外）；Agent 掉线期间服务端不向 monitor 推送 terminal.closed（中继增强方向）；多 JVM 实例部署、Docker 镜像实机构建、数据库异地备份定时调度（crontab）未配置
- **告警事件发布之后仍遗留（2026-08-12）**：`alert.triggered.v1` 消费者待接入（契约 §四 定义为出站事件，面向未来外部系统；当前消息堆积在 `susumonitor.alert.triggered` 业务队列等待消费者，不视为丢失）
