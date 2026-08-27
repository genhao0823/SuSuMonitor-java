/**
 * MVP-11 多消费者并发验收。
 *
 * 前置条件：两个独立 Java 后端进程已连接同一验证 MySQL/RabbitMQ，
 * 并以不同 SERVER_PORT 运行；本脚本只向本机验证环境发布消息，不启动或停止服务。
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_CONFIRM=MVP11_CONCURRENCY \
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=... SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=... \
 *   RABBITMQ_MANAGEMENT_USER=... RABBITMQ_MANAGEMENT_PASSWORD=... \
 *   node verify-mvp11-concurrency.mjs
 */
import crypto from 'node:crypto'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18081'
const managementUrl = process.env.RABBITMQ_MANAGEMENT_URL ?? 'http://127.0.0.1:15672'
const managementUser = process.env.RABBITMQ_MANAGEMENT_USER ?? 'guest'
const managementPassword = process.env.RABBITMQ_MANAGEMENT_PASSWORD ?? 'guest'
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const consumers = Number(process.env.SUSUMONITOR_VALIDATION_CONSUMER_INSTANCES ?? 0)
const count = Number(process.env.SUSUMONITOR_VALIDATION_MESSAGE_COUNT ?? 20)
const VHOST = 'susumonitor'
const EXCHANGE = 'susumonitor.events'
const QUEUE = 'susumonitor.alert.metrics'
const ROUTING_KEY = 'metrics.reported.v1'

function assert(condition, message) { if (!condition) throw new Error(message) }
function assertLocalValidationTarget() {
  const url = new URL(managementUrl)
  assert(['127.0.0.1', 'localhost', '::1'].includes(url.hostname), 'Only a local RabbitMQ Management API is allowed.')
  assert(process.env.SUSUMONITOR_VALIDATION_CONFIRM === 'MVP11_CONCURRENCY',
    'Set SUSUMONITOR_VALIDATION_CONFIRM=MVP11_CONCURRENCY to publish validation messages.')
  assert(consumers >= 2, 'Set SUSUMONITOR_VALIDATION_CONSUMER_INSTANCES to at least 2 after starting the instances.')
  assert(Number.isInteger(count) && count >= 2 && count <= 100, 'Message count must be an integer from 2 to 100.')
  assert(adminUsername && adminPassword, 'Set SUSUMONITOR_VALIDATION_ADMIN_USERNAME and SUSUMONITOR_VALIDATION_ADMIN_PASSWORD.')
}
async function api(path, { method = 'GET', body, token } = {}) {
  const response = await fetch(`${baseUrl}${path}`, { method, headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) })
  return { status: response.status, body: await response.json() }
}
async function management(path, { method = 'GET', body } = {}) {
  const response = await fetch(`${managementUrl}${path}`, { method, headers: { 'content-type': 'application/json', authorization: `Basic ${Buffer.from(`${managementUser}:${managementPassword}`).toString('base64')}` }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) })
  const result = await response.json().catch(() => null)
  if (!response.ok) throw new Error(`Management API ${path} failed: ${response.status}`)
  return result
}
async function queueMessages() { return (await management(`/api/queues/${encodeURIComponent(VHOST)}/${encodeURIComponent(QUEUE)}`)).messages ?? 0 }
async function waitFor(condition, description, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) { if (await condition()) return; await new Promise((resolve) => setTimeout(resolve, 500)) }
  throw new Error(`Timed out waiting for ${description}`)
}
function envelope(eventId, serverId, index) {
  // 只有第一个事件越界：同 event_id 的并发重复投递必须只产生一条告警记录；
  // 其余合法低值事件验证多消费者持续消费，但不依赖跨消息的处理顺序。
  const cpuPercent = index === 0 ? 90.5 : 10
  return JSON.stringify({ event_id: eventId, event_type: 'metrics.reported', schema_version: 1, occurred_at: new Date().toISOString(), producer: 'metrics-service', payload: { server_id: serverId, message_id: crypto.randomUUID(), collected_at: new Date(Date.now() + index * 1000).toISOString(), cpu_percent: cpuPercent, memory_percent: 52.4, memory_used: 524000000, memory_total: 1000000000, disk_percent: 66.8, disk_used: 668000000, disk_total: 1000000000, net_rx: 777777, net_tx: 888888, temperature: 43.1, load_avg: 0.88 } })
}

assertLocalValidationTarget()
let login = await api('/api/auth/login', { method: 'POST', body: { username: adminUsername, password: adminPassword } })
if (login.status !== 200) { await api('/api/auth/register', { method: 'POST', body: { username: adminUsername, password: adminPassword } }); login = await api('/api/auth/login', { method: 'POST', body: { username: adminUsername, password: adminPassword } }) }
assert(login.status === 200, 'Admin login failed')
const token = login.body.data.token
const suffix = Date.now()
const server = await api('/api/servers', { method: 'POST', token, body: { name: `mvp11_concurrency_${suffix}`, host: `127.9.${Math.floor(suffix / 254) % 254 + 1}.${suffix % 254 + 1}`, description: 'MVP-11 concurrency validation', ssh_host: '127.0.0.1', ssh_port: 22, ssh_user: 'validation', ssh_auth_type: 'password', ssh_password: 'validation-placeholder' } })
assert(server.status === 200, 'Server creation failed')
const serverId = server.body.data.id
const rule = await api('/api/alerts/rules', { method: 'POST', token, body: { server_id: serverId, metric: 'cpu', operator: '>', threshold_value: 80, level: 'warning' } })
assert(rule.status === 200, 'Alert rule creation failed')
const eventIds = Array.from({ length: count - 1 }, () => crypto.randomUUID())
const duplicateEventId = crypto.randomUUID()
const publishPayloads = [
  envelope(duplicateEventId, serverId, 0),
  envelope(duplicateEventId, serverId, 0),
  ...eventIds.map((eventId, index) => envelope(eventId, serverId, index + 1))
]
const startedAt = Date.now()
await Promise.all(publishPayloads.map((payload) =>
  management(`/api/exchanges/${encodeURIComponent(VHOST)}/${encodeURIComponent(EXCHANGE)}/publish`, {
    method: 'POST',
    body: { properties: {}, routing_key: ROUTING_KEY, payload, payload_encoding: 'string' }
  }).then((result) => assert(result.routed, 'Message was not routed'))
))
await waitFor(async () => (await queueMessages()) === 0, 'all messages to be consumed')
const expectedRecords = 1
await waitFor(async () => (await api(`/api/alerts/records?server_id=${serverId}&page=1&page_size=100`, { token })).body.data?.items?.length === expectedRecords, 'one alert record for duplicated trigger event')
const elapsedMs = Date.now() - startedAt
console.log(JSON.stringify({ status: 'PASS', server_id: serverId, rule_id: rule.body.data.id, published_deliveries: publishPayloads.length, unique_event_ids: count, duplicated_event_id: duplicateEventId, expected_alert_records: expectedRecords, consumer_instances_declared: consumers, elapsed_ms: elapsedMs, throughput_per_second: Number((publishPayloads.length / (elapsedMs / 1000)).toFixed(2)), queue_messages: await queueMessages(), db_confirmation_required: 'Run SELECT event_id, COUNT(*) FROM message_consume_records WHERE consumer = \'alert-evaluator\' AND event_id IN (...) GROUP BY event_id; every count must equal 1, including duplicated_event_id.', cleanup: 'No server, rule, record, or queue data was deleted by this script.', token_values_logged: false }))
