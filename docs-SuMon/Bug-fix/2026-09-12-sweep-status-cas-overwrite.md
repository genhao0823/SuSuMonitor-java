# 2026-09-12 sweep 状态机缺 CAS 守卫导致终态覆盖

- **严重级别**：高（数据正确性）
- **影响范围**：`server-java-SuMon` 命令域状态机（`ai_command_runs` 审计数据）+ 自动审批事后通知内容
- **发现方式**：2026-09-12 后端设计复查（人工时序推演取证，非线上故障）
- **修复提交**：`534b38f fix(server): sweep 状态机补 CAS 守卫并异步化通知发送`

## 一、根因

`CommandRunMapper.updateStatusByIds` 的 SQL 为无条件更新：

```sql
UPDATE ai_command_runs SET status=#{finalStatus}, error_code=#{errorCode}, completed_at=#{now}
WHERE id IN (...)
```

而同表的 `completeRun` / `approveRun` / `markExecuting` 均带状态 CAS 守卫。sweep 扫描采用"select ids → 批量置态"两步式，两步之间行状态可能已被并发事务流转，置态语句无守卫即产生**终态覆盖**。

## 二、复现时序（窗口虽小但真实存在）

| 时刻 | sweep 线程 | WS 消息线程（command.result） |
| --- | --- | --- |
| T1 | `selectTimeoutIds` 返回 id=2（executing 超时） | |
| T2 | | `completeRun`：executing → **succeeded**（结果真实到达） |
| T3 | `updateStatusByIds([2], timeout)` 无守卫 → 覆盖 | |

结果：真实执行成功的运行被改写为 timeout；且 2026-09-10 引入的事后通知基于 T1 前取的行，向管理员误报"timeout"。expired 路径同理：pending 在 select 与 update 之间被 approve → executing 后仍会被无条件改写为 expired（`markExecuting` 虽有守卫，但 sweep 的覆盖发生在其后）。另外 `expireIfNeeded`（approve/reject 懒过期路径）对并发 approve 存在同类覆盖窗口。

## 三、修复

1. `updateStatusByIds` 替换为两个带守卫的 CAS：
   - `markExpiredByIds(ids, now)`：`WHERE status='pending_approval' AND id IN (...)`
   - `markTimeoutByIds(ids, errorCode, now)`：`WHERE status='executing' AND id IN (...)`
2. `CommandRunService.sweep()`：timeout 通知改为 **CAS 后按 ids 重查、仅对落库终态仍为 timeout 的行**发送（结果已回填的行自动排除）；返回行数日志同步以 CAS 实际影响数为准。
3. `expireIfNeeded` 同步换用 `markExpiredByIds`。
4. 回归测试：`sweepShouldNotNotifyRunsCompletedBeforeTimeoutCas`（CAS 前结果已回填 → 不通知）；`sweepShouldNotifyOnlyAutoApprovalTimeouts` 改为以重查终态为通知依据；MySQL IT `sweepSelectAndStatusUpdate` 改用新 CAS 方法（真实验证库待执行）。

## 四、同轮顺带修复（同一提交）

| 项 | 问题 | 修复 |
| --- | --- | --- |
| UPSERT 返回值 | `!=1` 依赖 Connector/J found-rows 默认语义，`useAffectedRows=true` 时 update 返回 2 误报 DATABASE_ERROR | 放宽为 `<=0`；IT 断言改 `>=1` |
| 通知同步阻塞 | 事后通知在 WS 消息/消费线程同步发送（慢渠道 ~20-30s 阻塞） | `AsyncConfig` 新增有界 `notificationExecutor`（core1/max2/queue100/饱和丢弃记日志），命令事后通知、AI 解释补充通知、健康报告通知三处异步投递 |
| 时间炸弹测试 | `generateShouldDefaultToYesterday` 用真实时钟计算"昨天"，UTC 日期滚动后 stub 失配 | 改以 stub 时钟为基准的固定日期 |

## 五、预防

- 状态机所有终态写入必须带前置状态守卫（本项目 SQL 层 CAS 惯例）；review 时对"select 后批量 update"模式专门核对并发窗口。
- 依赖 JDBC 驱动默认行为（affected vs found rows）的断言不得出现在业务代码；返回值判断以"失败为 0"为准。
- 时间敏感的测试一律以注入的 `Clock` 为基准，禁止 `LocalDate.now()` 与 stub 时钟混用。
