# 2026-07-31 MVP-10 Metrics Outbox 实施与验收记录

**日期**：2026-07-31
**状态**：实施完成 + 真实 Broker 验收通过（出口条件逐项达成）
**代码版本**：commit `a6fdfbd`（模块 1-5：`9e31f94` V14 数据层、`0e79c14` 同事务写入、`fa55c30` 发布器、`00972f5` 就绪检查、`519211d` 时区修复、`a6fdfbd` 验收资产）
**业务定位**：告警可靠性保障层——指标入库与"待评估事件"同事务落盘（Outbox），经 RabbitMQ 可靠投递，Broker 恢复后自动补发，保证告警不漏报；为多实例/微服务化准备。

---

## 一、设计与实现

### 1.1 数据流（MVP-10 后）

```text
Agent WS 上报 metrics.report
  -> MySQL 事务：insert metrics + insert message_outbox(pending) + 本地事件(保留)
  -> OutboxPublisherScheduler（fixedDelay 1s 轮询）
  -> SELECT ... FOR UPDATE SKIP LOCKED（短事务）
  -> RabbitTemplate.convertAndSend(exchange, routingKey, payload, CorrelationData)
  -> Publisher Confirm 同步等待（5s 超时）
  -> ack -> markPublished / nack|超时|异常 -> markRetry(指数退避 min(2^n,300s))
  -> susumonitor.events -> susumonitor.alert.metrics 队列（MVP-11 消费）
```

### 1.2 关键决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 本地事件是否保留 | **保留**（双通道） | MVP-10 只做发布侧；告警链路零中断，队列消息堆积至 MVP-11 接入消费者 |
| Outbox 写入位置 | `MetricsServiceImpl.report()` 事务内直接入队 | `MetricsReportedEvent` 不含 message_id，且不动现有事件契约 |
| 发布失败是否丢弃 | **不丢弃**（仅退避留痕） | Outbox 语义：Broker 恢复后必须补发；消费侧幂等（event_id）属 MVP-11 |
| 轮询行锁 | `FOR UPDATE SKIP LOCKED` | 防多实例/重叠调度重复取行 |
| 时间口径 | 写入 UTC（`Clock.systemUTC`），SQL 比较 `UTC_TIMESTAMP()` | **验收发现**：`NOW()` 为会话时区（东八区），与 UTC 写入偏差 8 小时导致退避永不生效（见 §三） |

### 1.3 参数传递链

```text
scheduler(@Scheduled fixedDelay ${susumonitor.rabbitmq.poll-interval-ms:1000})
  -> OutboxPublisherService.publishOnce()
  -> transactionTemplate.execute(selectPendingForPublish(batchSize))
  -> 逐行: rabbitTemplate.convertAndSend(exchange, routingKey, payload, correlationData)
  -> correlationData.getFuture().get(publishTimeoutMs) -> ack?
  -> ack: outboxMapper.markPublished(id, now)
  -> 失败: outboxMapper.markRetry(id, attempts+1, now+min(2^n,300s), error)
```

配置（`application.yml`，env 可覆盖）：`OUTBOX_ENABLED/EXCHANGE/ROUTING_KEY/POLL_INTERVAL_MS/BATCH_SIZE/PUBLISH_TIMEOUT_MS/MAX_BACKOFF_SECONDS`；连接走标准 `spring.rabbitmq.*`。

## 二、验收结果（真实 Broker：本机免安装 RabbitMQ 4.3.4 + Erlang 27.3.4.13）

### 2.1 环境

| 项 | 值 |
|---|---|
| Broker | RabbitMQ 4.3.4（`local/rmq`，5672/15672），vhost `susumonitor` + 用户 `susumonitor`（administrator） |
| 验证实例 | `DB_NAME=susumonitor_metrics_validation` + `spring.rabbitmq.*` 指向本机，端口 18081，限流放开（同 MVP-9） |
| 拓扑 | 启动时自动声明 4 件套（管理 API 确认 durable + DLX 参数正确） |

### 2.2 verify-outbox.mjs 三阶段验收（8 项检查 PASS）

| 阶段 | 检查项 | 结果 |
|---|---|---|
| A 正常 | 基线 purge 归零；3 条上报 metrics.update 全收；队列消息数=3（Confirm 投递）；信封字段与 `message-contracts-v1` 一致（event_id UUID/type/schema_version/producer/payload server_id+message_id/UTC 时间） | ✅ |
| B 停机 | Broker 停机期间 2 条上报 **metrics 照常落库**；outbox 保留 pending 未投递 | ✅ |
| C 恢复 | Broker 恢复后发布器**自动补发**；队列消息数=5（3+2 与停机前上报数一致） | ✅ |

出口条件核对（规划文档 §五点二）：Metrics 与 outbox 同事务 ✅；Confirm/Return + 退避重试 ✅；**Broker 停止时 Metrics 继续落库且 Outbox 保留、恢复后补发成功** ✅；普通单元测试不依赖 RabbitMQ ✅（361 tests 全 mock）。

### 2.3 回归与性能

- `verify-alert-ws.mjs` 24/24 PASS（本地告警链路不受 outbox 影响）
- `mvn test` 361 全绿
- 性能对比（MVP-9 基线）：S1 metrics p50 20.2ms（基线 23.7，无回归）；S3 并发 p50 15.3ms（基线 12.5，+2.8ms 为同事务 outbox 写入成本）；吞吐 19.67/s 不变；0 错误帧

## 三、验收中发现并修复的缺陷

### 3.1 Outbox 退避失效（时区偏差，commit `519211d`）

- **现象**：Broker 停机期间 attempts 每秒 +1（可涨至数百），退避未生效
- **根因**：`next_attempt_at` 以 UTC 写入（`Clock.systemUTC`），轮询 SQL 用 `NOW()`（MySQL 会话时区 Asia/Shanghai）——8 小时偏差使退避时刻永远"已到期"
- **修复**：轮询改用 `UTC_TIMESTAMP()` 与写入口径一致；H2 无此函数，测试注册等价别名（`CREATE ALIAS IF NOT EXISTS`，public 静态方法返回 UTC Timestamp）
- **预防**：项目时钟统一 UTC（`ClockConfig`），凡与 SQL 时间函数比较的字段必须用 UTC 口径

### 3.2 验收脚本健壮性（3 处修正）

1. 管理 API `/get` 需 `encoding: auto` 参数（400）
2. 队列 purge（DELETE contents）为异步，基线需轮询归零（竞态超时）
3. 管理 API 204 响应无 body；Broker 重启期连接拒绝应容忍重试（阶段 C）

## 四、已发布 Outbox 保留期清理（后续运维增强）

`V16__add_message_outbox_published_retention_index.sql` 为
`message_outbox(status, published_at, id)` 添加索引，并新增独立的分批清理任务。

- **范围**：仅删除 `status='published' AND published_at < cutoff` 的记录；`pending`
  行（无论重试次数或存续时间）永不由该任务选择。RabbitMQ 业务队列/DLQ、Metrics
  数据和 `message_consume_records` 也不在范围内。
- **安全默认值**：`OUTBOX_CLEANUP_ENABLED=false`，不会因升级自动删除发布审计/重放
  payload。启用时默认保留 30 天、1000 行/批、100 批/轮，cron 为每日 03:30。
- **执行方式**：每批独立事务，同 JVM 通过 `AtomicBoolean` 防止重叠；多 JVM 不提供
  全局运行上限。cutoff 使用 UTC，SQL 按 `published_at, id` 从旧到新有界删除，并用
  双层派生表规避 MySQL 1093。
- **回滚/停用**：设置 `OUTBOX_CLEANUP_ENABLED=false` 并重启后端；已删除的 Outbox
  payload 不可从该表恢复，启用前须确认排障保留期。

## 五、未验证/遗留（留痕）

1. **消费侧（MVP-11）**：ACK 幂等消费、`message_consume_records`、重试耗尽进 DLQ、DLQ 查询与受控重放——队列消息当前堆积（预期行为）。
2. **多实例**：SKIP LOCKED 防重复取行已具备；多 JVM 同库发布待真实多实例验收。
3. **Return 回调**：仅日志留痕（绑定存在时不可路由不会发生）；不可路由重试策略未单独验收。
4. **生产部署**：云端 T5 已具备 RabbitMQ（5672/15672），按本记录接入时需建独立 vhost/用户；HTTPS/WSS 仍待域名备案。
5. 本机 RabbitMQ 为免安装版（`local/rmq` + `local/erl27`，前台运行），仅限本机验收；生产使用系统服务方式。

## 五、MVP-11 启动条件（下一步）

- 本机 broker 与验证实例可用（本记录环境）
- 实现 `susumonitor.alert.metrics` 幂等消费者（event_id 去重 + `message_consume_records` 同事务）
- 将 `AlertEvaluationServiceImpl` 从本地事件切换为消息消费（或双通道过渡），复用 `bench-alert-chain.mjs` 回归对比
