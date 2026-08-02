# 开发日志: Polish 5 — GitHub 首次推送(首次上云端部署 + 基本功能闭环)

**日期**: 2026-08-02
**操作人**: opencode
**关联计划**: `docs-SuMon/Develop-plans/20260712-SuSuMonitor项目规划.md` § 27.1 / `docs-SuMon/Develop-log/20260722-Sprint-4-Polish收口.md` Polish 5

## 状态

| 项 | 状态 |
|---|---|
| Polish 5: GitHub remote push | ✅ **完成(2026-08-02,tag `v0.5.0-cloud`)** |
| Polish 5 文档同步 | ✅ 本日志 + `BACKUP_REPO_README.md` § 后续 |
| Polish 5 README 标注 | ✅ README.md 顶部新增里程碑段落 |

## 推送快照

| 字段 | 值 |
|---|---|
| GitHub 仓库 | `https://github.com/genhao0823/SuSuMonitor-jvav-` |
| 本次 tag | `v0.5.0-cloud`(annotated,指向 `6d6744e`) |
| 推送范围 | `main` HEAD `e7f713f`(MVP-11 收口 commit) + 1 个 README 标注 commit `6d6744e` |
| 本次新增 commit | `6d6744e docs: Polish 5 — README 标注首次上云端部署 + 基本功能闭环(tag v0.5.0-cloud)` |
| 推送类型 | fast-forward(`origin/main` 与本地 `e7f713f` 同 hash,新增 1 个 commit 后本地领先 1) |
| 认证方式 | Git Credential Manager(`credential.helper = manager`),GCM 自动从 Windows 凭据管理器读取 OAuth/PAT,**全程未接触明文 token** |
| 同步范围 | origin(GitHub)✅ + backup(`D:\develop\Git\SuSuMonitor.git` 本地裸仓库)✅ |
| 推送前 husky | `npm run openapi:check` 5/5 通过(`openapi-admin/alert/auth/server/system.json`) |

## 本次落地的工程文件

### 修改文件(2)

| 文件 | 改动 | 行数 |
|---|---|---|
| `README.md` | 顶部徽章下新增「## 🚀 首次上云端部署 + 基本功能闭环(2026-08-02,Polish 5)」段落,含 GitHub URL / tag / 推送范围 / 认证方式 / 闭环要点 / 云端部署摘要 / 已知遗留 | +18/-1 |
| `BACKUP_REPO_README.md` | § 后续 第 75 行 `Polish 5(GitHub remote)留作后续(等用户提供 URL + 凭据)` → `Polish 5(GitHub remote)✅ 完成于 2026-08-02(tag v0.5.0-cloud)` | +1/-1 |

**总:+19/-2,2 文件**(其中 README 段落已通过 commit `6d6744e` 推到 GitHub;`BACKUP_REPO_README.md` 同步作为下一批 dirty 的一部分,本日志留作状态锚点)。

### 新建文件(1)

| 文件 | 路径 | 说明 |
|---|---|---|
| 本日志 | `docs-SuMon/Develop-log/20260802-Polish-5-GitHub-首次推送.md` | Polish 5 落地记录 |

## Polish 5 关键决策

### 1. 走 GCM 而非明文 PAT

**背景**:
- 用户明示本机 GCM(`credential.helper = manager`)已存 GitHub 凭据
- `gh` CLI 未安装
- 仓库 `.agents/skills/susumonitor-git/SKILL.md` 规范里也明确"本地提交后按需 push 到 backup",并未限制 GitHub push 流程

**决策**:完全依赖 GCM,执行 `git push origin main --tags` 时由 GCM 自动从 Windows 凭据管理器读取 OAuth token;**本次会话不接收、不读取、不写入任何 PAT 字符串**,避免凭据泄漏面。

**结果**:推送成功,无弹窗、无报错。

### 2. README 段落独立 commit,而非合并进 78 项 dirty

**背景**:
- 工作区有 78 项 dirty(含本次推送前的 MVP-11 验证脚本、`20260801` 单实例后端可靠性规划、Introduction 删除、多个 *_test.go 新增等)
- 用户明确"只推 main HEAD(暂不动 dirty)"

**矛盾**:要把 README 段落同步上 GitHub,必须新建 1 个 commit(因为远端 `e7f713f` 等于本地 HEAD,不动就等于推空)。

**决策**:
- 仅 `git add README.md`,**不** `git add .` 或 `git add -u`,确保 78 项 dirty 不被本次 commit 触及
- husky pre-commit 仍会跑(因为 `core.hookspath = web-vue-SuMon/.husky` 是仓库级配置),但 `npm run openapi:check` 只校验 OpenAPI 文件与 Controller 路径,README 变更不破坏契约,顺利通过(5/5)
- 78 项 dirty 全部留作下一轮单独 commit + push

**结果**:commit `6d6744e` 仅修改 README.md 1 文件 +18/-1,fast-forward 推送无冲突。

### 3. tag 命名 `v0.5.0-cloud` 而非 `v1.0.0`

**背景**:
- 上一个 tag `v0.4.0-sprint4` 命名规则为 `<version>-<sprint>`
- 当前里程碑是"首次上云端部署 + 基本功能闭环",非某个 sprint 收口

**决策**:`v0.5.0-cloud` 表达"0.5 大版本(对应 sprint 1-4 + MVP 系列)+ cloud(首次上云端)",语义清晰且与历史 tag 兼容。annotated tag 携带完整 message,方便后续 `git show v0.5.0-cloud` 追溯。

**未做**:GitHub Release 创建(从 `v0.5.0-cloud` 手动创建,本日志作为 Release notes 草稿)。

### 4. 同步本地 backup remote

**背景**:
- `BACKUP_REPO_README.md` 标题即说明"对应 `D:\develop\Git\SuSuMonitor.git`"
- 历史习惯(见 `BACKUP_REPO_README.md` § 收口操作步骤):重大里程碑同步 push 到 backup

**决策**:`git push backup main --tags`,确保本地裸仓库也包含 `6d6744e` + tag `v0.5.0-cloud`,与 GitHub 一致。

## 推送前后对比

### origin 推送前(只读探查 + 推送输出)

```
e7f713f..6d6744e  main -> main
 * [new tag]         v0.5.0-cloud -> v0.5.0-cloud
```

### origin 推送后验证(`git ls-remote origin`)

```
6d6744e...  HEAD
6d6744e...  refs/heads/main
86a0d98...  refs/tags/v0.4.0-sprint4
cacf131...  refs/tags/v0.4.0-sprint4^{}
860fc3a...  refs/tags/v0.5.0-cloud
6d6744e...  refs/tags/v0.5.0-cloud^{}
```

### backup 推送验证

```
f1399f4..6d6744e  main -> main
 * [new tag]         v0.5.0-cloud -> v0.5.0-cloud
```

## 已知遗留(留作后续 Polish)

- **78 项本地 dirty**:`Introduction/` 5 篇文档删除 + `20260801` 单实例后端可靠性规划 + MVP-11 验证脚本(`replay-mvp11-dlq.mjs` / `verify-mvp11-concurrency.mjs` / `verify-mvp11-broker-recovery.md`) + 新增 `agent-go-SuMon/cmd/susumonitor-agent/main_test.go` / `internal/wsclient/client_test.go` + 各模块 README / spec / 配置微调 —— 下一轮作为单独 batch commit + push
- **`BACKUP_REPO_README.md` Polish 5 状态同步**:作为下一轮 dirty 提交内容之一,本日志已锚定状态
- **HTTPS / WSS 待域名备案**:腾讯云 OpenCloudOS 首次部署为公网明文 HTTP,需域名备案后切 TLS(详见 `docs-SuMon/Handoff-SuMon/20260731-云端部署调试交接.md`)
- **GitHub Release**:可手动从 tag `v0.5.0-cloud` 创建,本日志可作为 Release notes 草稿
- **Dockerfile / docker-compose**:仍属增强阶段(见 `项目需求与规范.md` § 增强阶段 / `docs-SuMon/Summary-Technology/SuSuMonitor-技术栈总结.md` § 七/§ 九),代码未实现
- **GitHub Actions CI**:仓库无 `.github/workflows/`,与上面同属增强阶段

## 关联 commit / tag

- commit `6d6744e docs: Polish 5 — README 标注首次上云端部署 + 基本功能闭环(tag v0.5.0-cloud)`
- commit `e7f713f feat(server): validate metrics event contract before alert consume`(推送基线,MVP-11 收口最终 commit)
- tag `v0.5.0-cloud`(annotated,指向 `6d6744e`)
- tag `v0.4.0-sprint4`(历史 sprint 收口,本次推送后远端仍存在)

## 后续

- **下一轮 commit + push**:处理 78 项 dirty(建议拆为 `docs:` `test:` `feat:` 三批 commit,每批走 husky pre-commit)
- **GitHub Release**:浏览器从 tag `v0.5.0-cloud` 创建,Release notes 可引用本日志
- **Polish 6**(已先于 Polish 5 完成,见 `20260722-Sprint-4-Polish收口.md`):audit LONG_FILE 阈值 500→600 + CRLF 兼容
- **Polish 7+**(视新需求启动):前端 Sprint 5+ / 后端 MVP-12+ / 单实例后端可靠性规划(`20260801`)实施