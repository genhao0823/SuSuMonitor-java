/**
 * MVP-11 收口：DLQ 受控重放 / 清理工具（运维手册"死信处置"落地）。
 * 2026-08-12 泛化：支持 metrics（默认）与 alert 两类事件的 DLQ。
 *
 * 用法：
 *   RABBITMQ_MANAGEMENT_URL=http://127.0.0.1:15672 RABBITMQ_MANAGEMENT_USER=xxx \
 *   RABBITMQ_MANAGEMENT_PASSWORD=xxx node replay-dlq.mjs [--event metrics|alert] [--replay|--purge]
 *
 * 参数：
 *   --event    处理哪个事件的 DLQ：metrics（default）→ susumonitor.alert.metrics.dlq；
 *              alert → susumonitor.alert.triggered.dlq
 *   --replay   从 DLQ 取出全部消息并重新发布到 susumonitor.events，
 *              验证：合法信封被消费（event_id 幂等命中零业务效果）、
 *              不可重试数据错误再次进 DLQ（防循环提示）
 *   --purge    清空 DLQ（管理 API purge）
 *
 * 说明：重放不产生重复业务效果——消费侧以 event_id 幂等（message_consume_records）。
 * 数据错误消息重放会再次进 DLQ，需先修数据（构造合法信封）或人工丢弃。
 */

const managementUrl = process.env.RABBITMQ_MANAGEMENT_URL ?? 'http://127.0.0.1:15672'
const managementUser = process.env.RABBITMQ_MANAGEMENT_USER ?? 'guest'
const managementPassword = process.env.RABBITMQ_MANAGEMENT_PASSWORD ?? 'guest'

const args = process.argv.slice(2)
const MODE = args.includes('--purge') ? 'purge' : 'replay'
const EVENT = args.includes('--event') ? args[args.indexOf('--event') + 1] : 'metrics'

const VHOST = 'susumonitor'
const EXCHANGE = 'susumonitor.events'
/** 事件类型 → DLQ/路由键 二元组（冻结拓扑 rabbitmq-topology-v1.md §二）。 */
const EVENTS = {
  metrics: { dlq: 'susumonitor.alert.metrics.dlq', routingKey: 'metrics.reported.v1' },
  alert: { dlq: 'susumonitor.alert.triggered.dlq', routingKey: 'alert.triggered.v1' }
}
if (!EVENTS[EVENT]) {
  console.error(`不支持的 --event=${EVENT}（可选 metrics|alert）`)
  process.exit(2)
}
const QUEUE_DLQ = EVENTS[EVENT].dlq
const ROUTING_KEY = EVENTS[EVENT].routingKey
const waitTimeoutMs = 20000

const checks = []
function check(id, condition, description) {
  if (!condition) throw new Error(`${id} FAILED: ${description}`)
  checks.push(id)
  console.log(`✓ ${id} ${description}`)
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
const dlqPath = `/api/queues/${enc(VHOST)}/${enc(QUEUE_DLQ)}`

/** 实测 DLQ 条数：队列详情 messages 是聚合统计（滞后不可靠），用 /get 实测。 */
async function dlqMessages() {
  const result = await management(`${dlqPath}/get`, {
    method: 'POST',
    body: { count: 100, ackmode: 'ack_requeue_true', encoding: 'auto' }
  })
  return Array.isArray(result.body) ? result.body.length : 0
}

/** 取出并确认（从 DLQ 移除）count 条消息。 */
async function fetchMessages(count) {
  const result = await management(`${dlqPath}/get`, {
    method: 'POST',
    body: { count, ackmode: 'ack_requeue_false', encoding: 'auto' }
  })
  return result.body ?? []
}

/** 重新发布到业务 exchange。 */
async function republish(message) {
  // 管理 API get 返回的空 properties 是 []（数组），publish 需要对象——规范化。
  const properties = message.properties && !Array.isArray(message.properties) ? message.properties : {}
  return management(`/api/exchanges/${enc(VHOST)}/${enc(EXCHANGE)}/publish`, {
    method: 'POST',
    body: {
      properties,
      routing_key: ROUTING_KEY,
      payload: message.payload,
      payload_encoding: message.payload_encoding ?? 'string'
    }
  })
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

// ---- 执行 ----
const before = await dlqMessages()
console.log(`[replay-dlq] mode=${MODE} event=${EVENT} DLQ=${QUEUE_DLQ} 消息数=${before}`)

if (MODE === 'purge') {
  const purge = await management(`${dlqPath}/contents`, { method: 'DELETE' })
  check('P1', purge.status === 204 || purge.status === 200, `DLQ purge 请求成功（HTTP ${purge.status}）`)
  await waitForCondition(async () => (await dlqMessages()) === 0, 'DLQ 清空')
  console.log(`\nDLQ 已清空（原 ${before} 条）`)
  await new Promise((resolve) => setTimeout(resolve, 300))
  process.exit(0)
}

// --replay 模式
if (before === 0) {
  console.log('\nDLQ 为空，无可重放消息')
  await new Promise((resolve) => setTimeout(resolve, 300))
  process.exit(0)
}

const messages = await fetchMessages(before)
check('R1', messages.length === before, `从 DLQ 取出 ${before} 条消息`)

let replayable = 0
let dataError = 0
for (const message of messages) {
  const published = await republish(message)
  check('R2', published.status === 200 && published.body?.routed === true,
    `重放消息 routed=true（event_id=${extractEventId(message)}）`)
  // 分类：能解析出 event_id 的按幂等处理；解析失败的按数据错误处理。
  const eventId = extractEventId(message)
  if (eventId) replayable++
  else dataError++
}

// 等待消费侧处理完成：DLQ 数量连续两次采样一致（容忍管理 API 统计缓存延迟）。
await waitForCondition(async () => {
  const first = await dlqMessages()
  await new Promise((resolve) => setTimeout(resolve, 1000))
  const second = await dlqMessages()
  return first === second
}, 'DLQ 数量稳定')
const after = await dlqMessages()
check('R3', after >= before - replayable,
  `数据错误消息重新进入 DLQ（after=${after}，可重放=${replayable} 条已被幂等消费）`)
check('R4', after <= before, `DLQ 未异常膨胀（${after} <= ${before}）`)

const replayableBack = after - dataError
if (dataError > 0) {
  console.log(`\n⚠ 提示：${dataError} 条数据错误消息（非法 JSON/schema）重放后再次进 DLQ。`)
  console.log('  处置：构造合法信封（对应事件契约）后重放，或人工确认后 --purge 丢弃。')
}
if (replayableBack > 0) {
  console.log(`\n⚠ 提示：${replayableBack} 条可重放消息重放后仍回到 DLQ（可能因评估侧异常重试耗尽），请查后端日志后重试。`)
}
console.log(`\nDLQ 重放完成：取出 ${before} 条，幂等消费 ${replayable - dataError} 条，数据错误 ${dataError} 条`)

function extractEventId(message) {
  try {
    const payload = message.payload_encoding === 'base64'
      ? Buffer.from(message.payload, 'base64').toString('utf-8')
      : String(message.payload)
    const envelope = JSON.parse(payload)
    return typeof envelope.event_id === 'string' ? envelope.event_id : null
  } catch {
    return null
  }
}
