# 20260805-Android App 开发计划（app-kt-SuMon 核心监控版 MVP）

**状态**：已批准，实施中
**日期**：2026-08-05
**前置基线**：`main` HEAD `8ab99ce`（N6 运维收口）

## 一、总览

| 项 | 决策 |
|---|---|
| 范围 | 核心监控版：登录/注册 + 服务器列表/详情 + 实时指标 + 告警记录 + 前台服务告警通知 |
| 目录 | `app-kt-SuMon/`（需求文档 §15.9 既有规划，之前为空） |
| 源码目录 | `app/src/main/java/`（与 kotlin-coding skill §8 一致） |
| 技术栈 | Kotlin 2.0.21 + Jetpack Compose(M3) + Retrofit 2.11 + OkHttp 4.12 + kotlinx-serialization + Hilt + DataStore + AGP 8.13 + Gradle 8.10；minSdk 26 / target&compileSdk 36 |
| 后端 | 固定云端 `http://82.156.245.102`（nginx 80 反代 `/api/` 与 `/ws/monitor`） |
| 明文 HTTP | network_security_config 仅放行 `82.156.245.102`（演示环境） |

**不在本版**：服务器 CRUD、SSH 终端、告警规则管理、用户审核（后续迭代）。

## 二、实施阶段

### 阶段 A：工程脚手架
- A1 环境：本机 Android Studio 2026.1.1，SDK 位于 `D:\develop\android`（platforms=android-36.1，build-tools 36.1.0/37.0.0）；Gradle 8.10 wrapper 经腾讯云镜像分发
- A2 骨架：settings/build/gradle.properties/libs.versions.toml/wrapper/.gitignore/manifest/资源/network_security_config
- A3 最小可构建：SuSuMonitorApp/MainActivity/空 Dashboard/theme；`gradlew :app:assembleDebug` 通过
- 注意：SDK 仅 android-36.1 平台，compileSdk 计划由 34 调整为 36

### 阶段 B：数据层 + REST 契约（对齐 OpenAPI 唯一事实源）
- B1 DTO：ApiResponse/PageResult/Auth/Server/Metrics/Alert/System/WsModels/MonitorTicket
- B2 网络层：Retrofit/OkHttp/json、AuthInterceptor（JWT + X-Correlation-ID）、ApiException 映射
- B3 Repository（Auth/Server/Metrics/Alert/System）+ SessionStore（DataStore）
- B4 单元测试：DTO 反序列化 + WS 帧解析 + Repository（mockk）
- 注意：auth 模块字段为 camelCase（tokenType/expiresIn/reviewStatus），其余模块 snake_case，DTO 需分别对齐

### 阶段 C：实时层 + 前台服务
- C1 MonitorTicket：`POST /api/ws/monitor-ticket` 取 30s 一次性 ticket（经 URL query 传入，协议规定）
- C2 WsClient：`metrics.subscribe` → `metrics.update`/`alert.push`/`server.status.update`；断线指数退避重连、重连前重取 ticket
- C3 MonitorForegroundService：START_STICKY + 常驻通知 + 绑定 WsClient 生命周期
- C4 告警通知：`alert.push` → IMPORTANCE_HIGH 通知，PendingIntent 唤起 App

### 阶段 D：UI（Compose）
- D1 导航/会话：AppNavHost + MainViewModel（未登录→登录页）
- D2 登录/注册页：表单校验 + 错误码映射
- D3 仪表盘：服务器卡片 + WS 实时指标刷新
- D4 服务器列表 + 详情：分页 + 指标数值卡
- D5 告警列表：分页 + 状态筛选 Tabs + 标记已读 + WS 增量提示

### 阶段 E：验收 + 文档
- E1 `gradlew :app:assembleDebug :app:testDebugUnitTest` 全绿
- E2 真机/模拟器连云端全链路手测
- E3 文档：本计划 + dev-log + app README + 需求文档 §15.9/主 README 同步

## 三、验收出口条件

- [ ] `gradlew :app:assembleDebug` 构建成功
- [ ] `gradlew :app:testDebugUnitTest` 单元测试全绿
- [ ] 模拟器/真机连云端全链路手测 PASS（登录→列表→实时刷新→告警通知→已读→退出）
- [ ] 计划/日志/README/需求文档落地

## 四、风险与边界

- 明文 HTTP/WS 仅演示；云端历史有运营商劫持明文 WS 记录
- 本机无模拟器系统镜像/AVD，E2 手测需先装镜像或连真机
- 后台存活不做保活对抗；验收口径=「打开期间实时 + 收到即推送」
- AGP/Kotlin/Compose BOM 组合以 Android Studio 内置兼容为准
