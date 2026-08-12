# 开发日志: SSH 测试历史记录与 Dashboard 卡片（V23 + 历史接口 + 前端接入）

**日期**: 2026-08-10
**操作人**: ZCode

## 背景

Web 前端 Dashboard 的"SSH 测试历史卡"`DashboardSshCard` 自 MVP-1 起就是孤儿占位组件（未挂载、等待后端历史接口）。本轮补齐闭环：后端新增历史表与查询接口，前端卡片真实化并挂载到 Dashboard，同时清理多篇过时文档（Agent 字节上限矛盾、面试问答诚实声明过时等）。

## 改动内容

### 后端（新增）

- `V23__create_ssh_test_history.sql`：新建 `ssh_test_history` 表（server_id / connected / error_code / host_key_algorithm / host_key_fingerprint / auth_type / duration_ms / tested_at / created_at，索引 `idx_ssh_test_history_server_tested(server_id, tested_at)`）。
- `SshTestHistoryEntity` / `SshTestHistoryMapper`（接口 + XML `mapper/server/SshTestHistoryMapper.xml`）/ `SshTestHistoryVo`。
- `ServerSshServiceImpl`：`testConnection` 成功与失败均落历史（失败记录映射后的业务错误码如 50002/50003/50400，成功保留主机公钥算法/指纹）；新增 `listTestHistory(serverId)` 查询最近 10 条（按 tested_at 倒序）。
- `ServerController`：新增 `GET /api/servers/{id}/ssh/test/history`（权限沿用 `/api/servers/*/ssh/**` → admin）。
- 测试：`SshTestHistoryMapperMybatisTests`（H2 真实 XML：insert 主键回填、倒序 limit、按服务器隔离）、`ServerSshServiceTests` 扩展（成功/失败留痕 + 错误码、历史 VO 转换）、`ServerControllerTests` 扩展（GET 200 / 未认证 40100）；因新增 Mapper，同步给 9 个 `@SpringBootTest`/`@WebMvcTest` 测试类补 `@MockitoBean SshTestHistoryMapper`。
- OpenAPI 契约：`openapi-server.json` 新增 `GET /api/servers/{id}/ssh/test/history`（12 paths / 15 endpoints，`openapi:check` 5/5 通过）。

### 前端（新增）

- `src/api/server.ts`：新增 `listSshTestHistory(id)`；`SshTestResult` 类型补充 `error_code` 字段（`src/types/api.d.ts`）。
- `DashboardSshCard.vue`：从占位重写为纯 props 展示卡（history/loading/error），展示最近测试结果（成功/失败 + 错误码 + 耗时 + 时间），空态/错误/骨架屏分支。
- `DashboardView.vue`：运行概览区空 `DashboardCard` 替换为 `<DashboardSshCard>`，`refresh()` 增加 `loadSshHistory(firstServer)`。
- 测试：`DashboardSshCard.spec.ts` 5 个用例（loading/error/空态/成功/失败分支），全量 vitest 129/129 通过，typecheck + lint 通过。

### 文档对齐（过时内容修正）

- `agent-go-SuMon/README.md`：队列字节上限从"未实现"改为"已实现"（`SUSUMONITOR_METRICS_BUFFER_MAX_BYTES`，默认 0=不限制，2026-08-05 d0b17bf）。
- `docs-SuMon/Develop-log/20260805-Agent指标可靠投递（NACK死信与投递遥测）.md`：字节上限"仍未实施"行标记作废（同日已落地）。
- `docs-SuMon/Introduction/06-面试问答准备.md`：诚实声明表按 2026-08-10 代码状态更新（HTTPS/WSS、ECharts、Android App、告警外部通知、异地备份已实现；Docker 实机构建、crontab 调度、多实例仍为未做项）。
- `web-vue-SuMon/README.md`：Dashboard 描述与"SSH 测试结果卡"占位行更新为已接入。
- `docs-SuMon/Develop-plans/20260724-前端正式功能开发规划.md`：`DashboardSshCard` 范围外行标记已实现。

## 涉及文件

- server-java-SuMon/src/main/resources/db/migration/V23__create_ssh_test_history.sql（新增）
- server-java-SuMon/src/main/java/com/susumonitor/server/module/server/entity/SshTestHistoryEntity.java（新增）
- server-java-SuMon/src/main/java/com/susumonitor/server/module/server/mapper/SshTestHistoryMapper.java（新增）
- server-java-SuMon/src/main/resources/mapper/server/SshTestHistoryMapper.xml（新增）
- server-java-SuMon/src/main/java/com/susumonitor/server/module/server/vo/SshTestHistoryVo.java（新增）
- server-java-SuMon/src/main/java/com/susumonitor/server/module/server/service/ServerSshService.java / ServerSshServiceImpl.java
- server-java-SuMon/src/main/java/com/susumonitor/server/module/server/controller/ServerController.java
- server-java-SuMon/src/test/java/com/susumonitor/server/module/server/mapper/SshTestHistoryMapperMybatisTests.java（新增）
- server-java-SuMon/src/test/java/.../ServerSshServiceTests.java / ServerControllerTests.java（扩展）
- server-java-SuMon/src/test/java/...（9 个测试类补 @MockitoBean）
- docs-SuMon/OpenApi-SuMon/openapi-server.json
- web-vue-SuMon/src/api/server.ts、src/types/api.d.ts、src/components/DashboardSshCard.vue（重写）、src/views/DashboardView.vue、src/components/DashboardSshCard.spec.ts（新增）
- agent-go-SuMon/README.md、docs-SuMon/Develop-log/20260805-*.md、docs-SuMon/Introduction/06-面试问答准备.md、web-vue-SuMon/README.md、docs-SuMon/Develop-plans/20260724-前端正式功能开发规划.md

## 验证结果

| 验证项 | 结果 |
|---|---|
| 后端定向测试（Mapper/Service/Controller） | 通过 |
| 后端全量 `mvn test` | 通过（MAVEN_EXIT_CODE=0） |
| OpenAPI 契约 `npm run openapi:check` | 5/5 通过（server 15 endpoints） |
| 前端 `npm run typecheck` / `lint` | 通过 |
| 前端全量 vitest | 129/129 通过（含新卡片 5 用例） |
| 部署与公网验证 | 见部署记录 |

## 当前进度

- "SSH 测试历史卡"从占位到闭环完成：后端留痕（成功+失败）→ 查询接口 → 前端 Dashboard 展示 → 测试/契约/文档全同步。
- 文档对齐清理完成 5 处过时描述（Agent 字节上限、开发日志、面试问答诚实声明、web README、前端规划）。

## 备注

- 明确不做：SSH 历史清理调度（历史表随业务增长，后续随 retention 一并评估）；Android 侧 SSH 历史卡（Android 无此卡片）。
- 历史记录失败时 `duration_ms=0`（网络层失败不返回耗时），成功时保留主机公钥算法/指纹。
- 新增 Mapper 需在所有排除 DataSource 的测试类中补 `@MockitoBean`（本次 9 个），后续新增 Mapper 需注意同一模式。