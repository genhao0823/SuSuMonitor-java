# SuSuMonitor 项目规划

**日期**: 2026-07-12  
**依据**: 根目录 `项目需求与规范.md`  
**最后核对日期**: 2026-08-01
**当前实施阶段**: **MVP-11 收口完成（2026-08-01 + 2026-08-02 加固）**；下一阶段按矩阵推进
**当前状态**（2026-08-02 对齐）：MVP-1 核心 + MVP-2 Go Agent + MVP-3 实时监控 + MVP-5A Web 主页面 + MVP-6 告警业务闭环 + MVP-7 终端（T1-T4） 已完成；**MVP-9 微服务化准备收口完成**（性能基线 7 场景、数据所有权审计、契约冻结）；**MVP-10 Metrics Outbox 收口完成**（发布侧真实 Broker 三阶段验收 PASS）；**MVP-11 告警消费侧收口完成**：消息通道幂等消费 + 评估切换 + DLQ 分类，`verify-alert-ws` 24/24 + `verify-mvp11` 17 项验收 PASS；**MVP-11 收口（2026-08-01）**：outbox 轮询 1000→200ms（告警推送延迟 -71%）、DLQ 受控重放工具、Broker 停机消费侧重连验收 PASS；**2026-08-02 加固收口**：消费失败留痕落地（`FailedConsumeRecordRecoverer` + `upsertFailed`）+ 告警逃逸窗口（`confirm_count` 连续越界确认，V18），401 测试全绿（详见 `Develop-log/20260731-MVP10-Metrics-Outbox.md`、`20260731-MVP11-Alert-消费侧.md`、`20260801-MVP11-收口.md`、`20260802-收口加固-失败留痕与逃逸窗口.md`）。仍属"未验证"：首管理员独立空库真实并发、公网部署环境、多实例及跨 JVM 事件推送、Monitor 1012 真实背压、多消费者并发消费（验收脚本已备，`verify-mvp11-concurrency.mjs` 保留为扩容前资产）。

当前状态以本节矩阵和最新开发日志为准，后文历史实施顺序不代表当前完成状态。

| 能力 | 实现状态 | 测试状态 | 本机运行时 | 部署验证 |
|------|----------|----------|------------|----------|
| Java 工程骨架 | 已实现 | 已通过 | 已验证 | 未验证 |
| 统一响应、错误码和异常处理 | 已实现 | 已通过 | 已验证 | 未验证 |
| 请求追踪 | 新契约已实现 | 单元与 MockMvc 已通过 | HTTP 已验证 | 未验证 |
| Flyway V1-V7 | 已实现 | 已校验 | MySQL 8.4 已验证 | 未验证 |
| 用户注册 | 已实现 | 已通过 | HTTP 已验证 | 未验证 |
| 首管理员并发安全 | 状态行锁已实现 | 单元测试已通过 | V7 迁移和回填已验证；空库并发未验证 | 未验证 |
| JWT 配置基础 | 已实现 | 已通过 | 已验证 | 未验证 |
| JWT 签发、解析和 Bearer 鉴权 | 已实现 | 单元与 MockMvc 已通过 | 真实 HS256 Token、Claims、me/logout 已验证 | 未验证 |
| login、me、logout | 已实现 | Service 与 MockMvc 已通过 | admin/user 成功、pending/rejected 403 和 me/logout 已验证 | 未验证 |
| 管理员审核 | 已实现 | Service 与 MockMvc 已通过 | approve/reject、重复和并发审核已验证 | 未验证 |
| 服务器 CRUD 与凭据加密 | 已实现 | 单元与 MockMvc 已通过 | HTTP 与当前开发 MySQL 已验证；独立库未验证 | 未验证 |
| SSH 安全与连接测试 | 已实现 | 已通过 | password/private_key 真实成功、`50002`/`50003` 分类于 2026-07-25 通过 Apifox 受控 SSHD 真实验收 | 未验证 |
| Agent Token 生命周期(register/rotate/revoke) | 已实现 | 已通过 | 真实 HTTP/Agent 链路已验证 | 未验证 |
| Metrics 接收、存储、最新/历史查询 | 已实现 | 已通过 | 真实 Agent + MySQL 查询已验证 | 未验证 |
| Metrics 过期清理(分批/防重叠) | 已实现 | 已通过 | 独立 MySQL 已验证 | 未验证 |
| Agent WebSocket 鉴权/心跳/上报 | 已实现 | 已通过 | 真实 Agent 运行时已验证 | 未验证 |
| Monitor Ticket + 实时指标推送 | 已实现 | 已通过 | 真实 HTTP/WebSocket/MySQL 已验证 | 未验证 |
| WebSocket 协议文档 | 已建立 | — | — | — |
| Flyway V8-V9 | 已实现 | 已校验 | 隔离 MySQL V1-V9 已验证 | 未验证 |
| Vue Web M2-M6 | 已实现 | Vitest、ESLint、构建已通过 | 浏览器/UI E2E 历史已验证 | 未验证 |

## 一、已确认决策

| 决策项 | 结论 |
|--------|------|
| Java 后端目录 | 统一使用 `server-java-SuMon/` |
| 原后端目录 | `server-susumonitor/` 不再作为正式目录，后续删除前必须备份到 `C:\Backup` |
| 服务器删除策略 | 软删除 |
| 服务器新增、修改、删除权限 | 仅 `admin` 允许 |
| 服务器查看权限 | `admin` 和普通 `approved user` 都允许 |
| SSH 连接测试权限 | 仅 `admin` 允许 |
| 当前开发范围 | 只做本机开发和调试 |
| MVP-1 依赖 | 不依赖 Docker、Redis、k8s、Prometheus、Grafana、Android |
| Java 编码规范 | 遵循《阿里巴巴 Java 开发手册》 |
| Git 本机备份仓库 | `D:\develop\Git\SuSuMonitor.git` |
| Git remote 名称 | `backup` |
| opencode skill | 只保留 `.opencode/skills`，不保留 `.codex` |
| skill 语言边界 | 保留与 Go 无关内容，Java 后端遵循 Spring Boot 与阿里巴巴 Java 规范 |
| 首管理员并发安全 | 使用认证初始化状态记录和数据库事务锁保证原子性 |
| 请求追踪 | `X-Request-ID` 由服务端生成；客户端使用受限 `X-Correlation-ID` |
| OpenAPI 权威源 | `docs-SuMon/OpenApi-SuMon/*.json` 为唯一正式契约 |

## 二、规划原则

- 先完成 MVP-1 Java 后端基础闭环，再进入 Agent、实时指标、Web 前端、告警和 Web SSH 终端。
- 当前本机开发使用 MySQL 8.4，数据库地址为 `127.0.0.1:3306`，数据库名为 `susumonitor`。
- REST API 必须保持统一响应结构、统一错误码和请求追踪规范。
- `X-Request-ID` 始终由服务端生成；客户端关联请求使用长度和字符受限的 `X-Correlation-ID`。
- 密码、JWT、SSH 密码、SSH 私钥、AES 密钥等敏感信息不得明文存储或输出到日志。
- SQL 查询禁止使用 `SELECT *`，需要明确字段。
- 数据库结构通过 Flyway 管理，脚本目录只承担本机初始化和辅助职责。
- 静态 OpenAPI JSON 是唯一正式接口契约；新增或修改 API 时先更新契约，再同步实现、测试和 API 调试文件。
- 开发活动完成后记录开发日志。

## 三、目标根目录结构

```text
SuSuMonitor/
├── .editorconfig
├── .gitattributes
├── .gitignore
├── .dockerignore
├── .opencode/
├── api-test/
├── docs-SuMon/
├── scripts/
├── server-java-SuMon/
├── web-vue-SuMon/
├── app-kt-SuMon/
├── 项目需求与规范.md
└── 远程Linux主机部署配置清单.md
```

| 路径 | 作用 | 当前阶段 |
|------|------|----------|
| `.editorconfig` | 统一编辑器编码、缩进、换行规则 | 当前需要 |
| `.gitattributes` | 统一 Git 换行符规则 | 当前需要 |
| `.gitignore` | 忽略本机配置、密钥、构建产物、依赖目录 | 当前需要 |
| `.dockerignore` | Docker 构建上下文忽略规则 | 增强阶段使用，当前可先建立 |
| `.opencode/` | 项目级 opencode skills 配置目录 | 当前需要 |
| `api-test/` | 本机 HTTP API 调试文件目录 | 当前需要 |
| `docs-SuMon/` | 项目正式文档目录 | 当前需要 |
| `scripts/` | 数据库初始化、本机辅助脚本目录 | 当前需要 |
| `server-java-SuMon/` | Java 后端工程目录 | 已初始化工程骨架，当前继续实现 MVP-1 |
| `web-vue-SuMon/` | Vue 3 Web 前端工程目录 | MVP-5 使用 |
| `app-kt-SuMon/` | Android App 工程目录 | 增强阶段使用 |
| `项目需求与规范.md` | 项目需求、规范、结构、配置汇总文档 | 当前持续维护 |
| `远程Linux主机部署配置清单.md` | 后续远程部署配置参考 | 后续部署阶段使用 |

## 四、旧目录处理

当前旧目录 `server-susumonitor/` 不再作为正式后端目录。

删除流程必须遵守以下步骤：

1. 确认 `server-susumonitor/` 当前内容。
2. 在 `C:\Backup` 下创建备份目录。
3. 将 `server-susumonitor/` 完整复制到 `C:\Backup`。
4. 确认备份成功。
5. 再删除原目录。

即使该目录为空，也必须先备份再删除。

## 五、MVP 阶段划分

| 阶段 | 内容 | 当前执行 |
|------|------|----------|
| MVP-1 | Java 后端新建、MySQL 建表、JWT 登录注册、用户审核、服务器 CRUD、SSH 凭据加密、SSH 连接测试 | 核心实现完成；首管理员空库并发、当前 SSH 分类和部署验收未收口 |
| MVP-2 | Agent 指标采集、5 秒采集频率、WebSocket 连接、心跳、注册校验 | 已实现并完成本机运行时验收 |
| MVP-3 | 指标接收、MySQL 存储、WebSocket 推送、历史指标查询、10 天数据清理 | 指标接收/存储/查询/实时推送/清理已实现 |
| MVP-4 | OpenAPI 契约基线收口、lint、Apifox 导入和实现漂移检查 | 随接口同步，阶段性收口 |
| MVP-5A | 登录、注册、仪表盘、服务器列表和详情前端，直接对接真实 API | 已实现；告警与 Web SSH 不在本阶段 |
| MVP-6 | 模块化单体告警业务闭环：规则、状态机去重、记录、查询、已读、恢复和 WebSocket 推送；暂不依赖 RabbitMQ | 已收口（2026-07-28）：后端业务闭环（Commit `7b01a60`，2026-07-25，含 Flyway `V10__create_alert_states_and_soft_delete_rules`、`AlertPushPublisher` 推送 `alert.push`、OpenAPI 6 端点）+ 前端告警页面（2026-07-27 Sprint 0-7 收口）+ 真实 HTTP/WS 端到端链路 2026-07-28 验收通过 |
| MVP-7 | SSH 后端代理、PTY、多会话、20 分钟超时和 xterm.js 前端 | T1-T4 已完成：Java 端中继、Go Agent Linux PTY、协议契约、真实 WSL 单 JVM 联调 `PTY_RELAY_INTEGRATION_OK`（2026-07-26）、T4 xterm.js 前端最小可用版本（2026-07-28）；T5 云端部署已验证（明文 HTTP，2026-07-31）；仅 T6 家庭 Linux 主机 root systemd 部署待验；Monitor 1012 真实背压未覆盖 |
| MVP-8 | 安装、启动、升级、回滚、备份恢复和安全检查文档 | 已收口（2026-07-31）：Use-manual 手册系列（部署安装/升级回滚/备份恢复/安全检查/RabbitMQ 运维）+ `deploy/backup.sh`/`restore.sh` 配套脚本；恢复与安全检查空白已补齐 |

当前 MVP-1 至 MVP-8 默认采用模块化单体架构。微服务只作为 MVP-8 之后的增强路线，不改变当前 MVP-1 的开发顺序，也不作为本机调试前置依赖。

### 五点一、微服务增强阶段规划

| 阶段 | 内容 | 当前执行 |
|------|------|----------|
| MVP-9 | 微服务化准备：模块依赖、数据所有权、RabbitMQ 异步边界与版本化消息契约、统一日志和性能基线 | 已收口（2026-07-31）：性能基线 7 场景实测 + 数据所有权收口 + 契约冻结评审；RabbitMQ 运行时属 MVP-10/11 |
| MVP-10 | `metrics-service`：Agent 指标处理、Outbox 可靠事件发布、指标存储、最新/历史查询和清理 | 已收口（2026-07-31）：V14 outbox 表 + 同事务写入 + 发布器（Confirm/Return/指数退避）+ 拓扑声明 + ready 检查；真实 Broker 三阶段验收 PASS（见 `Develop-log/20260731-MVP10-Metrics-Outbox.md`）；消费侧属 MVP-11 |
| MVP-11 | `alert-service`：通过 RabbitMQ 幂等消费指标事件，完成告警检测、状态迁移、记录和推送 | 已收口（2026-07-31 + 2026-08-01 收口）：V15 消费幂等表 + `MetricsReportedMessage` 契约解析 + `AlertMessageConsumer`（AUTO 确认 + 幂等 + 有限重试）+ DLQ 分类；`verify-alert-ws` 24/24、`verify-mvp11` 17 项验收 PASS；收口：轮询 1000→200ms（延迟 -71%）、DLQ 重放工具、Broker 停机重连验收（见 `Develop-log/20260731-MVP11-Alert-消费侧.md`、`20260801-MVP11-收口.md`） |
| MVP-12 | `ssh-service`：SSH 连接测试、SSH 会话、PTY、输入输出和超时 | 否 |
| MVP-13 | Gateway 与服务治理：统一入口、路由、配置管理、服务发现和服务间鉴权 | 否 |
| MVP-14 | 分布式运行保障：独立部署、链路追踪、集中日志、RabbitMQ 重试/死信/积压治理和回滚 | 否 |

微服务阶段的服务边界初步规划如下：

```text
auth-service       -> users
server-service     -> servers
metrics-service   -> metrics、message_outbox
alert-service     -> alert_rules、alert_records、alert_states、message_consume_records
ssh-service       -> ssh_sessions
```

当前模块化单体阶段可以暂时使用同一个 MySQL 实例，但必须在代码层面保持数据访问边界。微服务拆分后，每个服务应逐步拥有独立数据库或独立 schema，并由自身维护 Flyway 迁移。

微服务拆分顺序和验收要求：

1. MVP-9 先完成模块依赖、数据所有权、同步/异步通信边界和性能基线；冻结 RabbitMQ Exchange、Queue、Routing Key、死信队列和 `metrics.reported.v1`、`alert.triggered.v1` 契约，验证模块不直接访问其他模块的 Mapper 和数据表。
2. MVP-10 优先拆分指标服务；使用 Transactional Outbox 保证 Metrics 与待发布事件在同一 MySQL 事务中提交，通过 Publisher Confirm 和 Return 可靠发布 `metrics.reported.v1`，验证 RabbitMQ 中断时指标不丢失且恢复后可补发。
3. MVP-11 在指标事件稳定后拆分告警服务；通过 RabbitMQ 消费 `metrics.reported.v1`，验证事件消费幂等、同一规则持续越界不重复生成告警、恢复后标记 `resolved`、再次越界可生成新告警，推送失败可重试或记录。
4. MVP-12 拆分 SSH 服务；验证凭据加密、服务间鉴权、会话超时、连接上限和异常隔离。
5. MVP-13 只有在服务数量和独立部署需求明确后再引入 Gateway、服务发现和配置中心；验证路由、超时、鉴权、限流和链路追踪。
6. MVP-14 完善多服务本地启动、独立健康检查、集中日志、消息重试、死信、积压监控与重放、部署回滚和集成测试。

微服务拆分前必须完成数据库备份、数据校验、回滚方案和流量切换方案。不得只复制多个 Spring Boot 工程而忽略服务数据边界、服务间认证、超时、重试、幂等和最终一致性。

### 五点二、RabbitMQ 分阶段实施规划

RabbitMQ 用于解耦 Metrics 与 Alert，不替代 Agent/Monitor WebSocket、MySQL 查询、心跳或 SSH 交互链路。当前规划采用至少一次投递语义，消费端必须通过全局唯一 `event_id` 实现幂等；消息中禁止包含 JWT、Agent Token、SSH 凭据、数据库密码和 RabbitMQ 密码。

| 阶段 | 实施内容 | 阶段出口条件 |
|------|----------|--------------|
| MVP-6 | 先在模块化单体内完成告警规则、状态机去重、恢复、记录、已读和 `alert.push`，使用本地事务事件验证业务规则 | 首次越界生成告警、持续越界不重复、恢复后转为 `resolved`、再次越界生成新告警；RabbitMQ 仍未引入 |
| MVP-9 | 冻结 `susumonitor.events`、`susumonitor.dlx`、`susumonitor.alert.metrics`、`susumonitor.alert.metrics.dlq` 和版本化事件契约；明确至少一次投递、幂等、重试和不可重试异常边界 | 消息契约、拓扑、数据所有权、兼容策略和故障语义完成文档评审，尚不宣称运行时可用 |
| MVP-10 | Metrics 与 `message_outbox` 同事务写入；Outbox 发布器使用 Publisher Confirm、Return 和退避重试发送 `metrics.reported.v1` | RabbitMQ 停止时 Metrics 继续落库且 Outbox 保留，Broker 恢复后补发成功；普通单元测试不依赖本机 RabbitMQ |
| MVP-11 | `alert-service` 幂等消费 `metrics.reported.v1`，匹配规则并维护 `alert_states`、`alert_records` 和消费记录；产生 `alert.triggered.v1` 并发送 `alert.push` | Agent 上报到告警推送的真实链路通过；重复投递不产生重复告警；重试耗尽的消息进入 DLQ |
| MVP-14 | 增加 Outbox 清理、DLQ 查询与受控重放、队列积压告警、处理耗时与失败率监控、Broker/消费者重启恢复和滚动升级验证 | 消息积压、死信、重放、恢复和回滚均有可执行手册及真实运行时验证记录 |

RabbitMQ 故障策略固定为“存活但未就绪”：`/api/health` 只表示 Java 进程存活；`/api/ready` 同时检查 MySQL 和 RabbitMQ。Broker 不可用时应用不退出，Agent 指标继续写入 MySQL 和 Outbox，但就绪检查失败；Broker 恢复后自动补发积压事件。

RabbitMQ 首批业务拓扑规划如下：

```text
metrics-service
    -> message_outbox
    -> susumonitor.events [metrics.reported.v1]
    -> susumonitor.alert.metrics
    -> alert-service
    -> alert_records / alert_states
    -> alert.push

susumonitor.alert.metrics
    -> retry exhausted
    -> susumonitor.dlx
    -> susumonitor.alert.metrics.dlq
```

RabbitMQ 相关验证必须分层记录：单元测试验证事件序列化、规则匹配和幂等逻辑；MySQL 集成测试验证 Metrics 与 Outbox 事务边界；真实 RabbitMQ 集成测试验证拓扑、Confirm、Return、重试和 DLQ；真实 WebSocket 验证 `alert.push`。任一层通过不得替代其他层，也不得在未执行真实 Broker 测试时宣称 RabbitMQ 运行时已验证。

## 六、MVP-1 完成标准

- 后端可在当前 Windows 主机本地启动。
- 后端监听端口为 `18080`。
- MySQL 8.4 连接成功，Flyway 可初始化基础表。
- `/api/health` 和 `/api/ready` 可调用。
- 所有 REST API 返回统一 JSON 响应结构。
- 每个 HTTP 请求生成 `request_id`，响应 header 返回 `X-Request-ID`，日志记录 request_id。
- 客户端可选传入合法 `X-Correlation-ID`；服务端忽略客户端伪造的 `X-Request-ID`。
- 用户注册、登录、退出、当前用户、管理员审核流程可用。
- 首个用户自动成为 `admin/approved`。
- 首管理员初始化必须通过数据库事务锁保证原子性，空库并发注册后管理员数量严格为 1。
- 后续注册用户默认为 `user/pending`，审核通过后才能登录。
- 服务器 CRUD 可用，删除为软删除。
- 服务器新增、修改、删除和 SSH 测试仅允许 `admin`。
- 普通 `approved user` 只允许查看服务器列表、详情和状态。
- SSH 密码、私钥、私钥口令使用 AES-256-GCM 加密存储。
- 查询接口不返回 SSH 敏感凭据明文。
- SSH 连接测试接口可用，并使用统一错误响应。
- 定时任务框架和 metrics 清理任务骨架存在。
- API 调试文件和 OpenAPI 文档与已实现接口同步。
- 静态 OpenAPI JSON 通过语义校验并完成 Apifox 导入验证。

## 七、Java 后端工程结构

Java 后端工程目录固定为：

```text
server-java-SuMon/
```

目标结构：

```text
server-java-SuMon/
├── pom.xml
├── README.md
├── .env.example
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── susumonitor/
│   │   │           └── server/
│   │   │               ├── SuSuMonitorServerApplication.java
│   │   │               ├── config/
│   │   │               ├── common/
│   │   │               ├── security/
│   │   │               ├── module/
│   │   │               ├── websocket/
│   │   │               ├── ssh/
│   │   │               └── scheduler/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       ├── logback-spring.xml
│   │       └── db/
│   │           └── migration/
│   └── test/
│       └── java/
└── target/
```

| 目录或文件 | 作用 |
|------------|------|
| `pom.xml` | Maven 项目配置，管理依赖、插件、构建和测试 |
| `README.md` | Java 后端启动、配置、测试、接口调试说明 |
| `.env.example` | 本机环境变量示例，不包含真实密钥 |
| `SuSuMonitorServerApplication.java` | Spring Boot 启动入口 |
| `config/` | Web、跨域、Jackson、OpenAPI、WebSocket、加密配置 |
| `common/` | 统一响应、错误码、异常处理、分页、request_id 工具 |
| `security/` | Spring Security、JWT、登录认证、管理员权限、密码哈希 |
| `module/` | 业务模块根目录 |
| `websocket/` | WebSocket 连接、消息编解码、Agent 通道、Web 客户端通道 |
| `ssh/` | SSH 连接测试、PTY 会话、终端输入输出转发、会话关闭 |
| `scheduler/` | Agent 离线检测、metrics 清理、告警检测 |
| `application.yml` | 默认配置文件 |
| `application-local.yml` | 本机开发配置文件 |
| `logback-spring.xml` | 日志格式、日志级别、控制台和文件输出配置 |
| `db/migration/` | Flyway 数据库迁移脚本目录 |
| `target/` | Maven 构建产物目录，不提交 Git |

## 八、Java 后端技术栈

| 层级 | 技术 | 用途 |
|------|------|------|
| 开发语言 | Java 21 LTS | 后端主语言 |
| 后端框架 | Spring Boot 3.x | 应用启动、依赖注入、配置管理 |
| Web 框架 | Spring Web MVC | REST API、健康检查、就绪检查 |
| 参数校验 | Hibernate Validator | 请求参数校验 |
| 数据访问 | MyBatis-Plus | MySQL CRUD、分页查询、条件查询 |
| 数据库 | MySQL 8.4 | 当前本机开发数据库和后续主数据库 |
| 数据库连接池 | HikariCP | 数据库连接池 |
| 数据库迁移 | Flyway | 表结构初始化和版本化变更管理 |
| 认证鉴权 | Spring Security | 登录态校验、接口鉴权、管理员权限控制 |
| Token | JWT | 登录后签发 Token，接口使用 Bearer Token 鉴权 |
| 密码哈希 | BCrypt | 用户密码哈希存储 |
| SSH 凭据加密 | JDK JCA AES-GCM | SSH 密码、私钥、私钥口令加密存储 |
| WebSocket | Spring WebSocket | Agent 连接、指标推送、告警推送、SSH 终端消息 |
| SSH 客户端 | sshj | 中心服务器发起 SSH 连接、创建 PTY 会话 |
| 定时任务 | Spring Scheduling | Agent 离线检测、指标清理、告警检测 |
| JSON 序列化 | Jackson | REST API 和 WebSocket JSON 消息处理 |
| API 文档 | springdoc-openapi | 生成和维护 OpenAPI 3 文档 |
| 日志 | SLF4J + Logback | 统一日志接口和本机日志输出 |
| 构建工具 | Maven | 依赖管理、构建、测试 |
| 单元测试 | JUnit 5 | 单元测试和基础集成测试 |
| Mock 测试 | Mockito | Service、组件隔离测试 |

## 九、业务模块结构

`module/` 下按业务边界拆分：

```text
module/
├── auth/
├── admin/
├── server/
├── metrics/
├── alert/
├── dashboard/
└── sshsession/
```

| 模块 | 作用 | MVP-1 处理 |
|------|------|------------|
| `auth/` | 注册、登录、退出、当前用户信息 | 实现 |
| `admin/` | 管理员审核用户、通过、拒绝 | 实现 |
| `server/` | 服务器新增、列表、详情、更新、删除、状态查询 | 实现 |
| `metrics/` | 最新指标、历史指标、指标写入和查询 | 建表和清理骨架，接口 MVP-3 |
| `alert/` | 告警规则、告警记录、告警推送 | 建表预留，接口 MVP-6 |
| `dashboard/` | 仪表盘汇总数据 | MVP-3/MVP-5 |
| `sshsession/` | SSH 会话记录查询和关闭 | 建表预留，接口 MVP-7 |

每个业务模块内部推荐结构：

```text
模块名/
├── controller/
├── service/
├── mapper/
├── entity/
├── dto/
└── vo/
```

| 目录 | 作用 |
|------|------|
| `controller/` | REST API 入口，只处理请求参数、鉴权上下文、响应封装 |
| `service/` | 业务逻辑、事务、规则校验、跨表协作 |
| `mapper/` | MyBatis-Plus Mapper 和自定义 SQL 查询 |
| `entity/` | 数据库表映射实体 |
| `dto/` | 请求参数对象 |
| `vo/` | 响应对象 |

## 十、权限设计

| 接口 | admin | approved user | 未登录 |
|------|-------|---------------|--------|
| `GET /api/servers` | 允许 | 允许 | 拒绝 |
| `GET /api/servers/{id}` | 允许 | 允许 | 拒绝 |
| `GET /api/servers/{id}/status` | 允许 | 允许 | 拒绝 |
| `POST /api/servers` | 允许 | 拒绝 | 拒绝 |
| `PUT /api/servers/{id}` | 允许 | 拒绝 | 拒绝 |
| `DELETE /api/servers/{id}` | 允许 | 拒绝 | 拒绝 |
| `POST /api/servers/{id}/ssh/test` | 允许 | 拒绝 | 拒绝 |

其他权限规则：

- `/api/health` 公开。
- `/api/ready` MVP-1 阶段公开，用于本机调试。
- `/api/auth/register` 公开。
- `/api/auth/login` 公开。
- `/api/auth/logout` 需要登录。
- `/api/auth/me` 需要登录。
- `/api/admin/**` 仅 `admin` 允许。

## 十一、服务器软删除设计

`servers` 表采用软删除，不物理删除记录。

建议字段：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `deleted` | tinyint / boolean | `0` | `0` 未删除，`1` 已删除 |
| `deleted_at` | datetime | `NULL` | 删除时间 |
| `delete_token` | varchar(64) | `ACTIVE` | 用于支持同一 host 软删除后重新添加 |

查询规则：

| 场景 | 规则 |
|------|------|
| 服务器列表 | 只查询 `deleted = 0` |
| 服务器详情 | 只允许查询 `deleted = 0` |
| 更新服务器 | 只允许更新 `deleted = 0` |
| 删除服务器 | 设置 `deleted = 1`、`deleted_at = now()`、`delete_token = UUID` |
| Agent 关联 | 默认只关联 `deleted = 0` 的服务器 |

唯一索引建议：

```text
uk_servers_host_delete_token(host, delete_token)
```

新增服务器时：

```text
deleted = 0
delete_token = ACTIVE
```

软删除服务器时：

```text
deleted = 1
```

## 十二、数据库设计

MVP-1 需要维护基础表：

```text
users
servers
metrics
commands
alert_rules
alert_records
ssh_sessions
```

| 表 | MVP-1 作用 |
|----|------------|
| `users` | 用户注册、登录、审核 |
| `servers` | 服务器管理、Agent 预留字段、SSH 凭据密文 |
| `metrics` | 建表预留，MVP-3 写入和查询 |
| `commands` | 命令记录，增强阶段使用 |
| `alert_rules` | 告警规则，MVP-6 使用 |
| `alert_records` | 告警记录，MVP-6 使用 |
| `ssh_sessions` | SSH 会话生命周期记录，MVP-7 使用 |

关键索引：

| 索引 | 用途 |
|------|------|
| `uk_users_username` | 用户名唯一 |
| `uk_servers_host_delete_token` | 活跃服务器 host 唯一，支持软删除后重建 |
| `uk_servers_agent_id` | Agent ID 唯一 |
| `idx_metrics_collected_at` | 指标清理 |
| `idx_metrics_server_time` | 历史指标查询 |

Flyway 迁移目录：

```text
server-java-SuMon/src/main/resources/db/migration/
```

建议迁移文件：

```text
V1__create_users_table.sql
V2__create_servers_table.sql
V3__create_metrics_table.sql
V4__create_commands_table.sql
V5__create_alert_tables.sql
V6__create_ssh_sessions_table.sql
```

## 十三、配置设计

配置文件：

```text
server-java-SuMon/src/main/resources/application.yml
server-java-SuMon/src/main/resources/application-local.yml
server-java-SuMon/.env.example
```

真实密钥只放本机环境或本机配置文件，不提交到 Git。

| 分类 | 配置项 |
|------|--------|
| 应用 | `APP_ENV`、`APP_NAME`、`SERVER_PORT` |
| 数据库 | `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD`、`DB_CHARSET`、`DB_TIMEZONE` |
| JWT | `JWT_SECRET`、`JWT_EXPIRE_HOURS` |
| SSH 加密 | `AES_GCM_KEY`、`SSH_CONNECT_TIMEOUT_SECONDS`、`SSH_IDLE_TIMEOUT_MINUTES` |
| Agent | `AGENT_REGISTER_KEY`、`AGENT_HEARTBEAT_TIMEOUT_SECONDS`、`AGENT_STATUS_SCAN_SECONDS` |
| 调度 | `METRICS_RETENTION_DAYS`、`METRICS_CLEANUP_CRON`、`ALERT_CHECK_SECONDS` |

安全要求：

- `JWT_SECRET` 不硬编码、不提交。
- `AES_GCM_KEY` 不硬编码、不提交。
- `AGENT_REGISTER_KEY` 不硬编码、不提交。
- 日志不输出密码、Token、SSH 私钥、SSH 密码、AES 密钥。

## 十四、REST API 设计

统一成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

统一分页响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [],
    "total": 100,
    "page": 1,
    "page_size": 20
  }
}
```

统一错误响应：

```json
{
  "code": 40000,
  "message": "bad request"
}
```

MVP-1 接口范围：

| 模块 | 接口 | 权限 |
|------|------|------|
| 系统 | `GET /api/health` | 公开 |
| 系统 | `GET /api/ready` | 公开 |
| 认证 | `POST /api/auth/register` | 公开 |
| 认证 | `POST /api/auth/login` | 公开 |
| 认证 | `POST /api/auth/logout` | 登录用户 |
| 认证 | `GET /api/auth/me` | 登录用户 |
| 管理员 | `GET /api/admin/users/pending` | admin |
| 管理员 | `PUT /api/admin/users/{id}/approve` | admin |
| 管理员 | `PUT /api/admin/users/{id}/reject` | admin |
| 服务器 | `GET /api/servers` | approved user / admin |
| 服务器 | `POST /api/servers` | admin |
| 服务器 | `GET /api/servers/{id}` | approved user / admin |
| 服务器 | `PUT /api/servers/{id}` | admin |
| 服务器 | `DELETE /api/servers/{id}` | admin |
| 服务器 | `GET /api/servers/{id}/status` | approved user / admin |
| SSH | `POST /api/servers/{id}/ssh/test` | admin |

## 十五、认证与用户审核设计

| 项 | 规则 |
|----|------|
| username | 3 到 50 位，允许字母、数字、下划线 |
| password | 8 到 64 位 |
| password_hash | 使用 BCrypt 存储 |
| JWT 有效期 | 默认 72 小时（`JWT_EXPIRE_HOURS`，代码 `application.yml:46` 默认 `72`），通过配置项调整 |
| 首个用户 | 自动成为 `admin/approved` |
| 后续注册用户 | 默认为 `user/pending` |
| 登录限制 | 只有 `approved` 用户可以登录 |
| pending/rejected 用户 | 登录返回 403 |
| logout | MVP 阶段服务端无状态退出，前端删除 Token |

首管理员原子性规则：

1. 新增单行认证初始化状态记录，并通过 `SELECT ... FOR UPDATE` 在事务内串行化首次管理员判断。
2. 迁移初始化状态时必须兼容已有 `admin/approved` 用户，禁止迁移后再次产生首管理员。
3. 用户插入和初始化状态更新必须在同一事务中提交或回滚。
4. 空测试数据库中两个不同用户名并发注册后，`admin/approved` 数量必须严格等于 1，另一个用户必须为 `user/pending`。
5. 不再使用 `users` 表计数作为首管理员的最终判断依据。

JWT 契约：

| 项 | 规则 |
|----|------|
| 算法 | 固定 HS256 |
| `iss` | `susumonitor` |
| `aud` | `susumonitor-api` |
| `sub` | 用户 ID 字符串 |
| 自定义声明 | `username` |
| 时间声明 | `iat`、`exp`，允许 30 秒时钟偏差 |
| Token ID | UUID 格式 `jti` |
| 授权依据 | 每个受保护请求查询数据库最新角色和审核状态 |
| 传输位置 | 仅 `Authorization: Bearer <token>` |

无状态 logout 只要求客户端删除 Token，服务端不维护黑名单。用户不存在、审核状态不是 `approved` 或角色非法时，旧 Token 必须立即返回 `40100`。

角色：

```text
admin
user
```

审核状态：

```text
pending
approved
rejected
```

## 十六、SSH 凭据与连接测试设计

服务器字段：

| 字段 | 存储方式 |
|------|----------|
| `name` | 明文 |
| `host` | 明文 |
| `description` | 明文 |
| `ssh_host` | 明文 |
| `ssh_port` | 明文 |
| `ssh_user` | 明文 |
| `ssh_auth_type` | 明文 |
| `ssh_password` | 入参字段，存储为 AES-GCM 密文 |
| `ssh_private_key` | 入参字段，存储为 AES-GCM 密文 |
| `ssh_private_key_passphrase` | 入参字段，存储为 AES-GCM 密文 |

数据库密文字段：

```text
ssh_password_encrypted
ssh_private_key_encrypted
ssh_private_key_passphrase_encrypted
```

认证方式规则：

| `ssh_auth_type` | 必填字段 |
|-----------------|----------|
| `password` | `ssh_password` |
| `private_key` | `ssh_private_key` |

AES-GCM 规则：

| 项 | 规则 |
|----|------|
| 算法 | AES-256-GCM |
| IV | 每次加密随机生成 |
| 密钥来源 | `AES_GCM_KEY` |
| 存储格式 | `v1:key-id:base64(iv):base64(ciphertext-with-tag)` |
| 日志 | 不输出明文、密文、密钥 |

AES-GCM 的 AAD 至少绑定 `server_id` 和 `credential_type`。SSH 阶段必须规划 key ID、密钥轮换、旧数据重加密、密钥丢失恢复和数据库备份与密钥分离。

SSH 主机身份和出站边界：

- 默认严格校验已登记的主机公钥指纹，禁止接受任意主机密钥。
- 未知指纹或指纹变化时拒绝连接，重新确认仅允许 `admin` 执行并记录审计。
- DNS 解析后必须校验目标 IP，限制禁止网段、端口、连接超时、握手超时和并发数。
- `local` Profile 可显式允许本机地址，部署环境不得默认继承本机规则。

SSH 连接测试接口：

```text
POST /api/servers/{id}/ssh/test
```

流程：

1. 校验用户为 `admin`。
2. 查询未软删除的服务器记录。
3. 根据 `ssh_auth_type` 解密对应凭据。
4. 使用 sshj 建立 SSH 连接。
5. 成功后立即关闭连接。
6. 失败返回 `50002 ssh connection failed`。
7. 日志只记录 serverId、sshHost、错误类型，不记录敏感内容。

## 十七、定时任务设计

MVP-1 只做定时任务框架和 metrics 清理任务骨架。

| 任务 | MVP-1 处理 |
|------|------------|
| metrics 清理 | 建立任务骨架和配置项 |
| Agent 离线检测 | 预留，MVP-2/MVP-3 完善 |
| 告警检测 | 预留，MVP-6 完善 |

配置项：

```text
METRICS_RETENTION_DAYS=10
METRICS_CLEANUP_CRON=0 0 3 * * ?
ALERT_CHECK_SECONDS=10
AGENT_STATUS_SCAN_SECONDS=30
```

## 十八、文档目录设计

```text
docs-SuMon/
├── Develop-plans/
├── Develop-log/
├── OpenApi-SuMon/
├── Protocol-SuMon/
└── 本机开发环境配置.md
```

| 路径 | 作用 |
|------|------|
| `docs-SuMon/Develop-plans/` | 开发计划目录 |
| `docs-SuMon/Develop-log/` | 开发日志目录 |
| `docs-SuMon/OpenApi-SuMon/` | REST API OpenAPI 3.0 JSON 文档目录 |
| `docs-SuMon/Protocol-SuMon/` | WebSocket、Agent、SSH 终端协议文档目录 |
| `docs-SuMon/本机开发环境配置.md` | 当前本机开发环境、MySQL、后端启动和验证说明 |

文档维护规则：

- 新增或修改 API 时，同步更新 OpenAPI 文档。
- 新增或修改 WebSocket/Agent 协议时，同步更新协议文档。
- 开发活动完成后记录开发日志。
- 文档中的服务器地址示例统一使用 `SERVER_IP_OR_DOMAIN`，不使用真实 IP 或内网 IP。

## 十九、API 调试目录设计

```text
api-test/
└── susumonitor.http
```

调试变量：

```http
@baseUrl = http://localhost:18080
@token = REPLACE_WITH_JWT_TOKEN
@serverId = 1
```

请求分组：

| 分组 | 请求 |
|------|------|
| System | health、ready |
| Auth | register、login、me、logout |
| Admin | pending、approve、reject |
| Servers | list、create、detail、update、delete、status |
| SSH | test |
| Metrics | MVP-3 预留 |
| Alerts | MVP-6 预留 |

## 二十、脚本目录设计

```text
scripts/
└── local-mysql-init.sql
```

`local-mysql-init.sql` 职责：

| 内容 | 要求 |
|------|------|
| 创建数据库 | `susumonitor` |
| 字符集 | `utf8mb4` |
| 排序规则 | `utf8mb4_unicode_ci` |
| 创建用户 | `susumonitor` |
| 登录来源 | `localhost` 和 `127.0.0.1` |
| 授权 | 授予 `susumonitor` 数据库权限 |

说明：业务表结构优先由 Flyway 管理，避免脚本和迁移重复建表。

## 二十一、根配置文件设计

需要建立：

```text
.editorconfig
.gitattributes
.gitignore
.dockerignore
```

`.editorconfig` 规则：

| 文件类型 | 缩进 |
|----------|------|
| 默认 | 2 空格 |
| JSON/YAML | 2 空格 |
| Kotlin | 4 空格 |
| SQL | 4 空格 |
| Markdown | 保留行尾空格以兼容换行 |

`.gitignore` 应忽略：

| 类别 | 内容 |
|------|------|
| 系统文件 | `.DS_Store`、`Thumbs.db`、`desktop.ini` |
| IDE | `.idea/`、`.vscode/` |
| 环境和密钥 | `.env`、`.env.*`、证书、私钥、`secrets/` |
| 日志 | `*.log`、`logs/` |
| Java 构建产物 | `target/` |
| 前端依赖和产物 | `node_modules/`、`dist/`、`.vite/` |
| Android 产物 | `.gradle/`、`local.properties`、`app/build/`、`captures/` |
| Docker/k8s 本地产物 | `*.tar`、`*.tgz`、`tmp/` |

`.gitattributes` 规则：

```text
* text=auto eol=lf
*.bat text eol=crlf
*.cmd text eol=crlf
*.ps1 text eol=crlf
```

`.dockerignore` 应排除 Git、IDE、本机环境密钥、依赖、构建产物、日志和临时目录。

## 二十二、实施顺序

### 阶段 0：结构基线

目标：让项目结构和新文档一致。

| 任务 | 验收 |
|------|------|
| 备份并删除旧目录 `server-susumonitor/` | `C:\Backup` 下存在备份，项目根目录不再有旧目录 |
| 新建 `server-java-SuMon/` | Java 后端正式目录存在 |
| 补齐 `scripts/` 和文档子目录 | 目录结构符合文档 |

### 阶段 1：根配置和辅助文件初始化

目标：建立项目工程外壳。

| 任务 | 验收 |
|------|------|
| 新建 `.editorconfig` | 编码、缩进、换行规则明确 |
| 新建 `.gitattributes` | Git 换行规则明确 |
| 新建 `.gitignore` | 密钥和构建产物不会提交 |
| 新建 `.dockerignore` | 容器化阶段预留 |
| 新建 `scripts/local-mysql-init.sql` | 可初始化本机数据库和用户 |
| 新建 `api-test/susumonitor.http` | 包含 MVP-1 调试请求框架 |

### 阶段 2：Java 后端工程初始化

目标：后端能启动、能测试。

| 任务 | 验收 |
|------|------|
| 初始化 Maven 工程 | 已完成，`pom.xml` 存在 |
| 添加 Spring Boot 入口 | 已完成，启动类存在 |
| 添加配置文件 | 已完成，端口、数据库、JWT、AES、Agent 配置项存在 |
| 添加 logback 配置 | 已完成，日志格式明确 |
| 添加 Flyway 目录 | 已补齐，`db/migration/` 目录和 MVP-1 基础表迁移脚本已存在；真实执行待本机 MySQL 账号确认 |
| 添加基础测试 | 已完成，基础 context 测试存在；历史日志记录 `mvn test` 已通过 |

### 阶段 3：横切基础能力

目标：所有后续接口有统一基础。

| 任务 | 验收 |
|------|------|
| 统一响应对象 | 成功、错误、分页结构统一 |
| 错误码枚举 | 覆盖文档错误码 |
| 全局异常处理 | 参数、认证、权限、资源、数据库、SSH 错误统一 |
| request_id 过滤器 | 响应头返回 `X-Request-ID` |
| 日志 MDC | 日志包含 request_id |
| health | `GET /api/health` 可用 |
| ready | `GET /api/ready` 检查数据库 |

### 阶段 4：数据库迁移

目标：基础表结构落地。

| 任务 | 验收 |
|------|------|
| 创建 `users` 表 | 迁移脚本已创建，真实执行待本机 MySQL 账号确认 |
| 创建 `servers` 表 | 迁移脚本已创建，支持软删除字段和唯一索引设计 |
| 创建 `metrics` 表 | 迁移脚本已创建，包含指标清理和历史查询索引 |
| 创建 `commands` 表 | 迁移脚本已创建，增强阶段可复用 |
| 创建告警表 | 迁移脚本已创建，MVP-6 可复用 |
| 创建 SSH 会话表 | 迁移脚本已创建，MVP-7 可复用 |
| 添加索引 | 迁移脚本已按文档补充关键索引 |

### 阶段 5：认证与审核

目标：用户闭环跑通。

| 任务 | 验收 |
|------|------|
| 注册 | 首个用户自动 admin/approved |
| 登录 | approved 用户可获取 JWT |
| logout | 无状态退出 |
| me | JWT 可查询当前用户 |
| pending list | admin 可查看待审核 |
| approve | admin 可审核通过 |
| reject | admin 可拒绝 |

阶段 5 出口条件：

| 条件 | 验收 |
|------|------|
| 首管理员原子性 | 空库并发两个不同用户名，管理员数量严格为 1 |
| JWT 完整验证 | 固定算法并校验签名、issuer、audience、subject 和过期时间 |
| 用户状态回查 | 状态或角色变化后旧 Token 权限立即变化 |
| Security 默认拒绝 | 移除 `anyRequest().permitAll()`，只保留明确公开白名单 |
| 统一 401/403 | 返回统一 JSON，并包含服务端 `X-Request-ID` |
| 管理员权限 | 普通用户访问 `/api/admin/**` 返回 `40300` |
| 日志安全 | 密码、Token 和密钥不进入日志或异常响应 |
| 正式契约 | 静态 OpenAPI 与实现一致并完成 Apifox 导入验证 |

未满足阶段 5 出口条件前，不进入服务器 CRUD。

### 阶段 6：服务器管理

目标：服务器 CRUD 闭环。

| 任务 | 验收 |
|------|------|
| 新增服务器 | 仅 admin，字段校验完整 |
| 查询列表 | admin 和 approved user 可查，支持分页、keyword、排序 |
| 查询详情 | admin 和 approved user 可查，不返回 SSH 敏感明文 |
| 更新服务器 | 仅 admin，支持 SSH 凭据更新 |
| 删除服务器 | 仅 admin，执行软删除 |
| 状态查询 | admin 和 approved user 可查，Agent 状态 MVP-2 后完善 |

### 阶段 7：SSH 凭据加密与连接测试

目标：服务器 SSH 能力闭环。

| 任务 | 验收 |
|------|------|
| AES-GCM 工具 | 可加解密，密钥来自配置 |
| 凭据加密存储 | 数据库无明文 |
| SSH test | 仅 admin，支持 password/private_key |
| 错误处理 | 失败返回 `50002` |
| 日志脱敏 | 不输出敏感字段 |
| 主机身份验证 | 严格校验已登记指纹，禁止接受任意主机密钥 |
| 出站访问边界 | 防 DNS rebinding，限制目标地址、端口、超时和并发 |
| 密钥轮换 | 密文包含版本和 key ID，具备重加密规划 |

### 阶段 8：MVP-1 收口

目标：MVP-1 可验收。

| 任务 | 验收 |
|------|------|
| metrics 清理任务骨架 | 定时任务可触发 |
| API 调试文件补齐 | MVP-1 接口可调试 |
| OpenAPI 文档同步 | 与实现一致 |
| 后端 README | 启动、配置、测试说明完整 |
| 本机开发环境文档 | MySQL、启动、验证说明完整 |
| 开发日志 | 记录本轮变更 |

## 二十三、验证命令和验收流程

后续实现完成后至少按验证层级分别记录，单元测试、MockMvc、MySQL 集成测试、本机运行时、Apifox 和部署验证不得互相替代：

```bash
mvn test
```

```bash
curl http://localhost:18080/api/health
curl http://localhost:18080/api/ready
```

认证流程：

```text
register first admin
login admin
GET /api/auth/me
register second user
login second user -> 403
admin approve second user
login second user -> success
```

服务器流程：

```text
admin create server
admin list servers
approved user list servers
approved user create server -> 403
approved user update server -> 403
approved user delete server -> 403
admin update server
admin ssh test
admin delete server -> soft delete
deleted server not shown in list
```

数据库检查：

```text
users.password_hash 不能是明文
servers.ssh_password_encrypted 不能是明文
servers.ssh_private_key_encrypted 不能是明文
servers.deleted = 1 表示软删除
servers.delete_token 支持软删除后同 host 重建
```

## 二十四、风险与处理

| 风险或问题 | 影响 | 处理 |
|------------|------|------|
| 当前存在旧目录 `server-susumonitor/` | 可能混淆后端目录 | 备份后删除，统一使用 `server-java-SuMon/` |
| 本机 MySQL 用户和权限未确认 | `/api/ready` 和 Flyway 可能失败 | 先执行或检查 `scripts/local-mysql-init.sql` |
| 本机 SSH 服务未确认 | SSH test 可能无法成功验证 | 先完成接口和错误处理，真实成功测试依赖可用 SSH 服务 |
| 首管理员判断并发竞态 | 可能产生多个管理员 | 使用初始化状态记录和数据库事务锁，并执行真实 MySQL 并发测试 |
| SSH 主机指纹未校验 | 可能遭受中间人攻击 | 默认严格指纹校验，未知或变化指纹拒绝连接 |
| SSH 出站边界缺失 | 可能形成 SSRF 或访问敏感地址 | DNS 解析后校验 IP，配置网段、端口、超时和并发限制 |
| AES-GCM 密钥来源不当 | 凭据加密不安全 | 只从配置或环境变量注入，不硬编码 |
| 普通用户误操作服务器 | 影响服务器资产安全 | 增删改删和 SSH test 仅 admin |
| 软删除唯一索引设计不当 | 同 host 删除后无法重建 | 使用 `delete_token` 参与唯一索引 |

## 二十五、下一步执行建议

建议按以下顺序开始实施：

1. 完成 MVP-1 剩余的首管理员独立空库真实并发验收；部署环境验证。_真实 SSH `50002`/`50003` 分类已通过（2026-07-25 Apifox 验收，见 `Develop-log/20260725-Apifox-SSH-50003-真实验收.md`、`20260725-Apifox-SSH-50002-真实验收.md`）。_
2. 收口 MVP-5A 文档、接口契约和浏览器回归记录。
3. 实施 MVP-6 模块化单体告警规则、状态机、记录和 `alert.push` 闭环。
4. MVP-9 冻结 RabbitMQ 消息契约，MVP-10 实现 Outbox 可靠发布，MVP-11 实现告警幂等消费。
5. 每次修改 REST、WebSocket 或消息契约时同步 OpenAPI、协议、调试样例、测试和开发日志。

## 二十六、AI 运维中枢规划

AI 运维属于 MVP-8 完成后的远期增强能力，不纳入且不阻塞 MVP-1 至 MVP-8 的设计、开发、测试与交付。完整规划见 `Develop-plans/20260724-公网Agent反向终端与云端部署计划.md` 等后续规划文档（原引用的 `20260717-AI运维增强路线.md` 未在仓库落地，AI 运维路线已并入各阶段开发日志）。

## 二十七、Git、环境配置与 opencode skill 规划

### 27.1 Git 本机备份仓库

当前项目使用本机 bare Git 仓库作为备份远程仓库。

| 项 | 配置 |
|----|------|
| 工作区 | `C:\Users\genhaosan\Desktop\SuSuMonitor(Jvav)` |
| 本机 bare 仓库 | `D:\develop\Git\SuSuMonitor.git` |
| remote 名称 | `backup` |
| 默认分支 | `main` |

使用原则：

- `D:\develop\Git\SuSuMonitor.git` 是 bare 仓库，只用于 `git push` 备份，不直接编辑源码。
- 日常开发仍在 `C:\Users\genhaosan\Desktop\SuSuMonitor(Jvav)` 工作区进行。
- 首次提交和推送需要明确确认后再执行。
- 提交信息遵循 Conventional Commits，例如 `chore(project): 初始化 SuSuMonitor Java 项目规划`。

### 27.2 Git 忽略规则

项目根目录维护 `.gitignore`，用于忽略本机配置、密钥、构建产物和工具依赖。

必须忽略：

```text
.env
.env.*
*.pem
*.key
*.crt
secrets/
target/
node_modules/
dist/
.gradle/
local.properties
logs/
tmp/
.opencode/node_modules/
.opencode/package.json
.opencode/package-lock.json
.opencode/bun.lock
.codex/
```

说明：

- `.env.example` 可以提交，用于说明配置项。
- `.codex/` 不作为本项目配置体系，统一忽略。
- `.opencode/skills/` 需要保留和提交。
- `.opencode` 下 package 相关文件属于本地工具依赖，不作为项目正式配置。

### 27.3 环境配置策略

Java 后端环境配置以后续 `server-java-SuMon/` 工程为准。

建议文件：

```text
server-java-SuMon/.env.example
server-java-SuMon/src/main/resources/application.yml
server-java-SuMon/src/main/resources/application-local.yml
```

配置原则：

- 真实密钥只放本机环境变量或本机 `.env`，不提交 Git。
- `.env.example` 只写示例值和占位符，不写真实密钥。
- `JWT_SECRET`、`AES_GCM_KEY`、`AGENT_REGISTER_KEY` 不允许硬编码。
- 启动时应对关键密钥为空进行校验，避免使用空密钥运行。
- 日志中不得输出数据库密码、JWT、AES 密钥、Agent 注册密钥、SSH 密码、SSH 私钥。

### 27.4 opencode skill 保留策略

项目只保留 `.opencode/skills`，不保留 `.codex`。

当前保留的 skill：

```text
.opencode/skills/
├── docker-standards/
├── git-conventions/
├── kotlin-coding/
├── mysql-standards/
├── susumonitor-docs/
└── vue3-coding/
```

| skill | 处理 |
|-------|------|
| `docker-standards` | 保留，容器化阶段使用 |
| `git-conventions` | 保留，Git 提交、分支和提交粒度规范 |
| `kotlin-coding` | 保留，Android 阶段使用 |
| `mysql-standards` | 保留，数据库设计和 SQL 编写使用 |
| `vue3-coding` | 保留，Web 前端阶段使用 |
| `susumonitor-docs` | 保留文档规范，移除 Go 编码规范，改为 Java/Spring Boot 与项目文档规范 |

### 27.5 Java 编码规范

所有 Java 代码必须遵循《阿里巴巴 Java 开发手册》和项目内 `susumonitor-docs` skill 中的 Java 后端规范。

核心约束：

- 类名使用 UpperCamelCase。
- 方法名、变量名使用 lowerCamelCase。
- 常量使用 UPPER_SNAKE_CASE。
- 布尔变量不使用 `is` 前缀。
- Controller 不写业务逻辑，业务规则放在 Service。
- DTO、VO、Entity 分离。
- SQL 禁止 `SELECT *`。
- 异常捕获后必须处理，不允许吞异常。
- 日志使用 SLF4J 门面和 `{}` 占位符，不使用字符串拼接输出变量。
- 敏感信息不写日志、不返回前端、不提交 Git。

### 27.6 opencode 生效说明

`.opencode/skills` 属于 opencode 配置时加载内容。修改 skill 后，当前运行中的 opencode 会继续使用已加载版本。

后续如需让新的 skill 内容完全生效，需要退出并重启 opencode。
