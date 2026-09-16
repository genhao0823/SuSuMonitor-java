# SuSuMonitor

> 涂山苏苏主题的服务器监控平台 — Web 前端 + Java 后端 + Go Agent + Android 全栈

## 项目简介

SuSuMonitor 是一套**前后端 + Agent 全栈**的服务器监控系统，主题采用涂山苏苏（狐妖小红娘）IP。Go Agent 部署在被监控服务器上采集指标，Java 后端负责接收、存储、告警评估与实时推送，Web 控制台（Vue 3）与 Android 客户端（Kotlin + Compose）提供监控、告警与 SSH 终端界面。

## 功能特性

- **实时监控** — Agent 5 秒级采集 CPU / 内存 / 磁盘 / 网络，WebSocket 实时推送；进程 Top 榜与多盘 / 多网卡资源下钻（90 秒新鲜窗口）
- **告警** — 阈值规则、触发 / 恢复状态机、实时推送、邮件 / 钉钉 / Webhook 通知渠道与投递历史；RabbitMQ 可靠消费 + 幂等去重 + 死信队列
- **SSH 运维** — 连接测试、主机指纹首次确认 / 一键信任、浏览器 Web 终端（xterm.js）、Agent 断线中继与流控
- **可靠投递** — Transactional Outbox、Agent 侧 FIFO 持久缓冲 + ACK/NACK 重传 + 本地死信、指数退避断线重连
- **AI 助手**（全部默认关闭，环境变量开关）— 只读诊断、告警智能解释、运维问答（只读工具 Tool Calling）、命令域（白名单模板 + 审批制 + 观察期评审）、定时健康报告
- **安全** — JWT 认证、AES-256-GCM 凭据加密、首管理员一次性初始化令牌、注册 / 登录限流、角色访问控制、SSH 出站 CIDR 白名单
- **多端** — Web 控制台 + Android 客户端（自研 ANSI 终端模拟器、前台服务告警通知）
- **可选 Redis** — 多实例 Ticket 单次核销、登出黑名单与分布式限流

## 项目状态

公网 HTTPS/WSS 生产运行中。质量基线：后端 JUnit **874**、前端 Vitest **229**、Android **128**、隔离库 MySQL IT **55** 全部通过；OpenAPI 契约 7 份（**50 路径 / 59 端点操作**）与代码双向校验；Flyway 迁移 **V37**。

## 技术栈

| 端 | 技术 |
|---|---|
| Web 前端 | Vue 3.5 + Pinia + Element Plus + axios，Vitest / E2E（puppeteer-core） |
| 后端 | Java 21 + Spring Boot 3.4 + MyBatis + MySQL 8.4 + Flyway + WebSocket + RabbitMQ |
| Agent | Go（gopsutil 采集 + WebSocket 上报 + PTY 终端 + 受限命令执行） |
| Android | Kotlin + Jetpack Compose + Retrofit + Hilt |
| AI | Spring AI（OpenAI-compatible 网关，默认关闭） |

## 架构总览

```
Go Agent ──WS上报──▶ Java 后端 ──▶ MySQL（指标/审计/Outbox）
                        │  └────▶ RabbitMQ（告警事件 → 消费者 → 通知）
Web 控制台 ◀──Monitor WS 推送──┤
Android App ◀──REST/WSS──────┘
        └──SSH 终端 / 命令域：后端中继 ◀──▶ Agent PTY / 受限执行器
```

详见 `docs-SuMon/Summary-Technology/`。

## 快速开始

### Docker Compose（一键起全栈）

```bash
git clone https://github.com/genhao0823/SuSuMonitor-java.git
cd SuSuMonitor-java
cp .env.example .env        # 填写必填项：JWT_SECRET、AES_GCM_KEY 等
docker compose up -d        # mysql + rabbitmq + server + web（nginx 入口 :8080）

# 首次启动获取首管理员一次性初始化令牌（消费后即失效）
docker compose logs server | grep -A 5 "一次性初始化令牌"
```

浏览器打开 <http://localhost:8080> → 注册页输入用户名 / 密码与初始化令牌，注册成功即成为管理员。完整部署 / 升级 / 备份流程见 `docs-SuMon/Use-manual/`。

### 本地开发

前置：Node.js ≥ 18.18、npm ≥ 9、JDK 21、Go ≥ 1.23、MySQL 8.4（RabbitMQ 用于告警链路）。

```bash
# 前端（web-vue-SuMon/）
npm install
npm run dev                 # http://127.0.0.1:5173
npm run test                # 单元测试
npm run openapi:check       # 契约与 Controller 一致性校验

# 后端（server-java-SuMon/）
./mvnw spring-boot:run -Dspring-boot.run.profiles=local    # http://localhost:18080
./mvnw test                 # 全量单元测试

# Agent（agent-go-SuMon/，纯环境变量配置，详见 agent-go-SuMon/README.md）
go build -o susumonitor-agent ./cmd/susumonitor-agent
SUSUMONITOR_BACKEND_URL=ws://localhost:18080 \
SUSUMONITOR_SERVER_ID=<server_id> \
SUSUMONITOR_AGENT_TOKEN=<agent_token> \
./susumonitor-agent
```

### Android 客户端

`app-kt-SuMon/` 目录用 Android Studio 打开，或 `./gradlew assembleDebug` 构建调试包。

## 文档

开发过程类内容（计划、日志、缺陷档案）全部在 `docs-SuMon/` 内按日期留档：

| 目录 | 内容 |
|---|---|
| `docs-SuMon/Use-manual/` | 运维手册：部署安装 / 升级回滚 / 备份恢复 / 安全检查 / RabbitMQ 运维 / Go-Agent 部署 |
| `docs-SuMon/OpenApi-SuMon/` | 7 份 OpenAPI 契约与校验说明（`npm run openapi:check` 双向校验） |
| `docs-SuMon/Protocol-SuMon/` | WebSocket 协议（v1.5）、命令协议、RabbitMQ 拓扑与消息契约 |
| `docs-SuMon/Summary-Technology/` | 架构总览 |
| `docs-SuMon/Introduction/` | 项目解读系列 |
| `docs-SuMon/Develop-plans/` · `Develop-log/` · `Bug-fix/` · `Difficulty-log/` | 开发计划 / 实施日志 / 缺陷修复档案 / 故障排查记录 |

## 涂山 IP 使用范围

本项目使用涂山苏苏（狐妖小红娘）IP 形象，**仅供内部学习与 demo 用途，非官方同人作品，不用于商业用途**。公开展示或商业化前请替换为自有素材或已获授权的版本。
