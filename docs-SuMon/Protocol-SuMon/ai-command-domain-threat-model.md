# AI 命令域威胁模型（M1 审批制）

**版本**：v1
**日期**：2026-09-03
**状态**：随 `command-protocol-v1.md` 同步冻结；实现以 `docs-SuMon/Develop-log/` 为证。
**配套契约**：`command-protocol-v1.md`（帧契约、L1 模板表单一来源、安全语义）

## 一、范围与安全目标

本文分析 AI 命令域 M1（审批制）的威胁与控制。M1 范围：仅 L1 只读诊断模板（`command-protocol-v1.md` §五）经 Java 侧审批状态机批准后，通过 `/ws/agent` 的 `command.execute`/`command.result` 独立通道下发到 Agent 执行。

- **M2/M3 不在本文范围**：未来若定义交互式或写操作类能力，必须另行契约修订与威胁建模，不得默认继承本文结论。
- **既有边界不变**：AI 永不接触 `terminal.*` PTY；`terminal.*` 仍为 admin-only 交互终端帧，与 `command.*` 互不复用。
- 安全目标：白名单外命令不可执行；参数注入不可行；越权 `server_id` 不可达；未审批/过期审批不可下发；输出不泄密；全程可审计、可一键止损。

## 二、组件与信任边界

| 组件 | 信任级别 | 责任与边界 |
|---|---|---|
| AI 诊断链路 | 不完全可信 | 只能产出"建议命令"（模板 id + 具名参数），不能自选模板外命令、不能直接触达 WS 通道、不能绕过审批。 |
| 审批管理员 | 受控人因 | 审批渲染后的 argv 预览；审批绑定 `execution_id`，不可部分授权或事后改参。 |
| Java 命令编排 | 受控边界 | 审批状态机、参数渲染与预校验、`server_id` 归属校验、下发、审计与 kill switch 的唯一编排点。 |
| `/ws/agent` 命令通道 | 既有受控通道 | 复用既有一次性 Agent Token 认证与连接-`server_id` 绑定；不新增平行的认证机制。 |
| Go Agent 执行器 | 受控执行端 | 执行前二次校验模板与参数、本机限流、独立进程+强制超时、输出脱敏截断、`execution_id` 原样回传。 |
| 目标主机 | 资产 | 仅暴露 L1 只读命令可观测的信息面；命令不得产生写效果。 |

## 三、威胁表

| # | 威胁 | 场景与影响 | 必须控制 |
|---|---|---|---|
| T1 | 模板外命令 | AI 输出被诱导或上游被篡改，试图执行白名单外命令（如删除文件、反弹 shell）。 | 双侧模板校验：Java 只接受 L1 模板 id，Go 执行前二次查表；未知 `template` 一律 `template_unknown` 不执行；协议无自由命令字段。 |
| T2 | 元字符注入 | `params` 中注入 `;`、`&&`、`$()`、反引号、换行等拼接新命令。 | 无 shell、argv 直执行（参数值整体作为单个 argv 元素）；参数正则白名单（`unit: ^[a-zA-Z0-9_.@:-]{1,128}$`、`lines: ^[0-9]{1,4}$`）双侧逐字一致，元字符不可通过。 |
| T3 | 越权 server_id | 借 A 服务器的已认证连接执行指向 B 服务器的命令，或 Java 侧错配目标。 | `payload.server_id` 必须等于已认证连接绑定的 `server_id`，Java 下发前与 Go 收帧后双侧校验；审计行记录实际 `server_id`。 |
| T4 | 审批绕过与过期 | 未审批直接下发、审批过期后仍下发、审批通过后改参换模板。 | 审批状态机：仅 `approved` 且未过期可下发；审批绑定 `execution_id` 与渲染后 argv 预览，下发前复核绑定一致；`AI_COMMAND_ENABLED` 默认 `false` 提供总止损。 |
| T5 | 重放 | 捕获或重发历史 `command.execute`/`command.result`，造成重复执行或重复审计效果。 | `execution_id` 幂等：同一执行只产生一次业务与审计效果；审批一次性消费且有有效期；重放请求仍受限流与开关约束。 |
| T6 | 输出泄密（凭据回显） | `stdout`/`stderr` 回显口令、Token、密钥等凭据，进入 AI 上下文或前端展示。 | 输出先凭据样式脱敏再回传；默认 64 KiB 截断 + `truncated` 标记；审计与 AI 上下文只允许取脱敏后内容。 |
| T7 | Agent 中间人 | 攻击者伪装 Agent 接收命令，或伪造 `command.result` 污染审计与展示。 | 复用既有 `/ws/agent` 认证：一次性 Agent Token、认证后连接与 `server_id` 绑定、新连接顶替旧连接；不新增平行认证路径。 |
| T8 | DoS / 资源耗尽 | 命令洪泛拖垮 Agent 或目标主机（`top`/`journalctl` 等输出放大）。 | Agent 本机限流默认 10/分钟（超限 `rate_limited` 不排队）；强制超时 `min(timeout_seconds≤300, 本机上限)` + 独立进程隔离；输出 64 KiB 截断。 |

## 四、控制矩阵

| 控制项 | 落点（Java / Go / 双侧） | 覆盖威胁 | 契约依据 |
|---|---|---|---|
| 双侧模板校验（L1 白名单逐字一致） | 双侧 | T1、T2 | command-protocol-v1.md §五、§六 |
| 参数双重校验（Java 渲染预览 + Go 执行前复查） | 双侧 | T2 | §六 |
| `server_id` 连接绑定校验 | 双侧 | T3 | §三 |
| 审批状态机（approved、未过期、绑定 `execution_id` 与 argv 预览） | Java | T4、T1 | §六 M1 审批制语义 |
| `execution_id` 幂等（审计主键 + result 幂等） | Java 为主，Go 原样回传 | T5 | §二、§四、§六 |
| 脱敏截断（凭据样式脱敏 + 64 KiB 截断 + `truncated` 标记） | Go 执行侧产出，Java 侧复核 | T6 | §四、§六 |
| 独立 kill switch（`AI_COMMAND_ENABLED` 默认 `false`；Agent 本机 enabled 开关） | Java 装配层 + Go 本机 | 全部（总止损） | §六 |
| 限流 + 强制超时 + 独立进程 | Go（本机限流/进程组 kill），Java 侧限流兜底 | T8 | §六 |
| 审计行（每 `execution_id` 一行） | Java | T1-T5 取证、T7 异常发现 | §三、§六 |

## 五、审计行要求

- 每条命令执行在 Java 侧以 `execution_id` 为主键落一行审计：`server_id`、`template`、渲染后 argv 预览、审批人与审批时间、下发/完成时间、`success`、`exit_code`、`truncated`、`error`、`duration_ms` 与脱敏后输出摘要。
- 审计行不得包含：Agent Token、SSH 凭据、未脱敏输出原文、AI prompt 原文。
- 重复 `command.result` 命中幂等时只更新既有行，不新增行；审批拒绝、过期作废、离线拒绝均各留最小审计记录。

## 六、明确不变式

- AI 永不接触 `terminal.*` PTY；该边界与 `ai-data-flow-threat-model.md` §九一致，命令域受控解禁不改变它。
- 命令只走 `command.*` 独立通道；禁止借 `terminal.*`、`metrics.*` 或任何 REST 执行面实现命令下发。
- M1 仅限 L1 只读模板；任何写操作、交互式会话（M2/M3 设想）不在本文与当前契约范围，未来必须另行设计并重做威胁建模。
- 双侧模板表不一致视为实现缺陷，一律以 `command-protocol-v1.md` §五为准修复。

## 七、当前实现边界

截至 2026-09-03，命令域仅有契约，实现未经验收；落地后以 `docs-SuMon/Develop-log/` 对应日志为证，并按 §三 威胁表逐项补测试证据（T1-T8 各至少一条用例）。
