# 2026-09-10 Android App（app-kt-SuMon）对接后端新增 AI 能力

**状态**：代码完成，构建/单元测试（115 项）通过；真机联调待后端开启 AI 开关
**前置基线**：`main` HEAD `d8eb375`（F2 运维问答后端 + 前端 AI 对接）

## 一、背景与范围

后端 2026-08 下旬~09 上旬陆续落地四大 AI 能力与若干辅助端点，安卓端此前停留在 Polish-7 收尾状态。本次对齐全量缺口：

1. **AI 只读诊断**：`POST /api/ai/diagnoses`（admin）
2. **AI 告警智能解释（F1）回看**：`GET /api/alerts/records/{id}/explanation`（admin）
3. **AI 运维问答（F2）**：`POST /api/ai/qa`（admin，单轮无状态）
4. **AI 审批制命令域（M1）**：`/api/ai/commands/*`（templates/suggestions/runs/approve/reject，admin）
5. 次要端点：SSH host-key observe、SSH 测试历史、告警通知投递记录、RabbitMQ 队列/消费者监控（MVP-14）

## 二、技术决策

| 项 | 选择 | 依据 |
|---|---|---|
| 入口 | 底部导航扩为 5 Tab（仪表盘/服务器/告警/AI/设置） | AI Tab 仅 admin 可见（AI 接口全部 admin-only） |
| 问答形态 | 会话式气泡列表，本地内存保留 | 后端单轮无状态；tool_calls 审计可折叠展开 |
| 命令结果获取 | 批准后轮询 `GET runs/{id}`（2s 间隔、60s 上限、终态停止） | 后端契约明确无推送通道 |
| 模板参数校验 | 提交前按后端下发正则本地全匹配预校验 | 减少 40004 无效请求；正则以后端 `CommandTemplateRegistry` 镜像为准 |
| 契约样本 | DTO 以 openapi-ai.json / openapi-command.json 示例 JSON 做单测快照 | 契约冻结源 docs-SuMon/OpenApi-SuMon |
| 降级语义 | AI/命令端点 404 → 「功能未启用」空态；42906/42907 → 限流文案 | 后端 `susumonitor.ai.enabled=false` 时 Controller Bean 不加载返回 404 |

**关键契约发现**：
- `CommandRun.status` 实际取值为 `pending_approval/approved/executing/succeeded/failed/rejected/expired/timeout`（`CommandRunService` 常量），早期探索材料中的 `pending/running/success` 写法不可用
- `AiEvidence.value` 为「数字或字符串或 null」三态，用 `JsonElement?` 承接
- `CommandRun.params` / `ManualCommandRequest.params` 均为 `Map<String, String>`（模板参数强类型字符串）
- 命令模板响应为 `{id, argv[], params:[{name, pattern}]}`，`argv` 含 `{name}` 占位符

## 三、改动文件

**数据层**：
- `util/ErrorCodes.kt`：补 40004/40404/40905/40906/42905/42906/42907/50302~50305/50401/50402
- `data/model/AiModels.kt`（新）：诊断/问答/告警解释全量 DTO
- `data/model/CommandModels.kt`（新）：模板/运行记录/建议与手动请求
- `AlertModels.kt` +`AlertNotification`；`SystemModels.kt` +`QueueBacklog`/`ConsumeStats`；`ServerManageModels.kt` +`SshHostKeyObservation`/`SshTestHistoryItem`
- `api/AiApi.kt`、`api/CommandApi.kt`（新）；`AlertApi`/`ServerApi`/`SystemApi` 各增端点；`di/ApiModule.kt` 注册
- `data/repository/AiRepository.kt`、`CommandRepository.kt`（新，含 404/限流语义映射与参数预校验）；`AlertRepository`/`ServerRepository`/`SystemRepository` 扩展

**UI 层（新包 `ui/ai/`、`ui/system/`）**：
- `AiQaScreen`（AI Tab 主体，会话式问答 + 服务器范围选择 + 工具调用审计）
- `AiDiagnosisScreen`（时间窗快捷档 30m~24h + 问题输入 + 结构化结果卡；入口=服务器详情「AI诊断」）
- `CommandRunsScreen` / `CommandRunDetailScreen`（批准/驳回 + 轮询）/ `CommandCreateScreen`（AI 建议 + 手动模板双 Tab）
- `AiAlertExplanationScreen` / `AlertNotificationsScreen`（入口=告警记录卡片按钮，admin）
- `SystemMonitorScreen`（队列积压超阈值标红 + 消费者统计；入口=仪表盘「队列监控」，admin）
- `ServerDetailScreen`：动作栏扩两行（AI诊断/发命令 + 观察指纹/测试历史），新增远端指纹不一致警示与测试历史弹层
- `AppNavHost`：5 Tab（`adminOnly` 过滤）+ 8 条新路由 + 底栏隐藏清单同步

**测试**：`AiModelsTest`（5）/`CommandModelsTest`（5）/`AiRepositoryTest`（3）/`CommandRepositoryTest`（6），共 19 项新增，全量 115 项通过。

**构建环境（本机）**：
- 补齐缺失的 `gradle/wrapper/gradle-wrapper.jar`（官方 GitHub 源）；发行版按项目既有配置走腾讯镜像
- `settings.gradle.kts` 仓库前置阿里云镜像（本机 JVM 对 dl.google.com TLS 握手被断，curl 直连正常、Java 全挂）
- `local.properties` 指向本机 SDK；`platforms;android-36` 与 `build-tools;35.0.0` 经腾讯 AndroidSDK 镜像手动解压安装（SDK 自动下载通道同被网络掐断，Android Studio 向导的模拟器下载 Premature EOF 同因，模拟器非编译必需）

## 四、手动验收清单（需后端开启 `susumonitor.ai.enabled=true` 等）

1. admin 登录 → 底部出现「AI」Tab；普通用户不可见
2. AI 问答：全局与指定服务器提问各一轮；展开 tool_calls 审计；后端关 AI 时展示「AI 功能未启用」
3. 服务器详情 → AI诊断：选时间窗提交，验证 severity 徽章、兜底摘要提示（model_used=false）
4. 命令域：AI 建议 / 手动模板发起 → 详情批准 → 轮询至终态并展示 stdout/exit_code；驳回路径；40905 冲突提示
5. 告警记录 → AI 解释（未生成时的空态文案）与通知投递记录
6. 服务器详情 → 观察指纹（与已登记不一致时红色警示）、测试历史
7. 仪表盘 → 队列监控：积压超阈值标红、消费者失败率展示
