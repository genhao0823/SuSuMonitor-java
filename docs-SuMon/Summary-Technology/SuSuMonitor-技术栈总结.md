# SuSuMonitor 技术栈总结（Java 全栈实习面试参考）

> 最后核对日期：2026-07-23
> 整理范围：`server-java-SuMon`（Java 后端）、`agent-go-SuMon`（Go 采集 Agent）、`web-vue-SuMon`（Vue 前端）、`api-test`（接口验证）及工程化基础设施。
> 编写原则：只描述代码中真实落地或真实声明的技术，对"已声明但未实际使用"的技术单独标注，不夸大。

> **文档进度对齐说明（2026-07-25 修订，仅文档层）**
>
> 本节由本次"文档与实际开发进度对齐"修订追加；未修改原文档正文、未删除任何段落、未改动任何代码或配置。**修订时间：2026-07-25。**
>
> 状态分类补充：本节原"七、增强阶段规划（未落地）"与"九、诚信声明（面试自检）"已显式区分"已声明但未使用"与"规划中"两类。本节追加把"当前可用"与"当前不可用/计划中"再做一次明确切分，便于面试自检。

### 当前 vs 计划中（2026-07-25 修订补充）

| 能力 | 状态 | 依据 |
|---|---|---|
| Spring Boot 3.4.7 后端 | 当前可用 | `pom.xml:7-26` |
| Flyway V1-V19 | 当前可用 | `db/migration/` |
| MySQL 8.4 集成 | 当前可用 | `application.yml:11` |
| JWT 72h 认证 + 行锁首管理员 | 当前可用，**空库并发仍未验证**（独立库场景留作下一步验收） | `JwtKeyConfig`、`UserService.java:71-105` |
| Go Agent WS 鉴权/心跳/重连 | 当前可用 | `wsclient/client.go` |
| Agent 真实 metrics 上报 | 已实现（B-005/B-006 已闭环，2026-07-31 更新） | `cmd/susumonitor-agent/main.go:70-71` 已接入 collector/reporter，本机 + 云端端到端验证 |
| Linux 构建目标 `build-linux` | 已实现（B-007 已闭环，2026-07-31 更新） | `agent-go-SuMon/Makefile:6` 已含 `build-linux` target；`bin/susumonitor-agent-linux-amd64` 已实测 |
| 完整 Nginx 站点配置 / Java systemd / Maven Wrapper / `application-prod.yml` | 已实现（2026-07-27 T5 补齐） | `server-java-SuMon/deploy/*` + `mvnw`，云端明文 HTTP 已跑通 |
| Dockerfile + docker-compose / DB 备份脚本 | 当前**未实现** | 全仓 0 命中 |
| ECharts 集成 | 当前**未使用** | `package.json` 已声明，源码无引用 |
| openapi-typescript 类型生成 | 当前**未使用** | devDep 已声明，无生成脚本 |
| MVP-6 告警后端业务 | 当前可用 | `module/alert/` 25 个 .java、`V10__create_alert_states_and_soft_delete_rules.sql`、`AlertPushPublisher` 推送 `alert.push`、MockMvc 完整 |
| MVP-6 告警前端 | 已实现（2026-07-27 Sprint 0-7 收口，2026-07-31 更新） | `views/AlertRecordsView.vue` + `AlertRulesView.vue` + `alert.push` WS 消费；真实端到端链路 2026-07-28 验收通过 |
| MVP-7 Web SSH 终端 | 已实现 T1-T4（T4 xterm.js 前端 2026-07-28，2026-07-31 更新） | `views/TerminalView.vue` + 路由 `/terminal/:serverId`；T5 云端部署已验证（明文 HTTP）、T6 家庭 Linux 主机部署待验 |
| Redis / Prometheus / Docker / k8s / GitHub Actions / Android | 计划中 | 均属增强阶段 |

---

## 一、项目概览

SuSuMonitor 是一个 **Linux 服务器性能监控平台**，采用**模块化单体架构**，按 MVP 分阶段推进。当前已落地 MVP-1 核心后端、MVP-2 Go Agent、MVP-3 实时监控链路、MVP-5A Web 主页面和 **MVP-6 告警业务后端闭环**（Commit `7b01a60`，2026-07-25）；MVP-7 终端 T1-T3 已完成（PTY_RELAY_INTEGRATION_OK 2026-07-26）。MVP-1 仍剩首管理员空库并发和部署环境验收缺口；真实 SSH `50003`/`50002` 分类已于 2026-07-25 通过 Apifox 验收。（**2026-07-31 更新**：MVP-6 告警前端已收口、MVP-7 T4 xterm.js 前端已完成、MVP-9 已收口，详见本文件"当前 vs 计划中"表）

**多模块结构：**

| 模块目录 | 语言 | 职责 | 当前状态 |
|---|---|---|---|
| `server-java-SuMon` | Java 21 | 后端服务（REST + WebSocket + SSH） | 开发中，核心闭环已通 |
| `agent-go-SuMon` | Go 1.23 | 服务器指标采集 Agent | 鉴权、心跳、重连、采集和上报已实现并完成本机运行时验收 |
| `web-vue-SuMon` | TypeScript + Vue 3 | Web 管理前端 | M2-M6 + MVP-6 告警前端 + MVP-7 T4 Web SSH 终端均已实现（2026-07-27/07-28，2026-07-31 更新） |
| `app-kt-SuMon` | Kotlin | Android App | 空目录，规划中 |
| `api-test` | Node.js | HTTP/WebSocket 联调脚本 | 本机调试用 |

---

## 二、技术栈总览表

### 后端（Java）

| 技术 | 版本 | 用途 |
|---|---|---|
| Java | 21 LTS | 主语言（用到 record、虚拟线程） |
| Spring Boot | 3.4.7 | 应用框架、自动配置、配置绑定 |
| Spring Web MVC | - | REST API |
| Spring Security | - | 无状态 Bearer 鉴权、RBAC |
| Spring Validation（Hibernate Validator） | - | 参数校验 |
| Spring WebSocket | - | Agent 通道 `/ws/agent` + Web 通道 `/ws/monitor` |
| Spring Scheduling | - | Agent 离线扫描、Ticket 清理、指标清理 |
| MyBatis-Plus | 3.5.12 | 数据访问、分页、条件构造 |
| MySQL | 8.4 | 主数据库 |
| HikariCP | - | 连接池 |
| Flyway | - | 数据库版本化迁移（V1~V19） |
| JJWT | 0.12.6 | JWT 签发/校验（HS256） |
| BCrypt | - | 密码哈希 |
| JDK JCA AES-256-GCM | - | SSH 凭据加密 |
| sshj | 0.39.0 | SSH 连接测试、主机指纹校验 |
| Jackson | - | REST/WebSocket JSON |
| springdoc-openapi | 2.8.9 | OpenAPI 文档 |
| SLF4J + Logback | - | 日志（含 request_id MDC） |
| Lombok | - | 样板代码消除 |
| JUnit 5 + Mockito | - | 测试 |
| Maven | - | 构建管理 |

### Agent（Go）

| 技术 | 版本 | 用途 |
|---|---|---|
| Go | 1.23 | Agent 主语言 |
| coder/websocket | v1.8.15 | WebSocket 客户端 |
| gopsutil | v3.24.5 | 跨平台指标采集 |

### 前端（Vue）

| 技术 | 版本 | 用途 | 实际使用情况 |
|---|---|---|---|
| Vue 3 | ^3.5.0 | UI 框架（Composition API + `<script setup>`） | 已用 |
| TypeScript | ^5.5.0 | 类型安全（strict 全开） | 已用 |
| Vite | ^5.4.0 | 构建/开发服务器、proxy | 已用 |
| Vue Router | ^4.4.0 | History 模式路由、守卫 | 已用 |
| Pinia | ^3.0.0 | 状态管理（Setup Store） | 已用 |
| pinia-plugin-persistedstate | ^4.0.0 | auth store 持久化到 localStorage | 已用 |
| Axios | ^1.7.0 | HTTP 客户端、统一拦截器 | 已用 |
| Element Plus | ^2.8.0 | UI 组件库（按需导入） | 已用 |
| ECharts | ^5.6.0 | 图表库 | **已声明依赖，未实际使用**（见 5.8） |
| NProgress | ^0.2.0 | 路由切换进度条 | 已用 |
| unplugin-auto-import / unplugin-vue-components | - | API/组件自动按需导入 | 已用 |
| openapi-typescript | ^7.0.0 | 由 OpenAPI 生成类型 | **devDep 已声明，未配置生成脚本**（见 5.9） |
| ESLint 8 + Prettier 3 | - | 代码规范 | 已用 |

---

## 三、Java 后端技术栈详解

> 包路径：`server-java-SuMon/src/main/java/com/susumonitor/server`
> 分层结构：`config / common / security / module / websocket / ssh / scheduler`

### 3.1 Java 21 LTS

**在项目中如何使用：**
- 使用 `record` 定义不可变数据载体，减少样板代码。例如 JWT 签发结果 `JwtTokenService.IssuedToken`、`ParsedToken`（`security/JwtTokenService.java:166`、`:176`），WebSocket 消息 `AgentMessage`（`websocket/AgentMessage.java:13`），指标事件 `MetricsService.MetricsReportedEvent`（`module/metrics/service/MetricsService.java:53`），Service 内部分页值 `ServerService.QueryValues`（`module/server/service/ServerService.java:433`）。
- SSH 连接测试使用**虚拟线程**执行器 `Executors.newVirtualThreadPerTaskExecutor()` 承载并发握手（`ssh/SshConnectionTester.java:44`），适合大量 IO 等待场景。

**面试可讲：** record 的不可变性、虚拟线程对比平台线程在 IO 密集型场景的优势。

### 3.2 Spring Boot 3.4.7

**在项目中如何使用：**
- 启动类 `SuSuMonitorServerApplication.java:19` 用 `@SpringBootApplication` + `@ConfigurationPropertiesScan` + `@MapperScan("com.susumonitor.server.module")` + `@EnableScheduling` 组合启用自动配置、Mapper 扫描、定时任务。
- 通过 `application.yml` + `application-local.yml` 分层配置，环境变量占位回退（如 `${DB_HOST:127.0.0.1}`，`application.yml:11`）。
- 排除自动配置：`spring.autoconfigure.exclude` 关闭 `UserDetailsServiceAutoConfiguration`（`application.yml:5-6`），避免 Spring Security 默认 UserDetailsService 干扰无状态 JWT 方案。

### 3.3 Spring Web MVC（REST API）

**在项目中如何使用：**
- Controller 只做参数接收/鉴权上下文/响应封装，业务下沉 Service。例：`AuthController.java`、`ServerController.java`、`MetricsController.java`、`SystemController.java`、`AdminUserController.java`、`MonitorTicketController.java`、`AgentTokenController.java`。
- 路径前缀统一 `/api`，模块前缀如 `/api/auth`（`AuthController.java:24`）、`/api/servers`、`/api/admin`。
- 响应统一用 `ApiResponse<T>` 包装 `{code, message, data}`（`common/ApiResponse.java`）。
- 登录接口主动设置 `Cache-Control: no-store`、`Pragma: no-cache` 防止 Token 被缓存（`AuthController.java:56-57`）。

### 3.4 Hibernate Validator（参数校验）

**在项目中如何使用：**
- DTO 上用 Bean Validation 注解，Controller 用 `@Valid` 触发。例 `RegisterRequest` / `LoginRequest` 的字段约束。
- `@ConfigurationProperties` 类同样启用 `@Validated`，使**无效安全配置在启动阶段直接失败**（`config/AppProperties.java:18`），如 JWT 密钥 `@NotBlank`、AES 密钥 `@NotBlank`、SSH 超时 `@Min/@Max`、清理批次 `@Min/@Max`。
- Controller 参数级校验：`@Positive` 校验路径 ID、`@Min/@Max/@Pattern` 校验分页与排序字段（如排序字段白名单 `^(id|name|host|status|created_at|updated_at)$`，`ServerController.java`）。
- 校验失败统一由 `GlobalExceptionHandler` 转成 `INVALID_REQUEST_PARAMETER` 错误码（`common/GlobalExceptionHandler.java:29`），且只记录字段名不记录值，防敏感泄漏。

### 3.5 Spring Security（无状态 Bearer 鉴权 + RBAC）

**在项目中如何使用：**
- `SecurityConfig.java` 构建唯一 `SecurityFilterChain`：关闭 CSRF / formLogin / httpBasic / logout，`SessionCreationPolicy.STATELESS`（`:72-77`）。
- 公开接口白名单：`GET /api/health|/api/ready`、`POST /api/auth/register|/api/auth/login`、`/ws/agent|/ws/monitor`；管理接口 `hasRole("ADMIN")`（`:81-92`）。
- 自定义 `JwtAuthenticationFilter` 注册到 `UsernamePasswordAuthenticationFilter` 之前，并用 `FilterRegistrationBean.setEnabled(false)` **防止 Servlet 容器重复注册**导致每个请求二次验签（`SecurityConfig.java:48-55`）。
- 认证上下文以 `AuthenticatedUser` 为 Principal，权限由数据库最新角色动态生成 `ROLE_<ROLE>`（`JwtAuthenticationFilter.java:181-183`）。
- 401/403 统一由 `SecurityErrorHandler` 处理，确保未认证响应也带 `X-Request-ID`。

**面试可讲：** 无状态会话的取舍、Filter 重复注册陷阱、动态角色与"每次回查数据库最新状态"的设计。

### 3.6 JJWT（JWT 签发/校验）

**在项目中如何使用（`security/JwtTokenService.java`）：**
- 固定 **HS256** 契约，不信任 Token 自报算法：解析时额外校验 header 算法必须为 HS256（`:93-95`）。
- 标准/自定义声明：`iss=susumonitor`、`aud=susumonitor-api`、`sub=用户ID`、`username` 自定义声明、`iat/exp`、`jti=UUID`（`:70-82`）。
- 解析器 `requireIssuer/requireAudience/clockSkewSeconds(30)` 严格校验（`:52-57`）；校验 `jti` 为 UUID、`sub` 为正数、时间区间合法（`:120-158`）。
- **默认有效期 72 小时**（`AppProperties.Jwt.expireHours=72` / `application.yml` `expire-hours: ${JWT_EXPIRE_HOURS:72}`）；可通过环境变量 `JWT_EXPIRE_HOURS` 覆盖。
- 签名密钥用 `@Qualifier("jwtSigningKey")` 精确注入，避免与同为 `SecretKey` 的 AES 密钥混淆（`:48`）。
- 每个受保护请求验签后**回查数据库**确认用户仍 `approved` 且角色合法（`JwtAuthenticationFilter.java:150-155`），实现"角色变更/审核拒绝后 Token 立即失效"。

**面试可讲：** 为什么不信任 alg=none、为什么每次回查 DB（安全性 vs 性能权衡）、无状态 logout 的本质（前端删 Token，服务端不撤销）。

### 3.7 BCrypt（密码哈希）

**在项目中如何使用：**
- `SecurityBeansConfig.java:13-16` 注册 `BCryptPasswordEncoder` Bean。
- 注册时 `passwordEncoder.encode(明文)` 存哈希（`UserService.java:87`）；登录时 `passwordEncoder.matches(明文, 哈希)` 校验（`UserService.java:118`）。
- **防时序攻击**：用户不存在时仍用预生成的 `dummyPasswordHash` 跑一次 BCrypt，使"用户不存在"与"密码错误"响应耗时一致（`UserService.java:39, 116-117`）。
- Entity 层用 `@ToString.Exclude @EqualsAndHashCode.Exclude` 防止哈希泄漏（`UserEntity.java:27-29`）。

### 3.8 AES-256-GCM（SSH 凭据加密，JDK JCA）

**在项目中如何使用（`security/CredentialCipher.java`）：**
- 算法 `AES/GCM/NoPadding`，12 字节随机 IV、128 位 Tag（`:21, 27-29`）。
- **AAD（附加认证数据）绑定服务器上下文**：`susumonitor:server:{serverId}:credential:{credentialType}`（`:25`），使密文不可跨服务器/跨字段互换，防混淆攻击。
- 信封格式 `v1:` 前缀 + Base64(IV + 密文)，支持后续密钥轮换（`:23, 72`）。
- 凭据类型白名单 `ssh_password / ssh_private_key / ssh_private_key_passphrase`（`:31-32`）。
- 业务用法：创建服务器先插入基础记录取得 ID，再用 ID 作 AAD 加密凭据，两步同事务（`ServerService.java:72-86`）；更新时按认证方式变化决定保留/替换/清除密文（`ServerService.java:341-393`）。

**面试可讲：** GCM 相比 CBC 的优势（认证加密）、AAD 绑定上下文的防混淆设计、IV 不可重用、信封版本化。

### 3.9 MyBatis-Plus

**在项目中如何使用：**
- Entity 用 `@TableName` + `@TableId(type = IdType.AUTO)` + `@TableField` 映射（如 `UserEntity.java:13-19`、`ServerEntity.java:16-23`、`MetricsEntity.java`）。
- 启用 `map-underscore-to-camel-case` 自动驼峰转换（`application.yml:31`）。
- Mapper 扫描由启动类 `@MapperScan` 统一开启（`SuSuMonitorServerApplication.java:15`）。
- 复杂查询走 XML Mapper（`mapper/auth/UserMapper.xml`、`mapper/server/ServerMapper.xml`、`mapper/metrics/MetricsMapper.xml`），SQL **显式指定字段**（遵守"禁止 SELECT *"规范），用 `<sql>` 片段复用列定义，区分 `UserColumns`（含密码哈希）与 `SafeUserColumns`（脱敏，`UserMapper.xml:19-40`）。
- 条件更新实现乐观锁/并发控制：审核 `updateReviewStatus` 带 `WHERE id=? AND role='user' AND review_status='pending'`，返回行数≠1 抛冲突（`UserMapper.xml:69-78` + `AdminUserService`）。
- 查询区分 `selectActiveServerById`（不含凭据列）与 `selectActiveServerWithCredentialsById`（含密文），最小权限读取。

### 3.10 MySQL 8.4 + HikariCP

**在项目中如何使用：**
- HikariCP 连接池 `maximum-pool-size: 10, minimum-idle: 2`（`application.yml:14-17`）。
- 字符集 `utf8mb4`，时区 `Asia/Shanghai`（`application.yml:11`）。
- 索引设计示例：`users` 表 `uk_users_username` 唯一索引、`idx_users_review_status`、`idx_users_role`（`db/migration/V1__create_users_table.sql:11-14`）。
- 就绪检查用 `DataSource.getConnection()` + `connection.isValid(2)` 验证连通（`SystemController`）。
- Agent 离线检测通过更新 `last_heartbeat_at`、`agent_status` 字段实现。

### 3.11 Flyway（数据库迁移）

**在项目中如何使用：**
- 启用 `baseline-on-migrate: true`，迁移脚本位于 `classpath:db/migration`（`application.yml:18-21`）。
- 版本脚本 V1~V19：V1 建 users 表、V2 servers、V3 metrics、V4 commands、V5 alert 表、V6 ssh_sessions、V7 auth_bootstrap_state、V8 server SSH 主机密钥字段、V9 agent_token 生命周期字段、V10 告警状态与规则软删除、V11 指标幂等表、V12 终端会话、V13 告警活跃规则唯一索引、V14 outbox 表、V15 消费幂等表、V16 outbox 清理索引、V17 心跳微秒精度、V18 告警确认窗口、V19 投递统计列。
- 表结构规范：`BIGINT UNSIGNED` 主键自增、`COMMENT` 字段注释、`InnoDB + utf8mb4_unicode_ci`、合理索引（见 V1 脚本）。

### 3.12 Spring WebSocket（双通道实时通信）

**在项目中如何使用：**

**Agent 通道 `/ws/agent`（`websocket/AgentWebSocketHandler` + `AgentWebSocketConfig`）：**
- 继承 `TextWebSocketHandler`，消息上限 64KB。
- 生命周期：连接建立 → 入 `pendingSessions`；首帧 `agent.authenticate` → `AgentAuthenticationService.authenticate` 校验 hash（SHA-256 + `MessageDigest.isEqual` 恒定时间比较）；鉴权成功回送 `agent.authenticated`，并 `replace` 同服务器旧连接。
- `@Scheduled(fixedDelay=5000)` 清理 10 秒内未认证连接（`AgentWebSocketHandler.java:130-140`）。
- `metrics.report` → 反序列化 → `MetricsService.report` 写库。

**Web 通道 `/ws/monitor`（`MonitorWebSocketHandler` + `MonitorHandshakeInterceptor`）：**
- 握手拦截器从 URL `?ticket=` 取一次性 ticket，`MonitorTicketService.consume` 原子移除（`ConcurrentHashMap.remove`），失败返回 401（`MonitorHandshakeInterceptor.java:27-41`）。
- ticket：32 字节 `SecureRandom`、Base64URL、TTL 30 秒、单次消费，`@Scheduled(fixedDelay=60000)` 清理过期（`MonitorTicketService.java`）。
- 处理 `metrics.subscribe/unsubscribe`，校验 server_id 有效且用户有权。

**指标推送（`MonitorMetricsPublisher`）：**
- 用 `@TransactionalEventListener(phase = AFTER_COMMIT)` **仅在事务提交后**广播 `metrics.update` 给订阅者（`MonitorMetricsPublisher.java:28-40`），保证"写库成功才推送"。
- 推送失败则移除订阅者，避免向死连接重试。

**在线状态管理：**
- `AgentConnectionRegistry`：`ConcurrentHashMap` 维护 sessionId→session 与 serverId→sessionId，`replace` 保证一机一活连接，`remove` 用 `remove(key, value)` CAS 语义。
- `AgentHeartbeatService`：`@Scheduled(fixedDelay=30000)` 扫描，心跳超 90 秒置离线、移除注册、关闭连接（`AgentHeartbeatService.java:37-54`）。

**面试可讲：** 一次性 ticket 替代 URL 传 JWT 的安全意义、事务提交后才推送的事件机制、并发在线状态用 ConcurrentHashMap + CAS、恒定时间比较防时序攻击。

### 3.13 sshj（SSH 连接测试 + 主机指纹校验 + 出站安全）

**在项目中如何使用（`ssh/SshConnectionTester.java` + `ssh/SshOutboundPolicy.java`）：**
- 注册为 `@Component` 实现 `AutoCloseable`，用 `net.schmizz.sshj` 发起连接。
- 主机指纹校验：自定义 `CapturingHostKeyVerifier` 实现 `HostKeyVerifier`，用 `FingerprintVerifier.getInstance` 做恒定内容比较，校验算法与指纹，按 OpenSSH 公钥 blob 计算 `SHA256:` 指纹（`:253-307`）。
- 出站安全策略 `SshOutboundPolicy`：端口白名单（默认仅 22）、DNS 解析后地址数量限制、禁止通配/多播/链路本地地址、**云元数据地址黑名单**（`169.254.169.254` 等，防 SSRF）、CIDR 白名单（`:74-85`）。
- 并发控制 `Semaphore`（默认 8）、虚拟线程执行器、整体超时 `future.get(totalTimeout)`、多地址依次尝试（`:191-216`）。

**面试可讲：** SSH 主机密钥 TOFU 模型、为什么禁止接受任意主机密钥、SSRF 与云元数据风险、DNS rebinding 防护。

### 3.14 Spring Scheduling（定时任务）

**在项目中如何使用：**
- `@EnableScheduling`（启动类）。
- Agent 未认证连接清理：`fixedDelay=5000`。
- 过期 ticket 清理：`fixedDelay=60000`。
- Agent 离线扫描：`fixedDelay=30000`，90 秒超时阈值。
- 指标清理：`@Scheduled(cron = "${susumonitor.metrics.cleanup-cron}")`，默认每天 03:00（`scheduler/MetricsCleanupScheduler.java:29`）。
  - 分批删除：`AtomicBoolean` 防重叠，每批独立 `TransactionTemplate` 事务，每批最多 1000 条、单次最多 100 批，返回 0 行停止（`MetricsCleanupService`）。

### 3.15 统一响应 / 异常 / 错误码

**在项目中如何使用：**
- `ApiResponse<T>`（`common/ApiResponse.java`）：`success(data)` / `error(ErrorCode)` / `error(ErrorCode, message)`，错误响应 `data` 恒为 null。
- `ErrorCode` 枚举覆盖 0/40000/40001/40002/40100/40300/40400/40900/50000/50001/50002/50003，每项带 HTTP 状态。
- `GlobalExceptionHandler`（`@RestControllerAdvice`）统一捕获 `BusinessException`、`MethodArgumentNotValidException`、`ConstraintViolationException`、`HttpMessageNotReadableException`、兜底 `Exception`，日志只记字段名/路径不记值（`common/GlobalExceptionHandler.java`）。
- 业务侧抛 `BusinessException(ErrorCode)`，不用异常做正常流程控制。

### 3.16 请求追踪（request_id / correlation_id）

**在项目中如何使用（`common/RequestIdFilter.java`）：**
- `@Order(HIGHEST_PRECEDENCE)` 确保先于 Spring Security 执行，401/403 也带请求 ID。
- 每请求生成 UUID `request_id`，写入响应头 `X-Request-ID` + MDC；客户端可选 `X-Correlation-ID`，正则 `^[A-Za-z0-9_-]{1,64}$` 校验合法才透传，非法忽略且不记原值。
- MDC 在 `finally` 清除，logback pattern 输出 `[request_id=...] [correlation_id=...]`（`logback-spring.xml:5-6`）。

### 3.17 Jackson / springdoc-openapi / Lombok / 日志

- **Jackson**：REST 与 WebSocket JSON 序列化，`AgentMessage` 为 record 直接序列化。
- **springdoc-openapi 2.8.9**：`/api-docs`、`/swagger-ui.html`（`application.yml:56-60`），但**正式契约唯一事实源是 `docs-SuMon/OpenApi-SuMon/*.json`**，springdoc 仅运行时辅助。
- **Lombok**：`@Data` 生成 getter/setter，敏感字段 `@ToString.Exclude @EqualsAndHashCode.Exclude`（`UserEntity.java:27-29`、`ServerEntity.java:62-64`）。
- **SLF4J + Logback**：`logback-spring.xml` 控制台输出，`com.susumonitor.server` 级别 DEBUG，Mapper 层 INFO。

### 3.18 @ConfigurationProperties 与启动校验

**在项目中如何使用（`config/AppProperties.java`）：**
- `@ConfigurationProperties(prefix="susumonitor")` + `@Validated`，嵌套 `Jwt / Security / Agent / Ssh / Metrics`。
- 关键约束：JWT secret `@NotBlank`、AES key `@NotBlank`、SSH 端口 `@NotEmpty`、各类超时/批次 `@Min/@Max`，**非法配置启动即失败**，避免运行时才暴露安全漏洞。

### 3.19 事务与事件

- `@Transactional` 标注写操作（如 `ServerService.create/update/delete`、`UserService.register/login`、`MetricsService.report`），读操作用 `@Transactional(readOnly=true)`。
- 首管理员原子初始化：`AuthBootstrapStateMapper.selectForUpdate()` 行锁 + `markAdminInitialized` 条件更新，杜绝并发注册产生多个 admin（`UserService.java:79-104`）。
- 指标写入后用 `ApplicationEventPublisher.publishEvent`，由 `@TransactionalEventListener(AFTER_COMMIT)` 事务提交后才推送。

### 3.20 测试

- `spring-boot-starter-test`（JUnit 5）+ `spring-security-test` + Mockito。
- 前端 Agent WS 协议有 `message_test.go`（`agent-go-SuMon/internal/wsclient/message_test.go`）。

---

## 四、Go Agent 技术栈详解

> 目录：`agent-go-SuMon`

### 4.1 Go 1.23

- 入口 `cmd/susumonitor-agent/main.go`：`config.Load()` → 构建 `slog` JSON 日志器 → `wsclient.NewClient` → `signal.NotifyContext` 监听 SIGINT/SIGTERM 优雅关闭 → `client.Run(ctx)`。
- UUID v4 手写实现（`crypto/rand` + 手动设版本/变体位），不引第三方库（`message.go:82-91`）。

### 4.2 coder/websocket v1.8.15

- 连接端点 `/ws/agent`（`client.go:21`）。
- 首帧鉴权 `agent.authenticate`（payload: server_id + token），等 `agent.authenticated` 响应（`client.go:103-141`）。
- 心跳 goroutine 每 30 秒发 `heartbeat`；接收 goroutine 读超时 95 秒（略大于后端 90 秒离线判定）（`client.go:147-189`）。
- **指数退避重连**：初始 5 秒，`backoff*2` 上限 60 秒，鉴权成功后重置（`client.go:66-82`）。
- 消息结构 `AgentMessage{type, message_id, timestamp, payload json.RawMessage}`，时间戳 UTC RFC3339Nano（`message.go`）。

### 4.3 gopsutil v3.24.5

- `collector/gopsutil_collector.go` 通过函数注入封装 gopsutil 调用，采集 CPU、内存、系统盘、网络累计字节，以及可选的温度和 Load Average。
- Windows 无法提供温度或 Load Average 时保留 nil，序列化为 JSON null；必需指标采集失败返回错误。
- `reporter.Report` 将采集快照映射为固定宽表 `metrics.report` Payload，并通过 WebSocket Client 发送。

---

## 五、前端技术栈详解

> 目录：`web-vue-SuMon`

### 5.1 Vue 3 + Composition API + `<script setup>`

- `src/main.ts`：`createApp(App)` → `createPinia()` + `pinia.use(persistedstate)` → 注册路由 → 全局 `errorHandler` → 注入 Axios 拦截回调（必须在 Pinia 安装之后）→ 启动时静默 `refresh()` → `mount`。
- `App.vue` 用 `el-config-provider` 注入中文 locale。

### 5.2 Vue Router 4（History 模式 + 守卫）

- 路由分公开（`publicRoutes`）与受保护（`MainLayout` 子路由），动态 `import()` 实现路由级代码分割（`router/index.ts`）。
- meta 字段：`title / requiresAuth / requiresAdmin / publicOnly`。
- 单一 `beforeEach` 守卫三层：角色拦截 → 登录拦截（带 redirect）→ 已登录访问公开页反送 dashboard（`router/guards.ts:36-56`）。
- 冷启动可靠性：刷新时 persistedstate 已从 localStorage 还原 token，首帧守卫即可判定登录态。

### 5.3 Pinia 3 + pinia-plugin-persistedstate

- `stores/auth.ts`：Setup Store，state（token/user/rememberMe）、getters（isAuthenticated/isAdmin/isApproved）、actions（login/register/refresh/logout/clearLocal）。
- 持久化 `persist: { key: 'susumonitor-auth', storage: localStorage, pick: ['token','user'] }`，白名单仅持久化 token 和 user，不持久化敏感字段。
- "记住我"：`rememberMe=false` 时登录后立即 `localStorage.removeItem`，使刷新即退出。
- `stores/metrics.ts`：瞬态 store，无持久化，`Promise.all` 并发拉取 latest + history。

### 5.4 Axios（统一封装）

- `api/client.ts`：`axios.create({ baseURL:'/api', timeout:10000 })`。
- 请求拦截器注入 `X-Correlation-ID`（UUID v4）与 `Authorization: Bearer <token>`（从 localStorage 读，避免与 store 循环依赖）。
- 响应拦截器：`code !== 0` 触发 `ApiBusinessError`；HTTP 401→`UNAUTHORIZED(40100)`、403→`FORBIDDEN(40300)`；40100 调 `onUnauthorized`（清本地 + 跳登录）、40300 跳 forbidden；网络异常 `ElMessage.error`。
- 回调机制 `setApiClientCallbacks` 解耦 client 与 Router。

### 5.5 Element Plus（按需导入）

- `vite.config.ts`：`unplugin-auto-import` + `unplugin-vue-components` + `ElementPlusResolver`，自动按需导入组件与命令式 API，生成 `auto-imports.d.ts` / `components.d.ts`。
- 全量引入 CSS，`ElMessage` 显式 import。
- 典型用法：`ServerListView.vue` 用 `el-table/el-table-column/el-pagination/el-popconfirm/el-card/el-input/el-select` + `v-loading`；`ServerFormDialog.vue` 用 `el-dialog/el-form` + `FormRules/FormInstance` 校验。

### 5.6 WebSocket 客户端

- `services/websocket.ts`：`MonitorWebSocket` 类，构造注入 `onMetrics`/`onConnected` 回调。
- 流程：`issueMonitorTicket()` 取 30 秒 ticket → 连 `/ws/monitor?ticket=` → `onopen` 发 `metrics.subscribe` → `onmessage` 收 `metrics.update` 调 `onMetrics` → 固定 3 秒重连（无指数退避）→ `disconnect()` 设 `stopped` 防重连。
- 协议自适应 `ws/wss`，不在 URL 放长期 JWT。

### 5.7 NProgress

- `composables/useRouterLoading.ts`：绑定路由 `beforeEach`→start、`afterEach`→done、`onError`→done，配置 `showSpinner:false, trickleSpeed:200, minimum:0.15`；`App.vue` 自定义玫红渐变主题。

### 5.8 ECharts（声明未用）

- `package.json` 声明 `echarts ^5.6.0`，但 `src` 全量搜索无任何 `import echarts` / `setOption` / `init(` 使用。
- 当前监控页 `MetricsView.vue` 用 `el-card` 指标卡片 + `el-table` 历史数据展示；`DashboardView.vue` 用原生 SVG spark line。
- **面试注意**：若被问及 ECharts，应如实说明为预留依赖，尚未集成；可谈"后续接入 ECharts 替换 SVG 折线的计划"。

### 5.9 openapi-typescript 与契约校验

- `openapi-typescript ^7.0.0` 在 devDep 声明，但 `package.json` 无调用它生成代码的脚本。
- `src/types/api.d.ts` 为**手写**类型定义，注释明确"OpenAPI JSON 是唯一事实源，字段变更先改 JSON 再同步本文件"。
- `scripts/check-openapi.mjs` 是零依赖的 OpenAPI 结构 lint 脚本（`npm run openapi:check`），校验 `docs-SuMon/OpenApi-SuMon/*.json` 符合 OpenAPI 3.0 最低结构，CI 友好（退出码 0/1）。
- `src/types/error-code.ts` 与后端 `ErrorCode.java` 严格对齐。

### 5.10 TypeScript / ESLint / Prettier

- `tsconfig.json` 继承 `@vue/tsconfig/tsconfig.dom.json`，`strict/noImplicitAny/noUnusedLocals/noUnusedParameters/noFallthroughCasesInSwitch` 全开，别名 `@/*`→`src/*`。
- `.eslintrc.cjs`：`eslint:recommended + plugin:vue/vue3-recommended + @vue/eslint-config-typescript`，`--max-warnings 0`，强制 `import type`。
- `.prettierrc.json`：单引号、无分号、行宽 100、LF、`arrowParens: always`。

### 5.11 Vite 构建

- 开发服务器 `port:5173, host:127.0.0.1`，proxy `/api`→`http://localhost:18080`（`vite.config.ts:26-35`）。
- 构建 `target:es2022, sourcemap:false, chunkSizeWarningLimit:1024`，脚本 `vue-tsc --noEmit && vite build`（类型检查先行）。

---

## 六、工程化与基础设施

| 项 | 说明 |
|---|---|
| Maven | 后端依赖与构建，`spring-boot-maven-plugin` 打包 |
| Make（Go） | `Makefile` 提供 build/run/test/vet/lint/clean |
| 多模块根目录 | Java 后端 / Go Agent / Vue 前端 / API 测试 / 文档 / 脚本 分离 |
| 配置管理 | 真实密钥走环境变量（`.env` 不入 Git），`.env.example` 提供示例 |
| HTTP 调试 | `api-test/susumonitor.http`（IntelliJ HTTP Client） |
| WS 联调脚本 | `api-test/verify-agent-ws.mjs`、`verify-monitor-ws.mjs`（Node + `ws`） |
| 运维脚本 | `scripts/local-mysql-init.sql`（建库建号）、PowerShell 脚本 |
| OpenAPI 契约 | `docs-SuMon/OpenApi-SuMon/*.json` 唯一事实源，前后端对齐 |
| 文档体系 | `docs-SuMon/` 下 Develop-plans / Develop-log / OpenApi / Protocol |
| 编辑器规范 | `.editorconfig`（缩进/换行/编码）、`.gitattributes`（LF + Windows 脚本 CRLF） |
| Git hooks | 前端 `.husky/` |

---

## 七、增强阶段规划（未落地，面试可谈"未来演进"）

以下在需求文档中规划但**当前代码未实现**，面试中可作为技术视野展示，需诚实区分"规划"与"已做"：

- Spring Data Redis：指标缓存、JWT 黑名单、限流、Agent 在线状态缓存
- Micrometer + Prometheus + Grafana：应用指标采集与监控
- Testcontainers：Docker 可用后的 MySQL/Redis 集成测试
- Quartz：复杂调度（若 Spring Scheduling 不足）
- Docker / Docker Compose / Kubernetes / Helm：容器化部署
- GitHub Actions：CI/CD
- Android App：Kotlin + Jetpack Compose + Retrofit + OkHttp + 前台 Service
- ~~前端 ECharts 集成、xterm.js Web SSH 终端（MVP-7）~~（**已落地 2026-07-28**：MetricsView ECharts 图表、TerminalView xterm.js 终端）
- 微服务演进（MVP-9 之后）：按数据所有权拆分 metrics/alert/ssh 服务

> **2026-08-02 更新**：原"已声明未落地"的两项已补齐——① 消费失败留痕（`FailedConsumeRecordRecoverer` + `upsertFailed`，原 `markFailed` 名存实亡已修复，新增 C4 验收场景）；② 告警逃逸窗口（`confirm_count` 连续越界确认，V18，默认 1 向后兼容防瞬时抖动误报）。

---

## 八、面试亮点与可深挖点（附代码位置）

> 以下为可在面试中主动展开的技术点，均可在代码中定位佐证。

| 亮点 | 关键代码 | 可讲深度 |
|---|---|---|
| 无状态 JWT + 每请求回查 DB 角色实现"即时失效" | `JwtAuthenticationFilter.java:150-155` | 安全 vs 性能权衡 |
| 首管理员原子初始化（行锁 + 条件更新，杜绝并发多 admin） | `UserService.java:79-104` | 并发与事务隔离 |
| AES-256-GCM + AAD 绑定 serverId 防凭据混淆 | `CredentialCipher.java:25, 127-134` | 认证加密设计 |
| Agent Token 只存 SHA-256 hash，明文一次性返回 | `AgentTokenService.java:80-90` | 凭证安全管理 |
| 一次性 monitor ticket 替代 URL 传 JWT | `MonitorTicketService.java` + `MonitorHandshakeInterceptor.java` | WebSocket 鉴权设计 |
| 事务提交后才推送（`@TransactionalEventListener AFTER_COMMIT`） | `MonitorMetricsPublisher.java:28-40` | 数据一致性 |
| SSH 出站安全（云元数据黑名单、CIDR、DNS rebinding 防护） | `SshOutboundPolicy.java:74-85` | SSRF 防护 |
| 恒定时间比较防时序攻击 | `AgentAuthenticationService.java:41-48` | 密码学安全 |
| 防时序攻击的登录（dummy BCrypt） | `UserService.java:39, 116-117` | 认证安全 |
| 软删除 + UUID 释放 host 唯一约束 | `ServerService.java:198-210` | 数据建模 |
| 软件删除 + 排序字段白名单防 SQL 注入 | `ServerService.java:47-49` | 安全编码 |
| 指标分批删除（AtomicBoolean 防重叠 + 独立事务批次） | `MetricsCleanupScheduler` + `MetricsCleanupService` | 大表清理 |
| request_id 全链路追踪 + MDC | `RequestIdFilter.java` + `logback-spring.xml:5-6` | 可观测性 |
| Filter 重复注册防护 | `SecurityConfig.java:48-55` | Spring 细节 |
| 前端 Axios 拦截器解耦 + 401/403 统一路由 | `api/client.ts` + `main.ts` | 前端工程化 |
| Pinia 持久化白名单 + "记住我"机制 | `stores/auth.ts` | 状态管理 |
| Go Agent 指数退避重连 | `agent-go-SuMon/internal/wsclient/client.go:66-82` | 分布式容错 |

---

## 九、诚信声明（面试自检）

以下为**声明但未实际落地**的内容，面试中如被追问须如实说明，避免夸大：

1. **ECharts**：`package.json` 已声明依赖，前端无任何实际调用代码。当前图表用 SVG/表格替代。
2. **openapi-typescript**：devDep 已声明，未配置生成脚本，`api.d.ts` 为手写。
3. **Redis / Prometheus / Docker / k8s / GitHub Actions / Android**：均属增强阶段规划，代码未实现。
4. ~~**xterm.js Web SSH 终端**~~：~~属 MVP-7，尚未实现~~（2026-07-31 更新：T4 前端已于 2026-07-28 实现最小可用版本，`views/TerminalView.vue` + 路由 `/terminal/:serverId`；T5 云端部署已验证、T6 家庭主机部署待验）。
5. ~~**Alert 告警系统前端**~~：~~前端告警页面未实现~~（2026-07-31 更新：MVP-6 告警前端已于 2026-07-27 收口，记录页 + 规则页 + `alert.push` WS 消费，2026-07-28 真实端到端链路验收通过）。

---

*本文档依据项目源码与配置文件整理，所有代码引用均可在对应文件定位核实。*
