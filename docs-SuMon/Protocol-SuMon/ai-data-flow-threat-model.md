# AI 数据流与威胁模型

**版本**：AI 只读诊断 MVP v0.3  
**日期**：2026-08-30（2026-09-02 增补实现状态标注；2026-09-03 启用前收口更新）  
**状态**：代码级已实现并完成治理收口（限流/预算/重试/明文开关），默认关闭；真实 provider 联调证据见 §十一，生产验收仍以独立验收记录为准。与 RC1 并行。

## 一、范围与安全目标

本文约束 `POST /api/ai/diagnoses` 的只读诊断链路。AI 只能基于经过白名单筛选、校验和脱敏的服务器监控摘要生成诊断建议；它不是执行器、终端代理或 SSH 客户端。

安全目标：

- 只有 `admin` Bearer 身份可发起诊断，并重新校验管理员状态和目标服务器可见性。
- 发送给第三方 provider 的内容最小化、可审计、可撤销；不包含凭据或可直接控制主机的内容。
- 脱敏、白名单、策略、预算或 provider 安全条件不满足时 fail-closed，不发送请求。
- 模型输出必须结构化并带证据、限制和不确定性，不能被解释为已执行的修复。
- 所有拒绝、调用和结果均可按 request_id 追溯，同时避免审计自身成为敏感数据泄漏源。

## 二、组件与信任边界

| 组件 | 信任级别 | 责任与边界 |
|---|---|---|
| 管理员 Web/API 客户端 | 不完全可信 | 可提交问题和窗口；不能选择 provider URL、密钥、工具、系统 prompt 或任意上下文。 |
| Java AI API/Service | 受控边界 | 鉴权、服务器授权、限流、白名单、脱敏、预算、kill switch、provider 调用和审计的唯一编排点。 |
| 监控数据库/只读查询 | 受控数据源 | 只读取允许的指标、状态、告警摘要；不得把凭据表、Token、SSH 字段或原始终端内容加入上下文。 |
| Prompt/Schema 配置 | 受保护配置 | 版本化、只读加载；变更需评审。外部数据不能覆盖系统约束。 |
| 第三方 provider | 外部不受信任 | 只接收脱敏最小上下文；通过固定白名单目标、TLS、超时、密钥隔离和供应商条款约束。不得授予本系统工具或回调能力。 |
| 审计/指标/日志 | 受控但可泄漏 | 记录最小元数据和拒绝原因；禁止完整 prompt、原始问题中的秘密、响应原文、密钥和 Token。 |

## 三、允许的数据白名单

### 允许进入诊断上下文

- 目标 `server_id` 的非敏感标识或稳定别名（按产品需要可进一步哈希）。
- 指标摘要：CPU、内存、磁盘、网络、温度、负载及其 UTC 时间窗口内的聚合值。
- 服务器在线/离线状态、Agent 状态、最近心跳时间及数据新鲜度。
- 告警摘要：指标名、阈值、等级、状态、触发/恢复时间；文本字段须经过长度限制和注入扫描。
- 管理员提交的诊断问题，但必须经过长度、字符集、内容策略和秘密扫描。
- 数据缺失、采样窗口、聚合方式等用于表达证据限制的元数据。

### 默认禁止进入诊断上下文

- JWT、Agent Token、Token hash、API key、provider key、Cookie、Authorization header。
- SSH 用户、密码、私钥、私钥口令、主机密钥原文、连接细节和任何凭据密文。
- 数据库连接串、数据库密码、RabbitMQ/Redis 凭据、环境变量和 secret 配置。
- 原始终端输入/输出、终端控制帧、shell 命令、脚本、进程环境和文件内容。
- 任意原始日志、堆栈中可能带秘密的内容、未定义字段和用户指定的外部 URL。
- 任何会形成写操作意图或执行参数的 payload，例如命令、补丁、重启、配置变更或 SSH action。

白名单采用显式 allowlist，未知字段默认拒绝。白名单不是“过滤后尽量发送”：只要字段来源、类型、用途或敏感性无法确定，就不发送。

## 四、数据流

```text
管理员
  | HTTPS + admin Bearer
  v
POST /api/ai/diagnoses
  |
  +--> 鉴权/管理员状态/服务器授权/参数边界
  |
  +--> kill switch、预算、并发与限流检查
  |
  +--> 只读查询监控摘要
  |       (白名单字段、固定时间窗口、无终端/SSH/凭据)
  |
  +--> schema/长度/类型校验
  |
  +--> 脱敏与秘密扫描 ----失败----> 拒绝，不出站
  |
  +--> 固定 system prompt + 不可信数据区 + 输出 schema
  |
  +--> provider 白名单/TLS/密钥/超时边界
  |       (第三方 provider；无工具、无回调、无写权限)
  |
  +--> 结构化输出校验/敏感回显扫描/限制补全
  |
  +--> 最小审计元数据 + 按策略保留
  |
  v
ApiResponse<AiDiagnosis>（不返回原始 prompt、密钥或终端/SSH 数据）
```

### 外发前置条件

以下条件必须全部成立才允许 provider 请求：

1. 请求通过 admin Bearer 鉴权、服务器权限与软删除检查。
2. 全局/租户/管理员/服务器限流、并发和预算均有余量。
3. kill switch 处于开启状态，provider 标识和固定 endpoint 在白名单内。
4. 只读查询和数据 schema 校验成功，字段全部来自 allowlist。
5. 脱敏规则已加载且版本明确；脱敏后二次扫描无秘密、终端、SSH 或写操作内容。
6. prompt、模型、工具和响应格式由服务端固定，客户端不能覆盖。

任何失败均返回稳定错误，不尝试“降级发送原文”。

## 五、第三方 provider 与密钥

- provider 只能从服务端配置的白名单中选择；客户端不得提交 URL、代理地址、模型工具或 function calling 配置。
- 出站默认只允许 HTTPS 端点和 TLS 校验；`susumonitor.ai.allow-insecure-http=true` 可显式放宽明文 HTTP endpoint（2026-09-03 新增，仅用于受控内网/联调环境——明文传输会暴露 API key，生产不得开启；其余 scheme 永远拒绝）。禁止任意 URL、重定向到未白名单主机和 provider 回调入站。
- API key 从 secret manager、受控环境变量或等价密钥配置读取；不得硬编码、进入 Git、日志、审计、异常、响应或数据库诊断上下文。
- key 按 provider/环境隔离，遵循最小权限和定期轮换；轮换失败时 fail-closed。
- provider 请求设置连接/读取超时、最大响应体、有限重试和退避；不对不可重试的策略拒绝进行重试。
- provider 条款、数据处理地域、训练使用和删除能力必须在上线前由产品/安全责任人确认；未确认时保持 kill switch 关闭。

## 六、保留期与审计

### 保留策略

- 原始管理员问题：默认仅在请求处理期间存在；如确需审计，保存经过秘密扫描的摘要，采用短保留期并限制访问。
- 脱敏上下文：默认不持久化；仅为故障排查临时保留不可逆摘要，过期自动清理。
- provider 请求/响应：默认不持久化原文；仅保存状态、耗时和 usage 等元数据。
- 审计元数据：按安全责任人批准的最短必要周期保存，至少支持成本、滥用和事件追踪；具体天数由 Java/运维配置确认。
- 备份、日志聚合和 provider 侧保留不得绕过本地保留策略；删除/到期任务必须可验证。

### 审计字段

至少记录 `request_id`、`admin_id`、`server_id`、请求结果、拒绝原因（分类码）、provider、model、prompt_version、开始/结束时间、耗时、usage、响应 HTTP 状态和 kill switch/限流命中情况。审计字段不得包含 Authorization、Token、密钥、完整问题、完整 prompt、完整模型响应、终端输入输出或 SSH 内容。

审计写入失败不应导致秘密回退到普通日志；按安全策略选择拒绝请求或记录不含敏感数据的最小失败标记。

## 七、成本、限流与 kill switch

- 按管理员、服务器、来源 IP、provider 和全局维度设置请求速率、并发数、历史窗口、问题长度和响应体上限。
- usage 至少统计输入/输出 token（若 provider 提供），成本按 provider/model 计量；超预算前有告警，耗尽后拒绝外发。
- provider 超时、5xx、429、响应过大或 schema 无效时使用有界重试；重试必须计入限流和成本保护，不能形成重试风暴。
- 提供全局 kill switch、provider 级 kill switch 和按环境的默认关闭开关；配置读取异常、开关状态不确定时视为关闭。
- kill switch 命中应返回可区分的服务不可用/策略拒绝错误，并写入最小审计事件；不泄露 provider 配置和内部原因。

## 八、威胁模型与控制

| 威胁 | 影响 | 必须控制 |
|---|---|---|
| 非 admin 越权调用 | 泄露监控数据、增加成本 | API 与 Service 双重 admin 校验；每次校验服务器存在、未删除和授权状态。 |
| 越权读取其他服务器 | 跨租户/服务器信息泄露 | `server_id` 只用于服务端查询；拒绝客户端注入上下文；记录拒绝审计。 |
| prompt injection | 覆盖系统约束、诱导泄密/执行 | 外部字段放入不可信数据区；固定 system prompt；无工具/无回调；输出校验和注入 fixture。 |
| 敏感字段误发 | 凭据或个人/基础设施信息出站 | 显式白名单、schema、脱敏、二次秘密扫描；任一不确定性 fail-closed。 |
| 模型幻觉/错误建议 | 错误运维决策 | 强制 evidence/limitations；输出仅是建议；禁止自动执行和写操作；展示数据新鲜度。 |
| provider 泄露或训练留存 | 第三方数据风险 | 最小化脱敏数据、合同/地域确认、固定 provider、关闭不必要留存，必要时保持开关关闭。 |
| API key 泄露 | provider 账户接管/成本失控 | Secret 管理、最小权限、轮换、日志/异常屏蔽、不可由请求覆盖。 |
| DoS/成本耗尽 | 服务不可用/费用异常 | 多维限流、并发上限、预算、最大窗口/体积、超时、有限重试和 kill switch。 |
| 重放/重复请求 | 重复成本、审计噪音 | 短时请求关联/幂等策略由 Java 确认；重复请求仍受限流、预算和审计约束。 |
| 模型输出敏感回显 | 二次泄露 | 响应秘密扫描、结构化 schema、长度限制；发现敏感内容则丢弃并返回受控错误。 |
| 日志/审计泄露 | 内部数据扩散 | 只存最小元数据；禁止完整 prompt/响应和秘密；访问控制与到期清理。 |
| 诱导终端/SSH/写操作 | 主机接管或数据破坏 | API 无工具调用、无终端帧、无 SSH client、无写事务；问题和输出均不得转成动作。 |

## 九、明确禁止的能力

AI MVP 永不直接使用 `terminal.open`、`terminal.input`、`terminal.resize`、`terminal.close` 或任何 `terminal.opened`、`terminal.output`、`terminal.closed`、`terminal.error` 帧。AI 不能建立终端会话、读取终端输入输出、连接 SSH、执行 shell/脚本、调用命令表、修改服务器/告警配置、写入监控数据或触发重启。模型提出的 `recommendations` 只能是人工阅读的说明，不是可执行指令或自动化任务。

任何未来需要执行动作的产品需求都必须另行设计独立权限、审批、审计和协议，不得通过修改 prompt、放宽白名单或复用本只读端点实现。

2026-09-03 起命令执行能力经独立命令域（`command-protocol-v1.md` + `ai-command-domain-threat-model.md`）受控解禁，只读诊断端点仍保持只读；AI 仍永不接触 `terminal.*` PTY。

## 十、验收证据

上线前至少提供：

- provider mock 的出站请求快照，证明只含白名单脱敏字段。
- 脱敏规则缺失、异常、未知字段和秘密扫描命中时无出站请求的测试证据。
- prompt injection、越权 `server_id`、非 admin、软删除服务器、超预算、限流和 kill switch 测试证据。
- 日志/审计秘密扫描结果，以及保留期清理任务的验证记录。
- 端到端调用中无 WebSocket `terminal.*`、无 SSH 连接、无写事务的证据。
- Java 主线确认的最终字段、HTTP 状态、错误码和配置项；本文当前错误码候选不等同于最终实现。

> 上文"错误码候选"说明已过时：错误码已于 2026-08-31 定稿，见 `ErrorCode.java` 与 §十一。

## 十一、实现状态标注（2026-09-02，按代码事实核对）

本节把前文各控制项映射到当前代码与测试证据，区分"已实现/简化实现/未实现"。前文为目标定义，冲突时以本节为准。

### 已实现且有测试证据

| 控制项 | 实现 | 测试证据 |
|---|---|---|
| admin-only 与用户状态复查 | `SecurityConfig` 显式 `POST /api/ai/diagnoses hasRole ADMIN`；JWT 过滤器每请求回查审核状态 | `AiDiagnosisControllerTests` 8 例：401/403/200/参数错误/42906 |
| 服务器授权与软删除拒绝 | Service 先 `existsActive` 再建上下文；40400 | `AiDiagnosisServiceTests.missingOrSoftDeletedServerShouldFailClosed` |
| kill switch 默认关闭、零触达 | `susumonitor.ai.enabled=false`（默认）时 Controller/Service/Provider/Mapper 均 `@ConditionalOnProperty` 不装配，端点 404；enabled 检查位于 `diagnose()` 最前 | `AiDiagnosisServiceTests.disabledAiShouldFailClosedWithoutQueries`（验证不查库） |
| 数据白名单 | `AiDiagnosisContext`：状态 + 指标证据 + 告警摘要（无 message/通知渠道/SSH 字段）；序列化断言不含 ssh/password/host | `AiDiagnosisServiceTests.successfulDiagnosis…` / `alertMessageAndChannelsShouldNotEnterContext` |
| provider 固定目标与 TLS | HTTPS-only + 固定 endpoint + 服务端 key/model；非 https 或缺配置 → `50304` 不出站 | `OpenAiCompatibleProviderTests`（9 例含 429/5xx/4xx/非法 JSON/未知字段） |
| 超时与响应体上限 | `SimpleClientHttpRequestFactory` connect/read 超时；响应 > `max-response-bytes` → `50305`→降级 | Provider 测试 + 配置边界 |
| 并发上限 | 全局 `Semaphore(max-concurrent-requests)`，耗尽 → `42906` | `AiDiagnosisServiceTests.exhaustedConcurrency…` |
| 审计与保留期 | V28 `ai_diagnostic_runs`：不存原始问题/prompt/密文/provider 原始响应；30 天默认保留 + 调度清理 | `AiDiagnosticRunMySqlValidationIT`（5 例，**未在本机执行**，需隔离库守卫） |
| 降级可用性 | provider 超时/不可用/响应非法 → HTTP 200 确定性摘要 + `model_used=false`，错误码入审计 | `providerTimeoutShouldReturnDeterministicFallbackAndFailAudit` |
| 终端/SSH/写操作隔离 | AI 模块不依赖 terminal/ssh 包；无写事务；`websocket-protocol.md` 已收口 terminal.* 仅 admin 且 AI 永不使用终端帧 | 代码结构事实 + 契约检查 |

### 简化实现（与目标条款有差距，启用前应评估）

- **注入/秘密扫描为关键词启发式**：输入拒绝 terminal/ssh/password/private key/token/execute command/run command/sudo/curl/http(s)://；输出建议拒绝 execute/sudo/URL/curl/wget 等。不是完整的秘密扫描或注入 fixture 套件，存在误杀（如问题合法提及"ssh 指标"）与漏检（编码/变形绕过）两类风险。
- **限流仅全局并发**：无按管理员/服务器/IP 维度的速率限制；请求体与窗口上限靠 DTO 校验。
- **无预算/成本执行**：`estimated_cost` 恒 0（provider 未回传），无预算耗尽拒绝、无成本告警；仅 token 计数入审计。
- **无重试**：计划要求的"有界重试"未实现，失败直接降级（避免重试风暴，但瞬时故障不恢复）。
- **审计未存响应 HTTP 状态单列**：可由 status + error_code 推导。

### 未实现 / 未验证（启用前必须补齐）

> 2026-09-03 收口更新：第 2~5 项已补齐并有测试/联调证据（见"已实现"增补与 §十一末尾 E2E 记录）；第 1 项的真实网关已联通并完成四路径 E2E，但 provider 条款/数据地域/训练留存确认与密钥轮换仍待产品/安全责任人完成；秘密扫描仍未建立独立组件。

- 真实第三方 provider 联调：出站域名确认、条款/数据地域/训练留存确认（§五最后一条——未确认前应保持关闭）。
- `AiDiagnosticRunMySqlValidationIT` 在隔离 MySQL 的实际执行（`RUN_MYSQL_VALIDATION_TESTS=true`）。
- 独立 prompt injection / 重放 / 越权 fixture 安全测试套件。
- 日志与审计链路的秘密扫描验证记录。
- 成本观测与预算策略；按维度的精细化限流。
- provider 条款与密钥轮换流程（当前仅环境变量注入，无轮换机制）。

### 已实现（2026-09-03 启用前收口增补）

| 控制项 | 实现 | 测试证据 |
|---|---|---|
| 按管理员固定窗口限流 | `AiRateLimiterConfig`：`InMemory`（Redis 关闭默认）/`Redis`（跨实例）互斥实现，`ai.rate-limit-max-requests`（默认 10）/`rate-limit-window-seconds`（默认 3600），超限 `42906`；位于并发许可之前，命中不建审计 | `AiDiagnosisRateLimiterTests` 4 例（上限/窗口推进/边界内保留/按 actor 隔离）+ Service 集成断言 |
| 按天 token 预算 | `ai.daily-token-budget`（0=不限）：当日 UTC completed 调用 `total_tokens` 聚合（V28 现有索引，零 DDL），达到上限 `42906` | Service 2 例（耗尽拒绝 / 未配置跳过聚合）+ MySQL IT 聚合断言 |
| provider 有界重试 | 仅 429 与瞬时网络错误重试，`ai.retry-max-attempts`（默认 2）+ 指数短退避 `retry-backoff-base-ms`（默认 500ms）；读超时/5xx/4xx 非限流/响应无效不重试；重试在并发许可内 | Provider 3 例（429 重试成功 / 耗尽仍 42906 / 5xx 不重试） |
| HTTP 明文显式放宽 | `ai.allow-insecure-http`（默认 false）：开启后仅放行 `http://`，其余 scheme 始终拒绝 | Provider 2 例（开启后 http 放行 / ftp 始终拒绝） |
| 输出 schema 固化与围栏容忍 | system prompt 固定完整 JSON schema（含 severity/confidence/source 枚举）；解析前剥离 markdown 围栏（真实网关实测会包裹 ```json），围栏剥离不放宽字段校验 | Provider 2 例（围栏内容解析成功 / severity 越界拒绝） |

### 真实 provider E2E 证据（2026-09-03，OpenAI-compatible 网关，HTTP 明文联调实例）

| 路径 | 结果 | 证据 |
|---|---|---|
| 真实模型诊断 | ✅ | `model_used=true`、`severity=critical`（合法枚举）、中文摘要与人工排查建议、`total_tokens=1394/1318`；审计行 `status=completed` |
| provider 超时降级 | ✅ | 真实读超时（30s）→ 审计 `status=failed, error_code=50401` → HTTP 200 确定性摘要 `model_used=false` |
| 错误密钥降级 | ✅ | provider 401 → 审计 `error_code=50303` → HTTP 200 降级；原始错误不透传 |
| 按管理员限流 | ✅ | 窗口上限 3 次，第 4 次 HTTP 429 `42906`，命中不建审计 |
| 按天 token 预算 | ✅ | 预算 1000、当日已耗 2712 → 直接 429 `42906`；日志 `budget exhausted, usedTokens=2712, budget=1000` |
| 反伪造 | ✅ | 模型自造 evidence（`observed_at:"current"` 等不可解析行）被整体替换为 Java 白名单证据（空上下文时响应 evidence 为空数组，与事实一致） |
| 密钥泄漏扫描 | ✅ | 四个后端日志文件 grep API key 前缀均 0 命中 |
| 隔离库 IT | ✅ | `susumonitor_metrics_validation`：V28 迁移 success=1；AI IT 7 例全过（建表/索引/回填/complete/fail/cutoff 清理/token 聚合），全部 6 类 IT 26 例 0 失败 |
