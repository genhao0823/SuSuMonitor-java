# SuSuMonitor Web 前端

SuSuMonitor 监控平台的 Web 前端工程，基于 Vue 3 + Vite + Element Plus。

## 当前里程碑

**M2-M6 主页面已实现**：认证、主布局、仪表盘、服务器管理、用户审核、实时指标页面以及 MVP-6 告警前端（告警记录 + 告警规则）均已接入真实后端。Web SSH 终端属于 MVP-7，~~当前尚未实现~~（T4 xterm.js 前端已于 2026-07-28 实现最小可用版本，路由 `/terminal/:serverId`，详见 [`docs-SuMon/Develop-log/20260728-MVP7-T4前端Web终端最小可用版本.md`](../docs-SuMon/Develop-log/20260728-MVP7-T4前端Web终端最小可用版本.md)）。

详细计划：[`docs-SuMon/Develop-plans/20260720-Web前端详细开发计划.md`](../docs-SuMon/Develop-plans/20260720-Web前端详细开发计划.md)
当前总览：[`docs-SuMon/Develop-log/20260722-Web前端总览.md`](../docs-SuMon/Develop-log/20260722-Web前端总览.md)

## 技术栈

| 类别 | 选型 |
|---|---|
| 构建 | Vite 5 |
| 框架 | Vue 3.5（`<script setup>` + Composition API） |
| 语言 | TypeScript 5 |
| 路由 | Vue Router 4 |
| 状态 | Pinia 3（含持久化插件） |
| 组件库 | Element Plus 2（按需引入） |
| HTTP | axios 1 |
| 代码规范 | ESLint 8 + Prettier 3 |

## 前置条件

- Node.js >= 18.18（推荐 LTS）
- npm >= 9
- 后端 `server-java-SuMon/` 已在 `localhost:18080` 运行并完成 Flyway 迁移

## 启动

```powershell
Set-Location "C:\Users\genhaosan\Desktop\SuSuMonitor(Jvav)\web-vue-SuMon"
npm install
npm run dev
```

默认开发服务器：[http://127.0.0.1:5173](http://127.0.0.1:5173)

Vite 已配置代理 `/api → http://localhost:18080`，前端直接以 `/api/*` 形式调用后端即可，无需关心跨域。

## 常用命令

| 命令 | 说明 |
|---|---|
| `npm run dev` | 启动 Vite 开发服务器（5173） |
| `npm run build` | 类型检查 + 生产构建到 `dist/` |
| `npm run preview` | 本地预览构建产物 |
| `npm run typecheck` | 仅类型检查 |
| `npm run lint` | ESLint 检查 |
| `npm run lint:fix` | ESLint 自动修复 |
| `npm run format` | Prettier 格式化 |
| `npm run test` | 执行 Vitest 单元测试 |
| `npm run openapi:check` | 校验 OpenAPI 结构、引用和 operationId |
| `npm run api:e2e` | 执行真实后端 HTTP 路径检查 |
| `npm run ui:e2e` | 执行浏览器 UI 路径检查 |

## 与后端契约

后端 OpenAPI JSON 是唯一事实源，前端字段定义必须与之保持一致：

- `docs-SuMon/OpenApi-SuMon/openapi-system.json`
- `docs-SuMon/OpenApi-SuMon/openapi-auth.json`
- `docs-SuMon/OpenApi-SuMon/openapi-admin.json`
- `docs-SuMon/OpenApi-SuMon/openapi-server.json`
- `docs-SuMon/OpenApi-SuMon/openapi-alert.json`


## 调试入口

- 健康检查：浏览器 DevTools → Network → `/api/health` 应见 `code=0`
- 就绪检查：`/api/ready` 应见 `code=0`、`database=ok`
- 服务器列表：`/api/servers` 无 Token 时返回 `code=40100`（预期）

## 已知约束

- 实时指标与 MVP-6 告警前端（记录页 + 规则页）已经接入；Web SSH 终端属于 MVP-7，~~尚未实现~~（T4 已于 2026-07-28 实现最小可用版本，见 [`docs-SuMon/Develop-log/20260728-MVP7-T4前端Web终端最小可用版本.md`](../docs-SuMon/Develop-log/20260728-MVP7-T4前端Web终端最小可用版本.md)）。
- Dashboard 通过现有 health、ready、servers 和 pending users 接口聚合，专用 `/api/dashboard/summary` 后置评估。
- ~~服务器总数趋势和 SSH 历史卡仍是明确标注的模拟/占位内容~~（2026-07-31 更新：服务器总数卡 spark line 已于 Sprint 3 接真实 metrics 历史；仅"SSH 测试历史卡"`DashboardSshCard` 仍为占位，等待 SSH test history 接口）。
- `package-lock.json` 在执行 `npm install` 后生成，需提交至版本控制。

## 前端开发

### 快速开始

```powershell
Set-Location "C:\Users\genhaosan\Desktop\SuSuMonitor(Jvav)\web-vue-SuMon"
npm install
npm run dev    # http://127.0.0.1:5173,自动代理 /api → :18080
```

### 当前已实现功能(2026-07-23 核对)

| 功能 | 状态 | 关联 |
|---|---|---|
| 工程骨架、Vite 代理、ESLint、Prettier | ✅ | M1 |
| 登录/注册/当前用户/退出 | ✅ | M2 |
| 路由守卫(requiresAuth/requiresAdmin/publicOnly) | ✅ | M2 + 启动兜底 |
| MainLayout(顶栏 + 侧栏 + 用户下拉) | ✅ | M3 |
| 涂山苏苏主题视觉(玻璃卡 + 铃铛花瓣波浪 + 签名引言) | ✅ | M3-M4 |
| Dashboard 美化(玻璃卡 + 渐变徽标 + 数字滚动 + 头像) | ✅ | 后续打磨 |
| 服务器列表/详情/创建/编辑/软删除 | ✅ | M4 |
| 管理员审核页(通过/拒绝 + 错误码映射) | ✅ | M5 |
| 路由切换 loading bar(NProgress) | ✅ | UX 打磨 |
| M6 实时监控页(ticket + WS + REST 历史 + 卡片 + 表) | ✅ | M6 |
| Sprint 1 SSH 测试按钮接真实后端 | ✅ | Sprint 1 |
| Sprint 2 spark line 接真实历史 | ✅ | Sprint 2 |
| Sprint 3 Dashboard spark 接真实 + ServerSparkLine 复用 | ✅ | Sprint 3 |
| MVP-6 告警前端(记录页 + 规则页 + alert.push 消费 + 菜单挂载) | ✅ | Sprint 5-7 |
| Polish 6 LONG_FILE 阈值调高 + CRLF 兼容 | ✅ | Polish 6 |
| OpenAPI 结构、引用和 operationId 校验脚本 | ✅ | 自动化 |
| catch-up 静态审计(audit:catchup,11 条规则 v0.2) | ✅ | 自动化 |
| HTTP API 自动化测试(api:e2e) | ✅ | 自动化 |
| UI E2E 浏览器自动化(ui:e2e,puppeteer-core) | ✅ | 自动化 |
| 代码拆分重构(Polish-3,拆 9 子组件) | ✅ | 自动化 |
| pre-commit 钩子(跑 openapi:check) | ✅ | 自动化 |
| 服务器列表 spark line | ~~⚠️ mock~~ → ✅ 已接真实（Sprint 2） | metrics history 接口已接入 |
| 上次 SSH 测试结果卡 | ⚠️ 占位 | 等待 SSH test history 接口 |
| 批量审核/用户搜索/历史记录 | ✅ 批量审核 + 用户搜索已实现（2026-08-02，Sprint 5）；历史记录待后端接口 | admin API 已接入 |
| 服务器详情投递状态（积压/最旧采样/缓冲丢弃/本地死信） | ✅ | M4-3（2026-08-05） |

### 扩展命令

| 命令 | 作用 |
|---|---|
| `npm run dev` | 开发服务器(端口 5173) |
| `npm run build` | 生产构建(类型检查 + 打包) |
| `npm run typecheck` | TypeScript 检查 |
| `npm run lint` / `lint:fix` | ESLint |
| `npm run format` | Prettier 格式化 |
| `npm run openapi:check` | OpenAPI 契约 lint |
| `npm run audit:catchup` | catch-up 静态审计(11 条规则,扫魔法数字 / 参数名 / API 路径 / 占位密码 / TS any / 残留日志) |
| `npm run test` | Vitest 单元测试(118 用例,覆盖 stores + utils + composables + api/services) |
| `npm run api:e2e` | HTTP API 自动化测试(覆盖 17 路径中的 HTTP 部分) |
| `npm run ui:e2e` | UI E2E 浏览器自动化(puppeteer-core + 系统 Chrome,17 路径) |

### 目录结构(2026-07-21 更新)

```text
web-vue-SuMon/
├── .gitignore
├── .eslintrc.cjs
├── .prettierrc.json
├── package.json
├── tsconfig.json
├── tsconfig.node.json
├── vite.config.ts
├── index.html
├── env.d.ts
├── README.md
├── scripts/
│   ├── check-openapi.mjs       # OpenAPI 契约结构 lint
│   └── README.md
├── public/
│   ├── favicon.jpg              # 涂山苏苏 favicon
│   └── tushansusu-hero.jpg      # LoginLayout 兜底图
├── .husky/
│   └── pre-commit               # 跑 npm run openapi:check
└── src/
    ├── main.ts
    ├── App.vue
    ├── styles/global.css
    ├── api/                     # HTTP 客户端封装
    ├── stores/                  # Pinia 状态(auth 等)
    ├── router/
    │   ├── index.ts             # 路由表 + 守卫 + 进度条
    │   └── guards.ts            # 鉴权守卫
    ├── layouts/                 # MainLayout
    ├── components/              # 公共组件(PageHeader/TushanFoxMark/ServerFormDialog)
    ├── composables/             # 组合式函数(useRouterLoading)
    ├── views/                   # 页面组件
    ├── utils/                   # 工具函数
    └── types/                   # TypeScript 类型(对齐 OpenAPI)
```

### pre-commit 钩子

`web-vue-SuMon/.husky/pre-commit` 在每次 `git commit` 前自动跑 `npm run openapi:check`,
失败则 commit 被阻止。

首次 clone 后手动激活(在仓库根执行):

```bash
git config core.hooksPath web-vue-SuMon/.husky
```

跳过:`git commit --no-verify`(不推荐)。

### 重要:涂山 IP 使用范围

本项目使用涂山苏苏等第三方 IP 形象,**仅供内部学习与 demo 用途,非官方同人作品,不用于商业用途**。
公开展示或商业化前请替换为自有素材或已获授权的版本。
详见各页面引言池、`/docs-SuMon/Bug-fix/` 目录与各 dev-log。

### 关键设计决策

| 决策 | 方案 | 理由 |
|---|---|---|
| 路由切换视觉反馈 | NProgress 顶部条 | 零状态管理,3KB,主题可定制 |
| Dashboard 数字滚动 | `requestAnimationFrame` + easeOutCubic | 平滑,无依赖,1.2s |
| 服务器列表排序 | 后端 `sort_by` / `sort_order` 白名单排序 | 已完成真实 MySQL、HTTP 和 Apifox 验收 |
| 鉴权 token 存储 | localStorage via pinia-plugin-persistedstate | 刷新保留,可被"记住我"控制 |
| 错误码映射 | `src/types/error-code.ts` 集中常量,各 view 各自映射 toast | 单一事实源,后端改码只改一处 |
