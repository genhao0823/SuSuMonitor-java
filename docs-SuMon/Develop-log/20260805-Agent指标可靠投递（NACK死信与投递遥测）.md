# 2026-08-05 Agent 指标可靠投递（NACK 死信与投递遥测）开发记录

## 完成内容

### M4-1：Server 永久拒绝分类（上一会话收口，本文档补记）

- 提交：`bcbffd0 feat(server): classify permanent metric rejection`
- 新增 `metrics.nack` 帧：以原 `message_id` 关联，payload 含 `server_id`、`code`、`reason`、`message`。
- 拒绝原因三类（`MetricsRejectionReason`）：`invalid_metrics_payload`、`stale_collected_at`、`server_not_found`。
- 只有可关联且永久无效的指标才 NACK；不确定/瞬时失败（DB、内部、限流）仍回泛化 `error`，不得触发 Agent 删队首。
- `AgentWebSocketHandlerTests` + `MetricsServiceTests` 18 PASS。

### M4-2：Agent 消费 metrics.nack 与本地持久化死信

- 提交：`616e531 feat(agent): dead-letter permanently rejected metrics on metrics.nack`
- `internal/wsclient`：`metrics.nack` 路由到专用 handler（`SetMetricsNackHandler`），解析 `MetricsNack`；缺 `message_id` 或载荷畸形时忽略。
- `internal/metricbuffer`：snapshot 升级 **v2**（新增 `dead_letter` 数组）；`Open` 对 v1 文件原地迁移并持久化一次，不丢待确认数据。
- 新增 `RejectHead(messageID, nack)`：仅当队首 `message_id` 匹配时，单次原子持久化"去队首 + 追加死信"；死信与队列共用容量上限（默认 720），超限丢最旧死信并 warn 日志。
- 新增 `Stats()`：pending count/bytes、oldest collected_at、drop count（缓冲满丢弃计数，进程内）、dead-letter count/bytes。
- `internal/reporter`：`HandleMetricsNack` 镜像 ACK 推进逻辑——NACK 命中队首则移入死信并按最小重放间隔发送下一条；**NACK 不重试**（服务端仅对永久无效指标 NACK）。泛化 `error` 帧仍不触碰队首。
- 新增 buffer/reporter/wsclient/main 测试共 10 个用例；`go test ./...`、`go test -race`、`go vet`、`go build` 全 PASS。

### M4-3：投递遥测（Agent 心跳携带 + Server 落库 + Web 展示）

- 提交：`98c1115 feat(agent): expose delivery stats in heartbeat`
  - `HeartbeatPayload` 扩展可选字段：`pending_count`、`pending_bytes`、`oldest_collected_at`、`drop_count`、`dead_letter_count`、`dead_letter_bytes`。
  - `SetHeartbeatStatsProvider` 注入 queue `Stats()`；未注入时载荷为空对象，与旧协议兼容。
- 提交：`dc32918 feat(server): persist agent delivery stats`
  - Flyway **V19**：`servers` 表新增 6 个可空 `delivery_*` 列。
  - 心跳分支解析 `AgentHeartbeatPayload`（新 record），`AgentHeartbeatServiceImpl.heartbeat(session, stats)` 落库；缺失字段保持原值，未携带统计（老 Agent）不触发更新。
  - `ServerStatusVo` 扩展 6 个投递字段，`GET /api/servers/{id}/status` 返回；`openapi-server.json` 契约同步，5/5 契约检查通过。
- 提交：`dcb4382 feat(web): show delivery status on server detail`
  - `ServerDetailView` 状态网格新增：投递积压（条数/字节）、最旧积压采样、缓冲丢弃、本地死信（条数/字节）；统计缺失显示 `-`。
  - 新增共享 `formatBytes` 工具。
- 新增服务端测试：心跳遥测落库（UTC 转换）、空统计跳过更新、handler 解析转发、status 返回投递字段。Go 测试：心跳载荷携带统计。

### E2E：NACK 死信与 error 帧边界

- `api-test/verify-agent-reliable-delivery-e2e.mjs` fixture 扩展至 **15 项 PASS**：
  - 新增 NACK 死信场景：fixture 对队首发 correlated `metrics.nack(stale_collected_at)` → spool entries 清空、`dead_letter` 含对应 `message_id`/`reason`/`code`、Agent 日志出现死信处置记录、**重启后死信仍在**。
  - 新增泛化 error 帧场景：`error` 帧不删除队首，随后真实 `metrics.ack` 正常投递该帧。
- 真实 Java 场景新增第 7 项：管理 API **软删除服务器** → Agent 下次上报收到 `server_not_found` NACK → spool 清空且 `dead_letter` 出现 `server_not_found` 记录（需管理员 DB 凭据运行 `run-agent-reliable-delivery-e2e.ps1`）。

## 验证矩阵

| 项目 | 结果 | 证据 |
|---|---|---|
| Server NACK 分类与关联 | PASS | `AgentWebSocketHandlerTests` + `MetricsServiceTests` 18 tests |
| Server 心跳遥测落库/状态返回 | PASS | `AgentHeartbeatServiceTests`、`AgentWebSocketHandlerTests`、`ServerServiceTests` |
| Maven 全量测试 | PASS | `mvn test` |
| Go 全量测试/race/vet/build | PASS | `go test ./...`、`CGO_ENABLED=1 go test -race ./...`、`go vet ./...`、`go build ./cmd/susumonitor-agent` |
| 前端类型检查与测试 | PASS | `vue-tsc --noEmit`、vitest 20 files / 118 tests |
| OpenAPI 契约 | PASS | 5/5（admin/alert/auth/server/system） |
| Fixture E2E | PASS | 15 项（含 NACK 死信 + 重启持久化 + error 帧不移队首） |
| 真实 Java + MySQL E2E | 待运行 | 需本地 DB 管理员凭据运行 `run-agent-reliable-delivery-e2e.ps1` |

## 备份

- `C:\Backup\SuSuMonitor\20260805-124922-agent-nack-dead-letter\M4-2-before-change`（SHA256SUMS + FILES 校验通过）

## 已知边界与后续

- 死信与队列共用容量上限（默认 720），超限丢最旧；`drop_count` 为进程内计数，重启归零。
- 队列字节上限（`SUSUMONITOR_METRICS_BUFFER_*` 字节级限制）仍未实施，属后续独立模块。
- 多实例/多消费者并发验证仍待独立环境执行。
- 投递遥测经现有 REST 状态接口展示，Monitor WebSocket 状态帧未携带投递统计。
