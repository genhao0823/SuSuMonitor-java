# 本地仓库收口指南(对应 `D:\develop\Git\SuSuMonitor.git`)

> **本索引为 Sprint 4 历史快照（2026-08-02），最新状态以主仓库 README 与 docs-SuMon 为准**

> **位置提示**:本文档是给 `D:\develop\Git\SuSuMonitor.git` 本地裸仓库的"内容索引"。物理 README.md 写不进 bare repo,这里放在主项目根目录,提交到 main 后即可通过 git log 追溯。

## 仓库

| 路径 | 类型 | 用途 |
|---|---|---|
| `D:\develop\Git\SuSuMonitor.git` | **bare repo** | 本地异地备份 + 多设备协作 + 收口里程碑 |
| `C:\Users\genhaosan\Desktop\SuSuMonitor(Jvav)` | working tree | 开发主目录,`backup` remote 指向裸仓库 |

## 分支

| 分支 | 指向 | 状态 |
|---|---|---|
| `main` | `ab22b2e` | ✅ Sprint 1-4 收口主干 |
| `feat/agent-monitoring` | `ab22b2e` | ✅ = main(已合并,保留供追溯)|

## Tag

| Tag | 指向 | 说明 |
|---|---|---|
| `v0.4.0-sprint4` | `ab22b2e` | Sprint 1-4 收口里程碑 |
| `v0.5.0-cloud` | `6d6744e` | Polish-5 GitHub 远程协作收口 |

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

## 收口操作步骤(供未来参考)

```bash
# 1. 在 work tree 合并 feat/agent-monitoring -> main
cd "C:\Users\genhaosan\Desktop\SuSuMonitor(Jvav)"
git fetch backup main
git reset --hard backup/main   # 同步到 backup main
git merge --no-ff backup/feat/agent-monitoring -m "Merge feat/agent-monitoring into main - Polish 5 收口"

# 2. 推 main
git push backup main

# 3. 打 tag
git tag -a v0.4.0-sprint4 -m "Sprint 1-4 收口" cacf131
git push backup v0.4.0-sprint4

# 4. (可选)给 main 写个收口 README — 物理 README.md 写不进 bare repo,改用 BACKUP_REPO_README.md(本文档)
```

## 已知遗留(留作未来 polish)

- 110 个 git status dirty 文件(其他工具引入,不影响代码)
- 1 个 ui:e2e WARN(搜索 v-model 传递,微调)
- 3 个 LONG_FILE 实际超 500 行(525-565)但 INFO 严重度,阈值 600

## 后续

- **后端**:`server-java-SuMon/` 进入下一阶段(SSH 真实实现 + 告警)
- **前端**:Sprint 5+ 视新需求启动
- **协作**:Polish 5(GitHub remote)✅ **完成于 2026-08-02(tag `v0.5.0-cloud`,commit `6d6744e`)**,详见 `docs-SuMon/Develop-log/20260802-Polish-5-GitHub-首次推送.md`
