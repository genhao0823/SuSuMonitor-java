# scripts/

自动化与校验脚本。

## check-openapi.mjs

OpenAPI JSON 契约 lint 与 Java Controller 路径漂移检查脚本。

### 用法

```bash
npm run openapi:check
```

扫描 `docs-SuMon/OpenApi-SuMon/*.json` 和 Java Controller，执行以下校验：

1. 顶层必含 `openapi` / `info` / `paths`
2. `openapi` 版本必须是 `3.0.x`
3. `info.title` 与 `info.version` 必须非空字符串
4. `paths` 至少含一条 HTTP 端点(get/post/put/delete/patch/options/head)
5. 每个 HTTP 操作具有非空且全局唯一的 `operationId`
6. 每个 HTTP 操作具有非空 `responses`
7. 所有本地 JSON Pointer `$ref` 均可解析
8. OpenAPI 与 Java Controller 的 HTTP 方法和路径双向一致

### 设计原则

- 纯只读:**不会修改任何 JSON 文件**
- 离线:**不依赖网络**,纯本地文件 IO
- 零依赖:仅用 Node 内置 `node:fs` / `node:path`,不需要额外 npm 包
- CI 友好:退出码 `0` 全通过,`1` 有失败

### 输出示例(成功)

```
OpenAPI 契约结构校验
================================

✓ openapi-auth.json     (8 endpoints)
✓ openapi-server.json   (15 endpoints)
✓ openapi-system.json   (3 endpoints)

================================
3 / 3 通过
```

### 输出示例(失败)

```
OpenAPI 契约结构校验
================================

✓ openapi-auth.json     (8 endpoints)
✗ openapi-system.json   失败:
    - info.title 缺失或为空
✓ openapi-server.json   (15 endpoints)

================================
2 / 3 通过
```

### 失败处理

修复契约 JSON → 重新执行 `npm run openapi:check`。
脚本不修改源文件,所有修复由人/后端工具完成。

### 当前边界

- **不做完整 Schema 语义校验**:当前验证引用存在性，不替代专业 OpenAPI Validator
- **Controller 漂移检查只覆盖当前使用的简单 Mapping 字符串**:不解析数组路径或组合注解
- **不比较 DTO/VO 字段与 Schema**:字段级一致性由 Controller 测试、类型检查和运行时验收覆盖
- **不校验中文/特殊字符**:OpenAPI 3 允许 Unicode

---

## pre-commit 钩子

`web-vue-SuMon/.husky/pre-commit` 会在每次 `git commit` 前自动跑 `npm run openapi:check`。
校验失败时 git 拒绝本次 commit。

### 钩子配置

- `core.hooksPath = web-vue-SuMon/.husky`（仓库根相对路径）
- 钩子脚本会 `cd` 到 `web-vue-SuMon/` 后跑校验
- 钩子的退出码透传给 git:非 0 时 commit 被中止

### 绕过钩子

紧急情况可用 `git commit --no-verify -m "..."` 跳过钩子,**不推荐**。

### 团队成员上手

**当前实现**：钩子通过手动 `git config core.hooksPath` 配置,不依赖 husky 包。
若在新机器上 clone 仓库,需要在仓库根跑一次:

```bash
git config core.hooksPath web-vue-SuMon/.husky
```

后续如果引入 husky 包(团队扩大、CI 多端),可加 `"prepare": "husky"` script 到 package.json 实现自动配置。
但当前不需要 husky 作为 devDep,手动 git config 即可。

### 关于 husky 包

我最初尝试用 husky 9.1.7 实现,遇到 Windows + 父子目录 `.git` 的兼容问题
(husky 在 web-vue-SuMon/ 跑找不到父级 .git)。
**当前方案放弃 husky,改为手写 shim**,更可控、更轻量、零依赖。

---

## audit-catchup.mjs

catch-up 流程静态审计脚本。**在 catch-up 大批 commit 前后各跑一次**,
捕获魔法数字 / 参数名错误 / API 路径拼错等常见 bug。

### 用法

```bash
npm run audit:catchup
```

扫描 `web-vue-SuMon/src/**/*.vue` / `*.ts`,对每份文件做 11 项规则审计:

| 规则 ID | 严重度 | 检测内容 |
|---|---|---|
| `MAGIC_PAGE_SIZE` | ERROR | `page_size: NNN` 硬编码 > 100 |
| `PARAM_NAME_MISMATCH` | ERROR | router-link 用 `params.id`(路由是 `:serverId`) |
| `ROUTE_PARAMS_ID` | ERROR | `route.params.id` 与路由 path 不匹配 |
| `HARDCODED_DEFAULT_PASSWORD` | ERROR | 占位符密码 / 默认密码(PLACEHOLDER / admin / 123456) |
| `API_PATH_TYPO` | WARN | `apiClient.*` 路径不在 OpenAPI 中 |
| `TS_ANY` | WARN | TypeScript `any` 类型(`: any` / `<any>` / `as any`) |
| `HARDCODED_HOST_PORT` | INFO | src/ 硬编码 host:port |
| `CONSOLE_LOG_RESIDUAL` | INFO | `console.log` 残留 |
| `COMMENTED_OUT_CODE` | INFO | 注释掉的代码块 |
| `TODO_FETCHER` | INFO | `// TODO` 残留 |
| `LONG_FILE` | INFO | .vue / .ts 文件 > 600 行(考虑拆分,阈值 Sprint 4 调高) |

### 输出示例(成功)

```
Catch-up 静态审计
================================
扫描 36 个 .vue / .ts 文件...

✓ 所有文件通过 11 条审计规则

================================
0 ERROR / 0 WARN / 0 INFO
```

### 输出示例(失败)

```
✗ [MAGIC_PAGE_SIZE] web-vue-SuMon/src/views/ServerListView.vue:305 page_size: 999 硬编码
⚠ [API_PATH_TYPO] web-vue-SuMon/src/api/auth.ts:20 /api/login — 不在 OpenAPI 中
================================
1 ERROR / 1 WARN
```

退出码:`0` 全通过 / `1` 有 ERROR / `2` 脚本执行异常。

### 设计原则

- 纯只读(不修改任何源文件)
- 离线(不依赖网络)
- 零依赖(仅用 Node 内置模块)
- CI 友好(退出码可被 `&&` 串接)

### 何时跑

- **catch-up 大批 commit 之前** — 提前发现风险
- **catch-up 大批 commit 之后** — 验证没遗漏
- **PR 提交前** — 防止新代码引入同类 bug
- **回归测试期间** — Module 2 M2-M5 回归前必跑

### 与 openapi:check 的关系

| 工具 | 检查目标 | 检查内容 |
|---|---|---|
| `openapi:check` | OpenAPI JSON + Java Controller | 结构、引用、operationId 和路径漂移 |
| `audit:catchup` | 前端 TS/Vue 文件 | 业务(魔法数字、参数名、API 路径、硬编码 host) |

两套互补,共同组成 "API 契约 + 前端用法" 双视角校验。

### 故意不做的事

- **不做完整 OpenAPI schema 字段交叉比对**(需要解析 properties 树,Module 2 视需求决定是否补)
- **不做 TS 类型推导对比**(需要解析 d.ts,留给 eslint-plugin-typescript)
- **不扫 .vue `<script>` 外的模板**(避免 false positive)

---

## api-e2e-test.mjs

HTTP API 自动化测试脚本。**在 catch-up 大批 commit 后跑一次**,
用 axios 直接调后端接口检测 4xx/5xx 错误、字段名不一致、未授权访问等
纯 HTTP 层 bug(不需要浏览器)。

### 用法

```bash
node scripts/api-e2e-test.mjs
# 或
npm run api:e2e
```

向 `http://127.0.0.1:18080` 发起请求,覆盖 17 用户路径中的 HTTP 部分:

| 路径 | 检查 |
|---|---|
| `/api/health` `/api/ready` | 后端活性 |
| `/api/auth/me` (无 token) | HTTP 401 + body 40100 |
| `/api/auth/register` | 注册新账号(pending 状态)|
| `/api/auth/login` (pending) | HTTP 403 + body 40300(预期)|
| `/api/auth/logout` | 40100 / 200 |
| `/api/servers` (无 token) | HTTP 401 + body 40100 |
| `/api/servers/{id}` | 200 + 字段名是 snake_case(ssh_host 等) |
| `/api/servers/{id}/status` | 200 |
| `/api/servers/{id}/metrics/latest` | 200 或 40400(无 agent)|
| `/api/servers/{id}/metrics` | 200 或 40400(无 agent)|
| `/api/admin/users/pending` (无 admin) | 40100 / 40300 |

### 不测的部分(需浏览器或 admin 凭据)

- UI 渲染(数字滚动、签名引言、玻璃卡视觉)
- 复杂交互(下拉、二次确认、模态框)
- M5 admin 操作 approve/reject(需要 admin 凭据)

### 设计原则

- 零依赖:仅用 Node 内置 `fetch`(Node 18+)
- 自动注册新 pending 账号(用 timestamp 后缀避免冲突)
- 输出对齐 `openapi:check` / `audit:catchup` 风格(✓/✗/⚠/退出码 0/1)
- 纯只读:不修改后端任何数据

### 与 audit:catchup 的关系

| 工具 | 范围 | 检查内容 |
|---|---|---|
| `audit:catchup` | 静态扫描 src/ | 代码层 bug(魔法数字 / 参数名 / 硬编码) |
| `api:e2e` | HTTP API 层 | 运行时 bug(4xx / 5xx / 字段命名 / 权限) |

两套互补:`audit:catchup` 防代码层错,`api:e2e` 防接口层错。

---

## ui-e2e-test.mjs

使用 `puppeteer-core` 驱动系统 Chrome，覆盖登录、退出、Dashboard、侧栏、服务器列表、
告警入口和管理员页面等真实浏览器路径。脚本不会保存或输出管理员密码、Token 和认证请求体。

### 运行参数

| 环境变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `SUSUMONITOR_UI_E2E_ADMIN_USERNAME` | 是 | 无 | 隔离验收管理员用户名 |
| `SUSUMONITOR_UI_E2E_ADMIN_PASSWORD` | 是 | 无 | 隔离验收管理员密码 |
| `SUSUMONITOR_UI_E2E_BASE_URL` | 否 | `http://127.0.0.1:5173` | 已启动的 Web 地址 |
| `SUSUMONITOR_UI_E2E_CHROME_PATH` | 否 | Windows Chrome 默认路径 | Chrome 可执行文件路径 |

缺少任一管理员凭据时，脚本在启动浏览器前以退出码 `2` 终止。浏览器场景失败返回 `1`，
全部通过返回 `0`。

### PowerShell 示例

```powershell
$env:SUSUMONITOR_UI_E2E_ADMIN_USERNAME='<validation-admin>'
$env:SUSUMONITOR_UI_E2E_ADMIN_PASSWORD='<validation-password>'
$env:SUSUMONITOR_UI_E2E_BASE_URL='http://127.0.0.1:5173'
npm run ui:e2e
```

必须使用隔离验收账号；不要把真实凭据写入脚本、README、`.env` 或 Git 提交。
