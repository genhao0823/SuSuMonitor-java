# 05 Bug 与困难全记录

> 面试"你遇到的最大困难/最有成就感的 Bug"必考题素材库。共 20+ 个真实问题,按根因类别分类,每个按 **现象 → 根因 → 解决 → 教训** 四段式记录。原始记录见 `docs-SuMon/Bug-fix/`(10 篇)与 `docs-SuMon/Difficulty-log/`(5 篇)及对应 dev-log。

---

## 类别 A:MyBatis 参数绑定问题(3 个同类,教训最深刻)

### A1. 告警规则 Mapper 500 —— 单测全绿,生产 500

- **现象**:`POST/PUT/DELETE /api/alerts/rules` 全部 HTTP 500 且响应体 0 字节(连标准 50000 JSON 都没有);GET 正常;IDEA 控制台无 ERROR 日志。
- **根因**:`AlertRuleMapper.insertRule` 缺 `@Param("rule")`,而 XML 全部用 `#{rule.xxx}`。MyBatis 参数解析**只在 SQL 真正执行时**才发生;若编译不保留参数名,参数名变成 `arg0`/`param1`,抛 `BindingException: Parameter 'rule' not found`。另一个隐患是 `keyProperty="rule.id"` 与接口签名不匹配。
- **解决**:补 `@Param("rule")` + 新增 `AlertRuleMapperMybatisTests`(H2 真实执行生产 XML,覆盖 INSERT 主键回填/UPDATE/软删除)。
- **教训**:**"Mock 全绿的测试 ≠ 真实行为正确"** —— Controller 测试 mock Service、Service 测试 mock Mapper,13 个用例没有一条真实 SQL。MyBatis 映射层必须用 H2/真实库执行生产 XML 测试。

### A2. 告警记录/状态插入失败(验收时暴露)

- **现象**:MVP-6 真实链路验收时,`alert.push` 永远不发送;评估事务回滚。
- **根因**:`AlertRecordMapper.insertRecord`、`AlertStateMapper.insertState` 同样缺 `@Param`,真实 MySQL 抛 `ReflectionException: There is no getter for property named 'record'`。**H2 MySQL mode 下参数绑定行为与真实 MySQL 不同**,所以单测没发现。
- **解决**:补 `@Param`;验收资产 `AlertRuleMySqlValidationIT`(仅 `RUN_MYSQL_VALIDATION_TESTS=true` 且隔离库时执行)固化真实 MySQL 校验。
- **教训**:H2 与 MySQL 的差异是真实盲区,必须保留真实数据库验证通道。

### A3. WebSocket 监控通道 5 个中危缺陷(07-21 修复)

- **现象**:ticket 无界累积泄漏内存、慢 DB 认证竞态把已关闭会话注册进 registry、heartbeat 抛 IllegalStateException 误报、握手 BusinessException 被吞无日志、坏 JSON 一条消息断开整个连接。
- **解决**:ticket 加 30s TTL + 60s 清理调度;认证原子状态机;心跳容错;握手异常显式记录;坏 JSON 回 error 帧不断连。
- **教训**:WS 长连接状态机(认证/注册/心跳)的边界条件必须逐一列出,配定时兜底清理。

---

## 类别 B:状态同步与数据一致性

### B1. Agent 断开后服务器仍显示"在线" —— 内存态与 DB 不同步

- **现象**:关掉 WSL(Agent 退出),前端服务器列表状态列永远"在线",刷新不变;DB 中 `status=online` 残留。
- **根因**:`AgentWebSocketHandler.afterConnectionClosed` 只清理内存 registry,不更新 DB。而 30s 离线扫描只扫 `connectionRegistry.sessions()` —— session 已被移除,扫描扫不到,断开 Agent 成为 DB 里"孤儿 online"。
- **解决**:`AgentHeartbeatService` 新增 `markOfflineOnDisconnect(session)`,复用乐观锁 SQL(`WHERE last_heartbeat_at = 断开时值`)——**只有 DB 心跳仍是断开时的值才置 offline**,防止误把已重连的新连接置离线;`afterConnectionClosed` 中调用。
- **教训**:连接断开回调必须**同步清理 DB 状态**,不能只靠超时扫描兜底;乐观锁配合"新连接替换旧连接"才能保证只有"真离线"才置 offline。

### B2. 服务器状态显示"离线"但 Agent 列"在线" —— 两个同义字段不同步

- **现象**:Agent 已鉴权并每 5s 上报(DB metrics 可见),但前端"状态"列显示离线,"Agent"列显示 online。
- **根因**:DB 中 `status=offline` 但 `agent_status=online`。`ServerMapper.xml` 的 `updateAgentHeartbeat` 只更新 `agent_status` 不更新 `status`;grep 全工程 `setAgentStatus` 无调用 —— **后端从不更新 status 字段**,而前端"状态"列读的正是 `row.status`。
- **解决**:三处 SQL 同步更新:`updateAgentHeartbeat`(status='online')、`markAgentOffline`(status='offline')、`revokeAgentToken`(status='offline')。
- **教训**:两个同义字段不同步是隐蔽 Bug;前端显示哪个字段必须与后端更新逻辑对齐。

### B3. 告警恢复后不再触发(最严重状态机 Bug,commit `f7dba69`)

- **现象**:首次越界正常告警 → 恢复变 resolved 正常 → **恢复后再次越界:无新记录、无推送**。在 MVP-9 性能基线执行前复核 `verify-alert-ws.mjs`(24 项)时暴露。
- **根因**:`AlertStateMachine.evaluate` 的 Trigger 分支要求 `currentState == null`,但 `handleResolve` 只把状态行置 `active=0` **不删除**。恢复后状态行仍在,再次越界时 Trigger(非 null)、Continue(非 active)、Resolve(已越界)三个分支全不匹配 → 落入 NoAction,规则**永久失效**。状态机 javadoc 与测试明确设计了"恢复后 state 被清除",实现与设计脱节。
- **解决**:恢复语义改为乐观锁删除状态行 `DELETE FROM alert_states WHERE id=#{id} AND version=#{version}`;下次评估 state=null,自然重新 Trigger。
- **教训**:① 实现与设计文档脱节会留下隐蔽逻辑死区,必须有"恢复后再触发"这类**跨状态转移的回归用例**;② **交接文档声称已修复 ≠ 已修复**,2026-07-28 交接声称修复过,复核证明并未落地 —— 一切以复核验证为准。

### B4. 服务器列表软删除过滤(M4)

- **现象**:怀疑 `GET /api/servers` 是否过滤 `deleted=1` 记录。
- **验证/修复**:J3 验证列表查询补 `deleted=0` 条件;删除后详情/状态/更新/SSH test 全部返回 40400;DB 行保留(`deleted=1` + `delete_token` 非空)。
- **教训**:软删除要全链路生效:list/get/status/update/ssh-test 所有入口统一过滤。

### B5. PUT 存在性检查顺序(B2)

- **现象**:`PUT /api/servers/99999`(不存在)返回 40002 而非 40400;GET 404 正确。
- **根因**:`@Valid @RequestBody` Bean Validation 在存在性检查前执行,`MethodArgumentNotValidException` 被映射为 40002。
- **解决**:Controller 先 `ServerService.existsActive()`(不存在 → 40400),再用 Jakarta Validator 校验 body。
- **教训**:参数校验与资源存在性校验的执行顺序也是契约的一部分。

### B6. 排序参数被忽略(M4,J2)

- **现象**:7 种排序参数组合返回完全相同顺序,后端完全忽略 `sort_by/sort_order`。
- **根因**:`ServerController.list` 未读取排序参数。
- **解决**:白名单 6 字段 + 默认 `id desc` + 非法值 40002;XML `<choose>` 固定分支拼 ORDER BY(防 SQL 注入);相同主排序值用同方向 id 稳定次级排序。
- **教训**:排序字段必须白名单 + 服务端固定分支,永远不直接拼接用户输入。

### B7. SSH test 错误码笼统(50002/50003)

- **现象**:SSH test 对所有场景(含不存在的 ID)都返回 50000,无法区分连接失败/认证失败/超时。
- **解决**:sshj `UserAuthException` 单独映射 `50003 AUTHENTICATION_FAILED`(HTTP 502),连接阶段异常映射 `50002 CONNECTION_FAILED`;补存在性检查。验收:独立 SSHD 127.0.0.1:2223 + 故意错误密码 → 真实 50003;先登记指纹再停 SSHD → 真实 50002。
- **教训**:错误分类要跟真实 SSH 阶段(握手/认证)对齐,用受控 SSHD 做真实验收,不能只靠模拟。

---

## 类别 C:前端与环境问题(明文 HTTP 部署的雷区)

### C1. 登录点击无反应 —— crypto.randomUUID 非安全上下文

- **现象**:浏览器访问 `http://82.156.245.102` 登录页,点登录:不跳转、console 无任何输出、network 无任何请求。
- **排查**:排除表单校验/浏览器缓存后,查 nginx access log 发现浏览器发了 **0 次** POST /api/auth/login(只有 curl 的 3 次)→ 请求在客户端发出前被阻断;grep 部署的 main bundle 发现 `function BO(){return crypto.randomUUID()}` **无兜底**。
- **根因**:明文 HTTP 是非安全上下文,`window.crypto.randomUUID` 是 `undefined`;`api/client.ts` 拦截器里 `newCorrelationId()` 直接调用抛 TypeError,异常在 axios 拦截器内同步抛出 → 请求 dispatch 前被 reject,network 无记录,异常被 axios 内部捕获不冒泡。
- **解决**:`newCorrelationId()` 加 `typeof crypto.randomUUID === 'function'` 守卫 + `Date.now()-random` 降级;websocket.ts 两处裸调用改复用。
- **教训**:① 明文 HTTP 部署是安全上下文 API 雷区(`crypto.randomUUID`/`crypto.subtle`/`navigator.clipboard`);② "无请求+无报错"优先查**请求拦截器同步代码**;③ nginx access log 是判断"请求是否发出"的铁证。

### C2. Web 终端黑框 —— terminal.open 的 message_id 非 UUID(与 C1 同根因复发)

- **现象**:/ws/monitor 已连上,终端黑框无光标,输入无反应,一直"建链中"。
- **排查弯路**:tcpdump 反复抓 12/20/30/40/60 秒**抓不到 terminal.open 帧**(浏览器 WS 默认 permessage-deflate 压缩,抓包是二进制,grep 文本匹配不到 —— 严重误导);最后用 DevTools Network → Messages 直接看 WS 帧突破:发出 `terminal.open` message_id=`1785407006966-e8ea7aa4ae2bc`(非 UUID),收到 `error{code:40003}`。
- **根因**:`terminal-ws.ts` 的 wrapFrame 在 randomUUID 不可用时降级为 `Date.now()-Math.random()` —— 不是 UUID 格式;后端 `TerminalProtocolValidator.isUuid()` 拒绝 → 40003。**上次修了 api/client.ts 漏了 terminal-ws.ts**。另根因 2:`t.open()` 后立即 `f.fit()`,容器布局未完成宽度≈0 → cols=2。
- **解决**:`newTerminalMessageId()` 优先 randomUUID,降级用 `crypto.getRandomValues` 手动设置 version/variant 位生成**标准 UUID v4**;onSocketReady 时重新 fit。
- **教训**:① tcpdump 抓 WS 不可靠,优先 DevTools Messages;② 同一根因(非安全上下文 API)会多处复发,**修一处必须全局 grep 同类调用点**。

### C3. 前端分页修复(两个根因)

- **现象**:ServerListView 翻页无效果。
- **根因**:`buildQuery()` 写死 `page: 1` 从不读 `page.value` + `@update:page` 只赋 page 不调 reload —— 两个独立 bug。
- **解决**:buildQuery 读 page.value;update:page 事件里赋完值调 reload。
- **教训**:翻页类交互要"状态 + 事件"双链路核对。

### C4. 前端构建失败:类型文件含运行时 const

- **现象**:云端部署 `npm run build` 报错。
- **根因**:`src/types/terminal.d.ts` 里写了运行时 const 常量,vite 打包时当作模块解析失败。
- **解决**:补 `terminal.ts` 镜像常量,删 .d.ts。
- **教训**:`.d.ts` 只放类型声明,常量放 `.ts`。

### C5. 采集时间显示 UTC(MetricsView 没调 formatDateTime)

- **现象**:前端指标时间显示 UTC 而非北京时间。
- **解决**:`formatDateTime` 用 `new Date(iso)` + 本地时间方法显示;全站展示层本地化、内部 UTC 的原则落地。
- **教训**:时间口径约定(内部 UTC、展示本地化)要写进规范并逐页检查。

### C6. M6 实时监控页:parseId 读错路由参数

- **现象**:监控页订阅的 serverId 错误。
- **根因**:`parseId` 读 `route.params.id` 应为 `serverId`(路由参数名不一致)。
- **解决**:统一路由参数名。

### C7. 前端告警 store 吞错(规则操作永远显示通用失败)

- **现象**:AlertRuleDialog 无论 40900 还是 40400 都只显示"规则创建失败"。
- **根因**:stores/alerts.ts 三个 action 在 catch 里设置 rulesError 后返回 null/false,**吞掉了 ApiBusinessError**,dialog 的 outer catch 永远收不到。
- **解决**:设置 rulesError 后**重抛原错误**;返回类型收紧;补 3 个失败重抛回归用例。
- **教训**:错误处理链上任何一层"吞错"都会让用户看到无意义提示 —— catch 之后要么处理要么重抛,不能静默消化。

---

## 类别 D:网络层问题(最精彩的排查)

### D1. 宽带运营商劫持 WebSocket(网络层,非代码 bug)

- **现象**:宽带网络下 `/ws/monitor` 连接 failed;同一浏览器**手机热点下正常(101)**;换 IP 仍失败;普通 HTTP 正常。
- **证据链**(四组对照):
  1. 云服务器本地 curl 带 Upgrade → 401(头保留,握手通过);
  2. 浏览器(宽带)→ 400(`invalid Upgrade header: null`);
  3. tcpdump 抓浏览器原始包:请求**缺 `Upgrade: websocket` 头**(Chrome 一定会发);
  4. `curl http://IP/` 返回的 index.html 被注入 `<script src="//ij.so9.cc/j/?t=fx...">` 广告脚本 + 请求带 WAF cookie → **运营商 HTTP 劫持铁证**。
- **根因**:运营商劫持明文 HTTP:注入脚本 + 对 WS 升级请求**剥掉 `Upgrade: websocket` 头**,后端收到 Upgrade=null 握手失败 400。curl 与 Go Agent 的请求特征不触发运营商的 WS 拦截。
- **解决**:非代码修复 —— 彻底方案 = **HTTPS + 域名 + WSS**(加密后无法劫持);临时 = 手机热点/换线路;可投诉运营商。
- **教训**:"curl 能连、浏览器不能连"指向网络中间层;"同一浏览器换网络就好"基本坐实中间人;运营商注入脚本是劫持铁证。

---

## 类别 E:时间与时区问题

### E1. Outbox 退避永不生效 —— MySQL 会话时区偏差(commit `519211d`)

- **现象**:outbox 发布失败后 `next_attempt_at` 永远在未来,退避重试永不算到期,消息卡死 pending。
- **根因**:应用时钟 UTC,但 MySQL 会话时区东八区;`NOW()` 返回的是东八区时间,与写入的 UTC 时间戳比较出现 **8 小时偏差**,`next_attempt_at <= NOW()` 恒为 false。
- **解决**:所有 SQL 时间比较改用 `UTC_TIMESTAMP()`;项目规范禁止再使用 `NOW()`。
- **教训**:MySQL 会话时区与业务时钟不一致是隐蔽杀手;比较型时间逻辑必须显式统一时区。

### E2. 验收脚本秒级 collected_at 乱序保护

- **现象**:验收脚本上报间隔 <1s 时触发后端乱序拒绝(40002),脚本误报失败。
- **解决**:脚本时间戳递增修复(毫秒级)。
- **教训**:测试工具自身也要满足被测系统的契约(严格递增)。

### E3. Spring 6.2 拒绝 @TransactionalEventListener + 默认 @Transactional 组合

- **现象**:告警评估器组合注解启动报错。
- **解决**:告警评估器改 `REQUIRES_NEW`(独立事务),确保 AFTER_COMMIT 语义正确。
- **教训**:框架新版本对注解组合的约束变化,升级要回归检查。

---

## 类别 F:构建与工具链

### F1. Flyway checksum mismatch

- **现象**:验证库迁移报 checksum 不匹配。
- **根因**:`target/classes` 残留修改过的 V2 迁移脚本(未 clean 打包)。
- **解决**:`./mvnw clean package` 清掉残留;规范:Flyway 变动后必须 clean package。
- **教训**:增量编译产物会污染 Flyway 校验,构建必须 clean。

### F2. PowerShell 5.1 ANSI/GBK 编码陷阱

- **现象**:验收脚本在 PowerShell 5.1 下输出乱码/行为异常。
- **解决**:脚本改纯 ASCII + `if/elseif` + 委托 `.cmd` 执行。
- **教训**:Windows 下脚本编码与执行器差异要提前规避。

### F3. MANUAL ack 滞留坑(MVP-11 验收发现)

- **现象**:消费侧用 MANUAL 确认 + afterCommit 提交,真实 broker 下队列堆积(消息已消费但 ack 未发出)。
- **解决**:改 **AUTO 确认** + 自定义容器工厂 —— 业务事务在监听方法内提交并返回后容器才 ACK,等价"业务事务成功才确认",队列不再堆积。
- **教训**:消息确认模式必须真实验收,理论方案(MANUAL+afterCommit)在真实容器回调时序下会滞留。

---

## 面试如何讲"最大困难"(推荐话术)

**推荐素材 D1(运营商劫持)** —— 最能体现排查能力:
> "最难的是公网部署后浏览器 WebSocket 连不上,但 curl 和 Go Agent 都正常。我做了四组对照实验:服务器本地 curl 握手成功、宽带下浏览器请求缺失 Upgrade 头、手机热点立即恢复、以及 HTTP 页面被注入广告脚本 —— 最终用证据链定位到是运营商对明文 HTTP 的劫持行为,剥掉了 WebSocket 的 Upgrade 头。这让我深刻理解了:真实网络环境的问题往往不在代码层,定位手段(对照实验 + 抓包 + 访问日志)比猜测重要得多。"

**备选素材 B3(告警状态机)** —— 最能体现系统思维:
> "告警恢复后不再触发,是状态机实现与设计脱节:恢复应该删除状态行而不是置 inactive,导致状态机落入 NoAction 死区。修复后我补了'恢复后再触发'的跨状态回归用例,并立了规矩:凡是交接文档声称已修复的问题,必须以复核验证为准。"

**备选素材 A1(MyBatis Mapper 500)** —— 最能体现测试认知:
> "单测全绿但生产 500,根因是 MyBatis 参数绑定错误,而所有单测都 mock 掉了 Mapper。这让我建立了'真实数据库验收不可替代'的认知,后续每个里程碑都配隔离库真实验收脚本。"
