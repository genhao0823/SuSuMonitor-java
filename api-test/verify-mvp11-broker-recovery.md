# MVP-11 Broker 停机、恢复与消费者重连验收

> 此验证需要人工停止/启动本机 RabbitMQ；脚本不得自动操作 Broker。仅在隔离验证库执行。

## 前置条件

- 两个验证后端以同一 MySQL、同一 RabbitMQ vhost 启动，其中至少一个 `AlertMessageConsumer` 已启用；
- 验证后端为 `http://localhost:18081`（或设置 `SUSUMONITOR_VALIDATION_BASE_URL`）；
- `SUSUMONITOR_VALIDATION_ADMIN_USERNAME`、`SUSUMONITOR_VALIDATION_ADMIN_PASSWORD` 和 RabbitMQ Management API 凭据已设置；
- 在验证数据库中已有一个 CPU 阈值规则；不得指向生产数据库、生产 Broker 或生产端口。

## 操作步骤与通过标准

1. **Broker 在线基线**：确认 `GET /api/ready` 返回 200，`rabbitmqctl list_queues -p susumonitor name messages` 中 `susumonitor.alert.metrics` 接近 0。
2. **停止 Broker**：在本机终端停止 RabbitMQ，并再次请求 `/api/ready`。期望 HTTP 503 且业务码 `50301`（`rabbitmq unavailable`）；Java 进程不得退出。
3. **停机上报**：通过 Agent WS 连续上报 2 条高 CPU 指标（可参照 `api-test/verify-outbox.mjs` 的阶段 B）。期望 Monitor 仍收到 `metrics.update`、MySQL metrics 增长、`message_outbox` 出现 pending 行；此阶段不应出现新的 `alert.push`。
4. **恢复 Broker**：启动本机 RabbitMQ，等待 `/api/ready` 恢复 200。观察后端日志中的 RabbitMQ 连接恢复记录，记录恢复起止时间。
5. **最终一致性**：等待最多 60 秒，断言 Outbox pending 行回落、`susumonitor.alert.metrics` 回到 0、上述两条指标各产生一次 alert record/alert.push；记录积压峰值和恢复耗时。
6. **重连证据**：保留两个后端实例日志中连接失败、连接恢复和消费恢复的时间戳。若没有独立日志，至少记录 RabbitMQ Management UI 的 consumer 数从 0 恢复到预期实例数。

## 辅助命令

```bash
# 安全运行既有 Outbox 停机/恢复验收（仅验证库；其队列计数断言适用于未启用消费者的 MVP-10 模式）
cd api-test
SUSUMONITOR_VALIDATION_ADMIN_USERNAME=... \
SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=... \
RABBITMQ_MANAGEMENT_USER=... \
RABBITMQ_MANAGEMENT_PASSWORD=... \
node verify-outbox.mjs

# 队列、consumer 与积压观察
rabbitmqctl list_queues -p susumonitor name messages consumers
curl -i http://localhost:18081/api/ready
```

`verify-outbox.mjs` 的阶段 A/C 以“队列消息增加”为断言，故在 MVP-11 消费者已运行时不能直接将其 PASS 结果作为本验收结论。本文件定义的验收必须额外确认告警记录、消费恢复与队列回落。

## 当前记录

- **状态：待真实环境执行。**
- 尚未记录 Broker 停止、恢复、消费者重连的实际时间、积压峰值或日志证据；不得将本方案视为已验收通过。
