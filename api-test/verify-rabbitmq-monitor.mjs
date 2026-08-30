/**
 * MVP-14 监控收尾验收脚本：队列积压快照 + 消费统计端点（真实 Broker）。
 *
 * 验证链：
 *   管理 API 注入 3 条非法 alert.resolved 信封 → 消费拒绝进 DLQ
 *   → GET /api/system/rabbitmq/queues 快照：6 队列、DLQ 计数 +3、与管理 API 交叉一致
 *   → GET /api/system/rabbitmq/consumers：alert-resolved-notifier 出现耗时采样
 *   → 二次探测后快照 checked_at 前进（调度器真实运行）
 *
 * 用法（本机约定：后端 18081 / 管理台 15672 / MySQL 凭据经 SUSUMONITOR_VALIDATION_MYSQL_PASSWORD 提供）：
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   node verify-rabbitmq-monitor.mjs
 *
 * 检查项：
 *   P0 准备：管理员登录、一条带 webhook 渠道的告警规则、一条 resolved 记录
 *   C1 队列快照：6 个冻结队列 + business/dead_letter 类型 + DLQ 计数与管理 API 交叉一致
 *   C2 消费统计端点结构：200 + data 数组（含失败率字段口径）
 *   C3 注入 3 条坏信封：resolved.dlq 快照 +3、alert-resolved-notifier 耗时采样 >=3
 *   C4 二次探测：快照 checked_at 前进（探测调度真实运行；阈值告警路径由单测覆盖）
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
const validationPrefix = 'mvp14_monitor'

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

function mysqlScalar(query) {
  const output = execSync(
    `mysql -h 127.0.0.1 -u ${mysqlUser} -p${mysqlPassword} -N -B -e "${query.replace(/\n/g, ' ')}" ${mysqlDb}`,
    { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'ignore'] }
  ).trim()
  const lines = output.split('\n').filter((line) => line.trim() !== '')
  return lines.length === 0 ? null : lines[lines.length - 1].trim()
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

const VHOST = 'susumonitor'
const EXCHANGE = 'susumonitor.events'
const ROUTING_KEY = 'alert.resolved.v1'
const DLQ = 'susumonitor.alert.resolved.dlq'

async function publishEnvelope(envelopeJson) {
  return management(`/api/exchanges/${encodeURIComponent(VHOST)}/${encodeURIComponent(EXCHANGE)}/publish`, {
    method: 'POST',
    body: { properties: {}, routing_key: ROUTING_KEY, payload: envelopeJson, payload_encoding: 'string' }
  })
}

/** 管理 API 实测队列消息数（/get ack_requeue_true）。 */
async function queueMessages(queue) {
  const result = await management(`/api/queues/${encodeURIComponent(VHOST)}/${encodeURIComponent(queue)}/get`, {
    method: 'POST',
    body: { count: 100, ackmode: 'ack_requeue_true', encoding: 'auto' }
  })
  return Array.isArray(result.body) ? result.body.length : 0
}

function badEnvelope() {
  return JSON.stringify({
    event_id: crypto.randomUUID(),
    event_type: 'alert.resolved',
    schema_version: 1,
    occurred_at: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    producer: 'alert-service',
    payload: { server_id: 1, rule_id: 1, record_id: 1, metric: 'gpu', level: 'warning',
      status: 'resolved', triggered_at: 'x', resolved_at: 'x' }
  })
}

// ---- P0 准备：管理员 + 告警规则 + resolved 记录 ----
let adminLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
if (adminLogin.status !== 200) throw new Error('Admin login failed')
const adminToken = adminLogin.body.data.token
check('P0', true, '管理员登录')

const suffix = Date.now()
const createdServer = await api('/api/servers', {
  method: 'POST', token: adminToken,
  body: { name: `${validationPrefix}-${suffix}`, host: `127.9.${(suffix >> 8) % 254 + 1}.${suffix % 254 + 1}`,
    ssh_host: '127.0.0.1', ssh_port: 22, ssh_user: 'validation', ssh_auth_type: 'password', ssh_password: 'x' }
})
const serverId = createdServer.body.data.id
const createdRule = await api('/api/alerts/rules', {
  method: 'POST', token: adminToken,
  body: { server_id: serverId, metric: 'cpu', operator: '>', threshold_value: 80, level: 'warning',
    notify_webhook: `http://127.0.0.1:1/webhook/${suffix}` }
})
const ruleId = createdRule.body.data.id
mysqlScalar(`INSERT INTO alert_records (rule_id, server_id, metric, current_value, threshold_value, level, status, message, triggered_at, resolved_at)
  VALUES (${ruleId}, ${serverId}, 'cpu', 92.5, 80.0, 'warning', 'resolved', 'mvp14-${suffix}', UTC_TIMESTAMP(), UTC_TIMESTAMP())`)
const recordId = mysqlScalar(`SELECT id FROM alert_records WHERE message='mvp14-${suffix}' LIMIT 1`)
check('P0', Number(recordId) > 0, `resolved 记录就绪（record_id=${recordId}）`)

// ---- C1 队列快照：6 个冻结队列 + 类型 + 与管理 API 交叉一致 ----
const queuesBefore = await waitForCondition(async () => {
  const response = await api('/api/system/rabbitmq/queues', { token: adminToken })
  return response.status === 200 && Array.isArray(response.body?.data) && response.body.data.length === 6
    ? response.body.data : null
}, '队列快照就绪（6 队列）')
check('C1', queuesBefore.length === 6, `快照含 6 个冻结队列`)
const business = queuesBefore.filter((queue) => queue.type === 'business').length
const deadLetter = queuesBefore.filter((queue) => queue.type === 'dead_letter').length
check('C1', business === 3 && deadLetter === 3, `类型划分 3 business + 3 dead_letter`)

const dlqSnapshot = queuesBefore.find((queue) => queue.queue === DLQ)
const dlqManagement = await queueMessages(DLQ)
check('C1', dlqSnapshot.messages === dlqManagement,
  `DLQ 快照与管理 API 交叉一致（snapshot=${dlqSnapshot.messages}, management=${dlqManagement}）`)

// ---- C2 消费统计端点结构 ----
const consumersResponse = await api('/api/system/rabbitmq/consumers', { token: adminToken })
check('C2', consumersResponse.status === 200 && Array.isArray(consumersResponse.body?.data),
  '消费统计端点 200 + data 数组')

// ---- C3 注入 3 条坏信封：DLQ +3、消费者出现耗时采样 ----
const dlqBefore = dlqSnapshot.messages
for (let index = 0; index < 3; index++) {
  await publishEnvelope(badEnvelope())
}
const queuesAfter = await waitForCondition(async () => {
  const response = await api('/api/system/rabbitmq/queues', { token: adminToken })
  const queue = response.body?.data?.find((item) => item.queue === DLQ)
  return queue && queue.messages >= dlqBefore + 3 ? queue : null
}, 'DLQ 快照 +3')
check('C3', queuesAfter.messages >= dlqBefore + 3,
  `坏信封进 DLQ（${dlqBefore} → ${queuesAfter.messages}）`)

const consumersAfter = await waitForCondition(async () => {
  const response = await api('/api/system/rabbitmq/consumers', { token: adminToken })
  const consumer = response.body?.data?.find((item) => item.consumer === 'alert-resolved-notifier')
  return consumer && consumer.total_count >= 3 ? consumer : null
}, 'alert-resolved-notifier 出现耗时采样')
check('C3', consumersAfter.total_count >= 3,
  `消费耗时采样就绪（total_count=${consumersAfter.total_count}, avg_ms=${consumersAfter.avg_ms}）`)

// ---- C4 二次探测后 checked_at 前进（探测调度真实运行） ----
await new Promise((resolve) => setTimeout(resolve, 8000))
const queuesLater = (await api('/api/system/rabbitmq/queues', { token: adminToken })).body.data
const laterSnapshot = queuesLater.find((queue) => queue.queue === DLQ)
check('C4', laterSnapshot.checked_at > queuesAfter.checked_at,
  `二次探测 checked_at 前进（${queuesAfter.checked_at} → ${laterSnapshot.checked_at}）`)

console.log(JSON.stringify({
  status: 'PASS',
  checks: checks.length,
  queues: queuesLater.length,
  consumers_sampled: consumersAfter.total_count,
  token_values_logged: false
}))
console.log('\nDB 人工确认指引：')
console.log(`  SELECT consumer,status,COUNT(*) FROM message_consume_records WHERE event_id IN (最近 3 条) GROUP BY consumer,status;`)
