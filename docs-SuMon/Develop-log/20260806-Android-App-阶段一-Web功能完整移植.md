# 2026-08-06 Android App 阶段一：Web 端功能完整移植（管理功能）

**状态**：代码完成，33 单测全绿 + assembleDebug 成功；云端联调待 approved/admin 账号
**前置基线**：Android 核心监控版 MVP（23 单测）

## 一、背景与范围

用户要求把 Web 端（web-vue-SuMon）功能完整移植到 Android App。经三方探索（Web 前端功能全景 / 终端与管理 API / Android 现状）确认差距后，采用**分两阶段**实施（用户已确认）：
- **阶段一（本次）**：管理功能——服务器 CRUD、SSH 测试、主机指纹、Agent Token、告警规则管理、用户审核、仪表盘增强、历史图表、设置页、通知权限/401 处理
- **阶段二（后续）**：SSH 终端（Termux 库，用户已确认方案）

技术决策（用户已确认）：终端用 `com.termux:terminal-view:0.118.0`（Maven Central）；图表自绘 Canvas（无第三方依赖）。

## 二、数据层补齐

**ServerApi +8**：`POST/PUT/DELETE servers`、`POST ssh/test`、`PUT ssh/host-key`、`POST agent/register`、`POST agent/rotate`、`DELETE agent/revoke`
**AlertApi +3**：`POST/PUT/DELETE alerts/rules`
**AdminApi 新建 +5**：`GET admin/users`、`PUT batch-approve/reject`、`PUT {id}/approve/reject`
**DTO 新增**：`CreateServerRequest`/`UpdateServerRequest`（SSH 凭据）、`SshTestResult`、`SshHostKeyVo`、`AgentTokenVo`、`AdminUserVo`、`BatchReviewRequest/Result`、`Create/UpdateAlertRuleRequest`、`AdminUserQuery`
**Repository**：ServerRepository 管理操作、AlertRepository 规则 CRUD、新建 AdminRepository；SystemRepository 补 health/ready；ApiModule 注册 AdminApi

## 三、UI 页面

| 页面 | 功能 |
|---|---|
| 服务器列表 | 搜索防抖、分页、SSH 测试、删除（admin）、WS 实时指标 |
| 服务器表单 | 新建/编辑（SSH 密码/私钥凭据、编辑空凭据保留原值） |
| 服务器详情 | 投递遥测 6 字段、主机指纹确认（SHA256 格式校验）、Agent Token 生成/轮换/吊销 |
| 实时监控 | 指标卡 + 自绘 Canvas 折线图（CPU/内存/磁盘 0-100 + 网络 I/O 自适应）+ 时间范围（1h/6h/24h/7d）+ 阈值线 |
| 告警规则 | 列表 + 新建/编辑对话框（编辑锁定 server/metric/operator）+ 启停 switch |
| 告警记录 | 通知渠道标签（email/dingtalk/webhook）、WS 推送横幅（计数+点击刷新） |
| 用户审核 | 状态 Tab + 搜索 + 分页 + 单条/批量通过拒绝 + 失败明细 |
| 仪表盘 | 健康/就绪探针卡、服务器在线/离线/未知分布、未读告警、admin 待审核入口 |
| 设置 | 用户信息、通知开关、后端地址、关于 |

## 四、基础设施

- **POST_NOTIFICATIONS 运行时权限**（Android 13+，MainActivity 启动请求）
- **401 会话失效**：AuthInterceptor 检测 401 响应 → 清 DataStore 会话 → 会话流驱动回登录页
- 权限模型：CRUD/SSH/Token/规则/审核=admin，终端=approved（阶段二），普通用户只读

## 五、验证

```bash
./gradlew :app:testDebugUnitTest   # 45 tests / 0 failures
./gradlew :app:assembleDebug       # app-debug.apk
```

新增测试：`ServerManageModelsTest`（7 DTO 序列化）、`AdminRepositoryTest`（3 列表/批量审核）。

## 五·五、云端联调结果（2026-08-06，模拟器 + smoke 管理员账号）

**全流程 PASS**：登录 → 仪表盘探针（后端/就绪）→ 服务器列表搜索过滤 → 服务器新建/编辑/删除（POST/PUT/DELETE 200 + curl 复核）→ SSH 测试（40901 主机指纹未确认，业务预期）→ 主机指纹确认对话框 + `SHA256:` 格式校验 → 告警规则创建/编辑/删除（POST/PUT/DELETE 200）→ 用户审核（待审核/已通过/已拒绝 Tab、搜索、单条通过/拒绝、批量通过）→ 实时监控历史图表（1h/6h/24h/7d 时间范围切换）。

**联调发现并修复 3 个契约/容错 bug**：
1. `AdminUserVo` 误用 snake_case（`review_status`/`created_at`），后端与 OpenAPI 实为 camelCase（`reviewStatus`/`createdAt`）→ 已通过列表解析失败。修复 `AdminModels.kt` + 测试。
2. `MetricsApi.getHistory` 默认 `page_size=500`，后端 `@Max(100)` → 历史图表 400。改 100。
3. 详情页 `metrics/latest` 对无 Agent 数据返回 40400，因裸 `HttpException` 未被 `ApiException` 判断命中而整页失败。修复：`ApiException.from()` 归一化后对 40400 静默，指标区显示"暂无指标数据"。

## 六、提交

- `feat(android): extend data layer for full web parity`（数据层 + DTO 测试）
- `feat(android): phase-1 full web parity - management UI and infrastructure`（UI + 基础设施）
- `fix(android): align admin user VO camelCase and metrics page_size`（云端联调契约修复）
- `fix(android): tolerate 404 on latest metrics in server detail`（详情页容错）

## 七、遗留

- **阶段二（SSH 终端）**已完成并云端联调 PASS（见 `20260806-Android-App-阶段二-SSH终端.md`）
- 服务器列表排序 UI（sortBy/sortOrder 已支持但未加下拉选择器）
- 通知开关目前仅内存态，未持久化
- 通知点击深链到告警 Tab 仍为扩展点（MVP 仅唤起 App）
