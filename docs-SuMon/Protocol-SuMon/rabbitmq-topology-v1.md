# RabbitMQ Topology v1

**版本**：v1
**状态**：已实施并完成真实验收；MVP-9 的命名冻结由 MVP-10/MVP-11 落地
**适用范围**：当前 Metrics → Alert 异步边界

## 一、拓扑目标

RabbitMQ 用于解耦 Metrics 与 Alert，不替代 Agent/Monitor WebSocket、MySQL 查询、心跳或 SSH 交互。Metrics 通过 Transactional Outbox 发布 `metrics.reported.v1`，Alert 消费者以幂等记录、有限重试和 DLQ 处理该事件；Monitor 广播仍使用本地事务后事件。

## 二、命名冻结

| 类型 | 名称 | 说明 |
|---|---|---|
| Topic Exchange | `susumonitor.events` | 业务事件交换器；当前由 Metrics Outbox 发布。 |
| Dead-letter Exchange | `susumonitor.dlx` | 重试耗尽或不可重试消息的死信交换器。 |
| Queue | `susumonitor.alert.metrics` | Alert 消费 `metrics.reported.v1` 的业务队列。 |
| Dead-letter Queue | `susumonitor.alert.metrics.dlq` | Alert 指标事件死信队列，不自动回投业务队列。 |
| Routing Key | `metrics.reported.v1` | Metrics 已落库指标事件。 |
| Routing Key | `alert.triggered.v1` | **预留，尚未实现 Broker 发布**；当前告警通知使用 `alert.push` Monitor WebSocket 帧。 |

Exchange、业务队列和 DLQ 均要求 durable、non-auto-delete；队列名称不包含实例 ID，不创建临时消费者队列。

## 三、绑定关系

```text
metrics-service
    -> susumonitor.events
       routing key: metrics.reported.v1
       message: metrics.reported.v1

susumonitor.events
    -> susumonitor.alert.metrics
       binding key: metrics.reported.v1
       consumer: alert-service

susumonitor.alert.metrics
    -> retry exhausted / non-retryable error
       dead-letter-exchange: susumonitor.dlx

susumonitor.dlx
    -> susumonitor.alert.metrics.dlq
       dead-letter routing key: metrics.reported.v1
```

`alert.triggered.v1` 只保留为未来版本化事件命名，当前没有 Alert Broker 发布器；不能把现有 `AlertPushPublisher`（Monitor WebSocket 推送）误称为该消息发布器。

## 四、至少一次投递

- 同一个 `event_id` 可能被生产者重复发布，也可能因 ACK 丢失被消费者重复收到。
- 同一事件的重试必须保持 `event_id` 不变和 payload 语义不变。
- 消费者仅在业务事务成功，或已确认该 `event_id` 已幂等完成后 ACK。
- 业务成功但 ACK 丢失时允许重新投递；重复投递不得产生第二次业务效果。
- Alert 消费幂等记录由 Alert 自有的 `message_consume_records` 管理，不依赖内存 Set、delivery tag 或共享 Metrics 表。
- 业务处理和消费记录必须在同一数据库事务内完成，或具备等价的原子语义。

## 五、错误分类与重试

### 可重试错误

- RabbitMQ 临时连接、通道或确认失败。
- 数据库连接短暂失败。
- 明确可恢复的事务锁冲突。
- 明确可恢复的下游超时。

可重试错误使用有限次数和退避，不能无限循环；每次重试保留原 `event_id`。

### 不可重试错误

- JSON 无法解析。
- `schema_version` 不支持。
- 必填字段缺失。
- UUID、枚举或数值范围非法。
- 鉴权/签名校验失败。
- 违反业务不变量且重试不会改变结果。
- 无法安全反序列化的消息。

不可重试错误直接进入死信流程；可重试错误达到冻结的最大次数后进入 `susumonitor.dlx`。DLQ 消息应保留原始事件标识并附带受控的失败分类/摘要，不写入密码、Token 或密钥。

消费侧采用容器级有限重试：默认最多 3 次、初始 1 秒、倍数 2、上限 10 秒；不可重试数据立即 reject，重试耗尽后路由到 DLQ。未引入独立 retry queue。

## 六、故障和就绪语义

当前已采用“存活但未就绪”：`/api/health` 只表示 Java 进程存活；`/api/ready` 同时检查 MySQL 和 RabbitMQ。Broker 不可用时应用不退出，Metrics 仍应通过同事务 Outbox 保留待发事件；Broker 恢复后由发布器补发。

该行为已在 MVP-10 实现并完成运行时测试：`/api/health` 仍仅表示进程存活；RabbitMQ 启用且不可达时 `/api/ready` 返回 `50301` / HTTP 503。

## 七、验证边界

以下能力已由 MVP-10/MVP-11 实现并完成对应真实 Broker 验收：

- Exchange/Queue/DLX/DLQ 自动声明。
- Publisher Confirm/Return。
- Broker 中断、恢复和 Outbox 补发。
- ACK、重复消费、重试耗尽和 DLQ 真实验证。
- 消费者重启恢复、死信查询和受控重放。

---

## 八、实现确认（2026-07-31，MVP-10 落地）

本文档冻结的发布侧拓扑已由 MVP-10 落地并真实验收（见 `Develop-log/20260731-MVP10-Metrics-Outbox.md`）：

| 冻结项 | 实现 |
|---|---|
| Exchange/Queue/DLX/DLQ 自动声明 | `RabbitMqTopologyConfig`（durable、non-auto-delete、DLX 参数），broker 启动即声明，管理 API 确认 |
| Publisher Confirm/Return | `CachingConnectionFactory` 开启 CORRELATED Confirm + Returns；`RabbitTemplate` mandatory=true；Confirm 经 `CorrelationData` future 同步读取 |
| 发布侧重试参数（冻结） | 指数退避 `min(2^attempts, 300s)` 封顶（`OUTBOX_MAX_BACKOFF_SECONDS` 可配）；**发布侧不设失败上限**——Outbox 语义为 Broker 恢复后必须补发，与消费侧 DLQ 语义分离 |
| Broker 中断、恢复和 Outbox 补发 | 真实验收 PASS：停机期间指标照常落库 + outbox 保留 pending；恢复后自动补发，队列消息数与停机前上报数一致 |
| 时间口径 | 写入 UTC（应用时钟），轮询比较 `UTC_TIMESTAMP()`（修复会话时区偏差，见 Develop-log §三） |

MVP-11 已完成 `susumonitor.alert.metrics` 消费者、幂等消费、重试耗尽进 DLQ 与受控重放；当前仍属后续边界的是多消费者并发消费。

## 九、实现确认（2026-07-31，MVP-11 消费侧落地）

本文档冻结的消费侧语义已由 MVP-11 落地并真实验收（见 `Develop-log/20260731-MVP11-Alert-消费侧.md`）：

| 冻结项 | 实现 |
|---|---|
| ACK 语义（§四） | **AUTO 确认模式**：业务事务（评估 + `message_consume_records` 插入）在监听方法内提交后返回，容器随后 ACK；异常不返回不 ACK。等价"业务事务成功才 ACK"（MANUAL + afterCommit 等价方案在真实验收中被弃用，原因见 Develop-log §四） |
| 重复消费幂等 | `message_consume_records(consumer='alert-evaluator', event_id)` 唯一键 + 消费事务内先查后插；重投递幂等命中零业务效果（真实验收：同 event_id 重投无新记录/无推送） |
| 重试冻结（§五） | 容器级有限重试：max-attempts=3（`ALERT_CONSUME_MAX_ATTEMPTS` 可覆盖）、退避 1s/×2/上限 10s；`AmqpRejectAndDontRequeueException`（含 cause 链）零重试立即 reject |
| 重试耗尽 → DLQ | 容器 reject(requeue=false) → `susumonitor.dlx` → `susumonitor.alert.metrics.dlq`（真实验收：非法 JSON、schema_version=2、字段契约越界消息均入 DLQ） |
| 失败留痕（§四） | **已落地（2026-08-02）**：`FailedConsumeRecordRecoverer` 在 reject 前尽力写 `message_consume_records` failed 行（attempts=不可重试 1 / 重试耗尽=max-attempts，last_error 截断 500，best-effort 不阻断 reject）；非法 JSON 无可解析 event_id 不留痕仅告警。`existsConsumed` 仅认 consumed——failed 行不阻塞 DLQ 重放，重放成功后 `upsertConsumed` 将 failed 行翻转回 consumed（failed→consumed 完整生命周期） |
| 业务处理与消费记录原子 | 同一 `TransactionTemplate` 事务提交，评估 + 幂等记录同生共死 |
| 消费者重启恢复 | **已验收（2026-08-01）**：Broker 停机期间后端存活（health 200 / ready 50301）、指标照常落库 outbox 堆积；恢复后发布器补发 + 消费者**自动重连补消费**（无需重启后端），业务队列归零、状态机正确（continue/resolve/trigger 无重复）；停机消息 6/6 消费无丢失（`verify-mvp11-broker-down.mjs`，见 `Develop-log/20260801-MVP11-收口.md` §三） |
| DLQ 受控重放 | **已落地（2026-08-01）**：`api-test/replay-dlq.mjs`（--replay 重放 + 防循环提示 / --purge 清空）；合法信封重放幂等命中零业务效果，数据错误消息重放仍回 DLQ |

仍属后续：多消费者并发消费（单消费者当前）。
