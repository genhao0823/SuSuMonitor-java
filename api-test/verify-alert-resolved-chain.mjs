/**
 * alert.resolved.v1 全链路验收脚本：发布 → 消费 → 恢复通知排程 → 幂等 → DLQ 分类。
 *
 * 验证链（不经 Agent WS / 评估器，直接投递 alert.resolved.v1 信封聚焦消费侧，与
 * verify-alert-triggered-chain.mjs 对称；发布侧由评估器单测 + V25 发布机制覆盖）：
 *   管理 API publish → susumonitor.events -- alert.resolved.v1 --> susumonitor.alert.resolved
 *   → AlertResolvedConsumer（alert-resolved-notifier：幂等 + 排程恢复通知 + 同事务记录）
 *   → alert_notifications pending 行（消息驱动"恢复通知"）
 *
 * 用法（本机约定：后端 18081 / 管理台 15672 / MySQL 凭据经 SUSUMONITOR_VALIDATION_MYSQL_PASSWORD 提供）：
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   node verify-alert-resolved-chain.mjs
 *
 * 检查项：
 *   P0 准备：管理员登录、服务器、告警规则（cpu>80 + webhook 渠道）、resolved 记录
 *   C1 正常消费：合法信封 → 业务队列归零 + alert_notifications 出现 pending 行
 *   C2 重复投递幂等：同 event_id 重投 → 通知行数不变（无第二次排程）
 *   C3 DLQ 分类：非法 JSON / schema_version=2 / 非法 metric → alert.resolved.dlq 增量 ≥3
 *   C4 失败留痕：message_consume_records 存在 failed 行（consumer=alert-resolved-notifier）
 */
import crypto from 'node:crypto'
import { execSync } from 'node:child_process'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18081'
const managementUrl = process.env.RABBITMQ_MANAGEMENT_URL ?? 'http://127.0.0.1:15672'
const managementUser = process.env.RABBITMQ_MANAGEMENT_USER ?? 'guest'
const managementPassword = process.env.RABBITMQ_MANAGEMENT_PASSWORD ?? 'guest'
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const mysqlUser = process.env.SUSUMONITOR_VALIDATION_MYSQL_USER ?? 'susumonitor'
const mysqlPassword = process.env.SUSUMONITOR_VALIDATION_MYSQL_PASSWORD
const mysqlDb = process.env.SUSUMONITOR_VALIDATION_MYSQL_DB ?? 'susumonitor'
const waitTimeoutMs = 20000
const validationPrefix = 'rmq_resolved_alert'

if (!adminUsername || !adminPassword || !mysqlPassword) {
  throw new Error('Set SUSUMONITOR_VALIDATION_ADMIN_USERNAME/PASSWORD and SUSUMONITOR_VALIDATION_MYSQL_PASSWORD.')
}

const checks = []
function check(id, condition, description) {
  if (!condition) throw new Error(`${id} FAILED: ${description}`)
  checks.push(id)
  console.log(`✓ ${id} ${description}`)
}

async function api(path, { method = 'GET', body, token } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  return { status: response.status, body: await response.json() }
}

async function management(path, { method = 'GET', body } = {}) {
  const response = await fetch(`${managementUrl}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      authorization: 'Basic ' + Buffer.from(`${managementUser}:${managementPassword}`).toString('base64')
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  return { status: response.status, body: await response.json().catch(() => null) }
}

/** 执行 MySQL 语句，返回输出文本（多语句时取最后一条的结果行）。 */
function mysqlExec(sql) {
  // Windows execSync 走 cmd.exe：SQL 内的换行会导致引号解析失败，先规整为单行。
  const output = execSync(
    `mysql -h 127.0.0.1 -u ${mysqlUser} -p${mysqlPassword} -N -B -e "${sql.replace(/\n/g, ' ')}" ${mysqlDb}`,
    { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'ignore'] }
  ).trim()
  return output
}

/** 执行 MySQL 查询，返回最后一行非空输出（string | null）。 */
function mysqlScalar(query) {
  const lines = mysqlExec(query).split('\n').filter((line) => line.trim() !== '')
  return lines.length === 0 ? null : lines[lines.length - 1].trim()
}

const VHOST = 'susumonitor'
const EXCHANGE = 'susumonitor.events'
const QUEUE = 'susumonitor.alert.resolved'
const QUEUE_DLQ = 'susumonitor.alert.resolved.dlq'
const ROUTING_KEY = 'alert.resolved.v1'

async function publishEnvelope(envelopeJson) {
  return management(`/api/exchanges/${encodeURIComponent(VHOST)}/${encodeURIComponent(EXCHANGE)}/publish`, {
    method: 'POST',
    body: { properties: {}, routing_key: ROUTING_KEY, payload: envelopeJson, payload_encoding: 'string' }
  })
}

/** 实测队列消息数：/get 走 ack_requeue_true（聚合统计滞后不可靠）。 */
async function queueMessages(queue) {
  const result = await management(`/api/queues/${encodeURIComponent(VHOST)}/${encodeURIComponent(queue)}/get`, {
    method: 'POST',
    body: { count: 100, ackmode: 'ack_requeue_true', encoding: 'auto' }
  })
  return Array.isArray(result.body) ? result.body.length : 0
}

async function waitForCondition(condition, description, timeoutMs = waitTimeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const value = await condition()
    if (value) return value
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`Timed out waiting for ${description}`)
}

/** 构造符合冻结契约 §五 的 alert.resolved.v1 信封（8 字段，不含触发值）。 */
function envelope(eventId, ruleId, serverId, recordId, metric = 'cpu') {
  const payload = {
    server_id: serverId,
    rule_id: ruleId,
    record_id: recordId,
    metric,
    level: 'warning',
    status: 'resolved',
    triggered_at: new Date(Date.now() - 3600_000).toISOString().replace(/\.\d{3}Z$/, 'Z'),
    resolved_at: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
  }
  return JSON.stringify({
    event_id: eventId,
    event_type: 'alert.resolved',
    schema_version: 1,
    occurred_at: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    producer: 'alert-service',
    payload
  })
}

// ---- P0 准备：管理员 + 服务器 + 告警规则（含 webhook 渠道） + resolved 记录 ----
let adminLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
if (adminLogin.status !== 200) {
  const registration = await api('/api/auth/register', {
    method: 'POST',
    body: { username: adminUsername, password: adminPassword }
  })
  if (registration.status !== 200) throw new Error(`Admin registration failed (${registration.status})`)
  console.log('⚠ 已注册新管理员（隔离验证库）')
  adminLogin = await api('/api/auth/login', {
    method: 'POST',
    body: { username: adminUsername, password: adminPassword }
  })
}
if (adminLogin.status !== 200) throw new Error('Admin login failed')
const adminToken = adminLogin.body.data.token

const suffix = Date.now()
const host = `127.9.${Math.floor(suffix / 256) % 254 + 1}.${suffix % 254 + 1}`
const createdServer = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `${validationPrefix}-${suffix}`,
    host,
    ssh_host: host,
    ssh_port: 22,
    ssh_user: 'validation',
    ssh_auth_type: 'password',
    ssh_password: 'validation-placeholder'
  }
})
check('P0', createdServer.status === 200, '验证服务器创建')
const serverId = createdServer.body.data.id

const createdRule = await api('/api/alerts/rules', {
  method: 'POST',
  token: adminToken,
  body: {
    server_id: serverId,
    metric: 'cpu',
    operator: '>',
    threshold_value: 80,
    level: 'warning',
    // 不可达 webhook：发送失败记 pending 不影响排程断言（通知行仍会插入）。
    notify_webhook: `http://127.0.0.1:1/webhook/${suffix}`
  }
})
check('P0', createdRule.status === 200, '告警规则创建（带 webhook 渠道）')
const ruleId = createdRule.body.data.id

// 真实链路中 record 由评估器先恢复（status=resolved + resolved_at）再发布事件；这里模拟该前提。
// （独立连接分两步执行：INSERT 后用唯一标记定位 id，避免多语句引号解析问题。）
const recordToken = `rmq-resolved-${suffix}`
mysqlExec(
  `INSERT INTO alert_records (rule_id, server_id, metric, current_value, threshold_value, level, status, message, triggered_at, resolved_at)
    VALUES (${ruleId}, ${serverId}, 'cpu', 92.5, 80.0, 'warning', 'resolved', '${recordToken}',
      UTC_TIMESTAMP() - INTERVAL 1 HOUR, UTC_TIMESTAMP())`)
const recordIdC1 = mysqlScalar(`SELECT id FROM alert_records WHERE message='${recordToken}' LIMIT 1`)
check('P0', recordIdC1 !== null && Number(recordIdC1) > 0, `resolved 告警记录插入（record_id=${recordIdC1}）`)

// ---- C1 正常消费：发布合法信封 → 队列归零 + 恢复通知行出现 ----
const eventIdC1 = crypto.randomUUID()
const published = await publishEnvelope(envelope(eventIdC1, ruleId, serverId, recordIdC1))
check('C1', published.status === 200 && published.body?.routed === true, '合法 envelope 发布 routed=true')

await waitForCondition(async () => (await queueMessages(QUEUE)) === 0, 'susumonitor.alert.resolved 队列归零')
check('C1', true, '业务队列消费归零')

const notificationsC1 = await waitForCondition(async () => {
  const count = mysqlScalar(`SELECT COUNT(*) FROM alert_notifications WHERE alert_record_id=${recordIdC1}`)
  return count !== null && Number(count) >= 1 ? Number(count) : null
}, 'alert_notifications 出现排程行')
check('C1', notificationsC1 >= 1, `消费驱动恢复通知排程落库（${notificationsC1} 行）`)

const consumeRowC1 = await waitForCondition(async () => {
  const row = mysqlScalar(
    `SELECT status FROM message_consume_records WHERE consumer='alert-resolved-notifier' AND event_id='${eventIdC1}'`)
  return row ?? null
}, 'alert-resolved-notifier 幂等记录落库')
check('C1', consumeRowC1 === 'consumed', `消费幂等记录 status=consumed`)

// ---- C2 重复投递幂等：同 event_id 重投 → 通知行数不变 ----
const republished = await publishEnvelope(envelope(eventIdC1, ruleId, serverId, recordIdC1))
check('C2', republished.status === 200 && republished.body?.routed === true, '同 event_id 重投 routed=true')

// 静默窗口：等待重投被消费并确保无第二次排程。
await new Promise((resolve) => setTimeout(resolve, 3000))
const notificationsC2 = Number(
  mysqlScalar(`SELECT COUNT(*) FROM alert_notifications WHERE alert_record_id=${recordIdC1}`))
check('C2', notificationsC2 === notificationsC1,
  `幂等命中：重投后通知行数不变（${notificationsC1} → ${notificationsC2}）`)

// ---- C3 DLQ 分类：三条坏消息 → DLQ 增量 ≥3 ----
const dlqBefore = await queueMessages(QUEUE_DLQ)
await publishEnvelope('not-a-json{{')
// JSON.stringify 输出无空格键值，替换模式必须匹配 "schema_version":1。
await publishEnvelope(envelope(crypto.randomUUID(), ruleId, serverId, 900000002).replace('"schema_version":1', '"schema_version":2'))
await publishEnvelope(envelope(crypto.randomUUID(), ruleId, serverId, 900000003, 'gpu'))
const dlqAfter = await waitForCondition(async () => {
  const count = await queueMessages(QUEUE_DLQ)
  return count >= dlqBefore + 3 ? count : null
}, 'alert.resolved.dlq 增量 ≥3')
check('C3', dlqAfter >= dlqBefore + 3, `三条坏消息全部进 DLQ（${dlqBefore} → ${dlqAfter}）`)

// ---- C4 失败留痕：alert-resolved-notifier 存在 failed 行 ----
const failedRows = await waitForCondition(async () => {
  const row = mysqlScalar(
    `SELECT COUNT(*) FROM message_consume_records WHERE consumer='alert-resolved-notifier' AND status='failed'`)
  return row !== null && Number(row) >= 1 ? Number(row) : null
}, 'alert-resolved-notifier failed 留痕行')
check('C4', failedRows >= 1, `失败留痕落库（failed 行=${failedRows}）`)

console.log(JSON.stringify({
  status: 'PASS',
  checks: checks.length,
  queue: QUEUE,
  dlq: QUEUE_DLQ,
  notifications_rows: notificationsC2,
  token_values_logged: false
}))
console.log('\nDB 人工确认指引：')
console.log(`  SELECT event_id,status FROM message_consume_records WHERE consumer='alert-resolved-notifier' ORDER BY id DESC LIMIT 6;`)
console.log(`  SELECT alert_record_id,channel,status FROM alert_notifications WHERE alert_record_id IN (${recordIdC1});`)
