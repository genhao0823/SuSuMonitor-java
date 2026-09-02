# OpenAPI 契约（SuSuMonitor REST API）

> 本目录是 SuSuMonitor 后端 REST API 的权威 OpenAPI 3.0 契约源，供 Apifox 导入、前端类型生成、CI 校验与人工查阅使用。
>
> 契约基线：`main`（2026-08-29 安全审计后）。`openapi:check` 只校验 JSON 结构、本地 `$ref` 与 Java Controller 路径/操作映射，不代表 DTO/VO 字段语义与前端类型完全一致。
>
> **漂移状态（2026-08-29）**：2026-08-28 列出的前端/契约漂移（PUT 全量语义、指标历史 `page_size` 上限、错误码 `42905/50301/50302`、`resolved_at`、`status` 排序）**均已修复**；2026-08-29 新增行为变更：`GET /api/alerts/rules` 的通知渠道详情（`notify_email/notify_dingtalk/notify_webhook`）仅 admin 可见，非 admin 返回 null（前端 TS 类型 `string | null` 兼容，渠道标签对非 admin 隐藏）；注册接口增加 IP 限流（HTTP 429 + 42905，独立于登录计数）。
>
> 校验命令：`cd web-vue-SuMon && npm run openapi:check`（CI 友好，退出 0 表示 JSON 结构、`$ref` 与 Controller 路径/操作映射一致；不校验 DTO/VO 字段语义、security、响应头和前端类型）。
>
> 业务代码位置：`server-java-SuMon/src/main/java/com/susumonitor/server/module/**`（VO/DTO/Controller 是契约唯一来源）。

## 文件清单

| 文件 | 覆盖模块 | 端点数 |
|---|---|---|
| `openapi-system.json` | 系统健康 / 就绪探针 / RabbitMQ consumers 与 queues（公开 + ROLE_ADMIN） | 4 |
| `openapi-auth.json` | 注册 / 登录 / 当前用户 / 登出 | 4 |
| `openapi-admin.json` | 管理员用户分页/搜索与单个、批量审核（ROLE_ADMIN） | 5 |
| `openapi-server.json` | 服务器 CRUD / 状态 / SSH 主机指纹与观察 / SSH 测试与历史 / Agent Token / Monitor Ticket / 指标最新值 / 指标历史 | 13 路径 / 16 端点操作 |
| `openapi-alert.json` | 告警规则 CRUD / 告警记录分页 / 标记已读 / 通知投递历史（ROLE_ADMIN + 已认证） | 5 路径 / 7 端点操作 |
| `openapi-ai.json` | 大模型只读诊断 MVP（已实现·代码级；admin Bearer；仅白名单脱敏监控摘要；运行期由 `susumonitor.ai.enabled` 门控，默认关闭） | 1 路径 / 1 端点操作 |

合计 32 条文档路径 / 37 个端点操作，全部与当前 Java Controller 声明 1:1 对齐（2026-09-02 起 `openapi:check` 对 AI 端点同样执行严格双向校验，planned 例外已随实现合入移除）。代码级实现不等于生产验收：真实 provider 联调、隔离库 MySQL IT 执行与 RC1 门槛仍待完成。

## 端点索引

### 系统（公开）

| 方法 | 路径 | 说明 | 错误码 |
|---|---|---|---|
| GET | `/api/health` | 进程存活探针，不依赖数据库 | 50000 |
| GET | `/api/ready` | 进程 + 数据库；Outbox/RabbitMQ 或 Redis 启用时还检查对应依赖就绪 | 50000, 50001, 50301, 50302 |
| GET | `/api/system/rabbitmq/consumers` | RabbitMQ 消费者耗时与失败率窗口快照（ROLE_ADMIN） | 40100, 40300 |
| GET | `/api/system/rabbitmq/queues` | RabbitMQ 队列积压快照（ROLE_ADMIN） | 40100, 40300 |

### 认证（公开 + Bearer）

| 方法 | 路径 | 说明 | 权限 | 错误码 |
|---|---|---|---|---|
| POST | `/api/auth/register` | 注册（首用户 admin/approved，后续 user/pending；IP 限流 42905） | 公开 | 40002, 40900, 42905 |
| POST | `/api/auth/login` | 登录，签发 JWT（Cache-Control: no-store） | 公开 | 40001, 40300 |
| GET | `/api/auth/me` | 当前数据库用户快照（每请求回查） | Bearer | 40100 |
| POST | `/api/auth/logout` | 登出确认；Redis 启用时按 JWT jti 写入剩余 TTL 黑名单，否则为无状态空操作 | Bearer | 40100 |

### 管理员（ROLE_ADMIN）

| 方法 | 路径 | 说明 | 错误码 |
|---|---|---|---|
| GET | `/api/admin/users` | 用户分页、状态筛选与关键字搜索 | 40002, 40100, 40300 |
| PUT | `/api/admin/users/batch-approve` | 批量通过审核，返回逐项结果 | 40002, 40100, 40300, 40400, 40900 |
| PUT | `/api/admin/users/batch-reject` | 批量拒绝审核，返回逐项结果 | 40002, 40100, 40300, 40400, 40900 |
| PUT | `/api/admin/users/{id}/approve` | 通过审核（pending → approved） | 40002, 40100, 40300, 40400, 40900 |
| PUT | `/api/admin/users/{id}/reject` | 拒绝审核（pending → rejected） | 40002, 40100, 40300, 40400, 40900 |

### 服务器管理

| 方法 | 路径 | 权限 | 说明 | 错误码 |
|---|---|---|---|---|
| POST | `/api/servers` | ADMIN | 创建服务器 | 40002, 40100, 40300, 40900, 50000 |
| GET | `/api/servers` | 已认证 | 分页 + 关键字 + 排序白名单 | 40002, 40100, 50000 |
| GET | `/api/servers/{id}` | 已认证 | 详情（不含凭据明文/密文） | 40002, 40100, 40400, 50000 |
| PUT | `/api/servers/{id}` | ADMIN | 全量更新（description 必填，可空字符串） | 40002, 40100, 40300, 40400, 40900, 50000 |
| DELETE | `/api/servers/{id}` | ADMIN | 软删除（`deleted=1`, delete_token 替换） | 40002, 40100, 40300, 40400, 50000 |
| GET | `/api/servers/{id}/status` | 已认证 | 数据库状态快照（非实时探测） | 40002, 40100, 40400, 50000 |

### SSH（ROLE_ADMIN）

| 方法 | 路径 | 说明 | 错误码 |
|---|---|---|---|
| PUT | `/api/servers/{id}/ssh/host-key` | 确认 / 轮换 SSH 主机指纹（仅握手 + 指纹校验，不发送凭据） | 40002, 40100, 40300, 40301, 40400, 40900, 40901, 40902, 42900, 50001, 50002, 50400 |
| POST | `/api/servers/{id}/ssh/host-key/observe` | 观察目标主机指纹（不修改登记） | 40002, 40100, 40300, 40301, 40400, 42900, 50001, 50002, 50400 |
| POST | `/api/servers/{id}/ssh/test` | 使用已存凭据测 SSH（仅握手指纹 + 认证，不执行命令） | 40002, 40100, 40300, 40301, 40400, 40901, 42900, 50001, 50002, 50400 |
| GET | `/api/servers/{id}/ssh/test/history` | SSH 测试历史列表（V23，90 天保留，按时间倒序；当前接口不分页） | 40100, 40300, 40400 |

### Agent Token（ROLE_ADMIN）

| 方法 | 路径 | 说明 | 错误码 |
|---|---|---|---|
| POST | `/api/servers/{id}/agent/register` | 生成 Agent Token（明文一次性返回，存 SHA-256） | 40002, 40100, 40300, 40400, 40900 |
| POST | `/api/servers/{id}/agent/rotate` | 轮换 Token（旧 token 立即失效） | 40100, 40300, 40400, 40900 |
| DELETE | `/api/servers/{id}/agent/revoke` | 撤销 Token，标记 Agent offline | 40100, 40300, 40400, 40900 |

### Monitor WebSocket Ticket

| 方法 | 路径 | 权限 | 说明 | 错误码 |
|---|---|---|---|---|
| POST | `/api/ws/monitor-ticket` | Bearer | 签发一次性 30s ticket，用于 `/ws/monitor` 握手 | 40100, 40300 |

### 指标查询（已认证）

| 方法 | 路径 | 说明 | 错误码 |
|---|---|---|---|
| GET | `/api/servers/{id}/metrics/latest` | 最新固定宽表指标 | 40100, 40300, 40400 |
| GET | `/api/servers/{id}/metrics` | 历史分页（必传 start_time/end_time，最大 7 天窗口） | 40002, 40100, 40300, 40400 |

### 告警（MVP-6，已实现后端）

| 方法 | 路径 | 权限 | 说明 | 错误码 |
|---|---|---|---|---|
| POST | `/api/alerts/rules` | ADMIN | 创建告警规则（指标、操作符、阈值、等级） | 40002, 40100, 40300, 40400, 40900 |
| GET | `/api/alerts/rules` | 已认证 | 列出所有未删除告警规则（按 created_at 倒序；当前接口不分页、不接收 server_id 过滤参数；通知渠道详情仅 admin 可见，非 admin 返回 null） | 40100 |
| PUT | `/api/alerts/rules/{id}` | ADMIN | 更新阈值、等级或启用标志 | 40002, 40100, 40300, 40400, 40900 |
| DELETE | `/api/alerts/rules/{id}` | ADMIN | 软删除告警规则 | 40100, 40300, 40400 |
| GET | `/api/alerts/records` | 已认证 | 告警记录分页（按 server_id/status/时间窗口过滤） | 40002, 40100 |
| PUT | `/api/alerts/records/{id}/read` | 已认证 | 标记告警记录已读（unread → read） | 40002, 40100, 40300, 40400, 40900 |
| GET | `/api/alerts/records/{id}/notifications` | 已认证 | 告警通知投递历史（V21） | 40002, 40100, 40400 |

### AI 只读诊断（已实现·代码级，默认关闭）

| 方法 | 路径 | 权限 | 说明 | 错误码 |
|---|---|---|---|---|
| POST | `/api/ai/diagnoses` | ADMIN Bearer | 读取白名单、脱敏后的监控摘要并返回结构化建议；不读取/发送终端或 SSH 数据，不执行任何写操作；`susumonitor.ai.enabled=false`（默认）时 Controller 不加载，请求在安全链后得到 404 | 40002, 40100, 40300, 40400, 42906, 50000, 50303, 50304, 50401 |

AI 错误码已由 Java 主线定稿（`ErrorCode.java`）：`42906` AI 限流（按管理员窗口限流 / 按天 token 预算耗尽 / 全局并发上限）、`50303` provider 不可用、`50304` AI 关闭/配置无效/脱敏失败、`50401` provider 超时；`50305`（provider 响应无效）仅内部使用。`50003` 保持 SSH authentication failed 语义，AI 不复用。当前 MVP 行为：`50303/50304/50401/50305` 在服务层被转换为 HTTP 200 的确定性降级（`model_used=false`），原始错误码记录于 `ai_diagnostic_runs.error_code`，不透传客户端；`42906` 在限流/预算/并发命中时直接返回。provider 429 与瞬时网络错误按 `AI_RETRY_MAX_ATTEMPTS`（默认 2）进程内有界重试。默认仅允许 HTTPS provider endpoint；`AI_ALLOW_INSECURE_HTTP=true` 显式放宽明文 HTTP（仅内网/联调，明文会暴露 API key）。代码级测试与契约检查通过不等于接口生产验收通过。


既有已实现契约的错误码以 `ErrorCode.java`（含 2026-08-31 新增 AI 段）与对应 OpenAPI `ErrorResponse.code` 定义为准：

| code | message | 用途 |
|---|---|---|
| 0 | success | 业务成功 |
| 40000 | bad request | 兜底请求错误 |
| 40001 | invalid username or password | 登录凭据错误 |
| 40002 | invalid request parameter | 请求参数/字段校验失败 |
| 40003 | terminal invalid payload | 终端消息载荷非法 |
| 40100 | unauthorized | 未鉴权或 JWT 失效 |
| 40300 | forbidden | 已认证但权限不足 / 待审核用户登录 |
| 40301 | ssh target forbidden | SSH 出站策略不允许 |
| 40302 | terminal access denied | 终端访问被拒绝 |
| 40400 | resource not found | 资源不存在或已软删除 |
| 40403 | terminal session not found | 终端会话不存在 |
| 40900 | resource conflict | 唯一键冲突 / 状态机冲突 |
| 40901 | ssh host key not confirmed | SSH 主机指纹未登记 |
| 40902 | ssh host key mismatch | SSH 主机指纹不匹配 |
| 40903 | terminal session state conflict | 终端会话状态冲突 |
| 40904 | terminal agent offline | 终端 Agent 离线 |
| 42900 | ssh connection limit reached | SSH 并发上限 |
| 42901 | agent connection limit reached | Agent 连接/未认证会话上限 |
| 42902 | agent message rate limit reached | Agent 心跳或指标消息限流 |
| 42903 | terminal session limit reached | 终端会话数量上限 |
| 42904 | terminal message limit reached | 终端控制消息限流 |
| 42905 | login rate limit reached | 登录/注册防滥用限流（独立计数），响应 HTTP 429 并带 Retry-After |
| 42906 | AI rate limit reached | AI 按管理员窗口限流 / 按天 token 预算耗尽 / 全局并发上限（HTTP 429） |
| 50000 | internal server error | 兜底 |
| 50001 | database error | 数据库异常 |
| 50002 | ssh connection failed | SSH 连接失败 |
| 50003 | ssh authentication failed | SSH 凭据认证失败 |
| 50301 | rabbitmq unavailable | RabbitMQ 启用时 Broker 未就绪 |
| 50302 | redis unavailable | Redis 启用时 Redis 未就绪 |
| 50303 | AI provider unavailable | AI provider 调用失败/被拒（MVP 内部转降级） |
| 50304 | AI diagnosis unavailable | AI 关闭、配置无效或脱敏失败（关闭时端点为 404） |
| 50305 | AI provider response invalid | provider 响应非法（仅内部，转降级） |
| 50400 | ssh connection timeout | SSH 连接超时 |
| 50401 | AI provider timeout | AI provider 连接/读取超时（MVP 内部转降级） |

## 字段命名约定

- 系统/认证/管理员模块使用 camelCase（如 `reviewStatus`、`createdAt`）。
- 服务器/Agent/指标模块使用 snake_case（如 `ssh_host`、`agent_id`、`collected_at`）。
- `ApiResponse` 统一信封：`{ code, message, data }`。

## WebSocket 协议

REST 之外的 `/ws/agent` 与 `/ws/monitor` 双通道协议见：

- `docs-SuMon/Protocol-SuMon/websocket-protocol.md`（v1.3）

OpenAPI 不覆盖 WS 协议层（消息帧、订阅、推送）。

## 工具链

| 命令 | 行为 |
|---|---|
| `npm run openapi:check` | 纯只读，扫描 `docs-SuMon/OpenApi-SuMon/*.json` 与 Java Controller 路径，校验标题/版本/端点/operationId/responses/`$ref` |
| `npm run audit:catchup` | 静态扫描 `web-vue-SuMon/src/**` 检查 11 条规则 |
| `npm run api:e2e` | 真实 HTTP 端到端（需运行中后端 + 数据库） |
| `npm run ui:e2e` | Puppeteer 浏览器 18 场景（需系统 Chrome） |

## 维护流程

1. 修改 Java Controller/VO/DTO 时同步更新对应 OpenAPI 文档。
2. `web-vue-SuMon/.husky/pre-commit` 调用 `npm run openapi:check`，失败时阻止 commit。
3. 紧急绕过：`git commit --no-verify`（不推荐）。
4. Apifox 导入：把单个 `openapi-*.json` 直接 Import → API 即可；不同模块可分项目独立维护。