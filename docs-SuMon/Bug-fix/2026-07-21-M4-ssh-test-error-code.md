# M4-ssh-test-error-code `POST /api/servers/{id}/ssh/test` 错误码笼统（已修复）

**日期**: 2026-07-21
**发现方式**: opencode 在 M4 开发期间探测 SSH test 端点
**优先级**: 中
**模块**: SSH 连接测试 API
**影响前端**: `ServerListView` / `ServerDetailView` 的"测试连接"按钮(已占位)

## 复现命令

```powershell
# 登录拿 token
$token = '<your-admin-jwt>'
$headers = @{ Authorization = ('Bearer ' + $token); 'X-Correlation-ID' = 'ssh-test-probe' }

# 任意 ID,无论是否存在
foreach ($id in @(1, 10, 99999)) {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:18080/api/servers/$id/ssh/test" `
            -Method Post -Headers $headers -UseBasicParsing -TimeoutSec 5
        Write-Host "ID=$id STATUS=$($r.StatusCode) BODY=$($r.Content)"
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $body = $reader.ReadToEnd()
        Write-Host "ID=$id STATUS=$status BODY=$body"
    }
}
```

## 期望行为

SSH test 端点应区分以下几类错误,各自返回合适错误码:

| 场景 | 期望错误码 |
|---|---|
| SSH 服务未实现(MVP-7 未到位) | `50700` SSH feature not implemented |
| 服务器不存在 | `40400` resource not found |
| 服务器已软删除 | `40400` resource not found |
| SSH 连接超时 | `50002` ssh connection failed |
| SSH 主机指纹未登记 | `50701` host fingerprint not registered |
| 凭据错误(auth fail) | `50003` ssh authentication failed |
| 业务级 SSH 成功 | `200` + `data: { connected: true, host_key_algorithm, host_key_fingerprint, auth_type, duration_ms, tested_at }` |

## 实际行为

所有 ID 都返回:

```json
{
  "code": 50000,
  "message": "internal server error",
  "data": null
}
```

错误码笼统的 `50000 internal server error`,无法区分"功能未实现"、"服务器不存在"、"SSH 连接失败"。

## 探测证据

```
ID=1    STATUS=500  {"code":50000,"message":"internal server error","data":null}
ID=10   STATUS=500  {"code":50000,"message":"internal server error","data":null}
ID=99999 STATUS=500  {"code":50000,"message":"internal server error","data":null}
```

注: 99999 也不存在,但仍报 500 而非 404,说明 SSH test 端点**根本没做存在性检查**,直接走 SSH 尝试逻辑然后抛通用异常。

## 影响范围

### 前端

- `ServerListView` 行内和 `ServerDetailView` 头部都有"测试连接"按钮
- 当前点击触发 `ElMessage.info('测试连接功能将在 MVP-7 接入,敬请期待')` 占位,**完全跳过真实调用**
- 后端修好后再调真接口,按业务错误码分类提示

### 后端

- `SshConnectionTester` 或对应 controller 异常路径粗粒度,所有失败都返回 50000
- 缺少 `SshFeatureNotImplementedException` 等细分异常
- 全局异常处理器 `GlobalExceptionHandler` 兜底成 50000

## 建议修复方向

1. **定义新错误码**(参考 OpenAPI 错误码体系):
   - `50700` SSH feature not implemented(临时返回,功能上线后废弃)
   - `50701` SSH host fingerprint not registered
   - `50702` SSH host fingerprint mismatch
   - `50703` SSH private key passphrase incorrect
   - `50002` SSH connection failed(已有,补充语义)
   - `50003` SSH authentication failed

2. **业务异常分层**:
   - `SshFeatureNotImplementedException` → 50700
   - `SshConnectionTimeoutException` → 50002 with timeout detail
   - `SshAuthFailedException` → 50003
   - `SshHostKeyUnregisteredException` → 50701
   - 已有 `SshConnectionException` → 50002

3. **controller 入口加存在性检查**(类似 Bug 4):
   - 服务器不存在直接 40400,不进入 SSH 尝试

4. **OpenAPI 同步**: `docs-SuMon/OpenApi-SuMon/openapi-server.json` 添加上述错误码的 response 示例

## 前端绕过方案

`ServerListView.vue` / `ServerDetailView.vue` 的测试连接按钮:

```ts
function handleTestConnection(_row: Server): void {
  ElMessage.info('测试连接功能将在 MVP-7 接入,敬请期待')
}
```

**不调后端 API**,直接占位 toast。后端修好后再改为:

```ts
async function handleTestConnection(row: Server): Promise<void> {
  try {
    const response = await testSshConnection(row.id)
    if (response.data?.connected) {
      ElMessage.success(`连接成功(${response.data.duration_ms} ms)`)
    }
  } catch (error) {
    if (error instanceof ApiBusinessError) {
      if (error.code === ErrorCode.SSH_NOT_IMPLEMENTED) {
        ElMessage.warning('功能开发中')
      } else if (error.code === ErrorCode.RESOURCE_NOT_FOUND) {
        ElMessage.error('服务器不存在')
      } else {
        ElMessage.error(`连接失败: ${error.message}`)
      }
    }
  }
}
```

## 后端修好后前端改动

1. 新增 `src/api/server.ts` 的 `testSshConnection(id: number)` 函数
2. 新增 `src/types/api.d.ts` 的 `SshTestResult` 接口
3. 修改 `ErrorCode` 加入新错误码
4. `ServerListView` / `ServerDetailView` 的 `handleTestConnection` 改为真实调用

## 验收标准

后端修完后:

- [x] `POST /api/servers/99999/ssh/test` 返回 `40400`(不存在)（2026-07-25 真实 HTTP 验证：40400 ✅）
- [ ] `POST /api/servers/{existing_id}/ssh/test` 未实现时返回 `50700`（50700 已废弃不实现，跳过）
- [x] `POST /api/servers/{existing_id}/ssh/test` 连接阶段失败返回 `50002`（2026-07-25 真实 HTTP/Apifox 验收通过）
- [x] `POST /api/servers/{existing_id}/ssh/test` auth 失败返回 `50003`（2026-07-25 真实 HTTP/SSHD 验收通过）
- [x] 成功响应 body 符合 OpenAPI `SshTestVo` schema（OpenAPI `SshConnectionTestResult` schema 与 `SshTestVo.java` 字段 1:1 对齐）
- [x] OpenAPI 文档同步更新错误码表

## J4 实现与验证结果（2026-07-23）

本轮保留现有正式契约 `50400 ssh connection timeout`，仅新增 `50003 ssh authentication failed`。

实现变更：

- sshj `UserAuthException` 单独映射为 `AUTHENTICATION_FAILED`，避免被通用 `IOException` 归类为连接失败。
- `ServerSshService` 将该分类映射为 `50003`，HTTP 状态保持 `502 Bad Gateway`。
- 不存在、软删除、未确认指纹、出站策略拒绝、连接失败、超时和并发限制等既有错误码未改变。

自动化验证：

| 验证项 | 结果 |
|---|---|
| Service 分类映射 | 覆盖 `AUTHENTICATION_FAILED -> 50003` |
| Controller HTTP 映射 | `50003 -> HTTP 502` |
| 超时契约 | 保持 `50400` |
| OpenAPI | 错误码枚举和 502 示例已包含 `50003` |

## J5 真实 SSH 验收结果（2026-07-23）

已复用 WSL Ubuntu 24.04.4 的受控 OpenSSH 服务，监听 `2222`；创建专用用户 `susu-ssh-j5-20260723`。开发库测试服务器为唯一记录 ID `39`，删除前已备份：

```text
C:\Backup\SuSuMonitor\java-backend-closure-20260723\J5-susumonitor-before-ssh-apifox-20260723.sql
```

TCP 连接、SSH KEX 和 Java 出站策略均已通过，但 sshj 主机密钥握手返回 `50002`，未成功登记主机指纹，因此未到达用户认证阶段，不能将本轮记为真实 `50003` 通过。使用实际 ED25519 和 RSA 指纹分别重试后仍为 `50002`，需后续排查 sshj 0.39.0 与当前 OpenSSH 主机密钥协商兼容性。

测试服务器 ID `39` 随后仅通过业务接口软删除：HTTP 200、业务码 0；数据库行保留且 `deleted=1`、`deleted_at` 非空、`delete_token` 非空。未执行物理删除、TRUNCATE、DROP 或数据库重置。

## J6 真实 SSH 认证失败验收（2026-07-25）

本次使用重新打包的 Spring Boot JAR、独立 WSL OpenSSH `127.0.0.1:2223` 和受控本机验证身份进行验收。临时 SSHD 使用独立 RSA 主机密钥；真实密码仅在运行时内存中使用，未写入 Git、Apifox、文档或日志。

验收步骤：

1. 通过业务接口创建唯一服务器记录 `ID=50`，保存故意错误的 SSH 密码。
2. 通过 `PUT /api/servers/50/ssh/host-key` 登记带外获取的正确 SHA-256 指纹，HTTP 200、业务码 0。
3. 使用真正无请求体且不携带 `Content-Type` 的 `POST /api/servers/50/ssh/test` 发起认证测试。
4. 响应为 HTTP 502、业务码 `50003`、消息 `ssh authentication failed`；`X-Request-ID` 为合法 UUID，响应不含密码、JWT、私钥或底层异常信息。
5. 仅通过 `DELETE /api/servers/50` 软删除测试记录；数据库行保留且 `deleted=1`。

客户端诊断日志确认：

```text
findExistingAlgorithms() -> []
host key observed -> algorithm=ssh-rsa, algorithmMatched=true, fingerprintMatched=true
negotiatedHostKeyAlgorithm=rsa-sha2-512
authentication exception -> UserAuthException
```

独立 SSHD 日志确认已经进入 `userauth-request method password`，随后记录 `Failed password`，不是认证前关闭或主机密钥协商失败。

诊断材料备份：

```text
C:\Backup\SuSuMonitor\java-backend-closure-20260723\ssh-50003-diagnostic-20260725\sshdiag50003.tar.gz
```

该验收解除 J5 的“sshj 主机密钥握手阻塞”边界；当前真实错误密码路径已确认 `UserAuthException -> AUTHENTICATION_FAILED -> HTTP 502 / 50003`。

## J7 真实 SSH 连接失败验收（2026-07-25）

本次先启动独立 WSL OpenSSH `127.0.0.1:2223` 并完成正确主机指纹登记，然后停止该 SSHD，再调用无请求体的 SSH test 接口。该顺序确保失败发生在已确认主机身份后的 TCP 连接阶段，而不是主机密钥校验或用户认证阶段。

Apifox 独立用例：

```text
项目：8585366
分支：ai/20260725-from-main-ssh-50003-validation
用例：397698534
名称：J7 SSH connection refused returns 50002
```

真实 JUnit 报告断言 HTTP `502`、业务码 `50002`、消息 `ssh connection failed` 和 `X-Request-ID` 存在，4 项断言均通过。临时服务器仅通过业务接口软删除，临时 SSHD、密钥目录和后端进程均已清理。

当前已确认连接阶段异常映射为 `CONNECTION_FAILED -> HTTP 502 / 50002`，认证阶段异常映射为 `AUTHENTICATION_FAILED -> HTTP 502 / 50003`。

## 2026-08-10 补充：超时分类修复与 sshj/OpenSSH 兼容性根因说明

- **超时分类已修复**：`SshConnectionTester.connect()` 的 `catch (IOException | RuntimeException)` 中新增 `isSocketTimeout()` 判断（遍历异常链识别 `SocketTimeoutException`），握手/认证阶段的读超时现在分类为 `TIMEOUT -> HTTP 504 / 50400`，不再被吞成 `CONNECTION_FAILED(50002)`。整体超时（`future.get(totalTimeoutSeconds)`）与取消路径仍为 `TIMEOUT`；连接拒绝仍为 `CONNECTION_FAILED`；主机密钥不匹配仍优先于超时判定。
- **自动化验证**：`SshConnectionTesterTests` 新增 3 个网络层用例——不响应 socket 的握手读超时 → `TIMEOUT`、连接拒绝 → `CONNECTION_FAILED`、整体超时 → `TIMEOUT`；`mvn test` 全量回归通过。
- **sshj 0.39.0 与 OpenSSH 主机密钥协商兼容性**：J5 记录的"真实 WSL OpenSSH 下指纹登记返回 50002、需后续排查"根因至今未在真实环境复现定位。J6/J7 已通过受控独立 SSHD 闭环 `50002/50003` 分类验收；本 bug 单涉及的其余阶段（50002 连接失败、50003 认证失败）均已真实验收。该兼容性项需真实 OpenSSH 环境，仓库内无待改代码缺陷，保留为真实环境待办。
