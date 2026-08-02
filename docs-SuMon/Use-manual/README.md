# SuSuMonitor 使用手册索引（MVP-8）

运维与使用手册总目录。按主题选择：

## Server（Java 后端 + 依赖组件）

| 手册 | 适用场景 |
|---|---|
| [SuSuMonitor-Server-部署安装手册.md](./SuSuMonitor-Server-部署安装手册.md) | 从零安装（MySQL/RabbitMQ/JAR/Nginx）、启停、健康检查、首次验收清单 |
| [SuSuMonitor-升级与回滚手册.md](./SuSuMonitor-升级与回滚手册.md) | 版本升级流程、回滚决策矩阵（JAR 可回滚 / 数据库不可回滚） |
| [SuSuMonitor-备份与恢复手册.md](./SuSuMonitor-备份与恢复手册.md) | 数据库 + 密钥备份（`deploy/backup.sh`）、恢复（`deploy/restore.sh`）、演练 |
| [SuSuMonitor-安全检查手册.md](./SuSuMonitor-安全检查手册.md) | 端口/数据库/RabbitMQ/密钥/TLS 安全基线 + 上线前检查表 |
| [SuSuMonitor-RabbitMQ-运维手册.md](./SuSuMonitor-RabbitMQ-运维手册.md) | MVP-10 Outbox 依赖的 RabbitMQ 日常运维（vhost/拓扑/积压/停机恢复） |

## Agent（Go Agent）

| 手册 | 适用场景 |
|---|---|
| [Go-Agent 部署使用手册.md](./Go-Agent 部署使用手册.md) | Agent 构建（Linux 交叉编译）、Token 发放、systemd 部署、验证、家庭内网部署 |
| [Go-Agent-WSS公网验收手册.md](./Go-Agent-WSS公网验收手册.md) | 公网 WSS/HTTPS 部署验收清单（域名/TLS/证书/反代与端到端验证步骤） |

## 配套资产与文档

- 部署资产：`server-java-SuMon/deploy/`（install.sh、backup.sh、restore.sh、systemd unit、nginx 片段、`DEPLOYMENT.md` 资产说明）
- 部署交接：`docs-SuMon/Handoff-SuMon/20260727-云端T5部署交接.md`、`20260731-云端部署调试交接.md`
- 运维计划：`docs-SuMon/Develop-plans/20260724-公网Agent反向终端与云端部署计划.md`
- 验收资产：`api-test/verify-alert-ws.mjs`、`api-test/verify-outbox.mjs`、`api-test/bench-alert-chain.mjs`
