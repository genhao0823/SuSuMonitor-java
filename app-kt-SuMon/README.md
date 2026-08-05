# app-kt-SuMon — Android 监控客户端

SuSuMonitor 的 Android 客户端（Kotlin + Jetpack Compose），对接云端后端
`http://82.156.245.102`（nginx 80 反代 `/api/` 与 `/ws/monitor`）。

## 功能（MVP 核心监控版）

| 模块 | 说明 |
|---|---|
| 认证 | 登录 / 注册 / 退出登录；JWT 72h 持久化（DataStore） |
| 仪表盘 | 服务器状态卡片列表 + 实时指标（WS 推送实时刷新） |
| 服务器 | 列表（分页）+ 详情（CPU/内存/磁盘/网络/温度/负载 + 状态） |
| 告警 | 记录分页 + 状态筛选（全部/未读/已读/已恢复）+ 标记已读 |
| 实时推送 | 前台 Service 常驻 + OkHttp WebSocket 订阅 `/ws/monitor` |
| 通知 | 告警到达时 IMPORTANCE_HIGH 系统通知，点击唤起 App |

**不在本版**：服务器 CRUD、SSH 终端、告警规则管理、用户审核（后续迭代）。

## 技术栈

- Kotlin 2.0.21 + Jetpack Compose（Material 3）+ ViewModel + StateFlow
- Retrofit 2.11 + OkHttp 4.12 + kotlinx-serialization（REST）
- Hilt 依赖注入
- 前台 Service（START_STICKY + dataSync）+ NotificationChannel
- DataStore Preferences（会话持久化）
- AGP 8.13 + Gradle 8.10；minSdk 26 / target&compileSdk 36

## 构建

```bash
# 前置：JDK 17+、Android SDK（local.properties 指向 sdk.dir）
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # 18 个单元测试
```

Debug APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 运行

1. 在 Android Studio 打开 `app-kt-SuMon/`（自动识别 Gradle 工程）
2. 连接真机或启动模拟器，运行 `app` 配置
3. 登录页输入后端账号（首个用户为 admin，或联系管理员审核）

> 明文 HTTP/WS 仅在演示环境可用（network_security_config 仅放行 `82.156.245.102`）；
> 生产切 HTTPS 后需同时收紧网络安全配置。

## 目录结构

```
app/src/main/java/com/susumonitor/
├── api/            # Retrofit 接口 + WsClient + 拦截器
├── data/           # DTO 模型 + Repository + SessionStore + 帧解析
├── di/             # Hilt 模块（网络/接口绑定）
├── service/        # MonitorForegroundService + NotificationHelper
├── ui/             # Compose 页面（login/dashboard/servers/alerts/navigation/components）
├── util/           # 格式化/错误码/常量
└── MainActivity.kt
```

## 协议对齐

- REST：`docs-SuMon/OpenApi-SuMon/*.json`（31 端点，auth 模块 camelCase，其余 snake_case）
- WebSocket：`docs-SuMon/Protocol-SuMon/websocket-protocol.md` v1.3
  - 先 `POST /api/ws/monitor-ticket` 取 30s 一次性 ticket → 连 `ws://…/ws/monitor?ticket=…`
  - `metrics.subscribe` → 收 `metrics.update` / `alert.push` / `server.status.update` / `error`
- 错误码：`server-java-SuMon/common/ErrorCode.java`（40100/40300/40001/40002…）
