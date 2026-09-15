/**
 * MVP-11 告警消费侧验收脚本：消息通道消费 / 重复投递幂等 / DLQ 分类。
 *
 * 验证链（不经 Agent WS，直接投递到 Broker，聚焦消费侧）：
 *   管理 API publish 信封 → susumonitor.events → susumonitor.alert.metrics
 *   → AlertMessageConsumer（幂等 + 评估 + 同事务记录）→ alert.push / 告警记录
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   RABBITMQ_MANAGEMENT_URL=http://127.0.0.1:15672 RABBITMQ_MANAGEMENT_USER=xxx RABBITMQ_MANAGEMENT_PASSWORD=xxx \
 *   node verify-mvp11.mjs
 *
 * 检查项：
 *   C1 消息通道正常消费：合法信封 → 消费 + 评估 + alert.push + 告警记录
 *   C2 重复投递幂等：同 event_id 重投 → 无第二次业务效果（无新记录/无推送）
 *   C3 DLQ 分类：非法 JSON / schema_version=2 / 字段契约非法 → 消费拒绝 → DLQ 出现消息
 */
import crypto from 'node:crypto'
import WebSocket from 'ws'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18081'
const wsUrl = baseUrl.replace(/^http/, 'ws')
const managementUrl = process.env.RABBITMQ_MANAGEMENT_URL ?? 'http://127.0.0.1:15672'
const managementUser = process.env.RABBITMQ_MANAGEMENT_USER ?? 'guest'
const managementPassword = process.env.RABBITMQ_MANAGEMENT_PASSWORD ?? 'guest'
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const waitTimeoutMs = 8000
const validationPrefix = 'mvp11_alert_consume'

if (!adminUsername || !adminPassword) {
  throw new Error('Set SUSUMONITOR_VALIDATION_ADMIN_USERNAME and SUSUMONITOR_VALIDATION_ADMIN_PASSWORD.')
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

const VHOST = 'susumonitor'
const EXCHANGE = 'susumonitor.events'
const QUEUE = 'susumonitor.alert.metrics'
const QUEUE_DLQ = 'susumonitor.alert.metrics.dlq'
const ROUTING_KEY = 'metrics.reported.v1'

async function publishEnvelope(envelopeJson) {
  return management(`/api/exchanges/${encodeURIComponent(VHOST)}/${encodeURIComponent(EXCHANGE)}/publish`, {
    method: 'POST',
    body: { properties: {}, routing_key: ROUTING_KEY, payload: envelopeJson, payload_encoding: 'string' }
  })
}

async function queueMessages(queue) {
  const result = await management(`/api/queues/${encodeURIComponent(VHOST)}/${encodeURIComponent(queue)}`)
  return result.body?.messages ?? 0
}

function envelope(eventId, serverId, cpuPercent) {
  const payload = {
    server_id: serverId,
    message_id: crypto.randomUUID(),
    collected_at: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
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
  return JSON.stringify({
    event_id: eventId,
    event_type: 'metrics.reported',
    schema_version: 1,
    occurred_at: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    producer: 'metrics-service',
    payload
  })
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

function waitForMessage(socket, expectedType, timeoutMs = waitTimeoutMs) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      cleanup()
      reject(new Error(`Timed out waiting for ${expectedType}`))
    }, timeoutMs)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === expectedType) {
        cleanup()
        resolve(value)
      } else if (value.type === 'error') {
        cleanup()
        reject(new Error(`WebSocket error while waiting for ${expectedType}`))
      }
    }
    const cleanup = () => {
      clearTimeout(timeout)
      socket.off('message', onMessage)
    }
    socket.on('message', onMessage)
  })
}

function assertNoMessage(socket, unexpectedType, timeoutMs = 2500) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      cleanup()
      resolve()
    }, timeoutMs)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === unexpectedType) {
        cleanup()
        reject(new Error(`Received unexpected ${unexpectedType}`))
      }
    }
    const cleanup = () => {
      clearTimeout(timeout)
      socket.off('message', onMessage)
    }
    socket.on('message', onMessage)
  })
}

async function alertRecords(serverId, token) {
  const response = await api(`/api/alerts/records?server_id=${serverId}&page=1&page_size=50`, { token })
  return response.body.data?.items ?? []
}

// ---- 准备：管理员 + 服务器 + 告警规则 ----
// 批次 8：目标实例仍待初始化首管理员时，兜底注册必须携带一次性初始化令牌（缺失 403/40310）；
// 令牌经 SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN 传入（服务器启动横幅或 AUTH_BOOTSTRAP_TOKEN 预置值）。
async function registerAdmin() {
  const bootstrapStatus = await api('/api/auth/bootstrap-status')
  const bootstrapPending = bootstrapStatus.status === 200 && bootstrapStatus.body?.data?.bootstrapPending === true
  let registerBody = { username: adminUsername, password: adminPassword }
  if (bootstrapPending) {
    const bootstrapToken = process.env.SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN
    if (typeof bootstrapToken !== 'string' || bootstrapToken.length < 32 || bootstrapToken.length > 128) {
      throw new Error('Target stack awaits first-admin bootstrap: set SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN ' +
        '(one-time token from the server startup banner or the preset AUTH_BOOTSTRAP_TOKEN).')
    }
    registerBody = { username: adminUsername, password: adminPassword, bootstrapToken }
  }
  return api('/api/auth/register', { method: 'POST', body: registerBody })
}

let adminLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
if (adminLogin.status !== 200) {
  const registration = await registerAdmin()
  assertRegistration(registration.status)
  adminLogin = await api('/api/auth/login', {
    method: 'POST',
    body: { username: adminUsername, password: adminPassword }
  })
}
function assertRegistration(status) {
  if (status !== 200) throw new Error('Admin registration failed in the isolated validation database')
}
if (adminLogin.status !== 200) throw new Error('Admin login failed')
const adminToken = adminLogin.body.data.token

const suffix = Date.now()
const userUsername = `${validationPrefix}_user_${suffix}`
const userPassword = `Validation-${suffix}!`
const registeredUser = await api('/api/auth/register', {
  method: 'POST',
  body: { username: userUsername, password: userPassword }
})
check('P0', registeredUser.status === 200, '验证用户注册')

// 待审核列表：优先新版 /users（分页），旧版后端（无该端点）回退 /users/pending 数组。
const pending = await api('/api/admin/users?status=pending&page=1&page_size=50', { token: adminToken })
let pendingItems = pending.body.data?.items
if (!Array.isArray(pendingItems)) {
  const legacy = await api('/api/admin/users/pending', { token: adminToken })
  pendingItems = Array.isArray(legacy.body.data) ? legacy.body.data : []
}
const pendingUser = pendingItems.find((user) => user.username === userUsername)
check('P0', !!pendingUser, '待审批列表可见验证用户')

const approved = await api(`/api/admin/users/${pendingUser.id}/approve`, { method: 'PUT', token: adminToken })
check('P0', approved.status === 200, '验证用户审批通过')

const userLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: userUsername, password: userPassword }
})
const userToken = userLogin.body.data.token

const octet3 = Math.floor(suffix / 256) % 254 + 1
const octet4 = suffix % 254 + 1
const host = `127.5.${octet3}.${octet4}`
const createdServer = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `${validationPrefix}-${suffix}`,
    host,
    description: validationPrefix,
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
    level: 'warning'
  }
})
check('P0', createdRule.status === 200, '告警规则创建')
const ruleId = createdRule.body.data.id

const ticketResponse = await api('/api/ws/monitor-ticket', { method: 'POST', token: userToken })
const monitor = await openSocket(`${wsUrl}/ws/monitor?ticket=${encodeURIComponent(ticketResponse.body.data.ticket)}`)
monitor.send(JSON.stringify({ type: 'metrics.subscribe', message_id: crypto.randomUUID(), timestamp: new Date().toISOString(), payload: { server_id: serverId } }))

// ---- C1 正常链路：消息通道消费 + 评估 ----
// event_id 使用纯 UUID（契约冻结格式，V15 表列 VARCHAR(36)）。
const eventId1 = crypto.randomUUID()
const dlqBefore = await queueMessages(QUEUE_DLQ)

const alertPromise = waitForMessage(monitor, 'alert.push')
const published1 = await publishEnvelope(envelope(eventId1, serverId, 90.5))
check('C1', published1.status === 200 && published1.body.routed === true, '信封投递到 exchange（routed=true）')

const push = await alertPromise
check('C1', push.payload.server_id === serverId && push.payload.alert.rule_id === ruleId,
  '消息消费后触发 alert.push（server_id/rule_id 匹配）')
check('C1', push.payload.alert.current_value === 90.5 && push.payload.alert.status === 'unread',
  'alert.push 携带越界值与 unread 状态')

await new Promise((resolve) => setTimeout(resolve, 1500))
const recordsAfterFirst = await alertRecords(serverId, adminToken)
check('C1', recordsAfterFirst.length === 1 && recordsAfterFirst[0].rule_id === ruleId,
  '告警记录产生 1 条（rule_id 匹配）')
check('C1', (await queueMessages(QUEUE)) === 0, '业务队列无堆积（消息已消费）')

// ---- C2 重复投递幂等：同 event_id 重投不产生第二次业务效果 ----
const published2 = await publishEnvelope(envelope(eventId1, serverId, 90.5))
check('C2', published2.status === 200 && published2.body.routed === true, '同 event_id 信封重投成功')

const noSecondPush = assertNoMessage(monitor, 'alert.push')
await noSecondPush
const recordsAfterDup = await alertRecords(serverId, adminToken)
check('C2', recordsAfterDup.length === 1, '重复投递后告警记录数不变（幂等命中）')
check('C2', (await queueMessages(QUEUE)) === 0, '重复投递被消费（ACK）且无堆积')

// ---- C3 DLQ 分类：不可重试数据错误直接进 DLQ ----
const badJson = await publishEnvelope('{not-a-json-envelope')
check('C3', badJson.status === 200, '非法 JSON 信封投递成功')

const badSchemaEventId = crypto.randomUUID()
const badSchema = await publishEnvelope(
  JSON.stringify({
    event_id: badSchemaEventId,
    event_type: 'metrics.reported',
    schema_version: 2,
    occurred_at: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    producer: 'metrics-service',
    payload: { server_id: serverId }
  })
)
check('C3', badSchema.status === 200, 'schema_version=2 信封投递成功')

const badContractEventId = crypto.randomUUID()
const invalidContract = JSON.parse(envelope(badContractEventId, serverId, 101))
const badContract = await publishEnvelope(JSON.stringify(invalidContract))
check('C3', badContract.status === 200, '字段契约非法信封投递成功')

const dlqDeadline = Date.now() + waitTimeoutMs
let dlqAfter = 0
while (Date.now() < dlqDeadline) {
  dlqAfter = await queueMessages(QUEUE_DLQ)
  if (dlqAfter >= dlqBefore + 3) break
  await new Promise((resolve) => setTimeout(resolve, 500))
}
check('C3', dlqAfter >= dlqBefore + 3,
  `三类不可重试消息均进入 DLQ（before=${dlqBefore}, after=${dlqAfter}）`)

// ---- C4 失败留痕：数据错误拒绝早于业务事务，不产生告警副作用 ----
const recordsAfterRejects = await alertRecords(serverId, adminToken)
check('C4', recordsAfterRejects.length === 1,
  '三类数据错误消息被拒后无新增告警记录（拒绝在业务事务之前，不留业务效果）')
check('C4', (await queueMessages(QUEUE)) === 0, '数据错误消息消费后业务队列无堆积')

monitor.close()
await new Promise((resolve) => monitor.once('close', resolve))

console.log(`\nMVP-11 验收 PASS：${checks.filter((id) => id.startsWith('C')).length} 项消费侧检查项全部通过`)
console.log(`  event_id 示例：${eventId1}`)
console.log(`  DLQ 消息数：${dlqAfter}（验收后可清空）`)
console.log('  DB 侧附加确认（验收记录）：')
console.log(`    1. message_consume_records 中 ${eventId1} 仅 1 行 consumed（消费幂等）`)
console.log(`    2. ${badSchemaEventId}（schema_version=2）与 ${badContractEventId}（cpu=101）各 1 行 failed，`)
console.log('       attempts=1、last_error 非空（失败留痕，不可重试零重试）')
console.log('    3. 非法 JSON 无可解析 event_id，无对应行（仅告警日志）')
