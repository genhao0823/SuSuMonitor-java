# 2026-09-12 告警状态机乐观锁冲突吞噬与通知链路一致性

- **严重级别**：高（数据正确性，单实例即可触发）
- **影响范围**：`AlertEvaluationServiceImpl` 状态机、`alert_notifications` 外发、`AlertTriggeredConsumer` 通知排程
- **发现方式**：2026-09-12 全量后端设计复查（3 方向并行取证 + 逐项读码核实），非线上故障
- **修复提交**：`083bb47 fix(server): 告警状态机冲突回滚与通知链路一致性修复`

## 一、根因

`AlertEvaluationServiceImpl` 的状态行更新全部采用乐观锁（version CAS），但五处冲突分支只记 warn 日志后照常继续并提交消费事务。`alert_states` 行会被 triggered/resolved 两个独立队列的消费线程、以及并发>1 的同队列消费者并发修改，冲突是设计预期内的常态，吞噬它等于接受不一致状态落库。

## 二、三类后果（均有代码时序佐证）

1. **触发激活冲突（handleTrigger.activateOnBreachThreshold==0）**：`insertRecord` 已先行执行且不回滚 → 孤儿 unread 记录落库；`AlertTriggeredEvent`（WS 推送）与 `alert.triggered.v1` Outbox 事件照常发布 → 外部通知（邮件/钉钉/Webhook）对一条状态机并未确认的告警真实外发。
2. **计数/持续越界/计数重置冲突**：越界计数静默丢失后消费幂等记录照常写入并 ACK，确认窗口（confirmCount）语义失真且无重试机会。
3. **恢复删行冲突（handleResolve.deleteState==0）**：record 已置 resolved 但 `alert_states` 残留 active 僵尸行 → 该 rule/server 后续越界走 ContinueBreached 分支**不再创建新告警记录**，告警永久静默，只能人工清库恢复。

## 三、修复

1. 五处冲突（激活/计数递增/计数重置/持续越界/恢复删行）统一改为抛 `org.springframework.dao.ConcurrencyFailureException`：消费事务整体回滚（含 handleTrigger 已插入的 record），RabbitMQ 容器按既有有限重试策略重试整条消息，以最新状态重评；重试耗尽经 `FailedConsumeRecordRecoverer` 失败留痕进 DLQ，可观测可重放。
2. `handleResolve` 中 `updateStatusToResolved==0`（记录缺失/已恢复）保持 return——这是合法幂等路径，与删行冲突性质不同，已在代码注释中区分。
3. 类 javadoc 同步改写设计口径（原"冲突记 warn 跳过"已废止）。

## 四、同提交顺带修复（通知链路一致性与加固）

| 项 | 问题 | 修复 |
| --- | --- | --- |
| 通知重复外发 | `insertPending` 不设 next_attempt_at（NULL），重试扫描条件含 `IS NULL`，30s 重试调度可与首次 @Async 发送并发拉取同一 pending 行 | pending 行初始化 `next_attempt_at = now+60s` 首试保护窗；异步任务被丢弃时窗口到期后由重试调度自愈（attempts 从 1 起，语义不变） |
| 乱序触发通知 | triggered/resolved 独立队列乱序时，迟到的触发消息对已 resolved 记录照常排程通知 | `AlertTriggeredConsumer` 对 `status=resolved` 的记录跳过排程（含解释请求登记） |
| @Async 无界线程 | `sendScheduled` 的 @Async 未限定执行器，多 TaskExecutor Bean 场景退化为 SimpleAsyncTaskExecutor（每任务一线程） | 限定 `@Async("notificationExecutor")`（有界）；饱和丢弃由重试调度自愈 |
| 分页无全局兜底 | MybatisPlus 分页拦截器未设 maxLimit | `setMaxLimit(500)`（各 Service 仍有 ≤100 校验，此为纵深防御） |
| WS 推送循环中断 | `AlertPushPublisher` 仅捕获背压/IO，容器并发关闭会话的 RuntimeException 会打断其余订阅者 | 增补 RuntimeException 兜底：终止该订阅者并继续循环 |
| 限流器内存无界 | `FixedWindowRateLimiter` buckets 无淘汰，伪造 IP 扫描可撑爆堆 | 桶表超 8192 阈值时惰性清理闲置超 2 窗口的 key（双键表条件删除防误删活跃 key） |

## 五、明确保留不改

`AlertRabbitConfig` 中计时拦截器置于重试拦截器外层是 MVP-14 的**有意决策**（口径为"含重试循环的整轮处理耗时"，代码注释已声明），不按"统计失真"处理；如需单次耗时口径另行立项。

## 六、预防

- 乐观锁 CAS 的冲突分支必须与业务语义匹配：可安全跳过（幂等合法路径）或必须回滚重试（状态正确性路径），禁止一律 warn 吞噬。
- "select 后批量 update"与"事务外预检查 + 事务内写入"两类模式在并发评审中必须核对窗口。
- 新增 `AlertEvaluationServiceTests` 5 个冲突传播用例、`AlertTriggeredConsumerTests` 乱序回归、`AlertNotificationServiceTests` 保护窗断言、`FixedWindowRateLimiterTests` 淘汰用例作为行为基线。
