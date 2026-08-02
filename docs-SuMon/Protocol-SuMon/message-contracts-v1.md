# Message Contracts v1

**版本**：v1  
**状态**：MVP-9 设计冻结草案，尚未接入 RabbitMQ 运行时  
**时间标准**：UTC ISO-8601，例如 `2026-07-28T12:00:00Z`

## 一、适用范围

本文定义 Metrics 与 Alert 后续异步边界使用的版本化事件契约。它不修改现有 REST、Agent WebSocket、Monitor WebSocket 契约，也不代表当前 Java 单体已经具备可靠消息发布能力。

当前 MVP-6 仍使用本地 Spring 事务事件；MVP-10 才实现 Metrics Transactional Outbox，MVP-11 才实现 Alert 消费和幂等记录。

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
| `event_type` | 是 | 逻辑事件名，不因路由实现变化；当前为 `metrics.reported` 或 `alert.triggered`。 |
| `schema_version` | 是 | 当前为整数 `1`；不支持的版本不可按旧版本猜测解析。 |
| `occurred_at` | 是 | UTC ISO-8601；表示事件产生时间，不使用本地时区。 |
| `producer` | 是 | 生产模块标识；当前为 `metrics-service` 或 `alert-service`。 |
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

## 四、`alert.triggered.v1`

该事件表示 Alert 已生成一条新的告警记录。持续越界不重复生成该事件；恢复语义需由后续明确的事件类型或查询状态表达，不能复用本事件伪装成恢复事件。

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

## 五、兼容性与失败处理

- 新增字段必须为可选，旧消费者应忽略未知字段。
- 删除字段、改变字段类型、改变枚举含义或改变空值语义必须递增 `schema_version`，不得复用 `v1`。
- 缺少必填字段、非法 UUID、非法枚举、数值越界或不支持版本属于不可重试数据错误，后续消费者应进入 DLQ。
- JSON 无法解析时不得用默认值猜测业务含义。
- 同一 `event_id` 的重试和补发必须保持 payload 语义不变。

## 六、未实现边界

本文是契约冻结文档。当前代码尚未提供 RabbitMQ 发布、消费、JSON Schema 运行校验、Outbox 或 `message_consume_records`。这些能力分别属于 MVP-10/MVP-11，不能以本文档替代运行时验证。

---

## 七、实现确认（2026-07-31，MVP-10 落地）

本文档 §二/§三 信封契约已由 MVP-10 的 `OutboxEnvelopeFactory` 实现（见 `Develop-log/20260731-MVP10-Metrics-Outbox.md`）：

- 信封字段与示例完全一致（snake_case、`event_type=metrics.reported`、`schema_version=1`、`producer=metrics-service`）。
- 时间格式固定为 UTC ISO-8601 秒级（`yyyy-MM-dd'T'HH:mm:ss'Z'`，与契约示例一致）。
- `temperature`/`load_avg` 允许 null 并输出 JSON null。
- 可选字段 `trace_id`/`correlation_id` 本阶段（MVP-10）不携带，消费侧不得要求必填。
- 真实 Broker 验收已核对出队消息与本文契约一致（verify-outbox.mjs）。

仍属 MVP-11：完整 JSON Schema 引擎、`message_consume_records`、消费幂等与 DLQ 分类执行。

## 八、实现确认（2026-07-31，MVP-11 消费侧落地）

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
