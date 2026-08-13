# SuSuMonitor RabbitMQ 运维手册（MVP-8）

**适用范围**：MVP-10 Outbox 引入 + MVP-11 消费侧的 RabbitMQ 依赖的日常运维。
**相关**：`Develop-log/20260731-MVP10-Metrics-Outbox.md`、`Develop-log/20260731-MVP11-Alert-消费侧.md`（实施与验收）、`docs-SuMon/Protocol-SuMon/rabbitmq-topology-v1.md`（冻结拓扑）。

---

## 一、架构位置（为什么需要 RabbitMQ）

```text
Agent 上报 metrics.report
  -> MySQL 事务：指标落库 + message_outbox(pending) 同事务写入
  -> OutboxPublisherScheduler（1s 轮询，FOR UPDATE SKIP LOCKED）
  -> Publisher Confirm 投递到 susumonitor.events
  -> susumonitor.alert.metrics 队列
  -> AlertMessageConsumer（AUTO 确认 + 幂等 + 有限重试；不可重试/重试耗尽 -> DLQ）
```

- RabbitMQ 承载 **metrics.reported.v1 事件投递**；Agent/Monitor WebSocket、MySQL、SSH/终端链路均不经过它。
- **Broker 停机不影响指标采集与落库**，只影响事件投递（outbox 退避堆积，恢复后自动补发补消费）；**告警评估依赖 Broker**——停机期间告警延迟评估，恢复后补消费（有界，不丢）。
- 消费侧：业务队列消息应**接近 0**（消费即 ACK）；异常消息进入 `susumonitor.alert.metrics.dlq`。

## 二、vhost / 用户 / 权限管理

```bash
# 查看
rabbitmqctl list_vhosts
rabbitmqctl list_users
rabbitmqctl list_permissions -p susumonitor

# 创建（部署安装手册 §3.2）
rabbitmqctl add_vhost susumonitor
rabbitmqctl add_user susumonitor '<强密码>'
rabbitmqctl set_permissions -p susumonitor susumonitor '.*' '.*' '.*'
rabbitmqctl set_user_tags susumonitor administrator    # 管理台/验收需要；生产可收回为 monitoring

# 轮换密码（需同步更新 server.env 的 SPRING_RABBITMQ_PASSWORD 并重启后端）
rabbitmqctl change_password susumonitor '<新密码>'

# 删除/禁用
rabbitmqctl delete_user <用户名>
rabbitmqctl clear_permissions -p susumonitor <用户名>
```

> **环境清单（2026-08-02 实测）**：
>
> | 环境 | 部署方式 | 版本 | vhost | 用户（密码） | 备注 |
> |---|---|---|---|---|---|
> | 本机（local/rmq） | 免安装，`local/rabbitmq.ps1 start` | 4.3.4 | `susumonitor` | `susumonitor`（`732682`，2026-08-02 重置） | 凭据已写入 Windows 用户级环境变量 `SPRING_RABBITMQ_*` / `RABBITMQ_MANAGEMENT_*`（新进程生效） |
> | 云端 82.156.245.102 | Docker `rabbitmq:4.3-management`（容器名 `rabbitmq`，restart=always，卷 `f821f894…`→`/var/lib/rabbitmq`） | **4.3.4**（2026-08-02 由 3.13 升级，与本地同版本线） | `susumonitor` | `susumonitor`（`732682`，administrator） | 升级后节点名变化导致旧 mnesia 数据未随卷迁移（vhost/用户为当日新建，零损失）；后端 `server.env` 已追加 `SPRING_RABBITMQ_*`（备份 `server.env.bak-20260802`），重启后连接 `amqp://susumonitor@127.0.0.1:5672/susumonitor`，队列拓扑由后端自动声明 |

> 注：本机与云端现为**同一版本线 4.3.4**，vhost/凭据完全一致，后端配置可互换（仅 `SPRING_RABBITMQ_HOST` 指向差异）。

> 凭据管理：RabbitMQ 密码通过 `server.env` 的 `SPRING_RABBITMQ_PASSWORD` 注入；vhost/用户清单建议随《备份与恢复手册》记录。

## 三、拓扑（冻结 4 件套，幂等声明）

| 类型 | 名称 | 关键参数 |
|---|---|---|
| Topic Exchange | `susumonitor.events` | durable, non-auto-delete |
| Topic Exchange | `susumonitor.dlx` | durable, non-auto-delete |
| Queue | `susumonitor.alert.metrics` | durable；`x-dead-letter-exchange=susumonitor.dlx`、`x-dead-letter-routing-key=metrics.reported.v1` |
| Queue | `susumonitor.alert.metrics.dlq` | durable，不自动回投业务队列 |
| Queue | `susumonitor.alert.triggered` | durable；`x-dead-letter-exchange=susumonitor.dlx`、`x-dead-letter-routing-key=alert.triggered.v1`；出站告警事件，消费者 `alert-notifier` 已接入（2026-08-12），驱动外部通知排程 |
| Queue | `susumonitor.alert.triggered.dlq` | durable，不自动回投业务队列 |

- 由 `RabbitMqTopologyConfig` 声明式创建，**broker 重启/升级后自动重建**，无需手工声明。
- 绑定：`susumonitor.events -- metrics.reported.v1 --> susumonitor.alert.metrics`；`susumonitor.events -- alert.triggered.v1 --> susumonitor.alert.triggered`；DLX → DLQ 同理。

```bash
rabbitmqctl list_queues -p susumonitor name durable arguments
rabbitmqctl list_exchanges -p susumonitor name type durable
```

## 四、健康检查与监控

| 项 | 方式 | 说明 |
|---|---|---|
| 后端视角 | `GET /api/ready` | DB + RabbitMQ 双检查；Broker 不可达返回 **HTTP 503 / 50301 "rabbitmq unavailable"**（存活但未就绪，应用不退出） |
| 积压监控 | `rabbitmqctl list_queues -p susumonitor name messages` | `susumonitor.alert.metrics` / `susumonitor.alert.triggered` 正常应接近 0（消费即 ACK）；**持续增长**说明消费者未运行或评估/通知排程失败（查后端日志与 DLQ）；对应 DLQ 增长 = 数据错误或重试耗尽，需人工介入 |
| 死信处置 | 管理台/管理 API 查看两个 DLQ（`susumonitor.alert.metrics.dlq` / `susumonitor.alert.triggered.dlq`）；先运行 `node api-test/replay-mvp11-dlq.mjs [--event metrics\|alert]`（默认 dry-run、最多 10 条）审查摘要。仅在**本机隔离验证环境**、根因已修复且消息契约有效时，设置 `SUSUMONITOR_VALIDATION_CONFIRM=I_UNDERSTAND_DLQ_REPLAY` 后追加 `--execute --limit=N`（N≤50）。工具只将合规信封（对应事件的契约）原样发布回 `susumonitor.events`，绝不直接写业务队列、不输出 payload；非法 JSON/schema/type 等消息拒绝重放。全量处理（含 --purge 清空）用 `node api-test/replay-dlq.mjs [--event metrics\|alert] [--replay\|--purge]`。生产死信仍须按变更流程人工审查与重放，禁止将本工具指向生产 Management API。 |
| 管理台 | http://127.0.0.1:15672（仅内网） | 队列/连接/节点监控 |
| Broker 状态 | `rabbitmqctl status` | 节点/版本/Erlang 版本 |

## 五、停机影响矩阵与恢复

| 场景 | 影响 | 恢复行为 |
|---|---|---|
| Broker 停机 | 指标照常落库；outbox 事件退避堆积（指数退避封顶 300s）；`/api/ready` 50301；**告警评估暂停**（消费侧无消息可消费） | Broker 恢复后发布器**自动补发**，消费者自动补消费并评估（MVP-10 三阶段 + MVP-11 验收 PASS 证据），无需人工干预 |
| Broker 数据目录损坏 | 消息丢失（队列中未消费事件）；**指标数据不受影响**（已落 MySQL） | 重建 broker → 拓扑自动声明 → 后续事件正常投递；丢失的队列事件需接受（已评估过的无损失；未评估的指标数据仍在 MySQL，可后续重放或接受） |
| 后端重启 | 无影响 | 发布器随应用恢复轮询；Broker 侧连接自动重连 |

## 六、生产部署建议

1. **系统服务方式**（官方 Windows 服务 / Linux 包管理安装），**不要用本机验收的免安装前台方式**（`local/rmq` 仅限开发机验收，`Develop-log/20260731-MVP10-Metrics-Outbox.md` 已声明）。
2. **版本匹配**：RabbitMQ 4.3 必须配 Erlang 27.x；**Erlang 29 不兼容**（horus 机制启动失败，实测记录）。升级前查官方版本矩阵。
3. 备份与恢复：RabbitMQ 配置/用户清单按《备份与恢复手册》记录；消息队列本身不做业务级备份（指标数据在 MySQL，消息是短暂的投递载体）。
