# SuSuMonitor 难点排查记录目录(Difficulty-log)

本目录跟踪 SuSuMonitor 开发与云端部署过程中"排查最艰难、最有代表性"的难点与生产环境问题(多为非纯代码 bug:网络环境、非安全上下文、部署遗漏等)。
每篇一份独立 Markdown 文件,命名规则 `YYYYMMDD-主题-关键词.md`。

## 当前索引(9 篇)

| 日期 | 文件 | 一句话摘要 |
|---|---|---|
| 2026-07-30 | 20260730-Agent断开后服务器仍显示在线-afterConnectionClosed不更新DB.md | Agent 断开后服务器仍显示"在线":afterConnectionClosed 只清理内存 registry 不更新 DB,断线回调补乐观锁置离线收口。 |
| 2026-07-30 | 20260730-Web终端黑框-terminal-open-message-id非UUID.md | Web 终端黑框无响应:terminal.open 的 message_id 在非安全上下文降级为非 UUID 被 40003 拒绝,且初始 cols 极小(fit 时机),双根因修复。 |
| 2026-07-30 | 20260730-前端登录点击无反应-crypto-randomUUID非安全上下文.md | 前端登录点击无反应:明文 HTTP 下 `crypto.randomUUID` 为 undefined,axios 拦截器同步抛错,请求发出前即被 reject(console/network 零输出)。 |
| 2026-07-30 | 20260730-宽带运营商劫持WebSocket-诊断记录.md | 宽带运营商劫持 WebSocket:明文 HTTP 被剥 `Upgrade` 头 + 注入广告脚本,四组对照实验证据链定位,非代码 bug,彻底方案 HTTPS/WSS。 |
| 2026-07-30 | 20260730-服务器状态显示离线-status字段不随agent更新.md | 服务器状态显示"离线":`status` 与 `agent_status` 两个同义字段不同步,后端从不更新 status,三处 SQL 同步更新收口。 |
| 2026-08-07 | 20260807-HTTPS迁移与前端根路由未重定向登录页.md | HTTPS 迁移与前端根路由未重定向登录页:备案通过迁移 genhaosan.online,根路由 `/` 未重定向 `/login` 的收尾修复。 |
| 2026-08-07 | 20260807-Web终端建链中-agent仍指向明文地址.md | Web 终端一直"建链中":HTTPS/WSS 迁移遗漏,Agent 仍指向明文旧地址。 |
| 2026-08-07 | 20260807-一键安装500-AgentToken注册误发GET.md | 一键安装 500:install-agent.sh 注册 Agent Token 时 curl 漏 `-X POST` 误发 GET,打 POST-only 端点。 |
| 2026-08-10 | 20260810-Swagger-UI被nginx-SPA-fallback吞掉.md | Swagger UI 公网被 nginx SPA fallback 吞掉:`/swagger-ui.html` 与 `/api-docs` 均返回前端 SPA 页面,两层根因修复。 |

## 文档结构

每篇按 现象 → 排查 → 根因 → 修复/解决 → 教训 记录,与 Bug-fix 目录的"现象/根因/修复/验收"四段式互为补充。

## 关联

- `docs-SuMon/Bug-fix/`:纯代码 bug 的修复记录(9 篇,README 建索引)。
- `docs-SuMon/Develop-log/`:当日开发日志,与本文档互为补充。
