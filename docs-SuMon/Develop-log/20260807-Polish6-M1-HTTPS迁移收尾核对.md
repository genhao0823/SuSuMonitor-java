# 2026-08-07 Polish-6 M1：HTTPS 迁移收尾核对

**状态**：完成
**前置基线**：HTTPS 迁移完成（Difficulty-log/20260807-*）

## 一、内容

1. **agent 机器核对**：活跃 agent 全部指向 `wss://genhaosan.online` ✓
   - wsl111（server_id=4，WSL 本机）：`SUSUMONITOR_BACKEND_URL=wss://genhaosan.online` ✓
   - txcloud（server_id=8，云端）：`SUSUMONITOR_BACKEND_URL=wss://genhaosan.online` ✓
   - DESKTOP-VT1VN6O-2051/31503、wsl-dev：离线旧机器，agent 未运行，无需修改（状态记录在案）
2. **全库明文 URL 扫描**（`http://82.156.245.102` / `ws://`）：
   - 历史日志（Develop-log 07-29、Difficulty-log 07-30/08-07、Handoff 07-31、Introduction）按"只追加"原则**保留原文**，Handoff 交接文档顶部追加 2026-08-07 更新说明
   - 活跃运维文档更新：`agent-go-SuMon/deploy/RELEASE.md`（旧明文安装命令 → HTTPS/WSS 命令）、`app-kt-SuMon/README.md`（对接地址 → https://genhaosan.online）
   - `ws://` 残留均为本地开发示例（localhost/172.29.240.1 内部地址）与 install-agent.sh 的安全校验守卫（临时 IPv4 明文模式专用），保留

## 二、备份

`local/backup-polish6/M1-https-sweep/`：RELEASE.md、app-kt-SuMon/README.md、Handoff 交接文档原版

## 三、提交

- `docs(ops): sweep remaining plaintext URLs after HTTPS migration`

## 四、后续检查项

- [x] 活跃 agent（wsl111/txcloud）已指向 wss://genhaosan.online
- [x] 活跃运维文档已更新为 HTTPS/WSS
- [ ] 离线机器（DESKTOP-*、wsl-dev）若重新启用需按新安装命令部署（见 RELEASE.md / 部署手册）
