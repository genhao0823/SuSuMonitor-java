# 2026-09-14 未映射 API 路径返回 500（应为 404）

- **级别**：中（错误语义失真：客户端与监控无法区分"路径错误"与"服务故障"，500 告警噪声）
- **发现方式**：全功能联调 Phase 2 —— `api-test/verify-alert-rules.mjs` R4 调用不存在的 `GET /api/alerts/rules/{id}` 时返回 500 而非 404
- **影响版本**：长期存在（非本轮引入）

## 一、现象

对任意未映射的 `/api/**` 路径（认证通过）：

```
GET /api/no-such-path     → 500 {"code":50000,"message":"internal server error"}
GET /api/alerts/rules/8   → 500 {"code":50000}（该 GET 单详情路由不存在）
```

对照：已映射资源不存在的场景行为正确（`GET /api/servers/999999` → 404/40400）。

服务端日志：`Unhandled exception: org.springframework.web.HttpRequestMethodNotSupportedException` / `NoResourceFoundException` 均落入同一兜底。

## 二、根因

`GlobalExceptionHandler` 的兜底 `@ExceptionHandler(Exception.class)` 捕获了两个 Spring MVC 内建信号异常：

1. **`NoResourceFoundException`**：Spring Boot 将 `/**` 映射到静态资源处理器，未匹配的 API 路径最终由它抛出该异常 → 被兜底吞成 500/50000，正确语义应为 **404**。
2. **`HttpRequestMethodNotSupportedException`**（405 场景，如对 `PUT /api/alerts/rules/{id}` 存在的路由发 GET）→ 同样 500。405 需要新错误码与契约联动，本轮记录为同类遗留（见 §四）。

## 三、修复方案（本轮范围）

- `GlobalExceptionHandler` 新增专用处理器：
  `@ExceptionHandler(NoResourceFoundException.class)` → HTTP 404 + `ErrorCode.RESOURCE_NOT_FOUND(40400)`，与既有资源缺失语义对齐。
- 单测覆盖：无 handler 路径断言 404/40400。

## 四、同类遗留（不本轮修）

- `HttpRequestMethodNotSupportedException` → 405：需要新增 40500 错误码并同步 7 份 OpenAPI 契约与前端 `error-code.ts` 映射，影响面较大，单独立项。
