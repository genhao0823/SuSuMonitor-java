# 2026-08-17 多实例化阶段一：Redis 基础设施 + Monitor Ticket 共享

**状态**：✅ 已收口（2026-08-17）
**前置基线**：`main` HEAD `a7308be`（Docker 镜像实机构建收口）
**承接**：规划文档（20260712）多实例化"ticket 改 SETNX"落地；`Develop-log/20260721-Agent-Monitor阶段验收收口.md` L64 明确"多 JVM 分布式状态…评估 Redis Ticket、订阅路由和跨实例广播"。

## 一、目标

1. **引入 Redis 底座**：`spring-boot-starter-data-redis`（lettuce），连接参数走 `spring.data.redis.*`（`REDIS_HOST/REDIS_PORT/REDIS_PASSWORD/REDIS_TIMEOUT`），业务开关 `susumonitor.redis.enabled`（`REDIS_ENABLED`，**默认 false**——过渡期不破坏现有 systemd/本机/compose 部署）。
2. **Monitor ticket 改 Redis**：内存 ConcurrentHashMap + 30s TTL + 60s 定时清理 → Redis 存储（GETDEL 原子取删 + TTL 自动过期），实现**跨实例一次性 ticket**；内存实现保留为默认兜底（`@ConditionalOnProperty` 互斥切换）。
3. **`/api/ready` 加 Redis 探活**：对称 RabbitHealthChecker，新错误码 **50302 REDIS_UNAVAILABLE**。
4. **compose 加 redis 服务**：`redis:7-alpine` + healthcheck + 卷；server 环境透传 `REDIS_ENABLED/REDIS_HOST/REDIS_PORT`。

**边界**：本阶段仅 ticket 跨实例共享；Monitor/Agent/Terminal 注册表与 WS 事件广播仍单 JVM（阶段二/三）。Redis 不可用时 **fail-fast**（ticket 401 + ready 50302），不做内存降级——降级会掩盖"未配置 Redis 的多实例"配置缺失。

## 二、模块与提交节奏

| 模块 | 提交 | 内容 |
|---|---|---|
| M1 | docs(plan) | 本计划文档 |
| M2 | feat(server) | pom + application.yml + AppProperties.Redis + `RedisMonitorTicketServiceImpl` + 内存实现加互斥条件 + `RedisHealthChecker` + ErrorCode 50302 + SystemController.ready + docker-compose redis 服务 + .env.example |
| M3 | test | `RedisMonitorTicketServiceTests`（6 例）+ `SystemControllerTests` 50302 用例 + 回归；全量 mvn test |
| M4 | test(api-test) | `verify-redis-ticket.mjs`（双实例跨实例 ticket）+ 真实验收（单实例回归 + 失效演练） |
| M5 | docs | dev-log + README/Introduction/Summary-Technology/规划文档注/本机环境配置/部署手册 + 备份留痕 |

## 三、实现细节与参数传递

### M2 代码（参数传递）
- `pom.xml`：+ `spring-boot-starter-data-redis`
- `application.yml`：
  - `spring.data.redis.host: ${REDIS_HOST:127.0.0.1}`、`port: ${REDIS_PORT:6379}`、`password: ${REDIS_PASSWORD:}`、`timeout: ${REDIS_TIMEOUT:2s}`
  - `susumonitor.redis.enabled: ${REDIS_ENABLED:false}`、`ticket-ttl-seconds: ${REDIS_TICKET_TTL_SECONDS:30}`
- `AppProperties.Redis`：`enabled` + `ticketTtlSeconds`（@Min 5 @Max 300，默认 30）+ getter/setter
- `websocket/RedisMonitorTicketServiceImpl`（`@ConditionalOnProperty(redis.enabled=true)`）：
  - `issue(user)`：SecureRandom 32B → Base64 URL ticket；`opsForValue().set("susumonitor:ticket:" + ticket, objectMapper.writeValueAsString(user), Duration.ofSeconds(ttl))`；返回 `MonitorTicketVo(ticket, now+ttl UTC)`
  - `consume(ticket)`：null/blank → UNAUTHORIZED；`getAndDelete(key)` 原子取删；null → UNAUTHORIZED；反序列化失败 → UNAUTHORIZED + warn；过期由 Redis TTL 强制
  - `purgeExpiredTickets()`：空实现（TTL 自动清理）
- `MonitorTicketServiceImpl`：+ `@ConditionalOnProperty(redis.enabled=false, matchIfMissing=true)`（与 Redis 实现互斥，默认注册）
- `module/system/RedisHealthChecker`：对称 RabbitHealthChecker，`isHealthy()` = `"PONG".equals(redisTemplate.execute(connection -> connection.ping()))`，异常 → false + warn
- `ErrorCode`：+ `REDIS_UNAVAILABLE(50302, "redis unavailable", SERVICE_UNAVAILABLE)`
- `SystemController.ready()`：`ObjectProvider<RedisHealthChecker>` 对称检查 → 50302
- `docker-compose.yml`：redis 服务（`redis:7-alpine`，healthcheck `redis-cli ping`，`redis-data` 卷）；server 环境 + `REDIS_ENABLED: ${REDIS_ENABLED:-false}`、`REDIS_HOST: redis`、`REDIS_PORT: 6379`
- `.env.example`：+ 可选 `REDIS_ENABLED/REDIS_PASSWORD/REDIS_TICKET_TTL_SECONDS`

### M3 测试（预计 569 → 576±）
- `RedisMonitorTicketServiceTests`：issue 写 JSON+TTL / consume 命中 / 未命中 / blank / 损坏 JSON / purge 空实现
- `SystemControllerTests`：+ @MockitoBean RedisHealthChecker + `readyShouldReturn50302WhenRedisUnhealthy`
- `MonitorTicketServiceTests` 内存实现回归；全量 `mvn test`（确认 starter 不破坏既有 @SpringBootTest 上下文）

### M4 验收（真实环境）
1. WSL Docker 起 `redis:7-alpine`（`-p 6379:6379`，先查 6379 空闲）
2. 单实例 18081（`REDIS_ENABLED=true`）→ `verify-alert-ws.mjs` 24/24 回归
3. 双实例 18081+18082（共享隔离库 + 同一 Redis）→ `verify-redis-ticket.mjs`：A 签发 → B 握手消费；B 签发 → A 握手消费；两实例 ready 200
4. 失效演练（shell）：停 redis 容器 → ready 503/50302 + ticket 401 → 起容器 → 恢复 200（自愈）
5. `docker compose config -q` 校验；验收后停实例、redis 容器保留

### M5 文档同步
- dev-log `20260817-多实例化阶段一Redis与Ticket.md`；README/Introduction 00/06/Summary-Technology/规划文档（追加带日期注释）/本机开发环境配置/部署安装手册
- 备份 `local/backup-multi-instance-ticket/`（pom/yml/AppProperties/MonitorTicketServiceImpl/SystemController/ErrorCode/测试/compose/.env.example/文档原版 + SHA-256）

## 四、验证边界（诚实声明）

- `REDIS_ENABLED` 默认 false；GETDEL 需 Redis ≥6.2（容器 redis:7-alpine）
- 注册表与 WS 事件广播仍单 JVM（阶段二/三）；双实例验收共享同一 DB，仅验证 ticket 跨实例；未验证负载均衡/会话亲和/滚动升级
- 不触碰工作树 12 个未提交前端样式改动；openapi 契约零变化（503 响应已存在）
