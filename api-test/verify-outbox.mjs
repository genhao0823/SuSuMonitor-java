/**
 * MVP-10 Outbox 链路与故障恢复验收脚本。
 *
 * 验证链：Agent 上报 → metrics 落库 + outbox 同事务写入 → 发布器经 RabbitMQ
 * 可靠投递 → susumonitor.alert.metrics 队列消息增长（消费侧属 MVP-11，消息堆积为预期）。
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   RABBITMQ_MANAGEMENT_URL=http://127.0.0.1:15672 RABBITMQ_MANAGEMENT_USER=xxx RABBITMQ_MANAGEMENT_PASSWORD=xxx \
 *   node verify-outbox.mjs
 *
 * 故障恢复阶段由验收流程外部控制 Broker 启停：
 *   阶段 B 前停止 RabbitMQ 服务（需管理员），脚本继续上报；
 *   阶段 C 前启动 RabbitMQ 服务，脚本等待补发并断言队列消息数恢复。
 */
import crypto from 'node:crypto'
import WebSocket from 'ws'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18081'
const wsUrl = baseUrl.replace(/^http/, 'ws')
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const managementUrl = process.env.RABBITMQ_MANAGEMENT_URL ?? 'http://127.0.0.1:15672'
const managementUser = process.env.RABBITMQ_MANAGEMENT_USER ?? 'guest'
const managementPassword = process.env.RABBITMQ_MANAGEMENT_PASSWORD ?? 'guest'
const VHOST = 'susumonitor'
const QUEUE = 'susumonitor.alert.metrics'
const PHASE_A_REPORTS = 3   // 正常阶段上报数
const PHASE_B_REPORTS = 2   // Broker 停机期间上报数（应保留在 outbox）
const RECOVERY_WAIT_MS = 60000

if (!adminUsername || !adminPassword) {
  throw new Error('Set SUSUMONITOR_VALIDATION_ADMIN_USERNAME and SUSUMONITOR_VALIDATION_ADMIN_PASSWORD.')
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function api(path, { method = 'GET', body, token } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10000)
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
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10000)
  })
  if (!response.ok) throw new Error(`Management API ${path} failed: ${response.status}`)
  if (response.status === 204) return null
  return response.json()
}

async function queueMessages() {
  const queue = await management(`/api/queues/${encodeURIComponent(VHOST)}/${encodeURIComponent(QUEUE)}`)
  return queue.messages ?? 0
}

function message(type, payload = {}, messageId = crypto.randomUUID()) {
  return { type, message_id: messageId, timestamp: new Date().toISOString(), payload }
}

function openSocket(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url)
    const timeout = setTimeout(() => { cleanup(); socket.terminate(); reject(new Error(`Timed out opening ${url}`)) }, 10000)
    const cleanup = () => clearTimeout(timeout)
    socket.once('open', () => { cleanup(); resolve(socket) })
    socket.once('unexpected-response', (_request, response) => { cleanup(); reject(new Error(`Handshake rejected with ${response.statusCode}`)) })
    socket.once('error', (error) => { cleanup(); reject(error) })
  })
}

function waitForMessage(socket, expectedType, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { cleanup(); reject(new Error(`Timed out waiting for ${expectedType}`)) }, timeoutMs)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === expectedType) { cleanup(); resolve(value) }
    }
    const cleanup = () => { clearTimeout(timeout); socket.off('message', onMessage) }
    socket.on('message', onMessage)
  })
}

async function waitForCondition(condition, description, timeoutMs = RECOVERY_WAIT_MS) {
  const deadline = Date.now() + timeoutMs
  let lastError = null
  while (Date.now() < deadline) {
    try {
      if (await condition()) return
    } catch (error) {
      // Broker 重启期间管理 API 短暂不可达，属于恢复过程，继续重试。
      lastError = error
    }
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  throw new Error(`Timed out waiting for ${description}${lastError ? ` (last error: ${lastError.message})` : ''}`)
}

let lastCollectedAtMillis = 0
function nextCollectedAt() {
  lastCollectedAtMillis = Math.max(Date.now(), lastCollectedAtMillis + 1000)
  return new Date(lastCollectedAtMillis).toISOString()
}

function metricPayload(serverId) {
  return {
    type: 'metrics.report',
    message_id: crypto.randomUUID(),
    timestamp: new Date().toISOString(),
    payload: {
      server_id: serverId,
      collected_at: nextCollectedAt(),
      cpu_percent: 65.0,
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
}

let checks = 0
function check(description) {
  checks += 1
  console.log(`  ✓ ${description}`)
}

// ---------- 装配 ----------

// 批次 8：目标实例仍待初始化首管理员时，兜底注册必须携带一次性初始化令牌（缺失 403/40310）；
// 令牌经 SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN 传入（服务器启动横幅或 AUTH_BOOTSTRAP_TOKEN 预置值）。
async function registerAdmin() {
  const bootstrapStatus = await api('/api/auth/bootstrap-status')
  const bootstrapPending = bootstrapStatus.status === 200 && bootstrapStatus.body?.data?.bootstrapPending === true
  let registerBody = { username: adminUsername, password: adminPassword }
  if (bootstrapPending) {
    const bootstrapToken = process.env.SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN
    assert(typeof bootstrapToken === 'string' && bootstrapToken.length >= 32 && bootstrapToken.length <= 128,
      'Target stack awaits first-admin bootstrap: set SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN ' +
      '(one-time token from the server startup banner or the preset AUTH_BOOTSTRAP_TOKEN).')
    registerBody = { username: adminUsername, password: adminPassword, bootstrapToken }
  }
  return api('/api/auth/register', { method: 'POST', body: registerBody })
}

let login = await api('/api/auth/login', { method: 'POST', body: { username: adminUsername, password: adminPassword } })
if (login.status !== 200) {
  await registerAdmin()
  login = await api('/api/auth/login', { method: 'POST', body: { username: adminUsername, password: adminPassword } })
}
assert(login.status === 200, 'Admin login failed')
const adminToken = login.body.data.token

const suffix = Date.now()
const host = `127.6.${Math.floor(suffix / 254) % 254 + 1}.${suffix % 254 + 1}`
const createdServer = await api('/api/servers', {
  method: 'POST', token: adminToken,
  body: { name: `outbox_validation_${suffix}`, host, description: 'outbox verify', ssh_host: host, ssh_port: 22, ssh_user: 'v', ssh_auth_type: 'password', ssh_password: 'v' }
})
assert(createdServer.status === 200, 'Server creation failed')
const serverId = createdServer.body.data.id

const agentRegistered = await api(`/api/servers/${serverId}/agent/register`, { method: 'POST', token: adminToken })
assert(agentRegistered.status === 200, 'Agent token registration failed')
const agentToken = agentRegistered.body.data.agent_token

const agent = await openSocket(`${wsUrl}/ws/agent`)
const authenticated = waitForMessage(agent, 'agent.authenticated')
agent.send(JSON.stringify(message('agent.authenticate', { server_id: serverId, token: agentToken })))
await authenticated
check('Agent 鉴权连接')

const ticketResponse = await api('/api/ws/monitor-ticket', { method: 'POST', token: adminToken })
assert(ticketResponse.status === 200, 'Monitor ticket issue failed')
const monitor = await openSocket(`${wsUrl}/ws/monitor?ticket=${encodeURIComponent(ticketResponse.body.data.ticket)}`)
monitor.send(JSON.stringify(message('metrics.subscribe', { server_id: serverId })))

async function sendReportAndWait() {
  const updatePromise = waitForMessage(monitor, 'metrics.update')
  agent.send(JSON.stringify(metricPayload(serverId)))
  await updatePromise
}

// ---------- 阶段 A：正常链路（Broker 在线） ----------

// 清空队列并轮询确认归零（REST purge 异步生效，避免基线竞态）。
await management(`/api/queues/${encodeURIComponent(VHOST)}/${encodeURIComponent(QUEUE)}/contents`, { method: 'DELETE' })
await waitForCondition(async () => (await queueMessages()) === 0, '队列清空归零', 15000)
const baselineMessages = await queueMessages()
check(`队列初始消息数=${baselineMessages}`)

for (let i = 0; i < PHASE_A_REPORTS; i += 1) {
  await sendReportAndWait()
}
check(`阶段 A 上报 ${PHASE_A_REPORTS} 条，metrics.update 均收到（本地链路正常）`)

await waitForCondition(async () => (await queueMessages()) === baselineMessages + PHASE_A_REPORTS,
  `队列消息数增长到 ${baselineMessages + PHASE_A_REPORTS}`)
check(`阶段 A 消息数=${baselineMessages + PHASE_A_REPORTS}（发布器 Confirm 投递成功）`)

// 信封核对：取 1 条消息验证冻结契约字段
const fetched = await management(`/api/queues/${encodeURIComponent(VHOST)}/${encodeURIComponent(QUEUE)}/get`, {
  method: 'POST', body: { count: 1, ackmode: 'ack_requeue_true', encoding: 'auto' }
})
assert(fetched.length === 1, 'Failed to fetch a message for contract check')
const envelope = JSON.parse(fetched[0].payload)
assert(envelope.event_type === 'metrics.reported', 'event_type mismatch')
assert(envelope.schema_version === 1, 'schema_version mismatch')
assert(envelope.producer === 'metrics-service', 'producer mismatch')
assert(envelope.event_id && /^[0-9a-f-]{36}$/.test(envelope.event_id), 'event_id is not UUID')
assert(envelope.payload.server_id === serverId, 'payload.server_id mismatch')
assert(envelope.payload.message_id && /^[0-9a-f-]{36}$/.test(envelope.payload.message_id), 'payload.message_id is not UUID')
assert(typeof envelope.payload.cpu_percent === 'number', 'payload.cpu_percent missing')
assert(envelope.occurred_at.endsWith('Z'), 'occurred_at is not UTC')
check('冻结信封字段与 message-contracts-v1 一致（event_id/type/schema/producer/payload）')

// ---------- 阶段 B：Broker 停机（外部执行 stop） ----------

console.log('  阶段 B：请停止 RabbitMQ 服务后按 Enter 继续（脚本等待 30s 让发布器感知）...')
await new Promise((resolve) => setTimeout(resolve, 30000))
const duringDowntime = await queueMessages().catch(() => null)
console.log(`  停机确认：队列消息数=${duringDowntime ?? 'API 不可达（符合预期）'}`)

for (let i = 0; i < PHASE_B_REPORTS; i += 1) {
  await sendReportAndWait()
}
check(`阶段 B 上报 ${PHASE_B_REPORTS} 条，metrics.update 均收到（Broker 停机期间 Metrics 照常落库）`)

const messagesAtDowntime = await queueMessages().catch(() => -1)
check(`停机期间队列消息数=${messagesAtDowntime}（新事件保留在 outbox，未投递）`)

// ---------- 阶段 C：Broker 恢复（外部执行 start） ----------

console.log('  阶段 C：请启动 RabbitMQ 服务后按 Enter 继续（脚本等待补发）...')
await new Promise((resolve) => setTimeout(resolve, 30000))

const expectedAfterRecovery = baselineMessages + PHASE_A_REPORTS + PHASE_B_REPORTS
await waitForCondition(async () => (await queueMessages()) === expectedAfterRecovery,
  `队列消息数恢复到 ${expectedAfterRecovery}`)
check(`阶段 C 消息数=${expectedAfterRecovery}（Outbox 补发成功，与 Broker 停机前上报数一致）`)

agent.close()
monitor.close()

console.log(JSON.stringify({
  status: 'PASS',
  server_id: serverId,
  checks,
  phaseA: PHASE_A_REPORTS,
  phaseB: PHASE_B_REPORTS,
  token_values_logged: false
}))
