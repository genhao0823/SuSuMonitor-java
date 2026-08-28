# SuSuMonitor WebSocket Protocol

**Version**: 1.3

**Time standard**: UTC ISO-8601, for example `2026-07-21T12:00:00Z`

**Deployment scope**: single JVM; connection and Monitor ticket state is in memory

## Channels

```text
/ws/agent   Agent authentication, heartbeat, metrics reporting and terminal responses
/ws/monitor Browser metrics subscription, metrics.update delivery and terminal requests
```

Only the two paths above are registered; the historical aliases `/api/ws/agent` and `/api/ws/client` are not supported. Long-lived JWT and Agent Token must not be placed in a URL. `/ws/monitor` accepts only a one-time 30-second Monitor ticket obtained from `POST /api/ws/monitor-ticket`.

## Common Message

Most frames use the following outer structure. `metrics.subscribe` and `metrics.unsubscribe` currently require only `type`, UUID `message_id`, and `payload`; their `timestamp` is optional for compatibility with the shipped browser client. Agent authentication, terminal frames, and server-generated update/error frames use UTC ISO-8601 `timestamp`.

```json
{
  "type": "heartbeat",
  "message_id": "uuid",
  "timestamp": "2026-07-21T12:00:00Z",
  "payload": {}
}
```

## Agent Messages

The first Agent message must be `agent.authenticate`:

```json
{
  "type": "agent.authenticate",
  "message_id": "uuid",
  "timestamp": "2026-07-21T12:00:00Z",
  "payload": {"server_id": 1, "token": "one-time-agent-token"}
}
```

Successful authentication returns `agent.authenticated` with payload `{"server_id": <id>, "authenticated_at": "<UTC ISO-8601>"}`. Authentication expires after 10 seconds if no valid first frame is received. A valid heartbeat updates `servers.last_heartbeat_at` and `agent_status=online`. The `heartbeat.ack` response payload is `{"server_id": <id>, "last_heartbeat_at": "<UTC ISO-8601>"}`. No heartbeat for 90 seconds marks the Agent offline. A newly authenticated connection replaces the previous connection for the same server.

The heartbeat payload may carry Agent delivery telemetry, all fields optional: `pending_count`, `pending_bytes`, `oldest_collected_at`, `drop_count`, `dead_letter_count`, `dead_letter_bytes`. Older Agents send an empty object; the server stores present fields in `servers.delivery_*` for the REST status snapshot and keeps absent fields unchanged.

The Agent message limit is 64 KiB. Invalid JSON uses close code `1007`; oversized messages use `1009`; policy/authentication failures use `1008`. The `error` message payload is `{"code": <int>, "message": "<string>"}`, where `code` uses the same numeric business error codes as the REST API (e.g. `40100` unauthorized, `40002` invalid request parameter). A connection or unauthenticated-session limit returns `42901` and closes with `1008`; heartbeat or metrics rate exhaustion returns `42902` followed by `1008`. Agent upgrade requests are limited per resolved client IP and receive HTTP `429` with `Retry-After: 60` before a WebSocket is created.

`metrics.report` contains one fixed-width `metrics` row, including `server_id`, `collected_at`, `cpu_percent`, `memory_percent`, `memory_used`, `memory_total`, `disk_percent`, `disk_used`, `disk_total`, `net_rx`, `net_tx`, `temperature`, and `load_avg`. Its `message_id` is a required UUID idempotency key. Retrying one report must reuse its original `message_id`; a duplicate is silently accepted without inserting another row or publishing `metrics.update` or `alert.push`. For one server, accepted `collected_at` values must be strictly increasing. A report whose `collected_at` is not strictly greater than the most recently accepted sample is permanently rejected with `metrics.nack` (reason `stale_collected_at`); it is not persisted and emits no event.

After the metrics ingress transaction has committed, the server returns `metrics.ack` with the request `message_id` and payload `{"server_id": <id>, "collected_at": "<accepted UTC ISO-8601>"}`. The acknowledgement confirms only that the server accepted the ingress transaction (including idempotent duplicate acceptance). It does **not** confirm RabbitMQ publication, Monitor frame delivery, or asynchronous alert evaluation. A failed validation or persistence transaction returns the standard `error` frame and never returns `metrics.ack`. Agents that do not consume this optional frame remain compatible. An Agent that has not received `metrics.ack` by its configured acknowledgement deadline may retransmit the unchanged complete `metrics.report` frame with its original `message_id`; the ingress idempotency key makes this retry safe.

A report that the server can correlate and classifies as deterministically permanent is rejected with `metrics.nack` carrying the original `message_id` and payload `{"server_id": <id>, "code": <int>, "reason": "invalid_metrics_payload|stale_collected_at|server_not_found", "message": "<string>"}`. The server returns `metrics.nack` **only** for permanent rejections; uncertain or transient failures (database, internal, rate limit) still return the generic `error` frame. On a correlated `metrics.nack` the Agent moves the rejected FIFO head into its local durable dead-letter and never retries it; generic `error` frames never remove the queue head. Agents that do not consume `metrics.nack` remain compatible but keep retrying a permanently rejected head until they upgrade.

## Monitor Messages

After ticket-authenticated handshake, a browser sends:

```json
{
  "type": "metrics.subscribe",
  "message_id": "uuid",
  "payload": {"server_id": 1}
}
```

`metrics.unsubscribe` removes a subscription. Duplicate subscriptions are idempotent. Only admin or approved users may subscribe to an active server.

After a committed Metrics transaction, subscribers receive:

```json
{
  "type": "metrics.update",
  "message_id": "uuid",
  "timestamp": "2026-07-21T12:00:00Z",
  "payload": {
    "server_id": 1,
    "metrics": {"cpu_percent": 35.2, "collected_at": "2026-07-21T11:59:58Z"}
  }
}
```

After a successful Agent online/offline state transition, subscribers of the affected server receive `server.status.update`:

```json
{
  "type": "server.status.update",
  "message_id": "uuid",
  "timestamp": "2026-08-01T12:00:00Z",
  "payload": {
    "server_id": 1,
    "status": "offline",
    "agent_status": "offline",
    "last_heartbeat_at": "2026-08-01T11:59:30.123456Z"
  }
}
```

The server sends this frame only when persisted Agent state changes between online and offline, never for an ordinary heartbeat. Delivery is best-effort and can be duplicated or delayed; clients must ignore a frame whose non-null `last_heartbeat_at` is older than their current snapshot. Only sessions subscribed to the affected `server_id` receive the frame.

The broadcast never contains Agent Token, Token hash, SSH credentials, database credentials, or private keys.

## Security and Lifecycle

- Monitor ticket lifetime is 30 seconds, expires exactly at `expires_at`, and each ticket is consumed once.
- Ticket state is single-JVM memory only; Redis and distributed session state are not implemented.
- Metrics broadcast runs after the database transaction commits.
- Disconnect removes all subscriptions.
- All Java-to-browser `/ws/monitor` frames use a per-connection protected outbound session. `TERMINAL_MONITOR_SEND_TIME_LIMIT_MILLIS` defaults to `5000` and `TERMINAL_MONITOR_BUFFER_SIZE_BYTES` defaults to `262144` (256 KiB). Spring terminates a slow consumer when either limit is exceeded; Java closes associated terminal sessions with reason `monitor_backpressure`, removes subscriptions, and closes the browser session with `1011` (`SESSION_NOT_RELIABLE`).
- Tokens and complete raw messages must not be logged.
- Agent client IP defaults to the TCP peer address. `X-Forwarded-For` is used only when the TCP peer is within `AGENT_TRUSTED_PROXY_CIDRS`; the resolver strips trusted proxies from right to left and never trusts a direct client header.

## Error Messages

Both Agent and Monitor channels use the same `error` message shape. Server-generated errors may use `message_id: null` when no client frame can be correlated; clients must accept either a UUID or `null`.

```json
{
  "type": "error",
  "message_id": "uuid",
  "timestamp": "2026-07-22T00:00:00Z",
  "payload": {"code": 40002, "message": "invalid request parameter"}
}
```

The `code` field uses the same numeric business error codes as the REST API (`ErrorCode.java`). Clients should branch on `code` rather than parsing `message` text.

## Alert Messages

After an alert is triggered and the alert evaluation transaction commits, subscribers of the affected server receive `alert.push`:

```json
{
  "type": "alert.push",
  "message_id": "uuid",
  "timestamp": "2026-07-22T00:00:00Z",
  "payload": {
    "server_id": 1,
    "alert": {"id": 1, "rule_id": 2, "metric": "cpu", "current_value": 90.5, "threshold_value": 80.0, "level": "warning", "status": "unread", "triggered_at": "2026-07-22T00:00:00Z"}
  }
}
```

`alert.push` reuses the `/ws/monitor` channel and `MonitorSubscriptionRegistry`. Only sessions subscribed to the affected `server_id` receive the push. The broadcast never contains Agent Token, SSH credentials, or database credentials.

When an alert recovers (the evaluation transaction marks the record `resolved`), the same `alert.push` frame is sent with `payload.alert.status` set to `resolved` and `payload.alert.resolved_at` carrying the recovery time (2026-08-15); clients treat it as the same push signal (e.g. refresh the records list) rather than a new alert. Recovery is additionally published as the `alert.resolved.v1` broker event (see message-contracts-v1.md §五) for outbound recovery notifications.

## Terminal Messages

Terminal messages use the common outer structure, require a UUID `message_id`, and use a UTC ISO-8601 `timestamp`. Java routes browser control frames to the matching authenticated Agent and routes Agent responses only to the browser connection that created the session.

All users whose latest database `review_status` is `approved` may request a root terminal regardless of role. This grants control equivalent to root access on the target family Linux host. Java must recheck the latest user state for every `terminal.open`, `terminal.input`, `terminal.resize`, and `terminal.close`; it must not rely only on the Monitor handshake snapshot.

Browser to `/ws/monitor`:

```text
terminal.open    payload: server_id, cols (1-300), rows (1-100)
terminal.input   payload: session_id, data (Base64, decoded 1-16 KiB)
terminal.resize  payload: session_id, cols (1-300), rows (1-100)
terminal.close   payload: session_id
```

Java to `/ws/agent` uses the same four types and additionally includes `server_id`; `terminal.open` also includes the Java-generated UUID `session_id`.

For every browser control frame, Java rechecks the user's current `approved` status and the session ownership in persistent metadata. It then resolves the `session_id` through the single-JVM relay registry and serializes writes to the target Agent connection. Browser-supplied `server_id` and `session_id` are never trusted for existing sessions; Java injects the persisted values before forwarding.

Java applies independent in-memory Token Buckets before forwarding browser control frames. `terminal.open` is scoped to the originating Monitor WebSocket because no server session exists yet. `terminal.input`, `terminal.resize`, and `terminal.close` are scoped to the originating Monitor WebSocket plus the validated `session_id`, so a busy terminal cannot consume another terminal's control-frame allowance. The default limits are: open 6/minute with burst 2, input 600/minute with burst 120, resize 60/minute with burst 20, and close 30/minute with burst 10. Limits are configured only through `TERMINAL_OPEN_*`, `TERMINAL_INPUT_*`, `TERMINAL_RESIZE_*`, and `TERMINAL_CLOSE_*` environment variables. Java releases all buckets when the Monitor WebSocket closes. An over-limit frame is not relayed and receives `error.payload.code=42904`.

Agent to `/ws/agent`:

```text
terminal.opened  payload: server_id, session_id, shell
terminal.output  payload: server_id, session_id, data (Base64, decoded 1-16 KiB)
terminal.closed  payload: server_id, session_id, reason (1-128 chars), optional exit_code (integer)
terminal.error   payload: server_id, optional session_id, code, message (1-256 chars)
```

The browser must never send `terminal.opened`, `terminal.output`, `terminal.closed`, or `terminal.error`. The Agent must never send `terminal.open`, `terminal.input`, `terminal.resize`, or `terminal.close`. Java generates `session_id`; the browser and Agent cannot choose it. Terminal input and output must not be persisted or logged. Java applies an independent in-memory per-server-`session_id` raw-byte Token Bucket after protocol, authenticated Agent `server_id`, and relay-binding checks, before forwarding `terminal.output`: `TERMINAL_OUTPUT_RATE_BYTES_PER_SECOND` defaults to 262144 (256 KiB/s) and `TERMINAL_OUTPUT_BURST_BYTES` defaults to 524288 (512 KiB). The bucket starts full and never waits for output. On an over-limit block Java does not send that output to Monitor; it sends a server-generated `terminal.close` to Agent, persists `status=closed` and `reason=output_rate_exceeded`, removes the relay binding, and releases the bucket. Both values must be positive, and the burst must be at least 16384 bytes, the protocol maximum decoded data size.

Before forwarding an Agent terminal response, Java validates its protocol payload, verifies payload `server_id` matches the authenticated Agent connection, and verifies that `session_id` is bound to that same server. `terminal.opened` transitions metadata to `open`; normal `terminal.closed` persists closure, removes the in-memory relay binding, and releases the output bucket.

When the originating Monitor connection disconnects, Java removes its relay bindings, sends a server-generated `terminal.close` to each reachable Agent, marks the related metadata closed with `monitor_disconnected`, and releases output buckets. If the protected outbound session detects backpressure first, the reason is instead `monitor_backpressure`; this reason is not overwritten by the later WebSocket close callback. When the current Agent connection disconnects, Java marks its routed sessions as `error` with `agent_disconnected`, releases output buckets, and sends a server-generated `terminal.closed` frame (payload `server_id`, `session_id`, `reason: "agent_disconnected"`) to each still-open originating browser session; this frame lets Web and Android clients surface the disconnect immediately (Android may auto-reconnect with backoff). Delivery is best-effort and never blocks session metadata cleanup; a superseded Agent connection cannot close or notify sessions owned by its replacement.

Terminal-specific error codes are `40003` invalid payload, `40302` access denied, `40403` session not found, `40903` session state conflict, `40904` Agent offline, `42903` session limit reached, and `42904` terminal message limit reached.

## Runtime Validation

（2026-08-16 注：本节为 2026-07-21 历史快照。）The following paths were validated against the isolated MySQL database `susumonitor_agent_ws_validation_20260721` and an application instance on port 18081:

```text
Agent Token REST       19 checks passed
/ws/agent              14 checks passed
/ws/monitor            16 checks passed
Flyway V1-V9           all success=1
```

The Agent validation covered authentication, heartbeat, Metrics persistence, latest/history queries and old Token rejection after rotation. The Monitor validation covered approved-user Ticket issue, subscribe, post-commit `metrics.update`, unsubscribe and single-use Ticket rejection.

Automated boundary tests additionally verify that a successful transaction sends one `metrics.update`, a rolled-back transaction sends none, a Ticket is usable at 29.999 seconds but rejected at 30 seconds, and two concurrent Ticket consumers produce at most one success.

The first version does not provide cross-JVM connection state, Ticket sharing, subscription routing or broadcast fan-out. These require an external state and messaging layer before multi-instance deployment.

## Current Implementation Gaps（2026-08-28，对照 `main @ 4e4cd86`）

本文档是目标协议定义。以下条目曾为当前代码与本文不一致的已知缺口；**第 1、2、4 项已于 2026-08-28 修复**，第 3、5、6 项保留为当前待办（此前修复曾被回滚提交 `0970c8c`（Web）、`870aec6`（Java）、`4e4cd86`（Go）撤销）：

1. ~~**Metrics `collected_at` 未严格校验 UTC**~~ **已修复**：`MetricsServiceImpl.validatePayload` 现拒绝非 UTC offset 的 `collected_at`。
2. ~~**Agent UUID fallback 不满足 RFC 4122**~~ **已修复**：Go `newUUID` 随机源失败时 fallback 仍产出合法 UUID v4（`formatUUID(newUUIDFallbackBytes(...))`），并有 `TestNewUUIDFallback` 覆盖。
3. **终端控制帧超限不关闭 Monitor 连接（待办）**：终端限流超限时服务端只回 `42904` error 帧并保留连接（`MonitorWebSocketHandler`），不再额外断开；验收矩阵中“控制帧洪泛后 Monitor 被断连”的描述应改为“收到 42904 error，连接保持”。
4. ~~**Agent backend URL 带路径会拼出未注册端点**~~ **已修复**：`config.validate` 解析 URL 并拒绝含 path/query/fragment 的 backend URL，避免拼出 `/api/ws/agent`。
5. **ACK/NACK 载荷校验不足（待办）**：Go 客户端对 `metrics.ack` 只校验外层 `message_id` 非空，对 `metrics.nack` 只校验可反序列化，未校验 payload 的 `server_id` 归属、`reason` 合法性与 canonical UUID。
6. **快照时间校验不足（待办）**：Go metricbuffer 快照打开时只校验 `timestamp`/`collected_at` 非空，未解析并校验 UTC ISO-8601。

上述待办（第 3、5、6 项）已记录在根 `README.md`“当前待解决问题”章节；修复后再按本文档重新执行验收。
