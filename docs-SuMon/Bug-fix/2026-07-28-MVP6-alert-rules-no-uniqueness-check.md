# MVP6-alert-rules-no-uniqueness-check `POST /api/alerts/rules` 允许完全重复规则入库（已解决）

**日期**: 2026-07-28
**发现方式**: opencode 在 MVP-6 真实联调阶段路径 B(冲突 toast 验证)发现后端实际未做查重
**优先级**: 中（不阻塞主路径，但产生数据冗余 + 重复触发评估）
**模块**: 告警规则 Service / 数据库 schema
**影响前端**: `AlertRuleDialog.vue` 创建第二条完全相同规则时显示"创建成功"而非期望的"规则冲突:..."中文 toast

## 解决记录

**状态**: 已解决（2026-07-28）

- 重复边界采用完全 5 元组：`server_id`、`metric`、`operator`、`threshold_value`、`level` 全部相同才判定为冲突；`server_id IS NULL` 的通用规则作为独立范围处理。
- `AlertRuleServiceImpl` 在创建和更新前执行活跃规则查重，命中时返回 `40900 RESOURCE_CONFLICT`。
- 新增 Flyway `V13__enforce_unique_active_alert_rules.sql`：已将本地数据库中历史重复活跃规则软删除（保留最小 ID），并通过**生成列 `active_server_scope_id`**（活跃记录取 `COALESCE(server_id, 0)`、软删记录取 NULL）与 `uk_alert_rules_active_signature` 唯一索引阻止并发重复写入；软删除后允许重建相同规则。注意：唯一索引是针对生成列而非裸 5 元组，软删行因生成列为 NULL 不参与唯一约束，这是"软删后可重建"的实现基础。
- 2026-08-10 补充：删除语义保持按 ID 软删（设计属性，不做"删除时级联删同元组"）；因 V13 已去重历史重复 + 创建期查重 + 唯一索引三保险，新建数据不会再出现"删一条留一条完全相同"场景。
- 执行迁移前已备份 `alert_rules` 到 `C:\Backup\susumonitor_alert_rules_before_v13_20260728.sql`，并验证备份非空、可读且包含表结构和数据。
- 本地 Flyway 已从 v12 成功迁移至 v13；数据库验证确认活跃重复规则组数量为 0，唯一索引存在。
- 已新增 Service 和真实 MyBatis/H2 回归测试；`mvn -q test` 与 `mvn -q -DskipTests package` 均通过。

## 复现命令

```powershell
# 1. 登录拿 token
$login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18080/api/auth/login' `
    -ContentType 'application/json' `
    -Body '{"username":"admin","password":"<ADMIN_PASSWORD>"}' -TimeoutSec 10
$tok = $login.data.token
$h = @{ Authorization = "Bearer $tok" }

# 2. 第一次创建
$r1 = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18080/api/alerts/rules' `
    -Headers $h -ContentType 'application/json' `
    -Body '{"serverId":null,"metric":"cpu","operator":">","thresholdValue":80,"level":"warning"}' `
    -TimeoutSec 10
$r1.data.id   # → 期望:任意 id; 实际:返回 id,data.code=0

# 3. 第二次创建完全相同规则
$r2 = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18080/api/alerts/rules' `
    -Headers $h -ContentType 'application/json' `
    -Body '{"serverId":null,"metric":"cpu","operator":">","thresholdValue":80,"level":"warning"}' `
    -TimeoutSec 10
$r2.data.id   # → 期望:40900 RESOURCE_CONFLICT; 实际:返回新 id,data.code=0
```

## 期望行为

POST 第二次完全相同规则应返回:

```json
{
  "code": 40900,
  "message": "resource conflict",
  "data": null
}
```

HTTP 状态 409。前端 `AlertRuleDialog.vue` 的 `explainError()` 已映射 40900 → "规则冲突:同一指标 / 服务器的同类规则已存在"。

## 实际行为

第二次 POST 返回 200 + data.code=0,创建了新规则行。数据库 `alert_rules` 现在有**两条完全相同的活跃行**(仅 `id` / `created_at` 不同)。

浏览器前端:
- toast: "规则已创建"(成功提示)
- 表格顶部出现新行

## 探测证据

### 后端业务代码静态分析

**`AlertRuleServiceImpl.java:78-86`** 的查重逻辑:

```java
private void validateCreateRequest(CreateAlertRuleRequest request) {
  if (AlertMetric.fromValue(request.getMetric()) == null) {
    throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
  }
  if (AlertOperator.fromValue(request.getOperator()) == null) {
    throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
  }
  validateLevel(request.getLevel());
}
```

只验证了 **enum 合法性**,没有查重查询(没有 `ruleMapper.selectActiveRuleByXxx(...)` 然后比对 server_id / metric / operator / threshold_value / level)。

### 数据库 schema 静态分析

**`V5__create_alert_tables.sql:1-16`** 的 `alert_rules` 表:

```sql
CREATE TABLE `alert_rules` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `server_id` BIGINT UNSIGNED DEFAULT NULL,
    `metric` VARCHAR(30) NOT NULL,
    `operator` VARCHAR(5) NOT NULL,
    `threshold_value` DECIMAL(12,2) NOT NULL,
    `level` VARCHAR(20) NOT NULL,
    ...
    PRIMARY KEY (`id`),
    KEY `idx_alert_rules_server_id` (`server_id`),
    KEY `idx_alert_rules_enabled` (`enabled`),
    KEY `idx_alert_rules_metric` (`metric`)
)
```

**无任何 UNIQUE KEY**,只有 3 个普通二级索引。

**`V10__create_alert_states_and_soft_delete_rules.sql`** 增加 `deleted` / `deleted_at` 与 `alert_states`,也**未补 UNIQUE 约束**。

### 真实 HTTP 验证

用户 2026-07-28 在浏览器手动复现:**创建第一条规则成功,然后立即创建完全相同的第二条,显示"创建成功"**。

## 业务影响

1. **重复评估**:两条完全相同的规则会同时匹配同一指标,产生**重复 alert_records**(数据库 V11 之后可能按 `(rule_id, server_id)` UNIQUE 触发 `alert_states` 唯一约束,但 `alert_records` 不会——见 V10 `idx_alert_records_rule_id` 仅单字段索引)
2. **数据冗余**:列表查询 (`/api/alerts/rules`) 返回所有 `deleted=0` 行,用户看到两条完全相同的规则,无法区分
3. ~~**修复歧义**:删除规则时,同 `(server_id, metric, operator, threshold_value, level)` 5 元组只能删一条,剩下一条仍然完全相同,问题未解决~~ —— **2026-08-10 结论**：此为软删除设计属性（按 ID 删除）：V13 已把历史重复软删为仅剩最小 ID 一条，且创建/更新期查重 + 唯一索引使新重复无法产生，故"删一条留一条完全相同"仅对 V13 迁移前的历史脏数据成立，该场景已由 V13 数据清理闭环，不再作为未解决项。
4. **路径 B 联调阻挡**:opencode 计划阶段 1-4 第 14 项"40900 冲突 toast 验证"无法观察到精确中文文案——只能看到通用"创建成功"

## 与前端关系

**前端已经准备就绪**:

| 部件 | 状态 |
|---|---|
| `ErrorCode.RESOURCE_CONFLICT(40900)` 定义 | ✅ 存在 |
| `AlertRuleDialog.vue` `explainError()` 包含 `40900 → "规则冲突:..."` 映射 | ✅ 存在 |
| `stores/alerts.ts` `createRule` 失败时重抛 `ApiBusinessError` | ✅ 0.2 已修 |
| HTTP wrapper `createAlertRule` 透传 axios error | ✅ 现有 |

**前端无需改动,后端返 40900 时将自动显示"规则冲突:同一指标 / 服务器的同类规则已存在"**。

## 建议修复方向(不写代码,只写思路)

### 必须先由用户/产品决定的边界

"什么算重复" 至少有 3 种合理定义,需要用户决策:

1. **完全 5 元组相同**:(`server_id`, `metric`, `operator`, `threshold_value`, `level`) 都相同才算冲突(最严格)
2. **同 server + metric + operator 算冲突**:不同 `threshold_value` 或 `level` 也算冲突(粗暴,不允许差异)
3. **同 server + metric + operator 算冲突,但 level 不同允许**:warning 80% + critical 80% 共存合理

### 修复方案 (按推荐度)

#### A. 应用层查重 + 数据库 UNIQUE(推荐)

两步并行,缺一不可:

1. **`AlertRuleServiceImpl.createRule`** 在写库前查重:
   ```text
   步骤 1: ruleMapper.existsByUniqueKey(server_id, metric, operator, threshold_value, level) → boolean
   步骤 2: 若 true → throw BusinessException(ErrorCode.RESOURCE_CONFLICT)
   ```
2. **新增 V11 migration** 给 `alert_rules` 加复合 UNIQUE 索引(必须基于"决定 1")+ 必要的 dedup 数据清理:
   ```text
   V11__alert_rules_unique_constraint.sql:
   - 先 DELETE 重复行(保留最早 id)
   - ALTER TABLE alert_rules ADD UNIQUE KEY uk_alert_rules_unique_per_scope
     (server_id, metric, operator, threshold_value, level, deleted)
   -- 软删除场景:同一组 5 元组可以 deleted=0 + deleted=1 各一行
   ```

#### B. 仅应用层查重(快速修,不彻底)

仅做步骤 A.1,不加 UNIQUE。优点:无需迁移、无数据清理;缺点:并发场景两个管理员同时点创建可能都通过应用层查重后入库产生重复。

#### C. 仅数据库 UNIQUE(简单粗暴)

仅做步骤 A.2 + 必要的 dedup 数据清理。优点:并发安全靠 DB;缺点:`deleted=0` 唯一约束时,软删除后想再次创建同规则会报 conflict(因为历史软删行还在)。

### 已采用方案：A 方案 + 选项 1

- 选项 1(完全 5 元组):最直观,符合"重复规则 = 完全一样"
- 选项 3(允许 warning + critical 同阈值):覆盖常见分级告警场景

已采用完全 5 元组边界，并通过应用层校验和数据库唯一索引双重保护实施。

## 前端绕过方案

- 无。前端逻辑正确,后端返 200 时显示成功是合理行为。

## 验收标准

修复完成后:

- [ ] 真实 HTTP 第二次创建完全相同规则返回 40900（2026-08-10 已交付 `api-test/verify-alert-rules.mjs`，R2 项覆盖；待隔离库/联调环境实跑）
- [ ] 浏览器 toast 显示 "规则冲突:同一指标 / 服务器的同类规则已存在"（待真实 HTTP 复验后确认）
- [x] 数据库 `alert_rules` 不存在完全 5 元组重复的 `deleted=0` 行；V13 已成功执行，活跃重复规则组数量为 0，`uk_alert_rules_active_signature` 已存在
- [ ] alert 评估时(阶段 3 路径 C 真实 alert.push 验证)同一指标只产生一条 alert_records（待后续真实告警链路验证）
- [x] 迁移前已完成并验证 `alert_rules` 表备份；V13 保留软删除历史记录，不包含物理删除，回退可基于备份恢复

## 关联

- 收口计划:`C:\Users\genhaosan\.claude\plans\iterative-bouncing-valley.md`
- 相邻 bug 单(后端 mapper bug):`docs-SuMon/Bug-fix/2026-07-27-MVP6-alert-rules-mapper-500.md`
- 相邻 bug 单(前端 store 吞错):`docs-SuMon/Bug-fix/2026-07-27-MVP6-alert-store-swallows-error.md`
- 收口日志:`docs-SuMon/Develop-log/20260727-MVP6告警前端代码层收口.md`
- 后端应用层实现文件:`server-java-SuMon/src/main/java/com/susumonitor/server/module/alert/service/AlertRuleServiceImpl.java`
- 后端 schema:`server-java-SuMon/src/main/resources/db/migration/V5__create_alert_tables.sql` 和 `V10__create_alert_states_and_soft_delete_rules.sql`
