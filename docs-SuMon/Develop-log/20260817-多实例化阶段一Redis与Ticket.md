# 2026-08-17 多实例化阶段一：Redis 基础设施 + Monitor Ticket 共享

**状态**：✅ 已收口（2026-08-17）——代码与单测完整；真实验收**部分完成**，环境受限项诚实声明（见 §五）
**前置基线**：`main` HEAD `a7308be`
**提交链**：`38eb394`（计划）→ `f86b63f`（Redis 底座 + ticket + ready）→ `decc841`（单测 576/576）→ `5006330`（verify-redis-ticket.mjs）→ 本收口提交
**承接**：规划文档多实例化"ticket 改 SETNX"落地；`Develop-log/20260721-Agent-Monitor阶段验收收口.md` L64 点名的"评估 Redis Ticket、订阅路由和跨实例广播"第一步。

## 一、目标与边界

引入 Redis 底座，把 Monitor ticket 从单 JVM 内存（ConcurrentHashMap + 30s TTL + 60s 定时清理）切换为 Redis 存储（GETDEL 原子取删 + TTL 自动过期），实现**跨实例一次性 ticket**；`/api/ready` 对称加 Redis 探活（新错误码 50302）；compose 加 redis 服务。

**边界**：本阶段仅 ticket 跨实例共享；Monitor/Agent/Terminal 注册表与 WS 事件广播仍单 JVM（阶段二/三）。Redis 不可用时 **fail-fast**（ticket 401/500 + ready 50302），不做内存降级——降级会掩盖"未配置 Redis 的多实例"配置缺失。`REDIS_ENABLED` 默认 **false**，现有 systemd/本机/compose 部署零影响。

## 二、代码改动（参数传递细节）

1. **pom.xml**：+ `spring-boot-starter-data-redis`（lettuce，唯一新依赖）
2. **application.yml**：`spring.data.redis.host: ${REDIS_HOST:127.0.0.1}` / `port: ${REDIS_PORT:6379}` / `password: ${REDIS_PASSWORD:}` / `timeout: ${REDIS_TIMEOUT:2s}`；`susumonitor.redis.enabled: ${REDIS_ENABLED:false}` / `ticket-ttl-seconds: ${REDIS_TICKET_TTL_SECONDS:30}`
3. **AppProperties.Redis**（新嵌套类）：`enabled`（默认 false）+ `ticketTtlSeconds`（@Min 5 @Max 300 默认 30）+ getter/setter；连接参数走 starter 标准 `spring.data.redis.*`（与 Rabbitmq 段同模式）
4. **`RedisMonitorTicketServiceImpl`**（新，`@ConditionalOnProperty(redis.enabled=true)`）：
   - `issue(user)`：SecureRandom 32B → Base64 URL ticket；`opsForValue().set("susumonitor:ticket:"+ticket, objectMapper.writeValueAsString(user), Duration.ofSeconds(ttl))`；返回 `MonitorTicketVo(ticket, expiresAt UTC)`
   - `consume(ticket)`：null/blank → UNAUTHORIZED；`getAndDelete(key)` 原子取删（GETDEL，需 Redis ≥6.2）；null → UNAUTHORIZED；反序列化失败 → UNAUTHORIZED + warn；过期由 Redis TTL 强制
   - `purgeExpiredTickets()`：空实现（TTL 自动清理，无 @Scheduled）
   - 用户快照 `AuthenticatedUser` 为 record（含 OffsetDateTime），由 Spring Boot 的 ObjectMapper（jsr310）序列化
5. **`MonitorTicketServiceImpl`**（内存）：+ `@ConditionalOnProperty(redis.enabled=false, matchIfMissing=true)` 与 Redis 实现互斥（默认注册，现有行为不变）
6. **`RedisHealthChecker`**（新，对称 RabbitHealthChecker）：`isHealthy()` = `"PONG".equals(redisTemplate.execute((RedisCallback<String>) c -> c.ping()))`，异常 → false + warn
7. **ErrorCode**：+ `REDIS_UNAVAILABLE(50302, "redis unavailable", SERVICE_UNAVAILABLE)`
8. **SystemController.ready()**：`ObjectProvider<RedisHealthChecker>` 对称检查 → 50302
9. **docker-compose.yml**：+ redis 服务（`redis:7-alpine`，healthcheck `redis-cli ping`，`redis-data` 卷，appendonly）；server 环境 + `REDIS_ENABLED: ${REDIS_ENABLED:-false}`、`REDIS_HOST: redis`、`REDIS_PORT: 6379`，depends_on redis healthy；`.env.example` + `REDIS_ENABLED/REDIS_PASSWORD/REDIS_TICKET_TTL_SECONDS` 可选行

## 三、测试（576/576 全绿）

- `RedisMonitorTicketServiceTests`（新，6 例）：issue 写 JSON+TTL 30s（`contains("\"username\":\"monitor\"")` + `eq(Duration.ofSeconds(30))` 验证）；consume 命中反序列化；未命中/blank/null/损坏 JSON → UNAUTHORIZED；purge 空实现
- `SystemControllerTests`：+ @MockitoBean RedisHealthChecker；`readyShouldReturnServiceUnavailableWhenRedisIsDown`（503 + code 50302）；既有 ready 用例补 redis healthy stub
- 期间修复：`redisTemplate.execute(lambda)` 的 RedisCallback/SessionCallback 类型歧义 → 显式转型；测试裸 ObjectMapper 缺 jsr310 → `registerModule(new JavaTimeModule())`；`delete(any())` 歧义 → `anyString()`
- 全量 `mvn test` 576/576（569 + 7），starter 引入未破坏既有 @SpringBootTest 上下文

## 四、真实环境验证（部分完成）

环境：WSL2 Ubuntu 24.04 原生 redis-server **7.0.15**（GETDEL 可用）+ 本机 Windows 双 Java 实例（18081/18082，REDIS_ENABLED=true，共享隔离库）。

**已真实观察到**：
- Redis 实现被真实加载：`REDIS_ENABLED=true` 时 RedisHealthChecker 注册并真实探活（日志 304+ 次 `redis health check failed: Unable to connect to Redis`）；ticket issue 曾对真实 Redis 发起连接（500 堆栈为 `QueryTimeoutException`——连接层错误，证明 Redis 实现生效且真实调用）
- **ready 自愈路径**：Redis 恢复后 `/api/ready` 自动由 50302 转 200（多次观察到，如 13:20、14:33 窗口），RedisHealthChecker 的 isHealthy/恢复逻辑在真实 Redis 上工作
- 失败演练的被动版本：Redis 不可达 → ready 50302（code 50302 真实返回），恢复 → 200

**未能完成（环境受限，诚实声明）**：
- `verify-alert-ws.mjs` 24 项回归（ticket 走 Redis 的全链路）
- `verify-redis-ticket.mjs` 双实例跨实例 ticket（A 签发→B 消费等 4 项，脚本已提交 `5006330`）
- 主动失效演练（systemctl stop/start redis → 50302/200 显式断言）

**根因（环境级 bug，非本项目代码）**：WSL2 2.7.3 → **2.7.11**（已升级尝试）在 Windows 10.0.26200 上 **VM 每 1-2 分钟崩溃重启循环**（静置 3 分钟也崩；停 dockerd、`.wslconfig` memory=6GB 限制均无效；Windows 事件日志仅见重启后正常 VmSwitch 事件，无崩溃根因）。任何 >30s 的连续操作都会被崩溃窗口打断；双实例同时就绪的窗口从未重叠。该问题在 2026-08-16 Docker 验收时已存在（当时靠单次会话紧凑编排通过），本次升级 WSL 未修复。

> **修正注（2026-08-18）**：上文"崩溃重启循环"结论**有误**——真实根因是 `.wslconfig` 的 **`vmIdleTimeout` 默认 60000 毫秒**（VM 空闲 60 秒自动关闭，Microsoft 官方文档确认），非崩溃。已通过 `vmIdleTimeout=600000` 修复（`.wslconfig` 合并段 + 重启，静置 3 分钟验证稳定）。**双实例跨实例 ticket 验收已于 2026-08-18 全部完成**（`verify-redis-ticket.mjs` P0+C1-C4 PASS + 失效演练 50302→恢复 + `verify-alert-ws` 24/24），验收链路改为 WSL 内单会话执行（MySQL/RabbitMQ 经网关直连，详见 `20260818-WSL修复与Redis安全加固.md`）。

## 五、诚实边界

- 跨实例一次性语义的代码保证 = `GETDEL` 原子取删（单测 `RedisMonitorTicketServiceTests` 覆盖命中/未命中/重放语义）；真实双实例验收留待稳定环境执行（脚本 `api-test/verify-redis-ticket.mjs` 已备，执行步骤见计划文档 §三）
- 验收期间对 WSL redis 的临时改动（bind 0.0.0.0 + protected-mode no）**已还原**（bind 127.0.0.1 + protected-mode yes）；复现验收需：`CONFIG SET protected-mode no` + bind 0.0.0.0（或 `--protected-mode no`），本机 NAT 内网可接受
- GETDEL 需 Redis ≥6.2（本机 7.0.15 / compose redis:7-alpine 均满足）
- 注册表与 WS 事件广播仍单 JVM（阶段二/三）；未验证负载均衡/会话亲和/滚动升级
- 工作树 12 个未提交前端样式改动（glass.css 等）保持原样，未触碰

## 六、文档同步清单

- README 技术栈行：+ Redis ticket（多实例化阶段一，可选启用）
- Introduction/00-README.md 多实例行、06-面试问答 Q18/L126（Redis 未用 → ticket 已落地可选）、Summary-Technology Redis 行：同步为"已落地（可选启用，真实验收部分完成）"
- 规划文档 20260712 多实例相关行：追加带日期注释
- 本机开发环境配置.md：+ `REDIS_*` 变量行 + WSL redis 说明
- 部署安装手册：+ `REDIS_*` 变量与 Redis 部署说明（并入 Docker Compose 章节）
- 备份 `local/backup-multi-instance-ticket/`：10 个改动文件原版 + SHA-256 清单（基线 38eb394）

## 七、后续（阶段二/三候选）

阶段二：MonitorSubscriptionRegistry/AgentConnectionRegistry 改 Redis 共享 + 实例路由（TerminalRelayBinding 持 live socket 需改为实例引用）；阶段三：WS 事件广播（AlertPushPublisher/MonitorMetricsPublisher 由本地事件改 MQ fanout）。均需先解决本机 WSL2 VM 崩溃问题（或换稳定 Docker 环境）。
