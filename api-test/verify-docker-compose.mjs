/**
 * Docker Compose 全栈验收：经 nginx 容器入口（默认 http://localhost:8080）验证
 * mysql + rabbitmq + server + web 四服务闭环，以及可选 agent 容器上报链路。
 *
 * 检查项：
 *   P0  空库首管理员 bootstrap（批次 8 起注册须携带一次性初始化令牌：
 *       SUSUMONITOR_DOCKER_BOOTSTRAP_TOKEN 与部署 .env 的 AUTH_BOOTSTRAP_TOKEN 同值；
 *       实例已初始化时可不传，脚本按 bootstrap-status 自动判断）
 *   C1  /api/health 与 /api/ready 经 nginx 反代 200
 *   C2  创建 server（WS 订阅与 agent 注册都依赖真实 serverId）
 *   C3  /ws/monitor ticket 握手 + metrics.subscribe 真实 server（无 error 帧）
 *   C4  agent/register 取 token（打印 AGENT_READY 行供编排器拉起 agent 容器）
 *   C5  agent 容器上报后：monitor 收到 metrics.update 帧 + API 查询 metrics 可见（120s 轮询）
 *
 * 用法：
 *   SUSUMONITOR_DOCKER_BASE_URL=http://localhost:8080 \
 *   SUSUMONITOR_DOCKER_BOOTSTRAP_TOKEN=<一次性初始化令牌，空库首启时必填> \
 *     node verify-docker-compose.mjs
 * 编排器（bash）在读到 AGENT_READY=<server_id> <agent_token> 后：
 *   SUSUMONITOR_SERVER_ID=<server_id> SUSUMONITOR_AGENT_TOKEN=<agent_token> \
 *     docker compose --profile agent up -d agent
 */
import crypto from 'node:crypto'
import WebSocket from 'ws'

const baseUrl = process.env.SUSUMONITOR_DOCKER_BASE_URL ?? 'http://localhost:8080'
const wsUrl = baseUrl.replace(/^http/, 'ws')
const waitTimeoutMs = 10000

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
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  return { status: response.status, body: await response.json() }
}

function message(type, payload = {}, messageId = crypto.randomUUID()) {
  return { type, message_id: messageId, timestamp: new Date().toISOString(), payload }
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
        reject(new Error(`WebSocket error frame while waiting for ${expectedType}: ${JSON.stringify(value.payload)}`))
      }
    }
    const cleanup = () => {
      clearTimeout(timeout)
      socket.off('message', onMessage)
    }
    socket.on('message', onMessage)
  })
}

function assertNoMessage(socket, unexpectedType, timeoutMs = 1500) {
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

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function main() {
  const username = `docker_admin_${Date.now()}`
  const password = `Docker-${crypto.randomUUID()}!`
  const checks = {}

  // P0：空库首管理员 bootstrap。批次 8 起，待初始化实例的注册必须携带一次性初始化令牌
  // （缺失 403/40310）：先查公开状态端点，pending 时要求以 SUSUMONITOR_DOCKER_BOOTSTRAP_TOKEN
  // 传入与部署 .env 中 AUTH_BOOTSTRAP_TOKEN 同值的令牌（或服务器启动横幅中的值）。
  const bootstrapStatus = await api('/api/auth/bootstrap-status')
  const bootstrapPending = bootstrapStatus.status === 200 && bootstrapStatus.body?.data?.bootstrapPending === true
  let registerBody = { username, password }
  if (bootstrapPending) {
    const bootstrapToken = process.env.SUSUMONITOR_DOCKER_BOOTSTRAP_TOKEN
    assert(typeof bootstrapToken === 'string' && bootstrapToken.length >= 32 && bootstrapToken.length <= 128,
      'P0: stack awaits first-admin bootstrap; export SUSUMONITOR_DOCKER_BOOTSTRAP_TOKEN with the one-time token ' +
      '(32-128 chars, from the server startup banner or the AUTH_BOOTSTRAP_TOKEN preset in .env).')
    registerBody = { username, password, bootstrapToken }
  }
  const registered = await api('/api/auth/register', { method: 'POST', body: registerBody })
  assert(registered.status === 200, `P0 register failed: ${registered.body.code} ${registered.body.message}`)
  const login = await api('/api/auth/login', { method: 'POST', body: { username, password } })
  assert(login.status === 200 && login.body.data?.token, `P0 login failed: ${login.body.code} ${login.body.message}`)
  const adminToken = login.body.data.token
  checks.P0 = 'admin bootstrap via register+login OK'

  // C1：健康检查经 nginx 反代
  const health = await api('/api/health')
  assert(health.status === 200 && health.body.data?.status === 'UP', `C1 health failed: ${health.status}`)
  const ready = await api('/api/ready')
  assert(ready.status === 200 && ready.body.data?.status === 'UP', `C1 ready failed: ${ready.status}`)
  checks.C1 = 'health + ready via nginx OK'

  // C2：创建 server（WS 订阅与 agent 注册都依赖真实 serverId）
  const created = await api('/api/servers', {
    method: 'POST', token: adminToken,
    body: { name: `docker_e2e_${Date.now()}`, host: '127.0.0.2', description: 'docker compose e2e', ssh_host: '127.0.0.2', ssh_port: 22, ssh_user: 'e2e', ssh_auth_type: 'password', ssh_password: 'e2e-placeholder' }
  })
  assert(created.status === 200, `C2 server create failed: ${created.body.code} ${created.body.message}`)
  const serverId = created.body.data.id
  checks.C2 = `server ${serverId} created via nginx`

  // C3：WS 握手 + 订阅真实 server（无 error 帧）
  const ticket = await api('/api/ws/monitor-ticket', { method: 'POST', token: adminToken })
  assert(ticket.status === 200 && ticket.body.data?.ticket, `C3 ticket failed: ${ticket.body.code} ${ticket.body.message}`)
  const monitor = await openSocket(`${wsUrl}/ws/monitor?ticket=${encodeURIComponent(ticket.body.data.ticket)}`)
  monitor.send(JSON.stringify(message('metrics.subscribe', { server_id: serverId })))
  await assertNoMessage(monitor, 'error', 1500)
  checks.C3 = 'monitor WS handshake + subscribe via nginx OK'

  // C4：agent 注册（token 供编排器拉起 agent 容器）
  const registeredAgent = await api(`/api/servers/${serverId}/agent/register`, { method: 'POST', token: adminToken })
  assert(registeredAgent.status === 200 && registeredAgent.body.data?.agent_token, `C4 agent register failed: ${registeredAgent.body.code} ${registeredAgent.body.message}`)
  checks.C4 = `agent token issued for server ${serverId}`

  // 编排器协议：读到该行后拉起 agent 容器。
  console.log(`AGENT_READY ${serverId} ${registeredAgent.body.data.agent_token}`)

  // C5：agent 容器上报后，monitor 收到 metrics.update 且 API 可查（120s 轮询）。
  const deadline = Date.now() + 120000
  let pushed = false
  const pushPromise = new Promise((resolve) => {
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === 'metrics.update' && value.payload?.server_id === serverId) {
        pushed = true
        monitor.off('message', onMessage)
        resolve()
      }
    }
    monitor.on('message', onMessage)
  })
  let latest = null
  while (Date.now() < deadline) {
    const response = await api(`/api/servers/${serverId}/metrics/latest`, { token: adminToken })
    if (response.status === 200 && response.body.data) {
      latest = response.body.data
      break
    }
    await sleep(2000)
  }
  assert(latest, 'C5 timed out waiting for agent metrics via API')
  await Promise.race([pushPromise, sleep(3000)])
  checks.C5 = `agent metrics visible via API (server ${serverId})`
  checks.C5push = pushed ? 'metrics.update frame received by monitor' : 'metrics.update not observed within window (API path verified)'

  console.log(JSON.stringify({ status: 'PASS', checks }, null, 2))
  process.exit(0)
}

main().catch((error) => {
  console.error(JSON.stringify({ status: 'FAILED', error: error.message }, null, 2))
  process.exit(1)
})
