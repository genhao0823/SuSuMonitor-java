# 2026-09-14 api-test 脚本与现行契约漂移（三处端点/语义过时）

- **级别**：低（测试资产维护；不影响生产代码）
- **发现方式**：全功能联调 Phase 2 内网实跑

## 一、漂移清单

| 脚本 | 漂移点 | 现状 |
| --- | --- | --- |
| `verify-alert-rules.mjs` R4 | 调用 `GET /api/alerts/rules/{id}` 单详情接口 | 该路由不存在（后端只有列表 `GET /api/alerts/rules`）；且因此暴露了未映射路径 500 缺陷（见同日 unmapped-api-path 文档） |
| `verify-monitor-ws.mjs` | 调用 `GET /api/admin/users/pending` | 该路由不存在；现行接口为 `GET /api/admin/users?status=pending`（分页结构） |
| `verify-agent-ws.mjs` | 轮换后断言旧 Token 立即失效（closeCode===1008） | 安全评审后语义已变更：宽限窗口（默认 300s）内旧 Token 可完成认证；窗口外才 1008 拒绝。脚本需按新语义适配（窗口内放行 + 断言 DB prev/grace 列） |

## 二、根因

api-test 脚本未随 API 演进同步维护；`openapi:check` 门禁只覆盖 `docs-SuMon/OpenApi-SuMon` 与 Controller 的漂移，不覆盖 api-test 脚本。

## 三、修复方案

1. `verify-alert-rules.mjs`：R4 改为通过列表接口按 id 检索断言更新值。
2. `verify-monitor-ws.mjs`：改用 `GET /api/admin/users?status=pending`（按现行分页响应取 items）。
3. `verify-agent-ws.mjs`：轮换段适配宽限语义——轮换后旧 Token 首帧认证成功（宽限内）、关闭码断言移除/调整为语义化；保留撤销后立即失效断言。
4. 预防：api-test 套件纳入联调基线门禁（每次接口变更后重跑），漂移在联调期即暴露。

## 四、备注

三个脚本修复随本轮缺陷修复批次一并提交（`test(api-test)` 范畴），并重跑验证。
