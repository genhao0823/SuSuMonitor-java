# 2026-09-16 空库首管理员验收与兜底注册未适配批次8一次性初始化令牌（40310）

- **严重级别**：中（验收工具链主路径失效：两套空库 E2E 与 docker-compose P0 在批次8 服务上必然失败；六个脚本的未初始化实例兜底路径失效；生产功能本身无影响）
- **影响范围**：`api-test/` 下 13 个文件（2 个空库 E2E 编排器 + 1 个共享验证器 + 1 个隔离验收验证器 + 1 个 docker-compose 验收 + 6 个兜底注册脚本 + `susumonitor.http`）
- **发现方式**：2026-09-16 对最近 24 小时提交（dd8a897..de99916，共 16 个）的专项 review——四端测试全绿后逐文件读码，比对批次8（2f9f124）注册公开行为变更与验收脚本调用契约
- **修复提交**：`ce68d79`（fix(api-test)），文档与本档案另行 docs 提交

## 一、业务逻辑背景（批次8 改变了什么）

批次8 之前，注册端点 `POST /api/auth/register` 在 `auth_bootstrap_state.admin_initialized=0`（空库）时把首个注册者直接提权为 `admin/approved`（先到先得）。批次8（`2f9f124`，协议 openapi-auth 0.3.0）引入一次性初始化令牌：

- 空库注册**必须**携带 `bootstrapToken`（32-128 字符）：缺失返回 `403/40310`，不匹配返回 `403/40311`；
- 校验与消费都发生在注册事务的 `selectForUpdate` 行锁内，令牌至多消费一次；
- 令牌来源优先级：环境变量 `AUTH_BOOTSTRAP_TOKEN` > 数据库未消费密文（重启回显横幅）> 启动自动生成（256-bit，横幅投递）；
- 首管理员已初始化的实例上该字段被忽略，请求体与历史完全一致。

这属于**公开行为变更**：任何在空库上不带令牌调用注册的调用方全部从 200 变为 403。

## 二、根因

批次8 开发计划（`20260916-批次8-首管理员一次性初始化令牌.md`）的兼容性结论 D10 断言「`api-e2e-test.mjs` 等脚本注册的是管理员已存在后的 user/pending 账号，不受影响零改动」。该结论只覆盖了「登录优先、注册兜底且栈已初始化」的场景，遗漏了两类调用方：

1. **按设计对空库注册的主路径**——首管理员并发验收（`verify-first-admin-concurrency.mjs`，业务目标就是验证空库并发注册恰好产生一个 admin）与可靠投递 E2E（`run-agent-reliable-delivery-e2e.ps1` 每次新建空 schema + 新管理员账号）、docker-compose 验收 P0（注释即「空库首管理员 bootstrap」）。三者对批次8 服务必然收到 40310，验收链路整体失效；
2. **未初始化实例的兜底注册路径**——`verify-mvp11` / `verify-admin-batch` / `verify-server-put` / `verify-alert-rules` / `verify-outbox` / `verify-mvp11-concurrency` 均为「先登录，失败则注册管理员」；当目标栈尚无管理员时（首次验收新部署栈），兜底注册同样 40310。

错误信息只有一个裸的 40310 码，不指引调用方去哪里取令牌，运维侧无法自解释。

## 三、修复

统一模式：**先查公开状态端点 `GET /api/auth/bootstrap-status`，`bootstrapPending=true` 时携带令牌，否则保持历史请求体**。令牌经各脚本既有环境变量族传入，命名跟随各族约定：

| 脚本 | 令牌环境变量 | 说明 |
| --- | --- | --- |
| `verify-first-admin-concurrency.mjs` | `SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN` | pending 且缺失时在并发注册**之前**快速失败，给出「去启动横幅/AUTH_BOOTSTRAP_TOKEN 取令牌」的可操作错误；错令牌透传服务端 40311 |
| `run-first-admin-concurrency-e2e.sh` / `.ps1` | （编排器生成） | 32 字节随机 → base64url 43 字符（与服务器自动生成口径一致），双路注入：服务器 env `AUTH_BOOTSTRAP_TOKEN` + 验证器环境变量；`.ps1` 在 finally 中按进程级还原 |
| `verify-agent-reliable-delivery-e2e.mjs` + `run-agent-reliable-delivery-e2e.ps1` | `SUSUMONITOR_RELIABLE_E2E_BOOTSTRAP_TOKEN` | 同上双路注入模式 |
| `verify-docker-compose.mjs`（P0） | `SUSUMONITOR_DOCKER_BOOTSTRAP_TOKEN` | 取值与部署 `.env` 的 `AUTH_BOOTSTRAP_TOKEN` 同值；已初始化栈自动免传 |
| 六个兜底脚本 | `SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN` | 兜底注册前按状态端点判断；已初始化实例行为与历史完全一致 |

令牌校验长度（32-128）与后端 `RegisterRequest @Size` 对齐，提前失败避免把错误推迟到服务端 40002。`susumonitor.http` 新增 bootstrap-status 查询与 pending 态注册示例。

**业务语义保持不变的证明点**：并发验收的核心断言「N 个并发注册恰好 1 个 admin/approved、其余 user/pending」不受影响——所有并发请求携带**同一有效令牌**，批次8 的行锁事务保证只有第一个提交者看到 `admin_initialized=0` 并成为 admin，后至者在已初始化分支忽略令牌字段进入 user/pending；令牌的「至多消费一次」与首管理员创建同事务完成。

## 四、验证

1. 静态：9 个 `.mjs` 全部 `node --check` 通过；`.sh` 过 `bash -n`；两个 `.ps1` 过 PSParser 解析。
2. 动态冒烟（临时 mock 服务器实现批次8 语义，不在仓库内）：修复后的 `verify-first-admin-concurrency.mjs` 三路——
   - pending 且未带令牌：在并发注册前以可操作错误快速失败（不再收到一批 40310 后才报「0 个 admin」）；
   - 令牌错误：透传服务端 `"code":40311`；
   - 正确令牌：`{"status":"PASS","concurrency":8,"adminApproved":1,"userPending":7}`，与批次8 前验收输出一致。
3. 回归：`./mvnw test` 874/874、web 五门禁（vitest 229/229、typecheck、lint、openapi:check 7/7）、`go build/vet/test` 全绿、Android `testDebugUnitTest` 128/128——均在本机实测重跑。
4. 真实空库 MySQL E2E（`run-first-admin-concurrency-e2e.sh`）待下次隔离库验收窗口执行（本机 Docker Desktop 未运行，与批次9 隔离库复跑同窗口）。

## 五、预防

- 今后任何**公开行为变更**批次，兼容性核对清单除「三端」外必须包含第四端：`api-test/` 验收脚本与 `susumonitor.http`（本档案即此清单的首个执行结果）；
- 状态可见的公开端点（如 `bootstrap-status`）应被脚本用于**运行时行为分叉**，而不是依赖编译期/评审期结论。
