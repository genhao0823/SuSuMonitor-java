# SuSuMonitor 后端 Bug 修复目录

本目录跟踪 SuSuMonitor 后端开发过程中发现的所有 bug。
每个 bug 一份独立 Markdown 文件,命名规则 `YYYY-MM-DD-代号.md`（README 首版写 `YYYYMMDD` 为笔误，实际文件均为连字符格式）。

## 当前 Bug 索引

| 日期 | 代号 | 标题 | 优先级 | 模块 | 状态 |
|---|---|---|---|---|---|
| 2026-07-21 | M4-server-list-sort-ignored | `GET /api/servers` 排序参数被忽略 | 高 | 服务器管理 | ✅ 已解决（2026-07-28 Sprint A）：后端 sort_by / sort_order 白名单排序真实 MySQL/HTTP/Apifox 全绿；前端已撤除 `sortedRows` / `pagedRows` 客户端排序 fallback，改为接后端真实分页（`serverItems` + `totalCount`）。 |
| 2026-07-21 | M4-server-put-existence-check | `PUT /api/servers/{id}` 校验顺序问题(不存在也报参数错) | 中 | 服务器管理 | ✅ 已修复（2026-07-23 B2）：`existsActive` 先于参数校验，不存在返回 40400；40400/40002 MockMvc 回归通过；2026-08-10 补 `api-test/verify-server-put.mjs` 覆盖剩余验收项（合法更新成功 200、软删后二次 PUT 40400、不存在 ID PUT 40400）。 |
| 2026-07-21 | M4-ssh-test-error-code | `POST /api/servers/{id}/ssh/test` 错误码笼统 | 中 | SSH 连接 | ✅ 已修复：50002/50003 已通过 Apifox 真实分类验收（2026-07-25，用例 `397698534`/`397636440`） |
| 2026-07-21 | M4-server-list-soft-delete | 列表过滤已软删除数据 | 低 | 服务器管理 | 已验证（J3，真实 MySQL/HTTP 通过） |
| 2026-07-27 | MVP6-alert-rules-mapper-500 | `POST/PUT/DELETE /api/alerts/rules` 返回 500 空响应体 | 高 | 告警规则 Mapper | ✅ 代码已修复：`@Param("rule")` 已加 + `AlertRuleMapperMybatisTests` 用 H2 真实走 INSERT/UPDATE/软删通过；`mvn test` + `mvn package` 通过；2026-08-10 补 `api-test/verify-alert-rules.mjs`（POST/PUT/DELETE 4 路 + 唯一性 40900 + 软删后重建），真实 MySQL/HTTP 联调脚本已备 |
| 2026-07-27 | MVP6-alert-store-swallows-error | 前端 alerts store 吞错误导致告警页无提示 | 中 | 前端告警 store | ✅ 已修复（2026-07-28 告警页收口同步修复，vitest 覆盖） |
| 2026-07-28 | MVP6-alert-rules-no-uniqueness-check | 告警规则无唯一性校验可重复创建 | 高 | 告警规则 | ✅ 已修复（V13 生成列唯一索引，2026-08-10 收口复核） |
| 2026-07-31 | MVP6-alert-state-not-retriggered | 告警恢复后不再触发（state 行未删） | 高 | 告警评估 | ✅ 已修复（`handleResolve` 删除 state 行，commit `f7dba69`；verify-alert-ws 24/24 复核） |

## 文档结构

每个 bug 文件包含:

1. 标题、日期、发现方式
2. 复现命令(curl / PowerShell)
3. 期望行为 vs 实际行为
4. 探测证据(响应 body、OpenAPI 字段)
5. 影响范围(前端 + 后端)
6. 建议修复方向(**不写代码,只写思路**)
7. 验收标准(修完后如何确认)

## 与前端协同

每个 bug 文件的"前端绕过方案"段说明:
- 当前如何在前端规避
- 后端修好后前端应做的撤除动作

## 排序类说明

`M4-server-list-sort-ignored` 已解决（2026-07-28 Sprint A）。
前端已撤除客户端排序 fallback：
- 删除 `sortedRows` / `pagedRows` computed
- `:data` 直接绑 `serverItems`
- `totalCount` 由后端 `data.total` 驱动
- `onSortChange` 触发 `reload()` 让后端排序
- `buildQuery()` `page_size` 跟随用户选择（不再硬编码 100）
