# Metrics、Agent 与 Web 监控闭环详细开发计划

**计划日期**：2026-07-21  
**计划状态**：主要闭环已执行。Metrics 清理、Agent Token、Agent/Monitor WebSocket、真实 Agent 采集上报和隔离 MySQL 验收均已完成；多实例与部署环境未验证。（2026-08-16 注：部署环境已验——云端明文 HTTP 2026-07-31、HTTPS/WSS 2026-08-07；仍未验证的仅多实例。）详见 20260723-Agent-Go运行时验收.md 和最新后端验收日志。
**适用项目**：SuSuMonitor  
**当前主线**：MVP-1 收口后进入监控核心数据闭环  
**执行原则**：先调查、先备份、最小修改、分阶段验证、完整留痕

## 一、计划目标

### 1.1 总目标

在已经完成服务器管理、V8 数据迁移和安全 SSH 验收的基础上，逐步建立以下闭环：

```text
Agent 注册
→ Agent Token 鉴权
→ WebSocket 连接与心跳
→ 指标接收与校验
→ MySQL 存储
→ 最新/历史指标查询
→ WebSocket 实时推送
→ 指标过期清理
→ Vue 监控页面展示
```

### 1.2 本计划包含

- MVP-1 最终文档和验收收口。
- Metrics 清理任务骨架。
- Agent 注册和 Token 生命周期。
- Agent WebSocket 鉴权、心跳和在线状态。
- Metrics 指标接收、存储、最新值和历史查询。
- Vue 3 监控页面和实时指标展示。
- 独立 MySQL、MockMvc、WebSocket、Apifox 和真实运行时验收。
- 修改、删除、数据库操作和敏感信息的备份与留痕。

### 1.3 本计划不包含

- Web SSH 终端、PTY、命令执行和文件传输。
- Android/Kotlin 客户端。
- 告警规则、告警检测和通知系统。
- Docker、Kubernetes、远程 Linux 生产部署。
- Redis、多实例分布式锁和微服务拆分。
- AES 多 Key ID、生产密钥轮换和历史密文批量重加密。
- 生产数据库迁移和生产环境部署。

## 二、当前基线与事实

### 2.1 当前代码事实

执行本计划前必须重新只读确认，以下内容是当前已知基线，不代表本计划已经实现：

- Java 后端目录：`server-java-SuMon/`。
- Java 版本：21。
- Spring Boot：3.4.7。
- MyBatis-Plus：3.5.12。
- MySQL：8.4。
- Flyway 最高版本：V8。
- Metrics 表已由 `V3__create_metrics_table.sql` 创建。
- 当前没有 Metrics Mapper、Service、Controller 和 Scheduler 业务实现。
- `scheduler/` 当前只有包说明文件。
- `websocket/` 当前只有包说明文件。
- `application.yml` 已存在 `metrics.retention-days` 和 `metrics.cleanup-cron` 配置。
- `V3__create_metrics_table.sql` 创建的是固定宽表，不是 `metric_name + metric_value` 动态指标表。
- `V2__create_servers_table.sql` 已存在 `agent_id`、`agent_token_hash`、`agent_status` 和 `last_heartbeat_at`；后续不得重复新增这些字段。
- `web-vue-SuMon/` 当前使用扁平 `src/views`、`src/api` 和 `src/stores` 目录，服务器路由仍为占位页面。
- 前端当前未安装 ECharts；引入图表前必须同步修改 `package.json` 和 `package-lock.json`。
- 指标接收、最新指标、历史指标和 WebSocket 实时指标尚未作为本计划实现内容完成。
- 当前工作树存在其他未提交和未跟踪改动，执行时不得回滚、清理或覆盖无关改动。

### 2.2 当前已有能力

- 服务器 CRUD 和软删除。
- JWT 用户认证和管理员权限。
- SSH host-key 确认、复核和显式轮换。
- SSH password/private_key 连接测试。
- SSH 出站 CIDR、端口、DNS、超时和并发限制。
- V8 主机公钥字段迁移。
- Apifox 项目和 SSH 安全测试用例。

### 2.3 执行前基线命令

```powershell
git status --short
git diff --check
git diff --stat
```

服务检查：

```powershell
Invoke-WebRequest http://localhost:18080/api/health
Invoke-WebRequest http://localhost:18080/api/ready
```

成功标准：

```text
health = HTTP 200
ready = HTTP 200
```

Apifox 检查：

```powershell
apifox auth status
apifox project get 8585366
apifox endpoint list --project 8585366 --branch main
apifox test-case list --project 8585366 --branch main
```

### 2.4 本版冻结决策

为避免数据库模型、协议和前端实现互相返工，正式编码前固定以下决策：

1. Metrics 第一版复用现有固定宽表，不引入 `metric_name`、`metric_value` 动态行模型。
2. `/ws/agent` 只用于 Agent 首帧 Token 鉴权、心跳和指标上报；浏览器实时订阅使用独立的 `/ws/monitor` 通道和用户 JWT 权限。
3. Agent 消息时间使用带时区的 ISO-8601 字符串；Metrics 数据库存储策略必须在实现批次开始前依据现有时间转换代码固定为 UTC 或应用时区，不能混用。
4. `register` 只允许首次生成 Token；已有 Token 必须通过 `rotate` 显式轮换；`revoke` 立即撤销并关闭现有 Agent 连接。
5. 同一服务器的新 Agent 连接只有在新 Token 认证成功后才替换旧连接。
6. 清理任务只保证单 JVM 内不重叠；本计划不引入 Redis 或分布式锁，多实例互斥属于未实现范围。
7. 前端沿用现有扁平目录，新增 `ServerListView.vue`、`ServerDetailView.vue`、`MetricsView.vue`，不重复创建 `views/auth` 或 `views/server` 子目录。
8. 首次引入 ECharts 时同步修改依赖锁文件，并单独记录依赖安装、类型检查和构建结果。
9. Agent 身份以 `servers.id` 为服务器归属，以已有 `servers.agent_id` 为 Agent 标识；不新增 Agent 主表、不新增第二套 Agent 标识字段。

## 三、统一技术栈

### 3.1 后端

- Java 21。
- Spring Boot 3.4.7。
- Spring Web MVC。
- Spring Validation。
- Spring Security。
- Spring Scheduling。
- Spring WebSocket。
- Jackson。
- MyBatis-Plus 3.5.12。
- MySQL 8.4。
- Flyway。
- JJWT 0.12.6，仅用于用户 JWT，不与 Agent Token 混用。
- JUnit 5。
- Mockito。
- MockMvc。
- sshj 0.39.0，仅复用现有 SSH 能力。

### 3.2 前端

- Vue 3。
- Vite。
- TypeScript。
- Pinia。
- Vue Router。
- Element Plus。
- ECharts。
- Axios 或项目现有 HTTP 客户端。
- 原生 WebSocket。

### 3.3 验收与运维

- Apifox Windows 桌面端 2.8.39。
- Apifox CLI 2.2.7。
- OpenAPI 3.0.3。
- PowerShell 5.1。
- MySQL CLI。
- WSL2 Ubuntu/OpenSSH，仅用于隔离验证。
- `C:\Backup` 文件备份目录。

## 四、阶段 0：基线、备份与计划留痕

### 4.1 目标

在任何代码或配置改动前固定当前事实，建立可恢复点，并记录本次执行的边界。

### 4.2 首批需要备份的已有文件

修改以下文件前，必须先备份到 `C:\Backup\SuSuMonitor\<timestamp>\`：

```text
server-java-SuMon/pom.xml
server-java-SuMon/src/main/resources/application.yml
server-java-SuMon/src/main/resources/application-local.yml
server-java-SuMon/src/main/resources/db/migration/V3__create_metrics_table.sql
server-java-SuMon/src/main/java/com/susumonitor/server/config/AppProperties.java
server-java-SuMon/README.md
docs-SuMon/Develop-plans/20260719-MVP-1收口与安全SSH连接测试计划.md
docs-SuMon/Develop-log/20260720-安全SSH主机身份与连接测试实现.md
项目需求与规范.md
```

后续涉及以下文件也必须先备份：

```text
docs-SuMon/OpenApi-SuMon/openapi-server.json
api-test/susumonitor.http
server-java-SuMon/src/main/java/com/susumonitor/server/common/ErrorCode.java
server-java-SuMon/src/main/java/com/susumonitor/server/security/SecurityConfig.java
```

### 4.3 备份验证要求

每份文件备份必须验证：

- 文件存在。
- 文件大小大于 0。
- 文件可读取。
- 备份路径和时间写入开发记录。
- 不把仓库外的密码、Token、私钥和 AES Key复制到仓库。

数据库变更前必须额外创建：

```text
C:\Backup\susumonitor-before-<operation>-<timestamp>.sql
```

推荐命令参数：

```text
mysqldump --single-transaction --routines --triggers --no-tablespaces
```

必须验证：

- 命令退出码为 0。
- 文件非空。
- 文件可读取。
- 包含目标表结构。
- 数据库名和影响范围记录在日志中。

### 4.4 删除留痕要求

删除任何文件、数据库对象、服务器记录或测试资源前必须：

1. 展示目标和影响范围。
2. 先备份。
3. 验证备份存在、非空且可读取。
4. 获得用户最终确认。
5. 执行删除或软删除。
6. 验证删除结果。
7. 在开发记录中记录时间、命令、备份路径和结果。

本计划不授权自动执行未来的删除动作。

## 五、阶段 1：Metrics 清理任务骨架

### 5.1 目标与边界

仅实现指标过期数据分批清理和定时调度，不实现 Agent 指标写入、查询 API、WebSocket 推送和 Dashboard。

固定删除边界：

```text
collected_at < cutoff_time：删除
collected_at = cutoff_time：保留
collected_at > cutoff_time：保留
```

### 5.2 配置代码

修改文件：

```text
server-java-SuMon/src/main/resources/application.yml
server-java-SuMon/src/main/resources/application-local.yml
server-java-SuMon/src/main/java/com/susumonitor/server/config/AppProperties.java
```

新增配置：

```yaml
susumonitor:
  metrics:
    retention-days: ${METRICS_RETENTION_DAYS:10}
    cleanup-cron: ${METRICS_CLEANUP_CRON:0 0 3 * * ?}
    cleanup-batch-size: ${METRICS_CLEANUP_BATCH_SIZE:1000}
    cleanup-max-batches-per-run: ${METRICS_CLEANUP_MAX_BATCHES_PER_RUN:100}
```

`AppProperties` 新增字段：

```java
private Integer cleanupBatchSize;
private Integer cleanupMaxBatchesPerRun;
```

启动校验：

```text
retention-days >= 1
cleanup-batch-size >= 1
cleanup-max-batches-per-run >= 1
```

非法配置必须启动失败，不允许退化为 0 天或无限批次。

### 5.3 Mapper 代码

新增清理专用 Mapper，避免清理 SQL 与后续指标写入、查询 SQL 互相耦合：

```text
server-java-SuMon/src/main/java/com/susumonitor/server/module/metrics/mapper/MetricsCleanupMapper.java
server-java-SuMon/src/main/resources/mapper/metrics/MetricsCleanupMapper.xml
```

接口：

```java
int deleteExpiredBatch(
        @Param("cutoffTime") LocalDateTime cutoffTime,
        @Param("batchSize") int batchSize);
```

SQL 设计：

```sql
DELETE FROM metrics
WHERE id IN (
    SELECT id
    FROM (
        SELECT id
        FROM metrics
        WHERE collected_at < #{cutoffTime}
        ORDER BY id
        LIMIT #{batchSize}
    ) expired_metrics
)
```

要求：

- 禁止 `SELECT *`。
- 使用参数绑定。
- 只删除 `collected_at < cutoffTime` 的记录。
- 使用主键小批量删除。
- 不把所有过期数据加载到 JVM。
- 复用并检查 `idx_metrics_collected_at`。

### 5.4 Service 代码

新增：

```text
server-java-SuMon/src/main/java/com/susumonitor/server/module/metrics/service/MetricsCleanupService.java
```

建议接口：

```java
Optional<CleanupResult> cleanupExpiredMetrics();
```

`Optional.empty()` 表示本 JVM 内已有清理任务运行，本轮直接跳过；数据库异常必须抛出并停止本轮，不把异常伪装成正常结果。

建议结果对象：

```java
public record CleanupResult(
        LocalDateTime cutoffTime,
        int batches,
        int deletedRows,
        long durationMs) {
}
```

核心流程：

```text
读取配置
→ 计算 cutoffTime
→ 尝试获取本机运行锁
→ 删除一批
→ 删除数量为 0 时结束
→ 达到最大批次时停止
→ 记录结果
→ 释放运行锁
```

规则：

- 同一 JVM 内禁止清理任务重叠。
- 第二次触发只记录跳过，不排队无限等待。
- 数据库异常停止本轮并继续向上抛统一异常或记录明确失败。
- 每批使用独立事务边界，优先使用 `TransactionTemplate`，不得在同一个外层 `@Transactional` 循环中删除全部批次。
- 日志不输出指标明细。
- 使用 `AtomicBoolean.compareAndSet(false, true)` 实现单 JVM 重叠保护，并在 `finally` 中释放运行标记。
- 不宣称多实例互斥；多实例部署时的分布式锁属于本计划未实现范围。

### 5.5 Scheduler 代码

新增：

```text
server-java-SuMon/src/main/java/com/susumonitor/server/scheduler/MetricsCleanupScheduler.java
```

职责：

- 使用 `@Scheduled(cron = "${susumonitor.metrics.cleanup-cron}")`。
- 只调用 `MetricsCleanupService`。
- 不直接访问 Mapper。
- 若应用尚未启用调度，修改启动类或独立配置类增加 `@EnableScheduling`。
- 记录开始、结束、跳过和异常。
- 使用 SLF4J `{}` 占位符。

日志允许字段：

```text
cutoffTime
batchCount
```

日志禁止字段：

```text
指标明细
数据库密码
JWT
AES Key
SSH 凭据
完整 SQL 参数
```

### 5.6 测试文件

新增：

```text
server-java-SuMon/src/test/java/com/susumonitor/server/module/metrics/service/MetricsCleanupServiceTests.java
server-java-SuMon/src/test/java/com/susumonitor/server/module/metrics/mapper/MetricsCleanupMapperTests.java
server-java-SuMon/src/test/java/com/susumonitor/server/scheduler/MetricsCleanupSchedulerTests.java
```

测试场景：

| 场景 | 预期 |
|---|---|
| 无过期数据 | 删除 0 行并结束 |
| 少量过期数据 | 删除全部过期数据 |
| 超过批次大小 | 多次批量删除 |
| 达到单轮上限 | 停止继续删除 |
| 等于 cutoff | 保留 |
| 早于 cutoff | 删除 |
| Mapper 异常 | 停止本轮 |
| 重叠执行 | 第二次跳过 |
| 非法 retention-days | 启动失败 |
| 非法 batch-size | 启动失败 |
| 非法 max-batches | 启动失败 |

### 5.7 独立 MySQL 验收

不得直接在开发库执行真实清理。

隔离数据库建议：

```text
susumonitor_metrics_cleanup_<date>
```

测试数据至少包括：

```text
cutoff - 1 秒
cutoff
cutoff + 1 秒
```

成功标准：

- `cutoff - 1 秒` 记录被删除。
- `cutoff` 记录保留。
- `cutoff + 1 秒` 记录保留。
- 分批和最大批次数生效。
- Flyway 状态正常。
- 索引仍存在。
- `SHOW CREATE TABLE metrics` 必须确认没有 `metric_name`、`metric_value` 或 `metric_unit` 动态列。
- `SHOW INDEX FROM metrics` 必须确认 `idx_metrics_collected_at` 和 `idx_metrics_server_time` 存在。

清理 SQL 的 Mapper 验收应在独立 MySQL 或 MyBatis 集成测试中完成，不能仅用 Mockito 验证 XML SQL 的实际行为。

## 六、阶段 2：Agent 注册与 Token 生命周期

### 6.1 目标

实现 Agent 注册、Token 哈希存储、显式轮换和撤销，明文 Token 只在注册响应中返回一次。

### 6.2 数据库设计

现有 `servers` 表已经存在以下字段：

```text
agent_id
agent_token_hash
agent_status
last_heartbeat_at
```

这些字段由现有 `ServerEntity` 和 `ServerMapper.xml` 复用，分别映射为 `agentId`、`agentTokenHash`、`agentStatus` 和 `lastHeartbeatAt`。公开 `ServerVo` 可以继续返回 `agent_id`、`agent_status`、`last_heartbeat_at`，但绝不能返回 `agent_token_hash`。

不得重复新增上述字段，不得创建 `agents` 表或第二套 Agent 字段。执行前必须用真实数据库确认 V2/V8 之后的实际结构；只有生命周期时间字段缺少时，才新增增量迁移：

```text
server-java-SuMon/src/main/resources/db/migration/V9__add_agent_token_lifecycle_fields.sql
```

V9 只增加：

```text
agent_token_created_at
agent_token_rotated_at
agent_token_revoked_at
```

V9 禁止增加以下字段或表：

```text
agent_id
agent_token_hash
agent_status
last_heartbeat_at
agents
agent_tokens
```

保留 `agent_token_created_at` 的首次创建时间；轮换时更新 `agent_token_rotated_at` 并清空 `agent_token_revoked_at`；撤销时更新 `agent_token_revoked_at` 并将 `agent_status` 设为 `offline`。

禁止修改 V1-V8 历史迁移。

### 6.3 API 设计

建议新增：

```http
POST /api/servers/{id}/agent/register
POST /api/servers/{id}/agent/rotate
POST /api/servers/{id}/agent/revoke
```

权限：

```text
仅 admin
```

注册成功响应只返回一次：

```json
{
  "server_id": 11,
  "agent_token": "一次性显示",
  "created_at": "2026-07-21T10:00:00Z"
}
```

列表、详情和状态接口不得返回：

```text
agent_token
agent_token_hash
```

### 6.4 代码文件

新增：

```text
server-java-SuMon/src/main/java/com/susumonitor/server/module/server/dto/AgentTokenRequest.java
server-java-SuMon/src/main/java/com/susumonitor/server/module/server/vo/AgentTokenVo.java
server-java-SuMon/src/main/java/com/susumonitor/server/module/server/service/AgentTokenService.java
server-java-SuMon/src/main/java/com/susumonitor/server/module/server/controller/AgentTokenController.java
```

可能修改：

```text
server-java-SuMon/src/main/java/com/susumonitor/server/module/server/mapper/ServerMapper.java
server-java-SuMon/src/main/resources/mapper/server/ServerMapper.xml
server-java-SuMon/src/main/java/com/susumonitor/server/common/ErrorCode.java
server-java-SuMon/src/main/java/com/susumonitor/server/security/SecurityConfig.java
```

还需修改 `ServerEntity.java` 和 `ServerMapper.xml`，映射三个生命周期时间字段；已有四个 Agent 字段只做复用和必要的内部更新 SQL，不重复定义。三个生命周期字段不得进入 `ServerVo`、列表、详情、状态响应、日志或 `toString`。

Token 规则：

- 使用 `SecureRandom` 生成至少 32 字节随机值。
- 使用不带填充的 Base64 URL-safe 编码。
- 数据库只保存 `sha256:<digest>` 哈希，不保存明文 Token 或可逆密文。
- 使用 `MessageDigest.isEqual` 进行常量时间比较。
- 轮换后旧 Token 立即失效。
- 撤销后 Token 立即失效。
- API、日志、异常不得输出 Token。
- `register` 只允许无 Token 的服务器首次生成；已有 Token 必须调用 `rotate`。
- 轮换成功后关闭该服务器旧 WebSocket 连接，但只有新 Token 认证成功后才替换旧连接。

### 6.5 测试

新增：

```text
AgentTokenServiceTests.java
AgentTokenControllerTests.java
```

覆盖：

- admin 注册成功。
- 普通用户 `40300`。
- 无 Token `40100`。
- Token 只返回一次。
- 数据库只保存 hash。
- Agent 记录复用 `servers` 表，不存在重复 Agent 表或重复 Agent 标识字段。
- 轮换后旧 Token 失效。
- 撤销后 Token 失效。
- A 服务器 Token 不能访问 B 服务器。
- 公开响应不返回 Token 和 hash。
- 日志不包含 Token。

## 七、阶段 3：Agent WebSocket 协议与心跳

### 7.1 协议文档

新增：

```text
docs-SuMon/Protocol-SuMon/websocket-protocol.md
```

必须冻结：

- WebSocket 地址。
- Agent 首帧认证。
- 认证成功和失败响应。
- 心跳消息和响应。
- 指标消息类型。
- 错误消息。
- 消息 ID。
- 时间戳格式。
- 重连策略。
- 在线/离线规则。
- 最大消息大小。
- 认证和心跳超时。

协议通道固定为：

```text
/ws/agent   Agent 上报通道，只允许 agent.authenticate 首帧认证
/ws/monitor 浏览器订阅通道，使用用户 JWT 和服务器读取权限
```

浏览器不得使用 Agent Token 连接 `/ws/agent`；Agent Token 不能进入浏览器 URL、前端 Store、控制台或监控订阅消息。

### 7.2 消息格式

统一消息结构：

```json
{
  "type": "heartbeat",
  "message_id": "uuid",
  "timestamp": "2026-07-21T10:00:00Z",
  "payload": {}
}
```

认证消息：

```json
{
  "type": "agent.authenticate",
  "message_id": "uuid",
  "timestamp": "2026-07-21T10:00:00Z",
  "payload": {
    "server_id": 11,
    "token": "..."
  }
}
```

禁止在 URL query 中传递 Token，禁止日志输出完整 Token。

### 7.3 代码文件

新增：

```text
server-java-SuMon/src/main/java/com/susumonitor/server/websocket/AgentWebSocketConfig.java
server-java-SuMon/src/main/java/com/susumonitor/server/websocket/AgentWebSocketHandler.java
server-java-SuMon/src/main/java/com/susumonitor/server/websocket/AgentWebSocketSession.java
server-java-SuMon/src/main/java/com/susumonitor/server/websocket/AgentMessage.java
server-java-SuMon/src/main/java/com/susumonitor/server/websocket/AgentMessageType.java
server-java-SuMon/src/main/java/com/susumonitor/server/websocket/AgentAuthenticationService.java
server-java-SuMon/src/main/java/com/susumonitor/server/websocket/AgentHeartbeatService.java
server-java-SuMon/src/main/java/com/susumonitor/server/websocket/AgentConnectionRegistry.java
server-java-SuMon/src/main/java/com/susumonitor/server/websocket/MonitorWebSocketConfig.java
server-java-SuMon/src/main/java/com/susumonitor/server/websocket/MonitorWebSocketHandler.java
```

技术选择：

- Spring WebSocket。
- 原生 WebSocket 文本协议。
- Jackson。
- 第一版不引入 STOMP。
- 第一版不引入 Redis。
- 使用受控内存连接表。
- 使用 Spring Scheduling 扫描过期心跳。
- 同一服务器新连接只有在新 Token 认证成功后替换旧连接。
- 认证超时 10 秒，心跳过期阈值 90 秒，单条文本消息最大 64 KiB；具体常量必须在协议文档和实现中保持一致。
- `/ws/monitor` 只接受已认证用户的订阅消息，并复用 approved/admin 读取权限，不读取或接收 Agent Token。

### 7.4 在线状态

建议规则：

```text
最后心跳超过 90 秒：offline
```

如数据库缺少生命周期字段，新增 Flyway 增量迁移，不修改历史迁移。

规则：

- 认证成功后标记 online。
- 心跳更新现有 `last_heartbeat_at`，不新增 `last_seen_at` 字段。
- 连接关闭或超过 90 秒未心跳时标记 `agent_status = offline`。
- 状态更新必须带服务器 ID 和预期的旧心跳时间，避免旧连接覆盖新连接状态。
- 同一服务器采用新连接替换旧连接；只有新连接认证成功后才关闭旧连接。
- 单 JVM 使用内存连接表；不实现多实例共享连接状态。

### 7.5 测试

新增：

```text
AgentWebSocketHandlerTests.java
AgentAuthenticationServiceTests.java
AgentHeartbeatServiceTests.java
AgentConnectionRegistryTests.java
MonitorWebSocketHandlerTests.java
```

覆盖：

- 合法 Token 认证成功。
- 错误 Token 拒绝。
- 服务器 ID 不匹配拒绝。
- 首帧认证超时。
- 心跳更新在线时间。
- 90 秒后离线。
- 连接关闭。
- 重连。
- 重复连接。
- 超大消息拒绝。
- 非法 JSON 拒绝。
- Token 不进入日志。
- 浏览器监控通道 JWT 鉴权和服务器读取权限。
- Agent 通道与浏览器通道不能互相使用错误类型 Token。

## 八、阶段 4：Metrics 接收与查询

### 8.1 数据模型

复用 `V3__create_metrics_table.sql` 创建的固定宽表；本阶段不新增动态指标迁移，不使用 `metric_name + metric_value` 行模型。

建议指标字段：

```text
id
server_id
cpu_percent
memory_percent
memory_used
memory_total
disk_percent
disk_used
disk_total
net_rx
net_tx
temperature
load_avg
collected_at
created_at
```

第一版指标白名单：

```text
cpu_percent
memory_percent
disk_percent
load_avg
net_rx
net_tx
```

Agent 上报 Payload 一条消息对应一行 `metrics` 记录；第一版不接受任意动态字段，不允许一次消息携带多条采样记录。

### 8.2 DTO/VO

新增：

```text
server-java-SuMon/src/main/java/com/susumonitor/server/module/metrics/dto/MetricReportMessage.java
server-java-SuMon/src/main/java/com/susumonitor/server/module/metrics/dto/MetricsReportPayload.java
server-java-SuMon/src/main/java/com/susumonitor/server/module/metrics/entity/MetricsEntity.java
server-java-SuMon/src/main/java/com/susumonitor/server/module/metrics/vo/MetricsLatestVo.java
server-java-SuMon/src/main/java/com/susumonitor/server/module/metrics/vo/MetricsHistoryVo.java
```

校验规则：

- `server_id > 0`。
- 百分比字段范围为 `0 <= value <= 100`。
- 字节数和负载字段不能为负数。
- `memory_used <= memory_total`，`disk_used <= disk_total`。
- 采集时间不能超过允许的未来偏差。
- 一条消息只允许一个采样时间点，并且至少包含一个指标字段。
- 单条消息字节数有限制。
- 未知字段不能污染持久化模型。
- `payload.server_id` 必须与已认证 WebSocket 会话绑定的服务器 ID 一致。
- 数据库时间字段和 REST 时间字段的时区策略必须固定，推荐 Agent 使用 UTC ISO-8601，并在开发记录中说明数据库 `DATETIME` 的转换规则。

### 8.3 Service/Mapper

新增：

```text
server-java-SuMon/src/main/java/com/susumonitor/server/module/metrics/service/MetricsService.java
server-java-SuMon/src/main/java/com/susumonitor/server/module/metrics/mapper/MetricsMapper.java
server-java-SuMon/src/main/resources/mapper/metrics/MetricsMapper.xml
```

Service 职责：

- 服务器存在性校验。
- Agent 连接上下文校验。
- 指标范围校验。
- 单条指标写入。
- 最新值查询。
- 历史分页查询。

Mapper 要求：

- 禁止 `SELECT *`。
- 显式列出查询字段。
- 所有用户输入使用参数绑定。
- 历史查询固定按 `collected_at DESC, id DESC` 排序，不接收动态排序字段。

### 8.4 REST 查询接口

新增：

```http
GET /api/servers/{id}/metrics/latest
GET /api/servers/{id}/metrics
```

历史查询参数：

```text
start_time
end_time
page
page_size
```

限制：

- `page >= 1`。
- `page_size <= 100`。
- 时间窗口不超过 7 天。
- 排序字段固定。
- 不允许跨服务器越权查询。
- 当前宽表不按 `metric_name` 过滤；前端从完整记录中选择图表字段。

权限：

- approved user 可读。
- admin 可读。
- 未认证返回 `40100`。
- 无权限返回 `40300`。
- 服务器不存在返回 `40400`。

### 8.5 契约同步

修改前备份并同步：

```text
docs-SuMon/OpenApi-SuMon/openapi-server.json
api-test/susumonitor.http
server-java-SuMon/README.md
```

同步内容：

- 请求参数。
- 响应 DTO/VO。
- 错误码。
- 分页结构。
- 时间格式。
- snake_case 字段。
- 固定宽表字段：`cpu_percent`、`memory_percent`、`disk_percent`、`load_avg`、`net_rx`、`net_tx` 等。

协议与需求文档同步：

```text
项目需求与规范.md
```

原需求文档已经定义固定的 `metrics.report` 上报方向以及 `/api/ws/agent`、`/api/ws/client` 通道。实现本计划时必须在开发记录中说明正式实现地址 `/ws/agent`、`/ws/monitor` 与原需求地址的映射关系；如正式 API 地址需要改动，必须同时更新需求文档、协议文档、OpenAPI、HTTP 样例和测试用例，不能只修改 Java 常量。

## 九、阶段 5：Metrics 清理与接收联动

完成指标接收后重新验证：

- Agent 写入指标。
- 清理任务按 cutoff 删除。
- 最新指标不会误删。
- 历史指标按时间边界删除。
- 清理期间写入行为符合事务设计。
- 清理任务不会长时间阻塞指标写入。

索引检查：

```text
idx_metrics_collected_at
idx_metrics_server_time
```

如需要新增复合索引，例如 `idx_metrics_server_collected`，必须先在独立库执行并检查查询计划，再决定是否修改迁移。

## 十、阶段 6：Vue 3 监控页面

### 10.1 技术栈

```text
Vue 3
Vite
TypeScript
Pinia
Vue Router
Element Plus
ECharts
Axios
原生 WebSocket
```

### 10.2 前端模块

当前前端采用扁平目录，执行前仍需读取真实结构；不重复创建 `views/auth`、`views/server` 或 `views/metrics` 子目录。

计划新增或修改：

```text
web-vue-SuMon/src/views/LoginView.vue（仅在认证状态需要调整时修改）
web-vue-SuMon/src/views/ServerListView.vue
web-vue-SuMon/src/views/ServerDetailView.vue
web-vue-SuMon/src/views/MetricsView.vue
web-vue-SuMon/src/stores/auth.ts
web-vue-SuMon/src/stores/server.ts
web-vue-SuMon/src/stores/metrics.ts
web-vue-SuMon/src/stores/agent.ts
web-vue-SuMon/src/services/websocket.ts
web-vue-SuMon/src/api/auth.ts
web-vue-SuMon/src/api/server.ts
web-vue-SuMon/src/api/metrics.ts
web-vue-SuMon/src/types/metrics.ts
web-vue-SuMon/src/router/index.ts
web-vue-SuMon/src/layouts/MainLayout.vue
web-vue-SuMon/package.json
web-vue-SuMon/package-lock.json
```

首次使用图表时新增 `echarts` 依赖；依赖安装、锁文件变化、类型检查和构建必须分别记录，不能将依赖安装成功等同于前端功能完成。

### 10.3 页面顺序

```text
登录页
→ 服务器列表
→ 服务器详情
→ Agent 在线状态
→ 最新指标卡片
→ 历史指标图表
→ `/ws/monitor` 用户订阅实时更新
```

### 10.4 前端状态要求

Pinia Store：

```text
authStore
serverStore
metricsStore
agentStore
```

`agentStore` 只保存服务器 Agent 状态、最后心跳和连接状态，不保存 Agent Token。浏览器使用用户 JWT 访问 `/ws/monitor`，不使用 Agent Token。

必须处理：

- JWT 过期。
- 401 自动跳转登录。
- 403 权限提示。
- WebSocket 自动重连。
- 页面卸载关闭连接。
- 防止重复订阅。
- 不在控制台输出 Token。
- 不在 URL 中传 Agent Token。
- 指标无数据和请求失败状态。
- WebSocket 订阅必须按 `serverId` 去重，路由离开时取消订阅并关闭连接。
- ECharts 实例在组件卸载时销毁，避免页面切换后的重复监听和内存泄漏。

### 10.5 图表

第一版展示：

- CPU 使用率。
- 内存使用率。
- 磁盘使用率。
- Load Average。
- 网络接收/发送。
- 时间区间切换。
- 无数据状态。
- WebSocket 断线状态。

路由调整：

```text
/servers → ServerListView.vue
/servers/:serverId → ServerDetailView.vue
/servers/:serverId/metrics → MetricsView.vue
```

`MainLayout.vue` 的服务器菜单高亮必须覆盖 `servers`、`server-detail` 和 `server-metrics` 三个路由。

## 十一、统一测试矩阵

### 11.1 Java 单元测试

```text
配置校验
Token 生成和哈希
Token 轮换和撤销
Metrics 值校验
固定宽表字段映射
清理 cutoff 计算
批量删除循环
调度重叠
WebSocket 认证
WebSocket 心跳
在线/离线判定
Monitor 用户 JWT 鉴权
Agent 与 Monitor 通道隔离
```

### 11.2 MockMvc

```text
Metrics latest 权限
Metrics history 权限
分页参数
时间范围
不存在服务器
统一错误响应
X-Request-ID
```

### 11.3 独立 MySQL

```text
V1-V9 或实际最新版本顺序迁移
Agent Token 字段
指标写入
固定宽表字段映射
历史查询
批量清理
cutoff 边界
索引检查
事务回滚
```

### 11.4 WebSocket

```text
Agent 正常认证
Token 错误
服务器 ID 错误
认证超时
心跳
断线
重连
重复连接
消息大小限制
Monitor 用户 JWT 鉴权
Monitor 服务器读取权限
Agent Token 不可用于 Monitor 通道
```

### 11.5 Apifox

```text
Agent 注册成功
普通用户禁止 Agent 注册
Agent Token 轮换
Agent Token 撤销
Metrics latest
Metrics history
非法指标
非法时间区间
401/403/404
X-Request-ID
snake_case 字段
```

Apifox 敏感值规则：

- 用例只保存变量名。
- JWT、Agent Token、密码只通过仓库外临时变量文件注入。
- 变量文件设置最小 ACL。
- 每次运行后清空并删除变量文件。
- 不上传包含敏感请求详情的报告。
- 不把 Token 写入 Apifox 云变量或 Git。

## 十二、验证命令

### 12.1 Maven

```powershell
mvn -Dtest=MetricsCleanupServiceTests test
mvn -Dtest=AgentTokenServiceTests test
mvn -Dtest=AgentWebSocketHandlerTests test
mvn clean test
mvn -DskipTests package
```

测试和打包必须分别记录，不能用打包成功替代测试通过。

### 12.2 静态检查

```powershell
```

并检查：

- SQL 无 `SELECT *`。
- VO 无密码、Token、私钥和密文。
- 日志无敏感字段。
- OpenAPI JSON 可解析。
- `$ref` 全部有效。
- 配置默认值合理。

### 12.3 MySQL

```sql
SELECT version, success
FROM flyway_schema_history
ORDER BY installed_rank;

SHOW CREATE TABLE metrics;
SHOW INDEX FROM metrics;
```

### 12.4 Apifox

```powershell
apifox test-case list --project 8585366 --branch main
apifox test-report list --project 8585366
```

报告保存到仓库外：

```text
C:\Users\genhaosan\AppData\Local\Temp\opencode\apifox-reports\
```

## 十三、开发记录留痕模板

正式执行时新增开发记录：

```text
docs-SuMon/Develop-log/20260721-Metrics-Agent-Web监控闭环开发记录.md
```

每个模块必须记录：

```markdown
## 模块名称

### 目标

### 设计

### 技术栈

### 修改文件

### 新增文件

### 删除文件

### 修改前备份

### 数据库变更

### API/协议变更

### 测试命令

### 测试结果

### 运行时验证

### 敏感信息检查

### 未验证范围

### 回滚方式

### 后续动作
```

状态必须明确区分：

```text
计划中
已备份
已创建
已实现
已编译
测试通过
Mock 测试通过
独立 MySQL 通过
真实 HTTP 通过
真实 WebSocket 通过
Apifox 通过
未验证
未执行
```

## 十四、回滚方案

### 14.1 文件回滚

- 使用 `C:\Backup` 中对应时间戳备份恢复。
- 恢复前先确认目标文件和备份文件路径。
- 恢复后执行编译、测试和 `git diff --check`。
- 不覆盖用户在此期间新增的无关改动。

### 14.2 数据库回滚

- 不修改历史 Flyway 迁移。
- 新迁移失败时停止发布，不执行历史重置。
- 需要恢复时使用独立备份恢复到隔离库验证。
- 生产数据库回滚必须另行展示影响范围并获得最终确认。

### 14.3 Apifox 回滚

- 新用例先写入 AI 分支。
- 合并前导出项目备份。
- 只按明确资源 ID 合并。
- 删除或撤销 Apifox 资源前先导出备份。
- 不自动归档或删除其他分支。

## 十五、阶段出口条件

### 15.1 Metrics 清理骨架出口

- 配置启动校验通过。
- Mapper SQL 通过静态检查。
- 单元测试通过。
- 独立 MySQL cutoff 边界通过。
- 分批和单轮上限通过。
- 重叠调度测试通过。
- 日志无敏感数据。
- 开发记录已写入命令、结果和备份路径。

### 15.2 Agent Token 出口

- Token 只返回一次。
- 数据库只保存 hash。
- 轮换和撤销通过。
- 旧 Token 立即失效。
- 公开 VO 不返回 Token。
- Apifox 和日志无 Token 明文。

### 15.3 WebSocket 出口

- 协议文档已冻结。
- 合法 Token 可认证。
- 错误 Token 被拒绝。
- 心跳可更新在线状态。
- 90 秒离线判定通过。
- 重连和断线通过。
- 日志不泄露 Token。
- `/ws/monitor` 用户 JWT 鉴权、服务器读取权限和 Agent/Monitor 通道隔离通过。

### 15.4 Metrics 闭环出口

- 固定宽表字段和数值范围校验通过。
- 指标成功落库。
- latest/history 查询通过。
- 权限、分页和时间窗口通过。
- 清理任务与写入并行边界通过。
- OpenAPI、协议、DTO/VO、实现和测试一致。

### 15.5 MVP-1 总出口

只有以下条件全部满足，才可以将相关计划标记为完成：

- `mvn clean test` 通过。
- `mvn -DskipTests package` 通过。
- Flyway 独立库迁移通过。
- 真实 HTTP 和 Apifox 关键接口通过。
- Metrics 清理独立 MySQL 验收通过。
- Token 和 WebSocket 安全测试通过。
- 指标接收和查询通过。
- 浏览器监控页面能够使用 `/ws/monitor` 接收实时指标，不使用 Agent Token。
- 所有敏感信息扫描无泄露。
- 所有备份和删除操作都有留痕。
- 开发日志明确记录已完成、未完成和未验证范围。

## 十六、推荐执行批次

### 批次 1：基线和 Metrics 清理

```text
读取当前 Metrics 表结构
→ 备份计划修改文件
→ 备份开发数据库
→ 增加清理配置
→ 实现 MetricsCleanupMapper
→ 实现 Service
→ 实现 Scheduler
→ 编写单元测试
→ 独立 MySQL 验收
→ 更新开发记录
```

### 批次 2：Agent Token

```text
读取 servers 表结构
→ 备份数据库和相关文件
→ 新增增量迁移
→ 只映射已有 Agent 字段和 V9 生命周期字段
→ 实现注册/轮换/撤销
→ 更新安全配置
→ 单元和 MockMvc 测试
→ 独立 MySQL 验收
→ Apifox AI 分支验收
→ 更新开发记录
```

### 批次 3：WebSocket 鉴权和心跳

```text
冻结协议文档
→ 备份协议和配置文件
→ 实现 WebSocket Handler
→ 实现 Agent 鉴权
→ 实现心跳
→ 实现在线/离线判定
→ 实现 AgentConnectionRegistry
→ 实现 `/ws/monitor` 用户订阅鉴权
→ WebSocket 测试
→ 真实本机 WebSocket 验收
→ 更新开发记录
```

### 批次 4：Metrics 接收和查询

```text
确认 V3 表结构
→ 确认固定宽表字段和时间策略
→ 实现消息 DTO
→ 实现校验和落库
→ 实现 latest/history
→ 同步 OpenAPI 和 HTTP 样例
→ 独立 MySQL 验收
→ Apifox 验收
→ 更新开发记录
```

### 批次 5：Vue 监控闭环

```text
读取现有扁平前端结构
→ 备份现有前端文件
→ 安装 ECharts 并验证 package-lock.json
→ 登录和权限状态
→ 服务器列表和详情
→ 最新指标卡片
→ 历史指标图表
→ `/ws/monitor` 用户 JWT 实时更新
→ 桌面/移动端验证
→ 前端构建和运行验证
→ 更新开发记录
```

## 十七、风险清单

| 风险 | 影响 | 处理 |
|---|---|---|
| 清理任务误删边界数据 | 指标历史丢失 | cutoff 独立库边界测试、先备份 |
| 清理任务长事务 | 锁表和写入阻塞 | 小批量、单轮上限、独立事务 |
| Agent Token 泄露 | 服务器被冒用 | 只保存 hash、只返回一次、禁止日志输出 |
| WebSocket Token 进入 URL | 代理和日志泄露 | 首帧认证，不使用 query 参数 |
| 恶意指标消息 | 数据污染或资源耗尽 | 固定字段反序列化、数值范围、单消息大小限制 |
| WebSocket 连接泄漏 | 连接数耗尽 | 心跳超时、断线清理、单服务器连接策略 |
| 历史查询慢 | 数据库压力 | 时间窗口、分页、索引和查询计划 |
| 前端重复订阅 | 数据重复和内存泄漏 | Store 统一管理连接和销毁流程 |
| Agent 与浏览器混用 WebSocket Token | 凭据泄露或权限绕过 | `/ws/agent` 使用 Agent Token，`/ws/monitor` 使用用户 JWT，通道和测试隔离 |
| 现有工作树改动混杂 | 误覆盖用户改动 | 只改计划范围文件、先备份、执行前后检查 diff |
| 文档状态过时 | 错误判断完成度 | 每个阶段结束立即更新开发记录 |

## 十八、当前计划结论

本文件只负责规划，不代表代码、配置、数据库、Apifox 或 WebSocket 已实现。

正式执行时必须遵循：

```text
先读取事实
→ 展示影响范围
→ 备份已有文件和数据库
→ 最小修改
→ 定向验证
→ 全量验证
→ 运行时验证
→ 更新开发记录
→ 用户确认后执行删除或合并
```
