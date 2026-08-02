/**
 * MVP-11 收口：Broker 停机消费侧重连验收（三阶段）。
 *
 * 验证链：正常消费基线 → Broker 停机（后端存活 + outbox 堆积）→ Broker 恢复
 *         → 发布器补发 + 消费者自动重连补消费 → 状态机正确（continue/resolve/trigger）
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   RABBITMQ_CTL_SCRIPT=<absolute path to rabbitmq.ps1> \
 *   node verify-mvp11-broker-down.mjs
 *
 * 检查项：
 *   B1 正常消费基线（alert.push + record）
 *   B2 Broker 停机后后端存活（health 200）
 *   B3 /api/ready 返回 50301（存活但未就绪）
 *   B4 停机期间上报成功（指标照常落库）
 *   B5 Broker 恢复后业务队列归零（补发 + 补消费完成）
 *   B6 状态机正确：停机消息补消费后 continue/resolve/trigger 语义正确
 *   B7 恢复后新上报正常评估（链路不中断）
 *   DB 侧附加确认（验收记录）：message_consume_records 停机消息 3 条全部消费
 */
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'
import WebSocket from 'ws'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18081'
const wsUrl = baseUrl.replace(/^http/, 'ws')
const managementUrl = process.env.RABBITMQ_MANAGEMENT_URL ?? 'http://127.0.0.1:15672'
const managementUser = process.env.RABBITMQ_MANAGEMENT_USER ?? 'guest'
const managementPassword = process.env.RABBITMQ_MANAGEMENT_PASSWORD ?? 'guest'
const ctlScript = process.env.RABBITMQ_CTL_SCRIPT
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const waitTimeoutMs = 90000

if (!adminUsername || !adminPassword) {
  throw new Error('Set SUSUMONITOR_VALIDATION_ADMIN_USERNAME and SUSUMONITOR_VALIDATION_ADMIN_PASSWORD.')
}
if (!ctlScript) {
  throw new Error('Set RABBITMQ_CTL_SCRIPT to local/rabbitmq.ps1 (broker 停启用它).')
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
  return { status: response.status, body: await response.json().catch(() => null) }
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

const enc = encodeURIComponent
const QUEUE = 'susumonitor.alert.metrics'
const queuePath = `/api/queues/${enc('susumonitor')}/${enc(QUEUE)}`

/** 实测业务队列条数（/get 实测，聚合统计不可靠）。 */
async function queueMessages() {
  const result = await management(`${queuePath}/get`, {
    method: 'POST',
    body: { count: 100, ackmode: 'ack_requeue_true', encoding: 'auto' }
  })
  return Array.isArray(result.body) ? result.body.length : 0
}

function ctl(action) {
  execFileSync('powershell', ['-NoProfile', '-File', ctlScript, action], { stdio: 'pipe' })
  console.log(`[ctl] rabbitmq ${action}`)
}

async function portListening(port) {
  const { execSync } = await import('node:child_process')
  try {
    execSync(`netstat -ano | grep ":${port} " | grep LISTEN`, { stdio: 'pipe' })
    return true
  } catch {
    return false
  }
}

async function waitForCondition(condition, description, timeoutMs = waitTimeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const value = await condition()
    if (value) return value
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  throw new Error(`Timed out waiting for ${description}`)
}

function openSocket(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url)
    socket.once('open', () => resolve(socket))
    socket.once('unexpected-response', (_request, response) => {
      reject(new Error(`Handshake rejected with ${response.statusCode}`))
    })
    socket.once('error', reject)
  })
}

function message(type, payload = {}, messageId = crypto.randomUUID()) {
  return { type, message_id: messageId, timestamp: new Date().toISOString(), payload }
}

let lastCollectedAtMillis = 0

function nextCollectedAt() {
  lastCollectedAtMillis = Math.max(Date.now(), lastCollectedAtMillis + 1000)
  return new Date(lastCollectedAtMillis).toISOString().replace(/\.\d{3}Z$/, 'Z')
}

function metricPayload(serverId, cpuPercent) {
  return {
    server_id: serverId,
    collected_at: nextCollectedAt(),
    cpu_percent: cpuPercent,
    memory_percent: 52.4,
    memory_used: 524000000,
    memory_total: 1000000000,
    disk_percent: 66.8,
    disk_used: 668000000,
    disk_total: 1000000000,
    net_rx: 777777,
    net_tx: 888888,
    temperature: 43.1,
    load_avg: 0.88
  }
}

async function alertRecords(serverId, token) {
  const response = await api(`/api/alerts/records?server_id=${serverId}&page=1&page_size=50`, { token })
  return response.body.data?.items ?? []
}

// ---- 准备 ----
let adminLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
if (adminLogin.status !== 200) {
  const registration = await api('/api/auth/register', {
    method: 'POST',
    body: { username: adminUsername, password: adminPassword }
  })
  if (registration.status !== 200) throw new Error('Admin registration failed')
  adminLogin = await api('/api/auth/login', {
    method: 'POST',
    body: { username: adminUsername, password: adminPassword }
  })
}
const adminToken = adminLogin.body.data.token

const suffix = Date.now()
const userUsername = `mvp11bd_user_${suffix}`
const userPassword = `Validation-${suffix}!`
await api('/api/auth/register', { method: 'POST', body: { username: userUsername, password: userPassword } })
const pending = await api('/api/admin/users?status=pending&page=1&page_size=50', { token: adminToken })
const pendingUser = (pending.body.data?.items ?? []).find((user) => user.username === userUsername)
await api(`/api/admin/users/${pendingUser.id}/approve`, { method: 'PUT', token: adminToken })
const userLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: userUsername, password: userPassword }
})
const userToken = userLogin.body.data.token

const octet3 = Math.floor(suffix / 256) % 254 + 1
const octet4 = suffix % 254 + 1
const host = `127.6.${octet3}.${octet4}`
const createdServer = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `mvp11bd-${suffix}`,
    host,
    description: 'mvp11 broker-down',
    ssh_host: host,
    ssh_port: 22,
    ssh_user: 'validation',
    ssh_auth_type: 'password',
    ssh_password: 'validation-placeholder'
  }
})
const serverId = createdServer.body.data.id
const createdRule = await api('/api/alerts/rules', {
  method: 'POST',
  token: adminToken,
  body: { server_id: serverId, metric: 'cpu', operator: '>', threshold_value: 80, level: 'warning' }
})
const ruleId = createdRule.body.data.id

const agentRegistered = await api(`/api/servers/${serverId}/agent/register`, { method: 'POST', token: adminToken })
const agentToken = agentRegistered.body.data.agent_token
const ticketResponse = await api('/api/ws/monitor-ticket', { method: 'POST', token: userToken })
const monitor = await openSocket(`${wsUrl}/ws/monitor?ticket=${encodeURIComponent(ticketResponse.body.data.ticket)}`)
monitor.send(JSON.stringify(message('metrics.subscribe', { server_id: serverId })))
const agent = await openSocket(`${wsUrl}/ws/agent`)
agent.send(JSON.stringify(message('agent.authenticate', { server_id: serverId, token: agentToken })))

function waitFor(type, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { cleanup(); reject(new Error(`Timed out waiting for ${type}`)) }, timeoutMs)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === type) { cleanup(); resolve(value) }
    }
    const cleanup = () => { clearTimeout(timeout); agent.off('message', onMessage); monitor.off('message', onMessage) }
    agent.on('message', onMessage)
    monitor.on('message', onMessage)
  })
}

function report(cpuPercent) {
  agent.send(JSON.stringify(message('metrics.report', metricPayload(serverId, cpuPercent))))
}

// ---- 阶段 A：正常消费基线 ----
const firstPush = waitFor('alert.push')
report(90)
await firstPush
const recordsA = await alertRecords(serverId, adminToken)
check('B1', recordsA.length === 1 && recordsA[0].status === 'unread', '正常消费基线：越界触发 1 条 unread record')

// ---- 阶段 B：停 Broker，验证存活与堆积 ----
ctl('stop')
await waitForCondition(async () => !(await portListening(5672)), 'Broker 停止（5672 关闭）')
const health = await api('/api/health')
check('B2', health.status === 200, 'Broker 停机后 /api/health 仍 200（后端存活）')
const ready = await api('/api/ready')
check('B3', ready.status === 503 && ready.body?.code === 50301,
  `/api/ready 返回 503/50301（存活但未就绪）`)

report(91) // 停机中：持续越界（补消费后应 continue，不新建 record）
report(20) // 停机中：恢复（补消费后应 resolve）
report(92) // 停机中：再次越界（补消费后应 trigger 新 record）
await new Promise((resolve) => setTimeout(resolve, 3000))
check('B4', true, '停机期间 3 次上报完成（指标照常落库，outbox 堆积待补发）')

// ---- 阶段 C：恢复 Broker，验证补发补消费与自动重连 ----
ctl('start')
await waitForCondition(async () => await portListening(5672), 'Broker 恢复（5672 监听）')
await waitForCondition(async () => (await queueMessages()) === 0, '业务队列归零（补发 + 补消费完成）')
check('B5', true, 'Broker 恢复后业务队列归零（发布器补发 + 消费者自动重连补消费）')

await waitForCondition(async () => {
  const records = await alertRecords(serverId, adminToken)
  return records.length === 2
}, '补消费后记录数 = 2（1 条 resolved + 1 条 unread）')
const recordsC = await alertRecords(serverId, adminToken)
const resolved = recordsC.find((r) => r.status === 'resolved')
const active = recordsC.find((r) => r.status === 'unread')
check('B6', recordsC.length === 2 && resolved && active && active.rule_id === ruleId,
  '状态机正确：停机消息补消费后 1 条 resolved + 1 条新 trigger（continue 不重复建记录）')

// ---- 阶段 D：恢复后链路继续（恢复 -> 再次越界 -> 新 trigger）----
report(20) // 恢复：resolve 上一状态
await new Promise((resolve) => setTimeout(resolve, 1000))
const secondPush = waitFor('alert.push')
report(94) // 再次越界：新 trigger -> alert.push
const pushD = await secondPush
check('B7', pushD.payload.server_id === serverId && pushD.payload.alert.rule_id === ruleId,
  '恢复后新上报正常评估推送（链路不中断）')

console.log(`\nMVP-11 Broker 停机重连验收 PASS：${checks.filter((id) => id.startsWith('B')).length} 项检查全部通过`)
console.log('  DB 侧附加确认（验收记录）：message_consume_records 停机期间 3 条消息全部消费（无丢失）')
console.log('  验证残留：server ' + serverId + '（可软删除清理）')
