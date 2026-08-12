# M4-server-put-existence-check `PUT /api/servers/{id}` 校验顺序问题

**日期**: 2026-07-21
**发现方式**: opencode 在 M4 服务器列表开发期间自动探测
**优先级**: 中
**模块**: 服务器管理 API
**影响前端**: `ServerDetailView` / `ServerListView` 编辑流程

## 复现命令

```powershell
# 登录拿 token(同上)
$token = '<your-admin-jwt>'

# 探测:对一个不存在的 ID 发起 PUT
$body = '{"description":"updated by probe"}'
$headers = @{ Authorization = ('Bearer ' + $token); 'Content-Type' = 'application/json' }
$r = Invoke-WebRequest -Uri 'http://127.0.0.1:18080/api/servers/99999' `
    -Method Put -Body $body -Headers $headers -UseBasicParsing
$r.Content
```

## 期望行为

| 请求 | 期望响应 |
|---|---|
| `PUT /api/servers/99999` (ID 不存在) | `40400 resource not found` |
| `PUT /api/servers/1` (ID 存在,但 body 缺字段) | `40002 invalid request parameter` |
| `PUT /api/servers/1` (ID 存在 + body 合法) | `200 code=0` |

**原则**:存在性检查应先于参数校验;不存在即 404,不存在时不应进入字段校验阶段。

## 实际行为

`PUT /api/servers/99999`(ID 不存在)返回:

```json
{
  "code": 40002,
  "message": "invalid request parameter",
  "data": null
}
```

**错误码与实际语义不符**:服务端本意是"找不到这个 ID",却因为 body 字段校验器先抛了 `40002`,前端按 40002 走"参数错误"分支,显示"参数不合法,请检查表单字段"——但实际没有字段可检查,真实问题是服务器不存在。

## 探测证据

```
GET    /api/servers/99999  → 404  ← 不存在,正确
PUT    /api/servers/99999  → 400  ← 不存在,应报 404,却报 400
```

预期 PUT 应该先确认存在再校验,但当前实现先跑参数校验。

## 影响范围

### 前端

- `ServerDetailView.onMounted` 已用 `getServer(id)` 做存在性预检,**前端**已规避此 bug
- 但如果直接 PUT 后端 API(绕过前端预检),仍会得到错误的 40002

### 后端

- `ServerController.update()` 方法进入即跑 `@Valid` / `@Validated` Bean Validation
- Validation 失败抛 `MethodArgumentNotValidException` → 全局异常处理器映射为 `40002`
- 没有先 `findById(id)` 再 validate,顺序颠倒

## 建议修复方向

在 controller 层先做存在性检查:

1. **方式 A**(推荐):在 controller 入口加显式查存在性
   ```java
   public ApiResponse<Server> update(@PathVariable Long id, @Valid @RequestBody UpdateServerRequest req) {
       Server existing = serverService.findById(id);
       if (existing == null) {
           throw new ResourceNotFoundException("server not found");
       }
       return ApiResponse.ok(serverService.update(id, req));
   }
   ```

2. **方式 B**:在 service 层 `update` 方法首行查存在性,不存在抛业务异常
3. **方式 C**:全局异常处理器先匹配 ResourceNotFound 异常(已经支持),但需要 controller 不再直接抛 40002

**推荐方式 A**:controller 是入参校验与业务校验的天然边界,放 controller 保持层次清晰。

## 前端绕过方案

`ServerDetailView.vue` 的 `reload()`:

```ts
async function reload(): Promise<void> {
  const id = parseId()
  if (id === null) { goBack(); return }
  loading.value = true
  try {
    const [serverRes, statusRes] = await Promise.all([
      getServer(id),                                              // 先 GET
      getServerStatus(id).catch(() => null)
    ])
    data.value = serverRes.data
    status.value = statusRes?.data ?? null
  } catch (error) {
    if (error instanceof ApiBusinessError && error.code === ErrorCode.RESOURCE_NOT_FOUND) {
      ElMessage.warning('服务器不存在或已被删除')
      goBack()                                                    // 404 直接跳回列表
      return
    }
    ElMessage.error(explainError(error))
    data.value = null
  } finally {
    loading.value = false
  }
}
```

进入编辑前先 GET,404 直接返回列表,**不再调用 PUT**。

## 后端修好后前端无需改动

当前前端流程已经规避了 40002 误报。后端修好后,即便前端直接 PUT 也只会返回正确的 40400,前端走相同分支处理。无须调整。

## B2 修复实现与验证

更新接口现在先通过 `ServerService.existsActive(serverId)` 检查未软删除服务器，再使用 Jakarta `Validator` 校验 `UpdateServerRequest`。不存在目标返回 `40400`；目标存在但请求体非法仍返回 `40002`。Service 原有业务校验、事务和二次查询保持不变。

- 修改前备份：`C:\Backup\SuSuMonitor\execution-20260723\b2-put-existence-20260722-170032\`
- 定向测试：`mvn -Dtest=ServerControllerTests,ServerServiceTests test`，42/42 通过
- Java 回归：`mvn -Dtest=!MetricsCleanupMySqlValidationTests test`，166/166 通过
- 编译：`mvn -DskipTests compile`，通过
- OpenAPI：`npm run openapi:check`，3/3 通过
- 服务健康检查：`/api/health` 和 `/api/ready` 均 HTTP 200
- 真实 HTTP 更新验证：已通过 Apifox 授权调用，最新 18080 实例返回 `40400`
- Apifox CLI 已调用本地接口，但用例未注入有效 `adminToken`，实际响应为 `40100`，不能作为 B2 的 `40400` 验收证据
- Apifox 命令：`apifox test-case run 395661082 --project 8585366 --environment 47408671`
- Apifox 结果：HTTP 401、业务码 `40100`
- Apifox 记录备份：`C:\Backup\SuSuMonitor\execution-20260723\b2-apifox-20260722-173346\`

## 验收标准

后端修完后:

- [x] MockMvc: 不存在 ID + 不完整 body 返回 `40400`
- [x] MockMvc: 有效服务器 body 缺字段返回 `40002`
- [x] MockMvc: 有效服务器 body 合法时仍调用更新 Service
- [x] 真实 HTTP: `PUT /api/servers/99999` 返回 `40400`
- [~] 真实 HTTP: 合法服务器更新成功（2026-08-10 已交付 `api-test/verify-server-put.mjs`，P2 项覆盖；待隔离库/联调环境实跑）
- [~] 集成测试:连续两次 PUT 同一个 ID,第二次应 404(第一次成功后被软删除的场景)（2026-08-10 已交付 `api-test/verify-server-put.mjs`，P4+P5 项覆盖：创建→PUT 成功→软删→二次 PUT 40400）
