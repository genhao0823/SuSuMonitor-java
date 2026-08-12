# 开发日志: Agent metrics.nack 有限重试策略（retriable_server_error + snapshot v3）

**日期**: 2026-08-12
**操作人**: ZCode
**阶段**: Polish-7 M5

## 背景

Agent 可靠投递 M1-M4 的设计中 `metrics.nack` 一律视为"永久拒绝 → 本地死信不重试"。20260810 收口日志将"metrics.nack 重试策略"列为独立模块。本模块落地分级重试：服务端区分**可重试拒绝**（入库阶段临时故障）与**永久拒绝**，Agent 对可重试原因做有限指数退避重试，预算耗尽后再死信。

## 设计决策（留痕）

- **现有三个 nack 原因均为永久性**：`invalid_metrics_payload`（载荷非法）、`stale_collected_at`（采样时间过期）、`server_not_found`（服务器不存在/已删除）——重发不会改变拒绝条件，维持死信不重试。
- **新增可重试原因** `retriable_server_error`：服务端 `MetricsServiceImpl.report()` 入库阶段捕获 `DataAccessException`（数据库连接/锁等临时故障，排除 `DuplicateKeyException` 竞态）→ 包装为 `MetricsRejectedException(RETRIABLE_SERVER_ERROR)`（映射 50001 DATABASE_ERROR）→ WS 层 nack 载荷自动携带 reason。
- **Agent 分级**：`RejectOrRetryHead` 判定 `retriable_server_error` 且 `NackCount < maxRetries` → **队首原地保留**（严格时序不受影响）+ 计数 +1 持久化 + 退避重发；否则移入死信（原逻辑）。
- **重试预算持久化**：快照升级 v3（`entries` 由裸帧改为 `{frame, nack_count}`），v1/v2 自动原地迁移（NackCount=0），不丢弃任何待确认数据。
- **退避**：`NackRetryInitial`(2s) 起指数翻倍，封顶 `NackRetryMaxDelay`(60s)，叠加既有 equal jitter；重发复用现有 retryTimer 机制。

## 备份留痕

- `local/backup-polish7/m5/`（8 个文件：reporter.go / metricbuffer/buffer.go+buffer_test.go / config/config.go / wsclient/message.go / MetricsRejectionReason.java / MetricsServiceImpl.java，修改前备份）

## 改动内容

### 服务端（server-java-SuMon）

- `MetricsRejectionReason`：新增 `RETRIABLE_SERVER_ERROR("retriable_server_error")`；枚举 javadoc 更新为两级分类（永久 vs 可重试）。
- `MetricsRejectedException`：reason → HTTP 错误码映射改为 switch（SERVER_NOT_FOUND→40400、RETRIABLE_SERVER_ERROR→50001、其余→40000）；javadoc 更新。
- `MetricsServiceImpl.report()`：DB 操作段包 try/catch——`DuplicateKeyException` 原样上抛（数据竞态非可重试），其余 `DataAccessException` → `MetricsRejectedException(RETRIABLE_SERVER_ERROR)`。

### Agent（agent-go-SuMon）

- `metricbuffer/buffer.go`：`snapshotVersion=3`；新增 `QueueEntry{Frame, NackCount}`；`snapshot`（v3，entries 带 frame 包装）与 `legacySnapshot`（v1/v2 裸帧）分版本解析；v1/v2 → v3 原地迁移；`Enqueue/Acknowledge/Head/Stats/persist` 适配 QueueEntry；新增 `RejectOrRetryHead(messageID, nack, maxRetries)`（返回 retried/count/entry/rejected/evicted），`RejectHead` 退化为 maxRetries=0 的快捷方式（旧测试兼容）。
- `reporter/reporter.go`：`Options` 新增 `NackRetryMax/NackRetryInitial/NackRetryMaxDelay`；`HandleMetricsNack` 分级——重试路径保留队首并调度退避重发（`nackRetryDelay` 指数退避 + jitter），拒绝路径维持原死信逻辑。
- `internal/config/config.go` + `.env.example` + `README.md`：`SUSUMONITOR_NACK_RETRY_MAX`(3) / `SUSUMONITOR_NACK_RETRY_INITIAL_SECONDS`(2) / `SUSUMONITOR_NACK_RETRY_MAX_SECONDS`(60)。
- `cmd/susumonitor-agent/main.go`：`newReporterOptions` 装配三个新配置。

### 测试

- buffer_test +3：可重试 nack 保留队首且计数跨重启持久化（v3）/ 预算耗尽转死信 / 永久原因跳过重试直接死信。
- reporter_test +3：可重试 nack 重发同一 message_id 后 ACK 出队 / 预算耗尽推进下一帧并死信 / `nackRetryDelay` 翻倍与封顶。
- 服务端 MetricsServiceTests +2：可恢复 DB 故障包装为 RETRIABLE_SERVER_ERROR（50001）/ DuplicateKeyException 不误判为可重试。

## 验证

- `go build ./...` + `go vet ./...` 通过；`go test ./...` 全绿（metricbuffer/reporter 新增 6 例）。
- `./mvnw test`：480 tests 全绿（478 + 2 新增）。

## 边界与说明

- nack 重试计数随快照持久化，Agent 重启后预算延续（不会重置导致无限重试）。
- 重试期间队首阻塞后续帧（严格时序设计属性）；预算耗尽或永久拒绝即推进。
- 泛化 `error` 帧仍不删除队首（原 M4 语义不变）。

## 下一步

- M6：告警消费多消费者并发（AlertRabbitConfig 并发参数 + 本地 broker 验收）
