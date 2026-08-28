# 本地仓库收口指南（历史归档）

> 本文档是 2026-08-02 的历史快照，仅用于理解当时的备份流程；不代表当前仓库指针、工作区状态或远程配置。当前状态以根目录 `README.md` 和 Git 实际输出为准。
> 文中的路径、提交和命令均不可直接照抄；尤其不要执行会覆盖工作区的命令。

## 仓库

| 路径 | 类型 | 用途 |
|---|---|---|
| 远程备份仓库 | `<BACKUP_REPO_PATH>` | bare repo 或备份 remote 的实际路径，以 `git remote -v` 为准 |
| 工作树 | `<WORKTREE_PATH>` | 当前开发目录，以 `pwd` 为准 |

## 分支

| 分支 | 指向 | 状态 |
|---|---|---|
| `main` | 历史快照：`ab22b2e` | 2026-08-02 时点的 Sprint 1-4 收口主干；不是当前指针 |
| `feat/agent-monitoring` | 历史快照：`ab22b2e` | 当时已合并，保留作追溯 |

## Tag

| Tag | 指向 | 说明 |
|---|---|---|
| `v0.4.0-sprint4` | 历史快照：`ab22b2e` | Sprint 1-4 收口里程碑 |
| `v0.5.0-cloud` | 历史快照：`6d6744e` | Polish-5 GitHub 远程协作收口 |

## Sprint 1-4 收口总结(主项目主分支 `ab22b2e`)

| Sprint | 状态 | 关键 commit | 内容 |
|---|---|---|---|
| **1: SSH 真实化** | ✅ | `8b5dcc0` + `6c5655d` | `/api/servers/{id}/ssh/test` 接真实 + 7 错误码映射 |
| **2: spark 真实化** | ✅ | `c99fa03` + `3383577` | ServerSparkLine 通用组件 + ServerListView 接历史 |
| **3: Dashboard 完整化** | ✅ | `3dab5c8` + `d5f5995` | 消除 50 行重复 SVG + 接真实 metrics + 4 单测 |
| **Polish 6: audit 收口** | ✅ | `170577d` | LONG_FILE 阈值 500→600 + CRLF 兼容 |

## 4 道测试防线 + 11 条 audit 规则

| 工具 | 命令 | 数量 | 状态 |
|---|---|---|---|
| Vitest 单元测试 | `npm run test` | 129 测试 / 22 spec 文件 | ✅ |
| audit:catchup(11 规则)| `npm run audit:catchup` | 0 ERROR / 0 WARN / 0 INFO | ✅ |
| api:e2e(真实 HTTP 检查)| `npm run api:e2e` | 19 项 | ✅ |
| ui:e2e(浏览器场景)| `npm run ui:e2e` | 18 场景 | ✅ |
| typecheck | `npm run typecheck` | 0 错 | ✅ |
| lint | `npm run lint` | 0 错 0 警 | ✅ |
| openapi:check | `npm run openapi:check` | 5/5 | ✅ |

## 收口操作步骤（历史记录，仅供追溯）

> 原步骤涉及覆盖工作区的危险操作，已不再提供可执行命令。当前如需同步备份仓库，请先执行只读检查 `git status`、`git remote -v` 和 `git log --oneline --decorate -5`，确认目标后再按团队备份流程推送；不要使用 `git reset --hard`。

## 已知遗留（2026-08-02 历史快照）

> 以下数量只描述当时的工作区，不代表当前状态；当前状态必须以 Git 命令为准。

- 当时记录的 dirty 文件数量：110
- 当时记录的 UI E2E 和 LONG_FILE 信息项

## 后续

- 当前后端、前端、Agent 和 Android 状态请查看根目录 `README.md`。
- 当前分支、远程及是否需要推送，必须先用 `git status --short --branch`、`git remote -v` 和 `git branch -vv` 核对。
