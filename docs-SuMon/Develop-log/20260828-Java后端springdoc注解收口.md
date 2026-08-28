# Java 后端 springdoc/Swagger 注解全量收口

**执行日期**：2026-08-28
**操作人**：ZCode
**会话范围**：仅 Java 后端（server-java-SuMon）

## 一、背景与目标

后端此前已引入 `springdoc-openapi-starter-webmvc-ui:2.8.9` 并配置 `/api-docs` 与 `/swagger-ui.html`（SecurityConfig 已放行 GET），但代码中没有任何 swagger/springdoc 注解，文档完全靠反射裸生成——无分组、无端点描述、无参数/字段说明、无错误码、无 Authorize 按钮。

本次为全部 36 个 REST 端点及请求/响应模型补上注解，让 Swagger UI 呈现完整可用文档。**注解文案与 `docs-SuMon/OpenApi-SuMon/*.json` 手写契约（权威契约源）逐条对齐**：operationId、summary、description、参数描述、错误码均从契约移植。

## 二、改动清单

### 1. 新增 OpenApiConfig（1 个文件）

`server-java-SuMon/src/main/java/com/susumonitor/server/config/OpenApiConfig.java`：

- `@OpenAPIDefinition` 声明全局标题（SuSuMonitor API）、版本（0.1.0）与描述。
- `@SecurityScheme(name="bearerAuth", type=HTTP, scheme=bearer, bearerFormat=JWT)`：项目用自定义 Bearer 过滤器而非 OAuth2 资源服务器，springdoc 无法自动推断认证方案，必须显式声明，Swagger UI 才会出现 Authorize 按钮。

### 2. 9 个 Controller（约 36 个端点）

每个端点加：

- 类级 `@Tag`：按契约 tag 分组（auth/admin/servers/alert/system/rabbitmq-monitor；SystemController 按方法区分 system 与 rabbitmq-monitor）。
- 方法级 `@Operation(summary/description/operationId)`：文案从契约 JSON 逐条移植；契约缺 description 的两个指标端点（getLatestMetrics/getMetricsHistory）按 VO 语义补写。
- `@ApiResponses`：错误响应（HTTP 状态 + 业务错误码说明）；错误码以契约 README 全表为准（README 比 JSON responses 更全，如 admin 批量/告警接口的 40400/40900）。
- `@Parameter`：path/query 参数描述。**注意：参数上不提供 `schema` 属性**——实验发现 `@Parameter` 提供 schema 会覆盖 springdoc 的自动类型推断（Integer 变 string、default 丢失），因此只写 description，schema 由 springdoc 从 `@RequestParam` 默认值、jakarta 校验注解、Java 类型自动推导；枚举允许值写进 description。
- `@SecurityRequirement(name="bearerAuth")`：除 4 个 permitAll 端点（GET /api/health、GET /api/ready、POST /api/auth/register、POST /api/auth/login）外全部声明。

### 3. 请求 DTO（8 个文件）

字段级 `@Schema(description/allowableValues/writeOnly)` 从契约 schema 移植；白名单/枚举加 `allowableValues`（如 ssh_auth_type、metric、operator、level）；敏感凭据字段（ssh_password/ssh_private_key/ssh_private_key_passphrase、登录/注册密码）标 `writeOnly=true`，防止出现在响应模型中。

### 4. 响应 VO（22 个文件 + 2 个继承复用）

字段级 `@Schema` 从契约 schema 移植，契约缺描述的用现有代码注释/语义补写；带 `@JsonProperty` snake_case 字段的保持原有映射。MetricsHistoryVo 继承 MetricsLatestVo 无需重复注解；ApiResponse、PageResult 等公共模型也加了类级与字段级说明。

## 三、技术要点

- **同名类冲突**：`com.susumonitor.server.common.ApiResponse`（业务统一响应）与 `io.swagger.v3.oas.annotations.responses.ApiResponse`（注解）同名。处理方式：import swagger 注解类，业务 ApiResponse 在 9 个 Controller 内用全限定名（方法签名与 `ApiResponse.success(...)` 调用处）。
- **`@Parameter` schema 陷阱**：参数级 `@Parameter(schema=@Schema(...))` 会覆盖 springdoc 自动推导，导致 type 变 string、default 丢失。最终所有参数 `@Parameter` 只保留 description（枚举写进描述），默认值/类型/最小值由 springdoc 从 `@RequestParam(defaultValue=...)` 与 Bean Validation 注解自动生成（已验证输出 `type=integer, default=1, minimum=1` 正确）。
- **契约不动**：手写 OpenAPI JSON 是权威契约源，本次未改任何接口语义，5 个 JSON 文件未变更。

## 四、验证

| 验证项 | 结果 |
|---|---|
| `mvn test` 全量（594 个测试） | 通过，0 失败 0 错误 |
| `npm run openapi:check`（契约 1:1 校验） | 5/5 通过，36 端点与 Controller 无漂移 |
| `/api-docs` 生成内容（MockMvc 冒烟验证后删除临时测试） | 31 路径/36 端点；operationId 与契约对齐（如 updateServer）；6 个 tag 齐全；需认证端点带 bearerAuth、公开端点无；sort_by 默认值/枚举描述正确；login 含 429 响应；CreateServerRequest 敏感字段 writeOnly 正确 |
| 完整 `mvn spring-boot:run` 启动 | 本机缺 JWT 密钥等配置无法启动（环境问题，与本次改动无关；test profile 下上下文可正常启动） |

## 五、后续建议

- Swagger UI 公网暴露策略维持现状（SecurityConfig 已放行 GET，仅限开发/联调环境；生产可考虑 `springdoc.swagger-ui.enabled=false`）。
- 后续修改 Controller/VO/DTO 时，按既有约定同步更新 `docs-SuMon/OpenApi-SuMon/*.json`，并保持注解文案与契约一致。
