# Command Protocol v1（AI 命令域 M1 审批制契约）

**版本**：v1
**状态**：v1 已冻结（2026-09-03）；M1 审批制。未实现前本文为契约源（source of truth）；实现落地后以 `docs-SuMon/Develop-log/` 对应日志为验证证据，本文与实现冲突时先修订本文、再改代码。
**时间标准**：UTC ISO-8601，例如 `2026-09-03T12:00:00Z`
**配套威胁模型**：`ai-command-domain-threat-model.md`

## 一、适用范围

本文定义 AI 命令域（command domain）在 `/ws/agent` 上使用的 `command.execute` 与 `command.result` 帧契约、L1 只读诊断命令模板白名单（单一来源）与强制安全语义。M1 阶段命令域为审批制（approval-based）：每条命令执行必须先经 Java 侧审批状态机批准，才允许下发到 Agent 执行；命令域由独立 kill switch `AI_COMMAND_ENABLED` 控制，默认 `false`。

本文不覆盖、也不修改 `terminal.*` 与 `metrics.*` 的任何既有契约，三者互不复用帧型。既有边界不变：AI 永不接触 `terminal.*` PTY，命令执行一律走独立的 `command.*` 通道。威胁分析与控制矩阵见 `ai-command-domain-threat-model.md`。

## 二、统一信封

`command.*` 帧与 `/ws/agent` 现有帧使用完全一致的外层信封：

```json
{
  "type": "command.execute",
  "message_id": "5a1e0c6e-9f2a-4b7e-8c1d-3f4a5b6c7d8e",
  "timestamp": "2026-09-03T12:00:00Z",
  "payload": {}
}
```

| 字段 | 必填 | 规则 |
|---|---:|---|
| `type` | 是 | Java→Agent 为 `command.execute`；Agent→Java 为 `command.result`。 |
| `message_id` | 是 | UUID。`command.result` 的外层 `message_id` 必须原样继承对应 `command.execute` 的 `message_id`（Go 侧 `NewMessageWithID` 语义）；Java 以该字段关联请求与响应。 |
| `timestamp` | 是 | UTC ISO-8601。 |
| `payload` | 是 | 见 §三、§四。 |

## 三、`command.execute` payload（Java→Agent）

```json
{
  "server_id": 123,
  "execution_id": "0d9f6a3e-1c2b-4d5e-9a8b-7c6d5e4f3a2b",
  "template": "service_status",
  "params": {"unit": "nginx.service"},
  "timeout_seconds": 30
}
```

| 字段 | 类型 | 必填 | 规则 |
|---|---|---:|---|
| `server_id` | int64 | 是 | 必须等于该已认证 Agent 连接认证时绑定的 `server_id`；不一致时拒绝执行。 |
| `execution_id` | UUID | 是 | Java 生成，命令审计主键；同一 `execution_id` 的重试与补发必须保持语义不变。 |
| `template` | string | 是 | L1 白名单模板 id（§五）；不在白名单内一律回 `error=template_unknown`，不执行。 |
| `params` | object | 是 | 具名参数；键名与取值必须匹配模板声明（§五）；无参模板必须传空对象 `{}`。 |
| `timeout_seconds` | int | 是 | 取值 1-300；Agent 实际取 `min(该值, 本机上限)` 作为有效超时。 |

## 四、`command.result` payload（Agent→Java）

```json
{
  "server_id": 123,
  "execution_id": "0d9f6a3e-1c2b-4d5e-9a8b-7c6d5e4f3a2b",
  "success": true,
  "exit_code": 0,
  "stdout": "Filesystem      Size  Used Avail Use% Mounted on\n...",
  "stderr": "",
  "truncated": false,
  "duration_ms": 42
}
```

| 字段 | 类型 | 必填 | 规则 |
|---|---|---:|---|
| `server_id` | int64 | 是 | 原样返回请求中的 `server_id`。 |
| `execution_id` | UUID | 是 | 原样返回请求中的 `execution_id`；幂等与审计主键。 |
| `success` | bool | 是 | `true` 当且仅当进程以退出码 0 正常结束且未超时、未被拒绝。 |
| `exit_code` | int | 是 | 进程退出码；未取得真实退出码时（校验拒绝、限流、超时终止、无法启动）固定为 `-1`。 |
| `stdout` | string | 是 | 已脱敏、已截断的标准输出（UTF-8 文本）；无内容为空串；默认上限 64 KiB。 |
| `stderr` | string | 是 | 同 `stdout` 规则。 |
| `truncated` | bool | 是 | `stdout` 或 `stderr` 是否因 64 KiB 上限被截断。 |
| `duration_ms` | int64 | 是 | 进程从启动到结束（含被强制终止）的实际耗时；未启动进程的校验类失败为 `0`。 |
| `error` | string | 否 | 可选；`success=false` 时的错误枚举值（下表），不得携带内部实现细节。 |

### `error` 枚举

| 枚举值 | 语义 |
|---|---|
| `template_unknown` | `template` 不在 L1 白名单；一律不执行。 |
| `param_invalid` | 参数键缺失、多余或值不匹配模板正则。 |
| `rate_limited` | Agent 本机限流（默认 10/分钟）命中，未执行。 |
| `timeout` | 超过有效超时被强制终止。 |
| `execution_error` | 进程已启动但执行失败（含非零退出、无法启动）。 |
| `unsupported_platform` | 本机平台或本机开关不支持该执行（如在无 systemd 主机执行 `service_status`），未执行。 |

语义细则：

- 校验类失败（`template_unknown`/`param_invalid`/`rate_limited`/`unsupported_platform`）不启动进程：`stdout`/`stderr` 为空串、`exit_code=-1`、`duration_ms=0`。
- 非零退出：`success=false`、`error=execution_error`，`exit_code` 携带真实退出码。
- 超时：进程被强制终止，`success=false`、`error=timeout`、`exit_code=-1`。

## 五、L1 模板表（单一来源）

**本表是 L1 模板的唯一权威来源（single source of truth）。** Java 与 Go 双侧模板表必须与本文逐字一致（模板 id、argv 序列、参数正则）；任何增删改必须先修订本文，再同步双侧实现，不允许任何一侧单独扩展。

| id | argv | 参数 |
|---|---|---|
| `disk_free` | `df -h` | 无 |
| `mem_free` | `free -m` | 无 |
| `uptime` | `uptime` | 无 |
| `listening_ports` | `ss -tlnp` | 无 |
| `process_list` | `ps aux` | 无 |
| `top_snapshot` | `top -b -n1` | 无 |
| `service_status` | `systemctl status {unit}` | `unit`: `^[a-zA-Z0-9_.@:-]{1,128}$` |
| `service_logs` | `journalctl -u {unit} -n {lines} --no-pager` | `unit`: 同 `service_status`；`lines`: `^[0-9]{1,4}$` |

渲染规则：

- `{unit}`、`{lines}` 为参数占位符；渲染后按空白切分为独立 argv 元素，参数值整体作为单个 argv 元素传入，不做二次拆分、不做 shell 解析。
- 参数必须同时满足：键名与模板声明一致、无多余键、值匹配对应正则；任一不满足即 `param_invalid`。
- M1 仅限本表 L1 只读模板；写操作或交互式模板属 M2/M3 设想，不在本文范围。

## 六、安全语义

以下为命令域强制安全语义，实现不得降级：

- **无 shell、argv 直执行**：模板渲染结果以 argv 数组直接交给进程创建 API（Go `exec.Command` 语义），不经过 `/bin/sh -c`，不允许字符串拼接命令行。
- **参数双重校验**：Java 下发前按模板渲染预览并校验参数正则，未通过不下发；Go 执行前对 `template` 与 `params` 按同一张模板表（§五）二次校验，任一侧拒绝即不执行。
- **输出截断与脱敏**：`stdout`/`stderr` 默认各 64 KiB 截断上限，超限置 `truncated=true`；输出必须先经凭据样式脱敏（口令、Token、密钥样式等）再回传。
- **每命令独立进程、强制超时**：每条命令在独立进程中执行，到有效超时 `min(timeout_seconds, 本机上限)` 强制终止（进程组 kill），并回 `error=timeout`。
- **Agent 本机限流**：默认 10 次/分钟；超限不排队，直接回 `error=rate_limited`。
- **result 幂等**：以 `execution_id` 为幂等键；同一 `execution_id` 的重复 `command.result` 在 Java 侧只产生一次业务与审计效果。
- **离线不下发**：Agent 离线时 Java 侧拒绝下发——不发送 `command.execute` 帧、不做离线排队积压；相关执行请求显式失败并留审计记录。
- **独立 kill switch**：Java 全局开关 `AI_COMMAND_ENABLED` 默认 `false`，关闭时命令域整体禁用、不组装不下发任何帧；Agent 侧另有本机 enabled 开关，双侧任一关闭即不执行。

### M1 审批制语义

- 每条命令执行必须先在 Java 侧登记为待审批请求并经管理员审批；仅 `approved` 且未过期的审批允许下发 `command.execute`。
- 审批对象必须绑定同一 `execution_id` 与渲染后的 argv 预览；审批通过后不得改参、不得换模板、不得换 `server_id`，下发前复核绑定不一致即拒绝。
- 审批过期（有效期由 Java 配置）后不得下发；过期审批只能作废或重新发起，不得自动续期。
- 审批拒绝、过期作废、离线拒绝均属于确定性失败，各留最小审计记录，不计入 Agent 限流。

## 七、兼容性与失败处理

- **未知模板**：收到不在 §五 白名单内的 `template` 一律回 `command.result`（`error=template_unknown`），绝不执行；Java 侧同样拒绝，不产生待下发帧。
- **未知字段**：新增字段必须为可选，接收方忽略未知字段；删除字段、改变字段类型或枚举含义必须递增版本（v2），不得复用 v1。
- **旧 Agent 兼容**：未实现命令域的 Agent 收到 `command.execute` 应忽略该帧并保持连接；Java 侧仅对命令域开启的连接下发，不依赖旧 Agent 的报错路径。
- **范围边界**：本契约不覆盖 `terminal.*` 与 `metrics.*`；`command.*` 帧不得携带终端数据，也不得复用 metrics 的 `message_id` 幂等语义（命令域幂等键是 `execution_id`，见 §二、§六）。

## 八、当前实现边界

截至 2026-09-03，命令域仅有本文契约，Java 侧尚未实现；实现落地后必须以 `docs-SuMon/Develop-log/` 对应日志为证据，并参照 `message-contracts-v1.md` §八-§十三 的模式在本文补记实现确认。
