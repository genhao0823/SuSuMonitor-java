# Message Contracts v1

**版本**：v1
**状态**：已实施；`metrics.reported.v1` 已由 MVP-10/MVP-11 的 Outbox 与 Alert 消费侧使用
**时间标准**：UTC ISO-8601，例如 `2026-07-28T12:00:00Z`

## 一、适用范围

本文定义 Metrics 与 Alert 异步边界使用的版本化事件契约。它不修改现有 REST、Agent WebSocket、Monitor WebSocket 契约。当前 Java 单体已通过 Metrics Transactional Outbox 可靠发布 `metrics.reported.v1`，并由 Alert 消费者执行幂等消费、有限重试与 DLQ 分类。

## 二、统一事件信封

```json
{
  "event_id": "9f4c2d10-8b7f-4c3d-a5e0-1ef5b67f2f1a",
  "event_type": "metrics.reported",
  "schema_version": 1,
  "occurred_at": "2026-07-28T12:00:00Z",
  "producer": "metrics-service",
  "trace_id": "trace-optional",
  "correlation_id": "correlation-optional",
  "payload": {}
}
```

| 字段 | 必填 | 规则 |
|---|---:|---|
| `event_id` | 是 | UUID；同一事件重试、补发必须保持不变；消费幂等主键。 |
| `event_type` | 是 | 逻辑事件名，不因路由实现变化；当前已实现 `metrics.reported`、`alert.triggered` 与 `alert.resolved`。 |
| `schema_version` | 是 | 当前为整数 `1`；不支持的版本不可按旧版本猜测解析。 |
| `occurred_at` | 是 | UTC ISO-8601；表示事件产生时间，不使用本地时区。 |
| `producer` | 是 | 生产模块标识；当前已实现 `metrics-service` 与 `alert-service`。 |
| `trace_id` | 否 | 链路关联标识，不得携带凭据。 |
| `correlation_id` | 否 | 业务关联标识，不得携带凭据。 |
| `payload` | 是 | 独立消息对象；不直接复用 HTTP VO、Entity 或数据库行。 |

事件不得包含 JWT、Agent Token、Token hash、SSH 密码、私钥、私钥口令、数据库密码或 RabbitMQ 凭据。

## 三、`metrics.reported.v1`

该事件表示 Metrics 已接受并成功落库的一条指标，不要求 Alert 查询 Metrics 数据库补全内容。

```json
{
  "event_id": "9f4c2d10-8b7f-4c3d-a5e0-1ef5b67f2f1a",
  "event_type": "metrics.reported",
  "schema_version": 1,
  "occurred_at": "2026-07-28T12:00:00Z",
  "producer": "metrics-service",
  "payload": {
    "server_id": 123,
    "message_id": "1a08f7b1-51c8-4b46-929a-8879f349a3a2",
    "collected_at": "2026-07-28T11:59:58Z",
    "cpu_percent": 72.5,
    "memory_percent": 61.2,
    "memory_used": 1024,
    "memory_total": 2048,
    "disk_percent": 55.0,
    "disk_used": 100,
    "disk_total": 200,
    "net_rx": 1000,
    "net_tx": 800,
    "temperature": null,
    "load_avg": null
  }
}
```

### 字段规则

- `server_id` 为正整数。
- `message_id` 为 Agent 上报消息的 UUID；同一 `server_id + message_id` 仅用于入口幂等，不替代 `event_id`。
- `collected_at` 使用 UTC ISO-8601；同一服务器被接受的采样时间必须严格递增。
- CPU、内存、磁盘百分比为 `0` 到 `100`；容量、网络值为非负数。
- `temperature` 和 `load_avg` 允许 `null`，表示采集平台不提供该值。
- 指标字段应保持与已冻结的 `metrics.report` 数据语义一致。

## 四、`alert.triggered.v1`（已实现 Broker 发布 + 消费者接入）

该事件表示 Alert 生成新告警记录后的出站通知，由 Outbox 发布器按行 `routing_key`（V25）
路由到 `susumonitor.alert.triggered` 业务队列（2026-08-12 发布落地）；
消费者 `alert-notifier` 已接入（同日），在消费事务内为规则配置的渠道排程外部通知
（邮件/钉钉/Webhook），提交后异步发送——外部通知触发源由此从本地 AFTER_COMMIT 直呼
切换为 Broker 消息驱动，Broker 中断恢复后 outbox 补发也能重新触发通知。`alert.push`
Monitor WebSocket 帧仍为本地事件实时推送，二者并存；持续越界不重复生成该事件；恢复
语义已由 `alert.resolved.v1`（§五，2026-08-15 落地）表达，不能复用本事件伪装成恢复事件。

```json
{
  "event_id": "ce4cb5a4-ff5c-4514-a7b9-1ab5cc7e81b0",
  "event_type": "alert.triggered",
  "schema_version": 1,
  "occurred_at": "2026-07-28T12:00:05Z",
  "producer": "alert-service",
  "payload": {
    "server_id": 123,
    "rule_id": 456,
    "record_id": 789,
    "metric": "cpu",
    "current_value": 92.5,
    "threshold_value": 80.0,
    "level": "warning",
    "status": "unread",
    "triggered_at": "2026-07-28T12:00:05Z"
  }
}
```

### 字段规则

- `server_id`、`rule_id`、`record_id` 为正整数。
- `metric` 使用已冻结指标名，例如 `cpu`、`memory`、`disk`、`temperature`、`load_avg`。
- `level` 使用现有告警级别枚举；新增级别必须评估兼容性。
- `status` 当前触发状态为 `unread`；恢复事件不得复用 `alert.triggered.v1`。
- `current_value`、`threshold_value` 的单位由 `metric` 决定，必须与规则评估语义一致。
- `triggered_at` 使用 UTC ISO-8601。

## 五、`alert.resolved.v1`（已实现 Broker 发布 + 消费者接入）

该事件表示告警记录由评估器自动恢复（`status=resolved`）后的出站恢复事件，与
`alert.triggered.v1` 对称：评估器 Resolve 时同事务登记 Outbox，发布器按行
`routing_key`（V25）路由到 `susumonitor.alert.resolved` 业务队列（2026-08-15 落地）；
消费者 `alert-resolved-notifier` 已接入（同日），在消费事务内为规则配置的渠道排程
"恢复通知"（邮件/钉钉/Webhook），提交后异步发送；`alert.push` Monitor WebSocket 帧
仍为本地事件实时推送（`payload.alert.status=resolved`）。恢复事件不得复用
`alert.triggered.v1` 伪装恢复；记录未实际转为 `resolved` 时不生成该事件。

```json
{
  "event_id": "ce4cb5a4-ff5c-4514-a7b9-1ab5cc7e81b0",
  "event_type": "alert.resolved",
  "schema_version": 1,
  "occurred_at": "2026-08-15T12:00:05Z",
  "producer": "alert-service",
  "payload": {
    "server_id": 123,
    "rule_id": 456,
    "record_id": 789,
    "metric": "cpu",
    "level": "warning",
    "status": "resolved",
    "triggered_at": "2026-08-15T10:00:05Z",
    "resolved_at": "2026-08-15T11:30:05Z"
  }
}
```

### 字段规则

- `server_id`、`rule_id`、`record_id` 为正整数。
- `metric` 使用已冻结指标名（与 `alert.triggered.v1` 一致）。
- `level` 使用现有告警级别枚举。
- `status` 固定为 `resolved`；非恢复状态不得发布本事件。
- `triggered_at` 为原触发时刻；`resolved_at` 为恢复时刻（V26 起与
  `alert_records.resolved_at` 一致），均使用 UTC ISO-8601。
- 载荷不携带触发值（`current_value`/`threshold_value`）：记录中的触发值不是
  恢复时刻的值，不得作为恢复载荷。

## 六、兼容性与失败处理

- 新增字段必须为可选，旧消费者应忽略未知字段。
- 删除字段、改变字段类型、改变枚举含义或改变空值语义必须递增 `schema_version`，不得复用 `v1`。
- 缺少必填字段、非法 UUID、非法枚举、数值越界或不支持版本属于不可重试数据错误，后续消费者应进入 DLQ。
- JSON 无法解析时不得用默认值猜测业务含义。
- 同一 `event_id` 的重试和补发必须保持 payload 语义不变。

## 七、当前实现边界

已实现 RabbitMQ 发布、消费、Outbox 与 `message_consume_records`；消费侧使用字段级运行校验，尚未引入完整 JSON Schema 引擎。`alert.triggered.v1` 的 Broker 发布与消费者均已实现（2026-08-12），`alert.resolved.v1` 的发布与消费者均已实现（2026-08-15）——本契约已可实际订阅。

---

## 八、实现确认（2026-07-31，MVP-10 落地）

本文档 §二/§三 信封契约已由 MVP-10 的 `OutboxEnvelopeFactory` 实现（见 `Develop-log/20260731-MVP10-Metrics-Outbox.md`）：

- 信封字段与示例完全一致（snake_case、`event_type=metrics.reported`、`schema_version=1`、`producer=metrics-service`）。
- 时间格式固定为 UTC ISO-8601 秒级（`yyyy-MM-dd'T'HH:mm:ss'Z'`，与契约示例一致）。
- `temperature`/`load_avg` 允许 null 并输出 JSON null。
- 可选字段 `trace_id`/`correlation_id` 本阶段（MVP-10）不携带，消费侧不得要求必填。
- 真实 Broker 验收已核对出队消息与本文契约一致（verify-outbox.mjs）。

> 2026-08-16 注：本段末"仍属 MVP-11：完整 JSON Schema 引擎…"的消费幂等与 DLQ 分类已于 2026-08-01 落地（见 §九）；完整 JSON Schema 引擎仍未引入（字段级运行校验）。

## 九、实现确认（2026-07-31，MVP-11 消费侧落地）

消费侧已按本文契约解析并验收（见 `Develop-log/20260731-MVP11-Alert-消费侧.md`）：

- `MetricsReportedMessage` record 反序列化：信封字段 snake_case 与 §三 示例逐字段一致（含 `producer=metrics-service` 断言）。
- 契约常量 `EVENT_TYPE`/`SCHEMA_VERSION` 由 `OutboxEnvelopeFactory` 公开，发布/消费两侧同一来源，杜绝硬编码漂移。
- 不可重试数据错误分类执行（§五）：JSON 无法解析、`schema_version≠1`、`event_type` 不符，
  以及字段级契约校验失败（event/message UUID、`producer`、UTC 时间、必填 payload、
  server_id、百分比/非负数/used≤total 不变量）→ `AmqpRejectAndDontRequeueException`
  → 零重试进 DLQ；完整 JSON Schema 引擎仍未引入。
- `message_consume_records` 落地（V15）：consumer+event_id 唯一键，消费幂等（真实验收：同 event_id 重投仅 1 行记录、无第二次业务效果）。
- 失败留痕（2026-08-02）：被拒消息（不可重试零重试 / 重试耗尽）在 reject 前写 failed 行（attempts 1 或 max-attempts、last_error 截断 500、best-effort 不阻断 reject）；非法 JSON 无可解析 event_id 不留痕。幂等查询仅认 consumed 行——failed 行不阻塞 DLQ 重放，重放成功后 upsert 翻转回 consumed。
- 时间口径：`occurred_at`/`collected_at` 解析沿用 UTC 秒级格式，消费记录 `consumed_at` 写入 UTC（应用时钟）。
- JSON Schema 运行校验仍未引入；已实现无外部依赖的字段级运行校验，确保畸形载荷在进入幂等查询和告警评估前直接拒绝进 DLQ。

## 十、实现确认（2026-08-12，alert.triggered.v1 发布侧落地）

本文档 §四 的 `alert.triggered.v1` 信封已由 `AlertTriggeredEnvelopeFactory` 实现
（见 `Develop-log/20260812-告警事件RabbitMQ发布.md`）：

- 信封字段与示例完全一致（snake_case、`event_type=alert.triggered`、`schema_version=1`、`producer=alert-service`）。
- 时间格式固定为 UTC ISO-8601 秒级（`yyyy-MM-dd'T'HH:mm:ss'Z'`，与契约示例一致）。
- payload 仅携带契约冻结字段（server_id/rule_id/record_id/metric/current_value/threshold_value/level/status/triggered_at），
  不携带 message 等展示字段——载荷为独立消息对象，不直接复用 HTTP VO 全量字段。
- 契约常量（EVENT_TYPE/ROUTING_KEY/SCHEMA_VERSION）由工厂公开，与 `OutboxEnvelopeFactory` 同模式，
  杜绝硬编码漂移。
- 发布时机：评估事务内与告警记录同事务登记 outbox 行（`message_outbox.routing_key=alert.triggered.v1`，V25）。
- 验证：`AlertTriggeredEnvelopeFactoryTests` 与契约示例逐字段断言，Maven 全量 486 tests 全绿。

消费者侧（`alert-notifier` 幂等消费 + 通知排程 + DLQ 分类）已于同日接入，见 §十一。

## 十一、实现确认（2026-08-12，alert.triggered.v1 消费者接入）

本文档 §四 的消费者侧已实现（见 `Develop-log/20260812-alert.triggered消费者接入.md`）：

- 消费者 `alert-notifier` 幂等消费 `susumonitor.alert.triggered`：消费事务内先查
  `message_consume_records`（V15 唯一键，consumer=`alert-notifier`），命中即无第二次业务效果。
- 业务事务：`selectActiveRuleById` → `selectRecordById` → `AlertNotificationService.scheduleNotifications`
  （为规则配置的渠道插 `alert_notifications` pending 行，同事务）→ `upsertConsumed` 幂等记录；
  事务提交后 `@Async sendScheduled` 逐渠道首次尝试发送并回写通知时间。
  规则禁用/软删除/无渠道/记录缺失时跳过排程，仅落幂等记录（重试无意义）。
- 错误分类：JSON 无法解析、schema_version≠1、event_type 不符、字段契约校验失败
  （UUID/UTC 时间/三 ID/冻结指标集/数值非空）→ `AmqpRejectAndDontRequeueException` 零重试进 DLQ。
- 失败留痕：`FailedConsumeRecordRecoverer` 按消费队列映射 consumer 名
  （`susumonitor.alert.metrics`→alert-evaluator / `susumonitor.alert.triggered`→alert-notifier），
  两个监听器共用同一 recoverer bean。
- 通知触发源切换：原 `AlertNotificationPublisher`（AFTER_COMMIT 直呼 notify）已删除，
  外部通知完全由本消费者驱动——同一 record 只触发一次通知，避免双发。
- 验证：Maven 全量 506 tests 全绿（新增消费者/消息/校验器 3+8+9 用例与 recovered 映射断言）。

## 十二、实现确认（2026-08-15，alert.resolved.v1 发布 + 消费侧落地）

本文档 §五 的 `alert.resolved.v1` 已由 `AlertResolvedEnvelopeFactory` 发布、`AlertResolvedConsumer`（consumer=`alert-resolved-notifier`）消费（见 `Develop-log/20260815-alert.resolved恢复事件链路.md`）：

- 发布时机：评估器 `handleResolve` 在恢复事务内——`updateStatusToResolved`（V26 落库 `resolved_at`）成功后同事务登记 outbox 行（`routing_key=alert.resolved.v1`）+ 发布本地事件（AFTER_COMMIT 推 `alert.push`，`payload.alert.status=resolved`）；记录未实际转为 resolved 不发事件。
- 信封字段与 §五 示例一致（snake_case、`event_type=alert.resolved`、`schema_version=1`、`producer=alert-service`；payload 8 字段不含触发值）。
- 消费侧：幂等（V15）→ 规则有效且有渠道且记录已 resolved → `scheduleNotifications` 排程"恢复通知"（文案按 status 分支为 `[恢复]` 语义）→ 提交后 `sendScheduled`；错误分类与失败留痕与 §十一 同模式（`FailedConsumeRecordRecoverer` 队列映射含 `susumonitor.alert.resolved`→alert-resolved-notifier）。
- 验证：真实 broker 验收 `verify-alert-resolved-chain.mjs` 11/11 PASS（2026-08-15）；Maven 全量 541→550 tests 全绿。
