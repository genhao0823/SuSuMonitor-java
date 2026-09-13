# 2026-09-14 Agent Token 轮换 500 与宽限期列缺陷（rotate 50001 / prev 列语义错误）

- **级别**：高（轮换功能完全不可用 / 宽限期语义失效）
- **发现方式**：全功能联调 Phase 2 —— `api-test/verify-agent-ws.mjs` 在内网真实环境轮换步骤失败；单元测试（mock Mapper）未能暴露
- **影响版本**：`7469d95`（V35 轮换宽限期上线后）
- **影响范围**：`POST /api/servers/{id}/agent/rotate` 必现 500/50001；即使绕过，宽限期写入的旧 Token 摘要也是错的

## 一、现象

1. 任意服务器执行轮换：`HTTP 500 {"code":50001,"message":"database error"}`，前端表现为未知错误。
2. 手工等价 UPDATE 可以执行成功，但 `agent_token_hash_prev` 被写入**新哈希**（与 `agent_token_hash` 相同），旧 Token 宽限窗口功能失效。
3. 服务端日志仅有 `Business exception: database error`（WARN，无堆栈），根因被吞。

## 二、根因（两个独立缺陷，同一语句）

### 缺陷 1：V35 列宽不足（Data too long）

`V35__servers_agent_token_grace.sql` 将新列定义为：

```sql
ADD COLUMN `agent_token_hash_prev` VARCHAR(64) NULL ...
```

而 Token 摘要格式为 `sha256:` 前缀 + 64 位 hex = **71 字符**（既有列 `agent_token_hash` 为 varchar(255)）。轮换写入 71 字符触发：

```
ERROR 1406 (22001): Data too long for column 'agent_token_hash_prev' at row 1
```

MyBatis 抛 `DataIntegrityViolationException`（DataAccessException 子类）→ `AgentTokenServiceImpl` 捕获转 `50001`。

### 缺陷 2：MySQL UPDATE SET 求值顺序

```xml
SET agent_token_hash = #{tokenHash},
    agent_token_hash_prev = agent_token_hash,   <!-- 拿到的是已更新的新值！ -->
```

MySQL 的 UPDATE SET 子句**从左到右求值并使用已更新值**（官方文档明确行为）。`agent_token_hash_prev` 排在 `agent_token_hash` 之后，读到的是刚写入的新哈希——即使列宽足够，`prev == 当前哈希`，旧 Token 立即失效，宽限期形同虚设。

## 三、复现证据（内网联调环境）

```
POST /api/servers/15/agent/rotate → 500 {"code":50001}
information_schema: agent_token_hash varchar(255) / agent_token_hash_prev varchar(64)
UPDATE ... agent_token_hash_prev = 'sha256:...(71字符)' → ERROR 1406 Data too long
手工短值 UPDATE 后：h=sha256:probe, p=sha256:probe（prev==new，缺陷 2 实证）
```

## 四、修复方案

1. **新增 `V36__servers_agent_token_hash_prev_widen.sql`**（V35 已入共享库，按规范不改只追加）：
   `ALTER TABLE servers MODIFY COLUMN agent_token_hash_prev VARCHAR(255) NULL COMMENT ...`
2. **`ServerMapper.xml` rotateAgentToken**：SET 顺序调整为 prev/grace 在 hash 赋值之前：
   ```xml
   SET agent_token_hash_prev = agent_token_hash,
       agent_token_grace_until = #{graceUntil},
       agent_token_hash = #{tokenHash}, ...
   ```
3. **可观测性（顺带）**：`AgentTokenServiceImpl` 对 DataAccessException 记录 warn 级根因摘要，避免同类问题只能靠盲猜。
4. **测试**：新增真实 SQL 语义的 Mapper 集成断言（rotate 后 prev == 旧哈希、grace_until == 轮换时间+宽限），不再允许仅 mock 覆盖该链路。

## 五、遗留与预防

- 单测对 Mapper SQL 全 mock 是本轮漏检根因：**涉及手写 SQL 的迁移/语句必须有真实 DB 集成断言**（沿用项目内 `*MapperMybatisTests` 基建）。
- 修复后需重新打包部署内网机并复验：rotate 200、prev=旧哈希、宽限窗口内旧 Token 可认证。
