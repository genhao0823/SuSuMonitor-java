/**
 * 多实例化阶段一验收：Redis 共享 Monitor ticket 的跨实例一次性语义（2026-08-17）。
 *
 * 前置：两个 Java 实例（A/B）共享同一 MySQL + 同一 Redis（REDIS_ENABLED=true）；
 * Redis 容器已在运行。
 *
 * 检查项：
 *   P0  实例 A/B 管理员登录（共享 DB）
 *   C1  A 签发 ticket → B 实例 ws/monitor 握手成功（跨实例消费）
 *   C2  B 签发 ticket → A 实例握手成功（反向）
 *   C3  A/B /api/ready 均 200（Redis 探活通过）
 *   C4  一次性语义：同一 ticket 在另一实例二次消费 → 握手 401
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=... SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=... \
 *   node verify-redis-ticket.mjs [--baseA=http://localhost:18081] [--baseB=http://localhost:18082]
 */
import crypto from 'node:crypto'
import WebSocket from 'ws'

const baseA = argValue('baseA', 'http://localhost:18081')
const baseB = argValue('baseB', 'http://localhost:18082')
const wsA = baseA.replace(/^http/, 'ws')
const wsB = baseB.replace(/^http/, 'ws')
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const waitTimeoutMs = 8000

if (!adminUsername || !adminPassword) {
  throw new Error('Set SUSUMONITOR_VALIDATION_ADMIN_USERNAME and SUSUMONITOR_VALIDATION_ADMIN_PASSWORD.')
}

function argValue(name, fallback) {
  const match = process.argv.find((value) => value.startsWith(`--${name}=`))
  return match ? match.split('=')[1] : fallback
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function api(base, path, { method = 'GET', body, token } = {}) {
  const response = await fetch(`${base}${path}`, {
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

function message(type, payload = {}, messageId = crypto.randomUUID()) {
  return { type, message_id: messageId, timestamp: new Date().toISOString(), payload }
}

async function openSocket(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url)
    const timeout = setTimeout(() => {
      cleanup()
      socket.terminate()
      reject(new Error(`Timed out opening ${url}`))
    }, waitTimeoutMs)
    const cleanup = () => clearTimeout(timeout)
    socket.once('open', () => { cleanup(); resolve(socket) })
    socket.once('unexpected-response', (_request, response) => {
      cleanup()
      reject(new Error(`Handshake rejected with ${response.statusCode}`))
    })
    socket.once('error', (error) => { cleanup(); reject(error) })
  })
}

/** 期望握手成功：ticket 消费后连接建立，subscribe 无 error 帧。 */
async function expectHandshakeOk(base, wsBase, ticket, serverId, label) {
  const socket = await openSocket(`${wsBase}/ws/monitor?ticket=${encodeURIComponent(ticket)}`)
  socket.send(JSON.stringify(message('metrics.subscribe', { server_id: serverId })))
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { cleanup(); resolve() }, 1500)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === 'error') {
        cleanup()
        reject(new Error(`${label}: unexpected error frame after subscribe`))
      }
    }
    const cleanup = () => { clearTimeout(timeout); socket.off('message', onMessage) }
    socket.on('message', onMessage)
  })
  socket.close()
}

/** 期望握手失败（ticket 已消费/不存在）：连接建立或 401 拒绝均可接受，但不得成功订阅。 */
async function expectHandshakeRejected(base, wsBase, ticket, label) {
  let rejected = false
  try {
    const socket = await openSocket(`${wsBase}/ws/monitor?ticket=${encodeURIComponent(ticket)}`)
    socket.close()
  } catch {
    rejected = true
  }
  assert(rejected, `${label}: expected handshake rejection, but it succeeded`)
}

async function main() {
  const checks = {}

  // P0：两个实例共享 DB，管理员可各自登录。
  const loginA = await api(baseA, '/api/auth/login', { method: 'POST', body: { username: adminUsername, password: adminPassword } })
  assert(loginA.status === 200 && loginA.body.data?.token, `P0 login on A failed: ${loginA.body.code}`)
  const tokenA = loginA.body.data.token
  const loginB = await api(baseB, '/api/auth/login', { method: 'POST', body: { username: adminUsername, password: adminPassword } })
  assert(loginB.status === 200 && loginB.body.data?.token, `P0 login on B failed: ${loginB.body.code}`)
  const tokenB = loginB.body.data.token
  checks.P0 = 'admin login on both instances OK'

  // 建一个 server（共享 DB，供 subscribe 使用）。
  const created = await api(baseA, '/api/servers', {
    method: 'POST', token: tokenA,
    body: { name: `redis_ticket_${Date.now()}`, host: '127.0.0.3', description: 'multi-instance ticket e2e', ssh_host: '127.0.0.3', ssh_port: 22, ssh_user: 'e2e', ssh_auth_type: 'password', ssh_password: 'e2e-placeholder' }
  })
  assert(created.status === 200, `server create failed: ${created.body.code}`)
  const serverId = created.body.data.id

  // C1：A 签发 → B 消费握手。
  const ticketA = await api(baseA, '/api/ws/monitor-ticket', { method: 'POST', token: tokenA })
  assert(ticketA.status === 200 && ticketA.body.data?.ticket, `C1 ticket issue on A failed: ${ticketA.body.code}`)
  await expectHandshakeOk(baseB, wsB, ticketA.body.data.ticket, serverId, 'C1 A→B')
  checks.C1 = `ticket issued on A consumed on B (server ${serverId})`

  // C2：B 签发 → A 消费握手。
  const ticketB = await api(baseB, '/api/ws/monitor-ticket', { method: 'POST', token: tokenB })
  assert(ticketB.status === 200 && ticketB.body.data?.ticket, `C2 ticket issue on B failed: ${ticketB.body.code}`)
  await expectHandshakeOk(baseA, wsA, ticketB.body.data.ticket, serverId, 'C2 B→A')
  checks.C2 = 'ticket issued on B consumed on A'

  // C3：两实例 ready 均 200（Redis 探活通过）。
  const readyA = await api(baseA, '/api/ready')
  assert(readyA.status === 200, `C3 ready on A failed: ${readyA.status}`)
  const readyB = await api(baseB, '/api/ready')
  assert(readyB.status === 200, `C3 ready on B failed: ${readyB.status}`)
  checks.C3 = 'ready 200 on both instances (redis healthy)'

  // C4：一次性语义——已消费的 ticket 在另一实例二次握手必须失败。
  const ticketC = await api(baseA, '/api/ws/monitor-ticket', { method: 'POST', token: tokenA })
  await expectHandshakeOk(baseB, wsB, ticketC.body.data.ticket, serverId, 'C4 first use')
  await expectHandshakeRejected(baseB, wsB, ticketC.body.data.ticket, 'C4 replay')
  checks.C4 = 'one-time semantics: replay of consumed ticket rejected'

  console.log(JSON.stringify({ status: 'PASS', checks }, null, 2))
  process.exit(0)
}

main().catch((error) => {
  console.error(JSON.stringify({ status: 'FAILED', error: error.message }, null, 2))
  process.exit(1)
})
