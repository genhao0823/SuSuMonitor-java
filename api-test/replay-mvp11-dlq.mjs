/**
 * MVP-11 DLQ 受控重放工具（2026-08-12 泛化：支持 metrics/alert 两类事件）。
 *
 * 默认 dry-run：用 ack_requeue_true 查看有限条 DLQ 消息，校验后重新入队，绝不发布。
 * --execute：仅在显式确认的本机验证环境中，取出有限条合规消息并原样发布回事件交换机。
 * 不会把消息直接写入业务队列，也不会输出消息 payload。
 *
 * 用法：
 *   RABBITMQ_MANAGEMENT_USER=... RABBITMQ_MANAGEMENT_PASSWORD=... node replay-mvp11-dlq.mjs [--event metrics|alert]
 *   SUSUMONITOR_VALIDATION_CONFIRM=I_UNDERSTAND_DLQ_REPLAY \
 *     RABBITMQ_MANAGEMENT_USER=... RABBITMQ_MANAGEMENT_PASSWORD=... \
 *     node replay-mvp11-dlq.mjs --event alert --execute --limit=10
 */
const managementUrl = process.env.RABBITMQ_MANAGEMENT_URL ?? 'http://127.0.0.1:15672'
const managementUser = process.env.RABBITMQ_MANAGEMENT_USER ?? 'guest'
const managementPassword = process.env.RABBITMQ_MANAGEMENT_PASSWORD ?? 'guest'
const VHOST = 'susumonitor'
const EXCHANGE = 'susumonitor.events'
/** 事件类型 → DLQ/路由键 二元组（冻结拓扑 rabbitmq-topology-v1.md §二）。 */
const EVENTS = {
  metrics: { dlq: 'susumonitor.alert.metrics.dlq', routingKey: 'metrics.reported.v1', eventType: 'metrics.reported' },
  alert: { dlq: 'susumonitor.alert.triggered.dlq', routingKey: 'alert.triggered.v1', eventType: 'alert.triggered' }
}
const EXECUTE_CONFIRMATION = 'I_UNDERSTAND_DLQ_REPLAY'

const execute = process.argv.includes('--execute')
const limitArgument = process.argv.find((argument) => argument.startsWith('--limit='))
const limit = Number(limitArgument?.slice('--limit='.length) ?? 10)
const eventArgument = process.argv.find((argument) => argument === '--event')
const EVENT = eventArgument ? process.argv[process.argv.indexOf('--event') + 1] : 'metrics'
if (!EVENTS[EVENT]) throw new Error(`Unsupported --event=${EVENT}（可选 metrics|alert）`)
const EVENT_CONFIG = EVENTS[EVENT]
const DLQ = EVENT_CONFIG.dlq
const ROUTING_KEY = EVENT_CONFIG.routingKey
const EVENT_TYPE = EVENT_CONFIG.eventType

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function assertLocalValidationTarget() {
  const url = new URL(managementUrl)
  assert(['127.0.0.1', 'localhost', '::1'].includes(url.hostname),
    'Only a local RabbitMQ Management API is allowed by this tool.')
  assert(Number.isInteger(limit) && limit >= 1 && limit <= 50, '--limit must be an integer from 1 to 50.')
  if (execute) {
    assert(process.env.SUSUMONITOR_VALIDATION_CONFIRM === EXECUTE_CONFIRMATION,
      `--execute requires SUSUMONITOR_VALIDATION_CONFIRM=${EXECUTE_CONFIRMATION}.`)
  }
}

async function management(path, { method = 'GET', body } = {}) {
  const response = await fetch(`${managementUrl}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      authorization: `Basic ${Buffer.from(`${managementUser}:${managementPassword}`).toString('base64')}`
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10000)
  })
  const result = await response.json().catch(() => null)
  if (!response.ok) throw new Error(`Management API ${path} failed: ${response.status}`)
  return result
}

/** 按事件类型校验信封：metrics 校验 server_id 整数；alert 校验三 ID 正整数与冻结指标集。 */
function validateEnvelope(rawPayload) {
  let envelope
  try {
    envelope = JSON.parse(rawPayload)
  } catch {
    return { valid: false, reason: 'invalid_json' }
  }
  if (!envelope || typeof envelope !== 'object') return { valid: false, reason: 'invalid_envelope' }
  if (envelope.event_type !== EVENT_TYPE) return { valid: false, reason: 'unexpected_event_type' }
  if (envelope.schema_version !== 1) return { valid: false, reason: 'unsupported_schema_version' }
  if (typeof envelope.event_id !== 'string' || !/^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(envelope.event_id)) {
    return { valid: false, reason: 'invalid_event_id' }
  }
  const payload = envelope.payload
  if (!payload || typeof payload !== 'object') return { valid: false, reason: 'invalid_payload' }
  if (EVENT === 'metrics') {
    if (!Number.isInteger(payload.server_id)) return { valid: false, reason: 'invalid_payload' }
  } else {
    const frozenMetrics = ['cpu', 'memory', 'disk', 'temperature', 'load_avg']
    if (!Number.isInteger(payload.server_id) || !Number.isInteger(payload.rule_id) || !Number.isInteger(payload.record_id)
      || payload.server_id <= 0 || payload.rule_id <= 0 || payload.record_id <= 0) {
      return { valid: false, reason: 'invalid_payload' }
    }
    if (!frozenMetrics.includes(payload.metric)) return { valid: false, reason: 'invalid_metric' }
  }
  return { valid: true }
}

assertLocalValidationTarget()
const ackmode = execute ? 'ack_requeue_false' : 'ack_requeue_true'
const messages = await management(`/api/queues/${encodeURIComponent(VHOST)}/${encodeURIComponent(DLQ)}/get`, {
  method: 'POST',
  body: { count: limit, ackmode, encoding: 'auto', truncate: 0 }
})

let replayed = 0
let rejected = 0
for (const message of messages) {
  const validation = validateEnvelope(message.payload)
  if (!validation.valid) {
    rejected += 1
    console.log(`SKIP ${validation.reason} (payload omitted)`)
    continue
  }

  if (!execute) {
    console.log(`DRY-RUN eligible ${EVENT_TYPE} message (payload omitted)`)
    continue
  }

  const published = await management(`/api/exchanges/${encodeURIComponent(VHOST)}/${encodeURIComponent(EXCHANGE)}/publish`, {
    method: 'POST',
    body: { properties: message.properties ?? {}, routing_key: ROUTING_KEY, payload: message.payload, payload_encoding: 'string' }
  })
  if (!published.routed) throw new Error('Replay publish was not routed; source message was already removed from DLQ.')
  replayed += 1
  console.log(`REPLAYED ${EVENT_TYPE} message (event_id and payload omitted)`)
}

console.log(JSON.stringify({
  status: 'PASS',
  mode: execute ? 'execute' : 'dry-run',
  inspected: messages.length,
  replayed,
  rejected,
  source_queue: DLQ,
  target_exchange: execute ? EXCHANGE : undefined,
  token_values_logged: false
}))
