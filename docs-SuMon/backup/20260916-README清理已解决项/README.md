# SuSuMonitor

> 涂山苏苏主题的服务器监控平台 — 前后端 + Agent 全栈

[![Branch](https://img.shields.io/badge/branch-main-blue)](https://github.com/genhao0823/SuSuMonitor-jvav-)
[![Tag](https://img.shields.io/badge/tag-v0.5.0--cloud-green)](https://github.com/genhao0823/SuSuMonitor-jvav-/releases/tag/v0.5.0-cloud)
[![Status](https://img.shields.io/badge/Polish--7%20%2B%20%E8%BF%90%E7%BB%B4%E6%94%B6%E5%8F%A3%E5%AE%8C%E6%88%90-brightgreen)](#%E5%BD%93%E5%89%8D%E8%BF%9B%E5%BA%A6)
[![Docs](https://img.shields.io/badge/docs--alignment-2026--08--27-blue)](docs-SuMon/Develop-log/20260824-首管理员空库并发真实验收.md)

## 🚀 当前进度快照（2026-09-16）

| 项 | 状态 / 值 |
|---|---|
| GitHub 仓库 | <https://github.com/genhao0823/SuSuMonitor-java> |
| 当前基线 | `main @ ce68d79`（2026-09-16；ce68d79 为批次 8 验收链路适配修复——api-test 空库首管理员验收/兜底注册补齐一次性令牌（40310），见 `Bug-fix/2026-09-16-first-admin-scripts-bootstrap-40310.md`；此前基线 2f9f124（2026-09-16）覆盖批次 8 首管理员一次性初始化令牌 2f9f124（契约 55ea1b9 0.3.0 先行）；此前基线 5ca5a36（2026-09-16）覆盖批次 6 metrics 面访问矩阵契约收口 3a1716c(契约 0.3.1)→5ca5a36(server 修约+测试固化)；此前基线 cf93816（2026-09-15）覆盖批次 4 多盘/多网卡资源下钻三端 db464e8(契约 v1.5)→3137e68(agent)→5528a2e(server)→57ed0ae(web)→cf93816(api-test)、批次 5 M3 门禁评审文档 a12a378、批次 2 联调 UX 修复 509a193、批次 3 L2 变更类模板 d106674、M2 观察期评审报告 45f508b、进程级监控、安全加固与全功能联调 0914 批次） |
| 契约与访问矩阵 | **批次 6（2026-09-16）**：openapi-server 0.3.1——`monitor-ticket` 与 `metrics/latest`、`processes/latest`、`resources/latest`、`metrics` 历史五个端点删除不可达的 403「非 admin」响应声明（七日审计留痕的「需专项决策」项已拍板：维持「任一认证用户可读」口径，修契约不收紧代码）；新增 `MonitorTicketControllerTests` 2 例固化访问矩阵（非 admin 200 + 未认证 401）；全量契约 **49 路径 / 58 端点操作**与 Java Controller 1:1 对齐（openapi:check 7/7）；后端 `./mvnw test` **855/855** 全绿；修改前文件全部备份至 `docs-SuMon/backup/20260916-批次6-访问矩阵契约收口/` |
| MySQL IT 隔离库复跑 | **批次 7（2026-09-16）**：一次性 `mysql:8.4.11` 隔离容器（回环 13307，随机凭据仅环境变量传递不落盘，收口即销毁）全量复跑 `**/*IT.java`——11 个 IT 类 **55/55 全绿（0 Failures 0 Errors 0 Skipped）**，surefire 单测同轮 **855/855**，Flyway **36 个迁移（V1→V36）全部 success=1**、业务表 23 张；首轮暴露 11 红（3F+8E）经归因全部为 **IT 测试侧欠账**（V33 NOT NULL 审计列实体缺省 / ODKU 返回值 H2 语义 / TINYINT(1) 驱动布尔映射 / 聚合断言与软删排除及窗口语义自相矛盾），产品代码零缺陷，修复后全量复跑全绿——详见 `docs-SuMon/Bug-fix/2026-09-16-mysql-it-四类欠账修复.md` 与 `Develop-log/20260916-批次7-隔离库IT复跑.md`；修改前 IT 文件与 README 备份至 `docs-SuMon/backup/20260916-批次7-隔离库IT复跑/`（含 SHA-256 manifest）；第 20 行 AI 条目内「45 例待隔离库复跑」为 2026-09-07 历史快照，已由本批全量收口 |
| 首管理员初始化令牌 | **批次 8（2026-09-16）**：admin_initialized=0 时注册强制一次性令牌（40310/40311），校验与消费在 selectForUpdate 行锁事务内至多一次；`AUTH_BOOTSTRAP_TOKEN` 可预置（非法长度启动 fail-fast），留空自动生成 256-bit 经启动横幅投递（Jenkins initialAdminPassword 同模式，日志敏感规范的唯一显式例外）；AES-256-GCM 密文落库（V37 三列，AAD 系统维度固定上下文）；新增公开端点 `GET /api/auth/bootstrap-status`（openapi-auth 0.3.0，50 路径/59 端点操作）；Web 注册页/登录页与 Android 注册表单三端条件渲染同步。门禁：`./mvnw test` **874/874**、前端五门禁（vitest **229/229**）、openapi:check 7/7、Android `compileDebugKotlin`+`testDebugUnitTest` **128/128**；新增 `AuthBootstrapTokenMySqlValidationIT` 4 例随批次 9 隔离库复跑；契约先行（55ea1b9）→ 后端（2f9f124）→ Web（4df0bc9）→ Android（70b3201）分模块提交，修改前文件备份至 `docs-SuMon/backup/20260916-批次8-首管理员初始化令牌/` |
| 认证方式 | Git Credential Manager(Windows 凭据管理器缓存,无需明文 token) |
| 基本功能闭环 | 鉴权(JWT 72h)/服务器 CRUD/SSH 测试/Web 终端/Dashboard 真实指标/告警评估(MVP-6)/Metrics Outbox(MVP-10)/告警消息消费(MVP-11)/Agent 指标可靠投递 M1-M4（ACK/FIFO/退避重传 + NACK 死信 + 投递遥测）/告警外部通知（邮件+钉钉+Webhook，含退避重试）/监控页 ECharts 图表+阈值线/Agent 队列字节上限/增长表保留期清理（幂等接收记录 7 天、消费记录 30 天、告警记录 90 天、SSH 测试历史 90 天、**通知投递记录 90 天（V27）**、**Outbox 已发布 30 天（默认开启）**，Flyway V22/V24/V27）/告警消费多消费者并发（V15 唯一键幂等，本地 broker 验收并发 4 消费 100 条零重复）/Agent nack 有限重试（retriable_server_error + snapshot v3 持久化预算）/告警事件 Broker 发布+消费（alert.triggered.v1：Outbox 按行路由 V25 发布，alert-notifier 幂等消费并驱动外部通知，Broker 中断恢复可补发触发）/**告警恢复事件链路（alert.resolved.v1：评估器恢复时同事务登记 V26 resolved_at 落库 + Outbox 发布，alert-resolved-notifier 幂等消费驱动恢复通知，真实 broker 验收 11/11）**/**终端断线中继（Agent 断开时服务端推送 terminal.closed(agent_disconnected)，20260814 WSL E2E 真实验收）**/**心跳超时路径终端收口修复（90s 心跳超时同样收口，AGENT_HEARTBEAT_TIMEOUT_SECONDS 参数化）**/**MVP-14 监控收尾（队列积压探测与阈值告警 + 消费耗时/失败率窗口统计，ADMIN 端点 /api/system/rabbitmq/queues|consumers，真实 broker 验收 9/9）**/Docker 资产 已实现；既有本机与真实 Broker 验收通过 |
| Web 前端 | 2026-08-21 完成克制玻璃设计系统、应用壳层、Dashboard 与列表页收口；恢复服务器列表 URL/防抖/自动刷新/SSH 错误映射，搜索收敛为 OpenAPI 的 keyword 契约；移动端表格不再被固定操作列覆盖；静态测试声明 133 个（22 个 spec），历史运行记录曾 133/133；工作区改动已随 `d8eb375` 提交收口，测试需在新基线干净提交重新执行。真实账号 UI E2E 待运行时隔离凭据与后端环境。**2026-09-15 批次 2 联调 UX 修复（509a193）**：登录请求携带 silent+authAttempt（失败单条提示、不再误触发登录态回调跳转），路由守卫判定顺序改为认证→角色（未登录访问 admin 路由落 /login?redirect= 而非 /forbidden）；新增 auth/client/guards/LoginView 四个 spec，vitest 五门禁全绿。**2026-09-15 批次 4 磁盘/网卡资源卡片（57ed0ae，协议 v1.5）**：监控页新增「磁盘 / 网卡」卡片（每挂载点容量条 + 分网卡 RX/TX 速率），`getResourcesLatest` REST 初载（404 转空态）+ `metrics.update` 可选 `resources` 节点覆盖刷新；新增 ResourcesCard/api-metrics 两 spec，vitest **219/219**、五门禁全绿（openapi-server 0.3.0，18 endpoints）。 |
| AI 能力 | **四块均已实现、默认全部关闭**：① **只读诊断 MVP**（2026-08-31~09-03，`module/ai`）：`POST /api/ai/diagnoses`（admin-only，`AI_ENABLED` 默认关闭、关闭时端点 404 零触达）；白名单脱敏上下文 + OpenAI-compatible provider（HTTPS 默认，`AI_ALLOW_INSECURE_HTTP` 显式放宽明文）+ 结构化输出严格校验 + 确定性降级；按管理员限流/按天 token 预算/有界重试；V28 最小审计（不存原文）+ 30 天清理；真实网关 E2E 四路径验证（见 `20260903` 开发日志），收口时 Java 641 单测、26 MySQL IT 全绿。② **AI 命令域 M1**（2026-09-04，`module/command` + Agent `internal/command`）：`/api/ai/commands`（suggestions/runs/approve/reject/templates，admin 审批制）+ V29 `ai_command_runs` 审计；`command.execute`/`command.result` 经 `/ws/agent` 下发回收；Go Agent 8 组 L1 只读白名单模板 argv 直执行（无 shell、超时/截断/脱敏）；`AI_COMMAND_ENABLED` 默认 false；真实 Agent E2E 见 `20260904` 开发日志。③ **告警智能解释 F1**（2026-09-05，`module/ai`）：`ai.alert.explanation.requested.v1` 事件 + ai-explainer 消费者 + V30 `ai_alert_explanations` + 回看端点 `GET /api/alerts/records/{id}/explanation`；`AI_EXPLANATION_ENABLED` 默认 false。④ **运维问答 F2**（2026-09-07，`module/ai` + Spring AI 1.0.0）：`POST /api/ai/qa`（admin，`AI_QA_ENABLED` 默认 false）；Spring AI ChatClient + Tool Calling 驱动 6 个只读工具（服务器状态/最新指标/指标历史/告警记录/命令模板白名单/命令提案——提案仅创建 pending_approval 绝不执行）；独立限流与日 token 预算；工具版失败回退无工具单次调用、再失败降级确定性摘要；V31 最小审计（question_hash + tool_calls_json）。测试口径：2026-09-07 F2 收口 `mvnw test` 718/718 全绿（新增问答 28 例；10 个 MySQL IT 文件 45 例待隔离库复跑）。⑤ **M2 观察期评审报告**（2026-09-15，`openapi-command` 0.2.0）：`GET /api/ai/commands/observation-report`（admin，窗口 1-90 天缺省 14）——`ai_command_runs` 查询时聚合 + 评审基线 v1 五项核对（样本量/auto 失败率/超时率/高风险自动执行不变量/过期率），passed 三态与 overall 结论；Web 命令域页新增「评审报告」Tab；只读、Flyway 零迁移、随 `AI_COMMAND_ENABLED` kill switch 同生灭；后端 839/839、前端 195 全绿。⑥ **L2 变更类模板准入**（2026-09-15，command-protocol-v1 §五 双层模板表）：模板清单 8→10——新增 `systemctl_reload`（`systemctl reload {unit}`，unit 白名单 64 长不含冒号）与 `journalctl_vacuum`（`journalctl --vacuum-time={days}d`，days 限 1-999），risk=medium；Go 侧 `replaceArgv` 与 Java 统一为子串替换语义（值先过白名单正则完整匹配，仍单 argv 元素无 shell）；Java 注册表镜像同步并补风险分级不变量（L1 全 low / L2 全 medium / 无 high）；默认自动审批策略 `enabled=0` 不变，medium 是否放开由管理员结合 ⑤ 观察报告决策；顺带修复存量缺陷：Agent 输出截断上限未接线 `MaxOutputBytes`、alpine 容器测试模板 dd 路径（97c3620）；Agent 容器 test/vet 全绿、后端 840/840、前端 openapi:check 7/7。⑦ **F3 定时健康报告与自动审批事后通知**（2026-09-12，`module/ai`+`module/command`，契约 openapi-ai）：`AI_REPORT_ENABLED` 默认 false、随 `susumonitor.ai.report.enabled` 装配（关闭时端点 404）——`AiHealthReportScheduler` 按 `AI_REPORT_GENERATE_CRON`（默认每日 07:30）聚合窗口事实生成健康报告（LLM 摘要，预算耗尽等降级为 degraded 并带 error_code 如 42906）；admin 端点 `GET /api/ai/health-reports`（分页）/`GET /api/ai/health-reports/{id}`/`POST /api/ai/health-reports/generate`；V34 `ai_health_reports`，审计保留 90 天（`AI_REPORT_AUDIT_RETENTION_DAYS`）、独立限流（默认 10 次/小时）；Web 新增「AI 健康报告」页（9a0b9cd）与 Android 列表/详情对接（9d86ced）。同批补齐 `CommandAutoApprovalNotifier`：auto 审批的命令到达终态后按渠道（邮件/钉钉/Webhook，`susumonitor.ai.command.auto-approval.notify-*`）尽力而为推送结果通知——与告警主通知链路分离，失败只记日志不重试，绝不影响命令状态机。 **非目标**：无终端/SSH/写操作（命令域仅限 L1 只读模板且默认关闭），无 RAG/自主运维；工具仅限平台注册的只读白名单；AI 永不使用 `terminal.*`。 |
| Android App | **阶段一+阶段二+Polish-7 已完整实现（2026-08-12）**：`app-kt-SuMon/`（Kotlin + Compose）登录/注册、服务器列表/详情/排序、实时指标（WS + OkHttp pingInterval 心跳）、告警记录（通知深链到告警 Tab）、DataStore 通知开关持久化、前台服务告警通知、SSH 终端（实际尺寸 resize + 功能键行 + 物理键盘 Ctrl + **自研 ANSI 终端模拟器**：增量 CSI 解析/双屏/滚动回退/256 色/备用屏，支持 top/htop 类 TUI + **断线自动重连**：指数退避自动重开）；历史提交记录为 89 个单测，终端改动已随 `d8eb375` 提交收口，测试数需在新基线干净提交重新统计；云端全链路手测待真机 |
| 云端部署 | HTTPS/WSS 生产入口与反向代理资产已具备；2026-07-31 的明文 HTTP 联调仅作为历史验收记录保留，禁止作为生产配置。后端 `18080`、RabbitMQ `5672` 仅供内网/回环访问，Agent 不监听入站端口。 |
| 监控快照面 | 进程快照（v1.4）与磁盘/网卡资源快照（v1.5）三端已通：Agent 采集→`metrics.update` 捎带→REST latest（90 秒内存新鲜窗口、不落库）；后端 **853/853**、Agent 容器 test/vet 全绿。**2026-09-15 生产部署与真实链路验收**：新 jar（md5 `e1d8aad2`）已部署内网应用机（旧 jar 备份 `*.bak-20260915-pre-resources`），Flyway 36 迁移校验通过、5.2s 就绪；对 `https://genhaosan.online` 全链路 verify-resources **8/8**、verify-processes 回归 **7/7** 通过；Agent 三机心跳秒级恢复，旧版 Agent（tx4）兼容 404 符合协议。 |
| 已知遗留 | 真实 Agent+Server 断网/重启/ACK 联合 E2E、真实 SMTP 发送、Monitor 慢消费者 1011 背压、Android App 云端全链路手测、数据库异地备份定时调度（crontab）仍需外部环境；多 JVM 的 Agent/Monitor/Terminal 连接状态和事件广播仍未分布式化。首管理员空库并发已于 2026-08-24 完成真实验收（conc=8 与 conc=20 通过，见 `Develop-plans/20260824-首管理员空库并发真实验收.md`）；Android 终端 vi 全指令集/DEC 私有光标样式/OSC 超链接为自研模拟器边界外。 |

> 本节反映 2026-09-06 的当前仓库基线；下方带日期的“对齐说明”和“收口”段落均为历史记录（原文保留，当前值以本快照和 RC1 计划为准）。AI 只读诊断的详细实现状态与威胁模型见 `docs-SuMon/Protocol-SuMon/ai-data-flow-threat-model.md` 与 `docs-SuMon/Develop-plans/20260830-大模型只读诊断MVP开发计划.md` §八。

> **文档进度对齐说明（2026-07-25 修订，仅文档层）**
>
> 本节保留为 2026-07-25 历史对齐记录；当前状态以顶部 2026-08-27 快照和后续带日期记录为准。
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
> **总体状态（2026-08-01 文档对齐修订，仅文档层，逐项核对代码事实；2026-08-27 注：本段为历史修订记录，数字已被顶部快照取代——当前实际值为 OpenAPI 5 文件 31 路径/36 端点操作、WS 协议 v1.3、dev-log 143 篇、Develop-plans 31 篇、Flyway V1-V27）**：
> 本次修订把"仓库结构/启动指南/协议工具"等历史段与代码事实对齐：OpenAPI 5 文件 31 端点（含 `openapi-alert.json`）、WS 协议 v1.2、views 13 个页面组件、dev-log 90+、Flyway V1-V18、JWT 默认 72h；Agent 启动改为纯环境变量方式（无 `--config` 命令行参数）；MVP-10/MVP-11 已合入 `main` 跟踪。
>
> **总体状态（2026-07-31 对齐说明，详见 `docs-SuMon/Use-manual/README.md`）**：
> **MVP-8 运维文档完成（2026-07-31，历史记录）**：Use-manual 手册系列落地（Server 部署安装 / 升级与回滚 / 备份与恢复 / 安全检查 / RabbitMQ 运维 + Go-Agent 手册索引）；配套 `deploy/backup.sh` 与 `deploy/restore.sh`（补齐 DEPLOYMENT.md 明言的"无备份脚本"空白）；恢复与安全检查两大空白已系统化。该历史记录中的 TLS/HTTPS 待办已由后续加密入口验收覆盖，当前生产仍必须使用 HTTPS/WSS。
>
> **总体状态（2026-08-01 对齐说明，详见 `docs-SuMon/Develop-log/20260801-MVP11-收口.md`）**：
> **MVP-11 收口完成（2026-08-01，历史记录）**：Outbox 默认轮询间隔已由 1000ms 调整为 **200ms**（当前代码默认值）；DLQ 受控重放工具落地（`replay-dlq.mjs`，幂等重放 + 防循环）；Broker 停机消费侧重连三阶段真实验收 PASS（停机后端存活/ready 50301、恢复后发布器补发 + 消费者自动重连补消费、状态机正确、6/6 消息无丢失）。
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
> 本次修订把下方”未验证 / 未实现 / 部署资产缺口”清单与代码事实逐项对齐，已闭环项见各清单内的划线注明：**公网 HTTPS/WSS 部署已具备；历史明文 HTTP 联调（2026-07-31）仅供追溯，禁止作为生产配置**（2026-07-31，腾讯云 OpenCloudOS，前端+后端+Agent 端到端，见 `docs-SuMon/Handoff-SuMon/20260731-云端部署调试交接.md`；后续生产入口须按实际域名重新验收）；**MVP-6 告警前端已收口**（2026-07-27 Sprint 0-7，真实 HTTP/WS 端到端链路 2026-07-28 验收通过）；**MVP-7 T4 xterm.js 前端已完成**（2026-07-28 最小可用版本）；**T5 部署资产已补齐**（Maven Wrapper / `application-prod.yml` / systemd Unit / Nginx 站点配置，2026-07-27）；**B-005/B-006/B-007 已闭环**（Agent 采集上报接入 + `build-linux` + Linux amd64 二进制实测）；**B-037/B-038 已收口**（`openapi-system.json:info.title` 补齐 + 前端 4 端点契约封装与真实浏览器联调，2026-07-29）；**后端限流 / CORS 已实现**；**Android App 核心监控版 MVP 已实现**（2026-08-05，`app-kt-SuMon/`，见 `docs-SuMon/Develop-log/20260805-Android-App-核心监控版MVP.md`）。仍属”未验证/未实现”：首管理员空库并发、多 JVM AFTER_COMMIT 事件跨实例、Monitor 1012 真实背压、数据库备份恢复演练、告警外部通知渠道、Android App 云端全链路手测（待设备）。

## 项目简介

SuSuMonitor 是一套**前后端 + Agent 全栈**的服务器监控系统,主题采用涂山苏苏(狐妖小红娘)IP。包含 Web 端控制台(基于 Vue 3 + Element Plus)、Java 后端(Spring Boot + MyBatis + WebSocket)、Go Agent(采集器 + WS 上报)。

## 当前进度(2026-07-22 收口)

> 本节是对齐修订保留的原文表格，未做删改。**2026-08-21 注：以下为 2026-07-22 历史快照；当前前端值：Vitest 133 测试（22 spec 文件）/ api:e2e 19 项 / ui:e2e 18 场景。**

| 阶段 | 状态 | 内容 |
|---|---|---|
| **M1-M6**(MVP) | ✅ 完成 | 鉴权 / 主布局 / CRUD / 审核 / 监控 |
| **Sprint 1** | ✅ 完成 | SSH 测试按钮接真实后端 (`/api/servers/{id}/ssh/test`) |
| **Sprint 2** | ✅ 完成 | ServerListView spark line 接真实 metrics 历史 |
| **Sprint 3** | ✅ 完成 | DashboardView spark 接真实 + 通用 `ServerSparkLine` 组件化 |
| **Polish 1-5** | ✅ 完成 | dirty 清理 / e2e 选择器 / LONG_FILE 拆分 / audit 收口 / GitHub 推送 |
| **总计** | **65+ commits / 67 dev-log / 37 单元测试 / 4 道防线** | |

### 4 道测试防线

> 2026-08-21 注：下表为历史快照（Vitest 现为 133 测试 / 22 文件；api:e2e 19 项；ui:e2e 18 场景；audit:catchup 仍 11 条规则）。

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
- Flyway V1-V9 + MySQL 8.4 隔离库迁移（历史快照；现为 V1-V30）。
- Go Agent 配置加载、WebSocket 鉴权、心跳、断线重连。
- Metrics 接收/存储/最新/历史查询、Metrics 过期清理（独立 MySQL 已验证）。
- Monitor Ticket + 实时指标推送（事务提交后 AFTER_COMMIT 推送）。
- Vue M2-M6、Vitest、ESLint、构建、`audit:catchup`、`api:e2e`、`ui:e2e`、`openapi:check`。

### 未验证（必须留痕，未来安排独立验证）

1. _(已通过：首管理员空库并发注册已于 2026-08-24 真实验收——独立空 MySQL schema 并发 8 与 20 均 PASS：全部注册成功、唯一 `admin/approved`、其余 `user/pending`、管理员可登录且 `/me` 返回 `admin/approved`、待审核用户登录 403、DB `auth_bootstrap_state` 已初始化并指向唯一管理员，见 `docs-SuMon/Develop-log/20260824-首管理员空库并发真实验收.md`)_
2. _(已通过：真实 SSH `50003`/`50002` 分类已于 2026-07-25 通过 Apifox 受控 SSHD 真实验收，详见 `docs-SuMon/Develop-log/20260725-Apifox-SSH-50003-真实验收.md`、 `20260725-Apifox-SSH-50002-真实验收.md`)_
3. _(历史记录：公网明文 HTTP 云端部署于 2026-07-31 跑通，**禁止执行/复用**；HTTPS/WSS 生产入口须按当前部署重新验收，见 `docs-SuMon/Handoff-SuMon/20260731-云端部署调试交接.md`)_
4. **多 JVM 实例下 AFTER_COMMIT 事件跨实例推送** — `MonitorTicketService` / `AgentConnectionRegistry` / `MetricsCleanupService` 全部为单 JVM 内存状态。
5. _(已通过：`Makefile:build-linux` 已补（B-007 关闭），`agent-go-SuMon/bin/susumonitor-agent-linux-amd64` 已交叉构建并在云端真实运行（明文 WS，2026-07-31 历史验收，**禁止复用**）；公网 HTTPS/WSS 长连接须按当前域名重新验证)_
6. _(已通过：`PUT /api/servers/{id}/ssh/host-key` 首次确认/轮换已实现，通过 Apifox 9 用例 27 断言（2026-07-20）+ 前端真实浏览器联调（2026-07-29，B-038 收口）；"自动化强制校验"策略仍待评估)_
7. _(已通过：spark line 已接真实 metrics 历史（Sprint 2/3 收口）；仅"SSH 测试历史卡"（`DashboardSshCard`）仍为占位，等待 SSH test history 接口)_
8. _(已通过：前端已补齐 4 个未签端点契约封装并以真实浏览器联调收口（2026-07-29，B-038 关闭）；`openapi-system.json:info.title` 已补齐（B-037 关闭）)_

### 未实现（计划中或结构性缺口）

1. _(已补齐：`server-java-SuMon/mvnw` + `mvnw.cmd`，2026-07-27 T5，`mvnw test` 326 全过)_
2. _(已补齐：`server-java-SuMon/src/main/resources/application-prod.yml`，2026-07-27 T5)_
3. ~~**Dockerfile / docker-compose** — 全仓 0 命中。~~（**已补齐 2026-08-05**：三端 Dockerfile + 根 `docker-compose.yml`；**镜像实机构建 2026-08-16 完成**：本机 WSL2 Docker 三镜像构建 + compose 全栈实跑验收 PASS，见 `docs-SuMon/Develop-log/20260816-Docker镜像实机构建.md`）
4. _(已补齐：`server-java-SuMon/deploy/susumonitor-server.service`，2026-07-27 T5)_
5. _(已补齐：`server-java-SuMon/deploy/nginx-susumonitor.conf.example` + `susumonitor-vhost.conf`，2026-07-27 T5，云端已生效)_
6. ~~**数据库备份脚本** — `scripts/` 仅本地开发脚本。~~（**已补齐**：`server-java-SuMon/deploy/backup.sh`+`restore.sh`（2026-07-31）、`scripts/remote-backup.sh` 异地加密备份（2026-08-07）；crontab 定时调度仍未配置）
7. _(已实现：2026-07-27 Sprint 0-7 收口，告警记录页 `/alerts/records`、告警规则页 `/alerts/rules`、菜单挂载、`alert.push` WS 消费；真实端到端链路 2026-07-28 验收通过)_
8. _(已实现：T4 xterm.js 前端最小可用版本 2026-07-28，路由 `/terminal/:serverId`；T5 云端部署已验证（明文 HTTP，2026-07-28 历史记录，**禁止作为生产配置**）、T6 家庭 Linux 主机部署仍待验)_
9. _(MVP-9 已于 2026-07-31 收口：性能基线 7 场景 PASS + 数据所有权收口 + RabbitMQ 契约冻结；MVP-10（2026-07-31）/MVP-11（2026-08-01）已收口；MVP-12~14 的微服务拆分仍规划中（对应能力已在单体内实现）)_
10. _(已实现：**Android App 核心监控版 MVP** 2026-08-05，`app-kt-SuMon/`——登录/注册、服务器列表/详情、实时指标（WS）、告警记录、前台服务告警通知；`gradlew assembleDebug` + 18 单测全绿；云端全链路手测待设备，见 `Develop-log/20260805-Android-App-核心监控版MVP.md`)_
11. _(限流 / CORS 已实现：`AgentConnectionLimiter` / `AgentMessageRateLimiter` / Monitor 背压 + `config/CorsConfig.java`；WebSocket Origin 白名单策略仍待评估)_

### 部署资产缺口清单（必须在新分支中补齐后，才可上线公网）

> 本表为 2026-07-25 快照；其中 6 项已于 T5（2026-07-27）补齐并云端实测，见"现状"列标注。

| 缺口 | 现状 | 阻碍 |
|---|---|---|
| Maven Wrapper | ~~缺失~~ → **已补齐**（2026-07-27 T5，`server-java-SuMon/mvnw`） | CI / 跨机器构建可复现（`mvnw test` 326 全过） |
| `application-prod.yml` | ~~缺失~~ → **已补齐**（2026-07-27 T5，`src/main/resources/application-prod.yml`） | 生产配置已就绪 |
| Dockerfile + docker-compose | ~~缺失~~ → **已补齐**（2026-08-05）→ **实机构建完成**（2026-08-16，三镜像构建 + compose 全栈实跑 PASS） | compose 首跑调优已落地（healthcheck/TZ/JVM 内存/根 `.env.example`） |
| Java systemd Unit | ~~缺失~~ → **已补齐**（2026-07-27 T5，`server-java-SuMon/deploy/susumonitor-server.service`） | 进程监管和优雅停机已就绪 |
| 完整宝塔 Nginx 站点配置 | ~~缺失~~ → **已补齐**（2026-07-27 T5，`deploy/nginx-susumonitor.conf.example` + `susumonitor-vhost.conf`） | 历史明文 HTTP/WS 联调（2026-07-31）仅供追溯，**禁止作为生产配置**；生产入口使用 TLS 并按实际域名现场核验 |
| 数据库备份脚本 | ~~缺失~~ → **已补齐**（`deploy/backup.sh`+`restore.sh`、`scripts/remote-backup.sh`） | crontab 定时调度未配置 |
| `agent-go-SuMon/Makefile:build-linux` target | ~~缺失（B-007）~~ → **已补齐**（`Makefile:6`；`bin/susumonitor-agent-linux-amd64` 已实测 + 云端真实运行） | Linux 二进制可构建 |
| `ssh_host_key_fingerprint` 自动化 | V8 字段 + `PUT /api/servers/{id}/ssh/host-key` 接口已实现并通过 Apifox 9 用例 27 断言（2026-07-20）+ 前端真实浏览器联调（2026-07-29） | SSH 测试已闭环；终端已接入 xterm.js（T4，2026-07-28） |

### 文档与代码事实不一致项（本次对齐已修正或留痕）

| 项 | 修订 |
|---|---|
| JWT 有效期 24 vs 72 小时 | 以配置键 `susumonitor.jwt.expire-hours`（默认 72）与 `AppProperties.java` 为准；同步 `.env.example` 与 `本机开发环境配置.md` |
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
│   │   ├── components/    # 19 个 SFC(含 PageHeader / ServerSparkLine / ServerFormDialog / Dashboard* / MetricsLineChart 系列等)
│   │   ├── api/           # 9 个 HTTP 模块(auth / system / server / agent-token / metrics / alert / admin / websocket 等)
│   │   ├── stores/        # Pinia stores + 3 个 spec
│   │   ├── services/      # WebSocket 客户端(Monitor 通道 + 终端复用)
│   │   ├── composables/   # useRouterLoading / useDebouncedRef + 2 spec
│   │   ├── utils/         # format + animate + 2 spec
│   │   ├── types/         # API 类型 + error-code + metrics
│   │   └── router/        # index + guards
│   ├── scripts/           # 工具(openapi / audit / api-e2e / ui-e2e)
│   └── vitest.config.ts
├── server-java-SuMon/       # Java 后端(18080 端口)
│   └── src/main/java/com/susumonitor/server/
│       ├── common/            # 统一响应/错误码/异常/请求链路
│       ├── config/            # AppProperties / RabbitMQ 拓扑 / 时钟 / CORS
│       ├── scheduler/         # 13 个清理与发布定时任务
│       ├── module/auth/         # register / login / me / logout
│       ├── module/server/       # CRUD + SSH test + status + Agent Token
│       ├── module/metrics/      # latest + history + Outbox
│       ├── module/alert/        # 评估/规则/记录/通知/消费(triggered+resolved)
│       ├── module/admin/        # users page/search / batch approve-reject / single approve-reject
│       ├── module/system/       # health / ready
│       ├── module/terminal/     # 终端会话元数据与配额
│       ├── module/ai/           # AI 只读诊断 + 告警智能解释(默认关闭)
│       ├── module/command/      # AI 命令域 M1 审批制(默认关闭)
│       ├── websocket/           # Agent / Monitor / Ticket / 终端中继
│       ├── security/           # JWT + AES-GCM + SSH outbound
│       └── ssh/                # SSH connection tester
├── agent-go-SuMon/          # Go Agent(独立进程,部署到目标服务器)
│   ├── cmd/susumonitor-agent/main.go
│   └── internal/
│       ├── collector/      # gopsutil 采集
│       ├── reporter/       # WS 上报
│       ├── metricbuffer/   # 未确认指标 FIFO 持久缓冲(死信/遥测)
│       ├── wsclient/       # 客户端连接(重连 + 心跳)
│       ├── terminal/       # Linux PTY 会话承载
│       ├── command/        # 受限命令执行器(L1 白名单模板,仅 Linux)
│       └── config/         # 配置加载
├── app-kt-SuMon/            # Android App(Kotlin + Compose,阶段一+二,自研 ANSI 终端模拟器)
│   └── app/src/main/java/com/susumonitor/
│       ├── api/            # Retrofit 接口 + WsClient + 拦截器
│       ├── data/           # DTO + Repository + SessionStore + 帧解析
│       ├── di/             # Hilt 模块
│       ├── service/        # 前台监控服务 + 通知
│       ├── ui/             # Compose 页面(login/dashboard/servers/alerts/terminal)
│       └── util/           # 格式化/错误码/常量
├── docs-SuMon/              # 项目文档
│   ├── Develop-log/         # 148 篇 dev-log(实施记录)
│   ├── Develop-plans/       # 34 个 plan(规划)
│   ├── OpenApi-SuMon/       # 7 个 OpenAPI 契约 JSON(auth / server / system / admin / alert / ai / command),共 40 路径 / 46 端点操作
│   ├── Protocol-SuMon/     # WebSocket 协议(v1.3) + 消息契约 + RabbitMQ 拓扑
│   ├── Bug-fix/             # 9 篇 bug 修复记录 + README 索引
│   ├── Difficulty-log/      # 9 篇故障排查记录
│   ├── Handoff-SuMon/       # 3 篇交接文档
│   ├── Introduction/        # 项目解读系列(00-07)
│   ├── Use-manual/          # 运维手册系列(7 本)
│   ├── 本机开发环境配置.md   # 本机开发约定(JWT/AES/SSH/Metrics/Outbox 环境变量)
│   └── Summary-Technology/  # 项目架构总览
├── api-test/                # API 集成测试(Apifox + curl + WS 验证,23 个 mjs + 4 个 ps1 + 1 sh + 1 py)
├── scripts/                 # 脚本(初始化 / 数据库 / WSL 集成 / 异地备份)
├── local/                   # 本机工具(rabbitmq/erlang)与各批次备份(不入库)
├── docker-compose.yml       # mysql + rabbitmq + server + web (+agent profile，2026-08-16 实机构建通过)
├── server-java-SuMon/pom.xml
└── api-test/package.json
```

## 启动指南

### 前置

- Node.js >= 18.18
- npm >= 9
- JDK 21(server)
- Go 1.23+(agent，`go.mod` 要求 1.23)
- MySQL 8.4(server)

### 前端(`web-vue-SuMon/`)

```bash
npm install
npm run dev                # http://127.0.0.1:5173
npm run test               # 单元测试
npm run audit:catchup      # 11 条规则静态扫描
npm run api:e2e            # HTTP 19 项检查
npm run ui:e2e             # 浏览器 18 场景
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
- **前端**:~~Sprint 5+（搜索 / 批量审核）~~（**已完成 2026-08-02**：用户审核页远端搜索/分页/批量选择 + 状态 Tabs + 批量失败明细 + 告警逃逸窗口 confirm_count 控件，vitest 118 全绿）；剩余候选：按状态管理增强、ECharts 图表能力增强、T4 Web SSH 终端增强
- **隔离环境验收**（**已完成 2026-08-02**）：verify-mvp11（C1-C4 + 失败留痕 DB 断言）、verify-admin-batch（18 项）、verify-go-agent-reconnect（6 checks）、verify-mvp11-broker-down（B1-B7）四脚本全 PASS；RabbitMQ 凭据仅通过验证环境变量注入，本文不记录口令，见 `Develop-log/20260802-隔离环境端到端验收.md`
- **Agent 指标可靠投递**（**M1-M4 已完成 2026-08-05**）：M1 Server `metrics.ack` 入口确认 + M2 Agent 完整帧持久化 FIFO + M3 同 UUID 断线/ACK 超时重传、指数退避/equal jitter、积压重放节流 + **M4** Server 永久拒绝分类 `metrics.nack`（invalid_metrics_payload / stale_collected_at / server_not_found）、Agent 本地持久化死信（snapshot v3，v1/v2 自动迁移）、心跳投递遥测（pending/bytes、oldest、drop、dead-letter）→ Flyway V19 → 状态接口 → 服务器详情页展示；loopback fixture 15 项 PASS，真实 Agent + Java/MySQL 独立 schema 场景仍需使用临时验证凭据运行；队列字节上限已在当前代码中支持，见 `Develop-log/20260805-Agent指标可靠投递（NACK死信与投递遥测）.md`
- **协作**:在 GitHub 上创建 PR / 提 issue
- **Polish-6 三端收尾**（**已完成 2026-08-07**，commit `75b41e5`→`29effc0`）：HTTPS 迁移后 agent 配置核对与全库明文 URL 清扫；Android 告警通知深链到告警 Tab / 通知开关 DataStore 持久化（前台服务联动）/ 服务器列表排序选择器 / WS 半开连接检测（OkHttp pingInterval）/ 终端基础增强（实际尺寸 resize + 功能键行 + 物理键盘 Ctrl）；数据库异地备份脚本 `scripts/remote-backup.sh`（云端备份→拉回→AES-256 加密落盘，含 backup.sh CRLF 修复）；Web 告警通知投递历史详情弹窗（新增 `GET /api/alerts/records/{id}/notifications`，OpenAPI alert 6→7 端点）；顺带恢复 Web lint 零警告基线。详见 `docs-SuMon/Develop-plans/20260807-Polish-6-三端全面收尾.md` 与各模块 Dev-log（`20260807-Polish6-M*.md`）
- **Polish-7 遗留收尾**（**已完成 2026-08-12**，commit `c2672c8`→收口）：20260810 WIP checkpoint（SSH 测试历史 V23 / 主机密钥一键信任 / 超时分类 50400 / Swagger 放行）；SSH 测试历史保留期清理（V24 + CleanupService/Scheduler）；Android 自研 ANSI 终端模拟器（增量 CSI 解析/双屏/滚动回退/256 色/备用屏 + 逐格渲染）；Android 终端断线自动重连（指数退避重开 + UI 提示）；Agent metrics.nack 有限重试（retriable_server_error + snapshot v3 持久化预算）；告警消费多消费者并发（本地 broker 验收：并发 4 消费 100 条零重复 + DLQ 分类正确）。详见 `docs-SuMon/Develop-plans/20260812-Polish-7-遗留收尾.md` 与各模块 Dev-log（`20260812-Polish7-M*.md`）
- **RabbitMQ 告警事件全链路 + 终端断线中继 + 运维收口**（**已完成 2026-08-13~16**）：
  - 终端断线中继增强（08-13/14）：Agent 断开时 Java 中继推送服务端生成的 `terminal.closed(agent_disconnected)`；WSL 真实 E2E 验收 `TERMINAL_FLOW_CONTROL_INTEGRATION_OK`（`20260813-M1-终端断线中继增强.md`、`20260814-终端断线中继E2E验收.md`）
  - 心跳超时终端收口修复（08-15）：90s 心跳超时路径同样收口中继（先收口再移除注册表），`AGENT_HEARTBEAT_TIMEOUT_SECONDS` 参数化，WSL E2E 验收（`20260815-心跳超时终端收口修复.md`）
  - alert.resolved.v1 恢复事件链路（08-15）：V26 `resolved_at` 落库 + Outbox 发布 + `alert-resolved-notifier` 幂等消费驱动恢复通知 + WS 推送，真实 broker 验收 11/11（`20260815-alert.resolved恢复事件链路.md`）
  - 运维收口（08-15/16）：V27 通知投递保留期清理（默认开启 90 天）+ Outbox 已发布清理默认开启 + poll 默认对齐 200ms，550/550 单测 + 19/19 真实 MySQL IT（`20260815-运维收口-通知清理与配置一致性.md`）

## 协议 / 工具

- **OpenAPI 契约**: `docs-SuMon/OpenApi-SuMon/{openapi-auth,server,system,admin,alert,ai,command}.json`（7 个文件 / 49 路径 / 58 个端点操作）
- **WebSocket 协议**:`docs-SuMon/Protocol-SuMon/websocket-protocol.md`(v1.3)
- **后端 OpenAPI 自动化**:`web-vue-SuMon/scripts/check-openapi.mjs`(pre-commit 钩子)
- **代码质量门**:`web-vue-SuMon/scripts/audit-catchup.mjs`(11 条规则)
- **API E2E**:`web-vue-SuMon/scripts/api-e2e-test.mjs`
- **UI E2E**:`web-vue-SuMon/scripts/ui-e2e-test.mjs`

## 已知遗留（当前基线）

- 真实 Agent→Server 断网/重启/ACK 联合 E2E、真实 SMTP 发送、Monitor 1011 慢消费者背压和 Android 真机云端联调仍需外部环境。
- 多 JVM 下的连接注册表、订阅和终端中继仍未完成分布式化；数据库异地备份脚本已有，crontab 调度仍未配置。
- JWT 默认有效期 72h 且无 refresh 机制：Redis 未启用时登出为无状态空操作（token 到期前无法吊销）。为兼顾无 refresh 下的使用体验暂不缩短默认值，部署方可经 `JWT_EXPIRE_HOURS` 调低并启用 `REDIS_ENABLED=true` 获得真实吊销能力。
- ~~首用户注册即管理员（先到先得）~~ **已由批次 8 根治（2026-09-16）**：首管理员未初始化时注册必须携带一次性初始化令牌（`AUTH_BOOTSTRAP_TOKEN` 或启动日志横幅投递，AES-256-GCM 密文落库、消费即作废；缺失 40310/无效 40311），详见部署手册 §五点五与 `Develop-log/20260916-批次8-首管理员初始化令牌.md`。
- 详细文档对齐性检查与修正记录：见 `docs-SuMon/Develop-log/20260824-首管理员空库并发真实验收.md` 和 `docs-SuMon/OpenApi-SuMon/README.md`。

## 当前待解决问题（2026-08-29 对照 main 安全审计后基线）

以下问题为本轮文档对齐与安全审计时对照代码确认的现状。**第 1-9、12 项已于 2026-08-28 修复，第 13 项于 2026-08-29 安全审计修复**，第 10、11 项为安全默认/结构性边界，按“代码最小修复+文档标注”处理：

1. ~~**告警评估异常被吞**~~ **已修复**：`AlertEvaluationServiceImpl` 不再吞单规则异常，异常向上传播使消费事务回滚并重试/DLQ（`AlertEvaluationServiceImpl.java:70-77`）。
2. ~~**告警事件 `load`/`load_avg` 契约断裂**~~ **已修复**：`AlertMetric.toEventMetric()` 将规则 `load` 映射为事件 `load_avg`，触发/恢复工厂均使用映射（`AlertTriggeredEnvelopeFactory.java`、`AlertResolvedEnvelopeFactory.java`）。
3. ~~**Metrics `collected_at` 未严格校验 UTC**~~ **已修复**：`MetricsServiceImpl.validatePayload` 拒绝非 UTC offset（`MetricsServiceImpl.java:165-171`）。
4. ~~**Web 指标历史默认分页超限**~~ **已修复**：前端 `getMetricsHistory` 默认 `page_size=100`，与后端 `@Max(100)` 一致（`web-vue-SuMon/src/api/metrics.ts`）。
5. ~~**Web PUT 服务器更新与后端全量契约不一致**~~ **已修复**：`UpdateServerRequest` 基础字段改必填，`ServerFormDialog` 提交完整基础字段并在切换认证方式时强制新主凭据（`web-vue-SuMon/src/types/api.d.ts`、`ServerFormDialog.vue`）。
6. ~~**Web 错误码常量缺失**~~ **已修复**：前端 `error-code.ts` 补 `42905/50301/50302`。
7. ~~**Web 告警记录缺 `resolved_at`**~~ **已修复**：`AlertRecord`/`AlertPushAlert` 增加 `resolved_at: string | null`。
8. ~~**Web 服务器排序缺 `status`**~~ **已修复**：`ServerQuery` 类型与 `ServerListView` URL 恢复白名单加入 `status`。
9. ~~**Go Agent UUID fallback 不符合 RFC 4122**~~ **已修复**：`crypto/rand` 失败时 fallback 仍产出合法 UUID v4（`agent-go-SuMon/internal/wsclient/message.go`），并拒绝带路径的 backend URL（`config.go`）。
10. **SSH 出站默认拒绝所有目标（安全默认，保留）**：`SSH_ALLOWED_CIDRS` 默认空，未显式配置时 SSH 测试/主机指纹观察全部返回 `40301`（`application.yml:106`）。部署时必须显式配置 CIDR，属安全默认而非缺陷。
11. **`commands` 表未接入业务（遗留结构，保留）**：V4 迁移仅建表，未发现对应 API/Service；不新增清理迁移以免破坏已应用迁移历史。
12. ~~**Android 后端地址硬编码 / WS 超时未关旧连接**~~ **已修复**：`Constants.kt` 改读 `BuildConfig`（构建时可覆盖），`WsClient.connectOnce` 超时后显式 `cancel()` 旧连接（`app-kt-SuMon`）。
13. **2026-08-29 安全审计收尾（本批修复）**：
    - ~~**WebSocket 终端通道越权**~~ **已修复**：`terminal.*` 帧仅 admin 可发，普通用户回 `40302` 且保留连接（`MonitorWebSocketHandler`），与 REST 面 SSH admin-only 策略一致。
    - ~~**告警规则通知渠道泄露**~~ **已修复**：`GET /api/alerts/rules` 对非 admin 脱敏 `notify_email/notify_dingtalk/notify_webhook`（`AlertRuleServiceImpl.toVo(exposeNotify)`）。
    - ~~**注册接口无限流**~~ **已修复**：新增独立 `RegisterRateLimiter`（内存/Redis 双实现，`REGISTER_LIMIT_MAX_ATTEMPTS` 默认 60/60s，兼容首管理员并发验收上限 20）。
    - ~~**Swagger 生产公开放行**~~ **已修复**：`application-prod.yml` 关闭 springdoc api-docs/swagger-ui。
    - ~~**Redis 无认证**~~ **已修复**：compose redis 加 `--requirepass ${REDIS_PASSWORD:-}`，server 注入 `SPRING_REDIS_PASSWORD`，`.env.example` 注明 REDIS_ENABLED=true 时必填。
    - ~~**HTTP-only 反代配置**~~ **已加固**：`deploy/susumonitor-vhost.conf` 顶部显著警告 + HTTPS/WSS 模板与 301 跳转示例，公网生产禁用明文版本。
    - ~~**logback 无条件 DEBUG**~~ **已修复**：`com.susumonitor.server: DEBUG` 限定 `springProfile local`。
    - ~~**Spring Boot 3.4.7 偏旧**~~ **已升级**：3.4.13（Spring Framework 6.2.13，覆盖 CVE-2025-35249/35250），603 单测全绿。
    - ~~**Android JWT 明文存储 / 备份泄露**~~ **已修复**：token 经 Android Keystore AES-GCM 加密落盘（`TokenCipher`，旧明文自动兼容），`allowBackup=false`。
    - ~~**Android 401 竞态误登出**~~ **已修复**：仅当本地 token 未变时才清会话，WS 升级请求 401 不触发登出（`AuthInterceptor`）。
    - ~~**Android 杂项**~~ **已修复**：WsClient 订阅集改 `CopyOnWriteArraySet`、通知 ID 改原子自增、release 开启 R8、设置页版本号读 `BuildConfig`、注册密码不一致提示、折线图 O(n²)、删死代码、`stopSelf`、登出先清本地、终端缓冲提升至 ViewModel（旋转不清屏）。
    - ~~**api-test / 文档密码残留**~~ **已清理**：3 个 mjs 脚本密码改必填环境变量，Develop-log 中 `732682` 全部替换为 `<REDACTED>`。
    - ~~**Go ACK/NACK 载荷校验与快照 UTC 校验**~~ **已修复**：见 websocket-protocol.md 第 5/6 项。

另：`api-e2e-test.mjs` 已改用当前 `/api/admin/users?status=pending` 端点并把 pending 登录断言修正为 HTTP 403/40300；首管理员验收端口已统一为 18183；`local-mysql-init.sql` 密码已脱敏为占位符；`AuthControllerTests` 时间炸弹测试已修复。

> 以上编号仅用于本文件内部引用；修复优先级见 `docs-SuMon/Develop-plans/` 后续规划。

> 下方带日期的收口段落是历史记录，不代表当前待办；历史命令和临时公网配置禁止直接执行。
