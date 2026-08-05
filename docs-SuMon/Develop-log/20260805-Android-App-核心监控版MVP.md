# 2026-08-05 Android App（app-kt-SuMon）核心监控版 MVP

**状态**：代码完成，构建/单元测试通过；云端联调待真机/模拟器
**前置基线**：`main` HEAD `8ab99ce`（N6 运维收口）

## 一、背景与范围

需求文档自始规划 Android App（`app-kt-SuMon/`，增强阶段），本次正式落地「核心监控版 MVP」：
登录/注册 + 服务器列表/详情 + 实时指标 + 告警记录 + 前台服务告警通知。
不在本版：服务器 CRUD、SSH 终端、告警规则管理、用户审核。

## 二、技术决策

| 项 | 选择 | 依据 |
|---|---|---|
| 语言/UI | Kotlin 2.0.21 + Compose(M3) | kotlin-coding skill §2 |
| 网络 | Retrofit 2.11 + OkHttp 4.12 + kotlinx-serialization | skill §3；字段与 OpenAPI 对齐 |
| DI | Hilt | skill §7 |
| 实时 | OkHttp WebSocket + 前台 Service | skill §4/§5；websocket-protocol.md v1.3 |
| 通知 | NotificationChannel + IMPORTANCE_HIGH | skill §6 |
| 持久化 | DataStore Preferences | 轻量会话存储 |
| 构建 | AGP 8.13 + Gradle 8.10 + version catalog；compileSdk 36 | 本机 SDK 仅 android-36.1 |

**关键契约发现**：
- auth 模块字段为 camelCase（`tokenType`/`expiresIn`/`reviewStatus`），server/metrics/alert 模块为 snake_case——DTO 分别对齐，未统一转换
- Monitor ticket 经 **URL query**（`/ws/monitor?ticket=…`）握手传递（后端 `MonitorHandshakeInterceptor` 从 query 读），而非消息帧；长时 JWT/Agent Token 才禁止入 URL
- WS 外层帧 `payload` 为任意 JSON 值，用 `JsonElement` 承接后按 type 二次解析

## 三、改动文件

**新增工程 `app-kt-SuMon/`**（约 45 文件）：
- Gradle：settings/build/gradle.properties/libs.versions.toml/wrapper/gradlew/.gitignore/local.properties
- 资源：AndroidManifest（INTERNET/FOREGROUND_SERVICE_DATA_SYNC/POST_NOTIFICATIONS）、network_security_config（放行云端 IP）、苏苏主题色/启动图标/通知图标
- 源码包 `app/src/main/java/com/susumonitor/`：
  - `api/`：AuthApi/ServerApi/MetricsApi/AlertApi/SystemApi + WsClient（ticket 握手→subscribe→收帧→指数退避重连）+ AuthInterceptor
  - `data/`：model（ApiResponse/PageResult/Auth/Server/Metrics/Alert/System/WsModels）+ repository（5 个）+ SessionStore + WsMessageParser + ApiException + AppJson
  - `di/`：NetworkModule/ApiModule
  - `service/`：MonitorForegroundService（START_STICKY + 常驻通知 + alert.push→高优通知）+ NotificationHelper
  - `ui/`：theme + navigation（AppNavHost/SettingsScreen）+ login + dashboard + servers（列表/详情）+ alerts + components + MainViewModel
  - `util/`：Constants/ErrorCodes/TimeFormatter/ValueFormatter
- 测试 `app/src/test/`：AuthModelsTest/ServerModelsTest/WsModelsTest/AuthRepositoryTest/FormatterTest

**文档**：
- `docs-SuMon/Develop-plans/20260805-Android-App开发计划.md`
- 本 dev-log
- `app-kt-SuMon/README.md`

## 四、验证

```bash
cd app-kt-SuMon
./gradlew :app:assembleDebug          # PASS（app-debug.apk ~18MB）
./gradlew :app:testDebugUnitTest      # PASS：18 tests / 0 failures
```

测试覆盖：登录响应/错误响应反序列化、Server/ServerStatus/Metrics/AlertRecord/AlertPush 反序列化、
WS 四种帧 parseFrame→parseByType 分发（含未知类型忽略）、AuthRepository（mockk，登出容错）、数值/时间格式化。

## 五、踩坑记录

1. **AGP 仓库可达性**：默认 `services.gradle.org` 重定向至 GitHub（被墙），wrapper 生成与构建改用腾讯云镜像 `mirrors.cloud.tencent.com`；`distributionUrl` 已写死镜像地址
2. **`converter-kotlinx-serialization` 版本**：误配 1.0.0 不存在，实际与 Retrofit 同版本（2.11.0）
3. **BuildConfig 未生成**：AGP 8.x 默认关闭；改用 `ApplicationInfo.FLAG_DEBUGGABLE` 判断 debug 日志，避免依赖生成开关
4. **Hilt Context 绑定**：`NotificationHelper`/`SessionStore` 的 `Context` 参数必须标 `@ApplicationContext`；`WsMessageParser` 用 @Inject 构造器时勿重复提供 @Provides（重复绑定）
5. **Compose weight 作用域**：`Modifier.weight` 仅在 Row/ColumnScope 可用，独立 Composable 内不可直接调用，需由调用方传 `Modifier.weight(1f)`
6. **auth camelCase vs 其余 snake_case**：CurrentUser 初版误标 `@SerialName("review_status")`，对照 `openapi-auth.json` 修正为 `reviewStatus`

## 六、遗留

- **approved 用户全链路未验证**：云端 admin 凭据未在仓库留痕（安全惯例），无法审核新注册账号；仪表盘数据、WS 实时推送（`metrics.update`/`alert.push`）、告警通知需 approved 账号登录后手测
- 通知点击跳转告警 Tab 未实现（MVP 仅唤起 App）
- 服务器详情历史曲线未做（本版仅最新值）
- 前后台切换与多实例 WS 保活未做对抗处理

## 七、模拟器联调记录（E2）

**环境**：Android 36 系统镜像 + Pixel 6 AVD（headless）；App 连云端 `82.156.245.102`

| 用例 | 结果 | 说明 |
|---|---|---|
| 登录页/注册页渲染 | PASS | 用户名/密码/确认密码/登录/注册 元素齐全 |
| 注册新账号 | PASS | `app_test_0805` 创建成功，云端确认 `role=user, reviewStatus=pending` |
| 用户名冲突（409） | PASS | 重复注册显示冲突提示（修复前为 "HTTP 409 "） |
| pending 用户登录（403） | PASS | 显示"账号未通过审核，暂无法登录"（修复前为 "HTTP 403 "） |
| 云端连通 | PASS | health OK，ping 通，REST 全链路工作 |

**联调发现并修复**：
1. **错误提示原始化**：Retrofit 抛 `HttpException` 未被转换为 `ApiException`，UI 直接显示 "HTTP 403 "。修复：5 个 ViewModel 的 catch 统一 `ApiException.from(e)` 转换 + LoginViewModel 按业务码映射友好文案
2. **业务错误体未解析**：`ApiException.from` 原仅返回 "HTTP $code"。修复：解析非 2xx 响应体 `{code,message}`，新增 `ApiExceptionTest`（4 用例）
3. **adb 输入特殊字符**：`input text` 不支持 `@`（测试密码 `Test@123456` 输入异常导致确认密码不匹配，注册静默失败）。联调改用纯字母数字密码规避；代码侧确认密码不一致时应显式提示（后续迭代）

**新增测试**：`ApiExceptionTest` 4 用例 → 单测总数 **23**，全绿

## 八、登录页软键盘不弹出（2026-08-06 修复）

**现象**：点击登录页用户名/密码输入框，软键盘不弹出。

**根因**：MainActivity 使用 `enableEdgeToEdge()`（targetSdk 36 强制 edge-to-edge），但 Manifest 未配置 `windowSoftInputMode`，默认 `adjustUnspecified` 在 edge-to-edge 下不调整窗口——输入框被键盘遮挡区域、焦点丢失，表现为"点了不弹"；LoginScreen 根 Column 也缺 `imePadding()`，键盘弹出时内容不避让。

**修复**（commit `160d54c`）：
1. `AndroidManifest.xml`：MainActivity 增加 `android:windowSoftInputMode="adjustResize"`
2. `LoginScreen.kt`：根 Column 增加 `Modifier.imePadding()`

**验证**：模拟器实测——点击用户名框后 `dumpsys input_method` 显示 `mInputShown=true` / `mIsInputViewShown=true`，输入连接绑定 Compose 输入框；`adb input text` 输入 `keyboard_test` 成功显示。软键盘弹出与输入均正常。
