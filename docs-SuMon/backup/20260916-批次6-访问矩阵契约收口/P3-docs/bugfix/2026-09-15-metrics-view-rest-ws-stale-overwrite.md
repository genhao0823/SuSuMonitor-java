# 2026-09-15 监控页快照初载竞态（迟到 REST 覆盖新 WS 快照）与 lint 门禁回归

- **严重级别**：中（数据时效性瞬态失真，自愈但不修正则每次竞态命中后旧快照驻留至下一个上报周期）/ 低（门禁回归）
- **影响范围**：`web-vue-SuMon/src/views/MetricsView.vue`（进程/资源两张实时卡片）、`web-vue-SuMon/src/components/ResourcesCard.spec.ts`（lint 门禁）
- **发现方式**：2026-09-15 对最近 24 小时提交（cadc788..4dd5cdc，共 29 个）的专项 review——三套测试全绿后逐文件读码，定位到挂载期异步时序缺陷；lint 回归由 `npm run lint`（--max-warnings 0）在 HEAD 上复现
- **修复提交**：本文件所在批次（fix(web) 两个 commit + docs 一个 commit）

## 一、业务逻辑背景（快照如何到达前端）

进程快照（协议 v1.4）与磁盘/网卡资源快照（协议 v1.5）均为**服务端内存态、不落库、90 秒新鲜窗口**的数据，到达监控页有两条独立路径：

1. **REST 初载**：`MetricsView` 挂载时调用 `GET /api/servers/{id}/processes/latest` 与 `GET /api/servers/{id}/resources/latest`，404（Agent 未上报/版本过旧/快照过期）转空态；
2. **WS 覆盖**：Monitor WebSocket 的 `metrics.update` 帧按 Agent 上报节奏（默认 5 秒）捎带可选 `processes`/`resources` 节点，`MonitorWebSocket` 分派给视图回调直接覆盖本地状态。

两条路径**并发启动、无先后保证**：服务端订阅（`metrics.subscribe`）不触发即时快照推送，推送只跟随新的 `metrics.report` 事件；而 REST 响应受网络往返影响。

## 二、根因

`loadProcessSnapshot()` / `loadResourcesSnapshot()` 的成功分支无条件写 `processSnapshot.value = response.data`。当一次 Agent 上报恰好在 REST 请求飞行途中经 WS 推送到达（5 秒节拍下每次页面加载都有概率命中），晚到的 REST 响应会用**发起时刻更旧的快照**覆盖刚收到的 WS 新快照。失真窗口 = 覆盖发生到下一个 WS 推送（≤1 个上报周期），期间卡片"采样于"时间戳回跳；若此后 Agent 离线，旧快照将驻留至 90 秒新鲜窗口过期。

## 三、修复

为两条链路各引入一个挂载期标志（`processSnapshotViaWs` / `resourcesSnapshotViaWs`）：

- WS 回调（`applyProcessSnapshot` / `applyResourcesSnapshot`）先置位再落值——WS 数据永远是最新权威来源；
- REST 成功分支仅在对应标志为 `false` 时落值——初载只承担"WS 首推之前有数据可看"的职责，不再与 WS 争写。

不采用"按 collected_at 比较新旧"方案：REST 与 WS 的 collected_at 同源（同一指标行的采样时间），但 REST 响应体可能早于 WS 帧生成，时钟比较引入跨路径顺序假设，标志位仅依赖本地事件顺序，语义更简单可测。

新增 `src/views/MetricsView.spec.ts` 4 例：REST 先到→WS 覆盖（正常序回归）、WS 先到→迟到 REST 不覆盖（资源卡）、同上（进程卡）、卸载断连与 store 复位。

## 四、同批次顺带修复

| 项 | 问题 | 修复 |
| --- | --- | --- |
| lint 门禁回归 | `ResourcesCard.spec.ts`（57ed0ae 引入）用 `defineComponent` 在同文件定义两个 stub 组件，触发 `vue/one-component-per-file` 警告 2 处；`npm run lint` 为 `--max-warnings 0`，HEAD 上该门禁实际为红（提交说明宣称五门禁全绿与实际不符） | 按项目自身约定（见 `AiCommandsView.spec.ts` 内注释："普通对象形式定义 stub 组件，避免触发 vue/one-component-per-file"）改为普通对象 + 显式 `PropType`/`SetupContext` 类型，行为与断言不变 |
| 开发机 Go 工具链不可用（环境项，非仓库缺陷） | 本机 Go 构建缓存/包索引损坏，`go test ./...` 对全部 std 包报 `package X is not in std`，Agent 模块完全无法编译测试 | `go clean -cache` 后恢复正常（std 383 包全量可解析）；处置记录同步至 `docs-SuMon/本机开发环境配置.md` |

## 五、验证口径

- 前端五门禁全绿：`typecheck`（vue-tsc）/ `lint`（--max-warnings 0，修复前 HEAD 为红）/ `vitest` 223/223（新增 4 例）/ `openapi:check` 7/7 / `build`。
- Java（`./mvnw test` 退出码 0）与 Go（`go test ./...` 9 个包全 ok）测试套件在修复前基线即全绿，本次修复不触碰两侧行为。

## 六、明确保留不改

`MetricsController` 三个 metrics 面端点的 `@ApiResponses`/`@Operation` 文案与 `openapi-server.json` 均声明 403 "Authenticated user is not an admin (40300)"，但 `SecurityConfig` 对 `GET /api/servers/**` 仅要求 `authenticated()`（`SecurityConfig.java:117`），非 admin 用户实际可得 200，403 为**不可达响应**。这是窗口内新增端点（processes/latest、resources/latest）从既有 metrics/latest 复制的文案，属契约描述与真实访问矩阵不符；修正涉及 Java 注解与契约 JSON 的同步变更（代码变更），按本批次"文档审计不改代码"的边界仅在此留痕，待专项确认访问矩阵意图（任一认证用户可读 vs 收紧 admin）后统一修约。

## 七、预防

- "REST 初载 + WS 覆盖"双写同一段状态的组件，REST 成功分支必须声明与 WS 的优先级关系（标志位或时间戳），禁止无条件写。
- 门禁结论以本机实际命令输出为准回填提交说明；`--max-warnings 0` 类零容忍门禁在 CI 缺位时容易"宣称全绿"，review 时应抽查。
- 新增视图级 spec（MetricsView.spec）补齐挂载期异步时序断言，作为双路径数据流的回归基线。
