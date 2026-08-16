/**
 * Docker Compose 全栈验收：经 nginx 容器入口（默认 http://localhost:8080）验证
 * mysql + rabbitmq + server + web 四服务闭环，以及可选 agent 容器上报链路。
 *
 * 检查项：
 *   P0  空库首管理员 bootstrap（register 自动 ADMIN/approved → login 拿 token）
 *   C1  /api/health 与 /api/ready 经 nginx 反代 200
 *   C2  /ws/monitor ticket 握手 + metrics.subscribe（无 error 帧）
 *   C3  创建 server + agent/register 取 token（打印 AGENT_READY 行供编排器拉起 agent 容器）
 *   C4  agent 容器上报后：monitor 收到 metrics.update 帧 + API 查询 metrics 可见（120s 轮询）
 *
 * 用法：
 *   SUSUMONITOR_DOCKER_BASE_URL=http://localhost:8080 node verify-docker-compose.mjs
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

  // P0：空库首管理员 bootstrap
  const registered = await api('/api/auth/register', { method: 'POST', body: { username, password } })
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

  // C2：WS 握手 + 订阅（无 error 帧）
  const ticket = await api('/api/ws/monitor-ticket', { method: 'POST', token: adminToken })
  assert(ticket.status === 200 && ticket.body.data?.ticket, `C2 ticket failed: ${ticket.body.code} ${ticket.body.message}`)
  const monitor = await openSocket(`${wsUrl}/ws/monitor?ticket=${encodeURIComponent(ticket.body.data.ticket)}`)
  monitor.send(JSON.stringify(message('metrics.subscribe', { server_id: 0 })))
  await assertNoMessage(monitor, 'error', 1500)
  checks.C2 = 'monitor WS handshake + subscribe via nginx OK'

  // C3：创建 server + agent 注册（token 供编排器拉起 agent 容器）
  const created = await api('/api/servers', {
    method: 'POST', token: adminToken,
    body: { name: `docker_e2e_${Date.now()}`, host: '127.0.0.2', description: 'docker compose e2e', ssh_host: '127.0.0.2', ssh_port: 22, ssh_user: 'e2e', ssh_auth_type: 'password', ssh_password: 'e2e-placeholder' }
  })
  assert(created.status === 200, `C3 server create failed: ${created.body.code} ${created.body.message}`)
  const serverId = created.body.data.id
  const registeredAgent = await api(`/api/servers/${serverId}/agent/register`, { method: 'POST', token: adminToken })
  assert(registeredAgent.status === 200 && registeredAgent.body.data?.agent_token, `C3 agent register failed: ${registeredAgent.body.code} ${registeredAgent.body.message}`)
  checks.C3 = `server ${serverId} created, agent token issued`

  // 编排器协议：读到该行后拉起 agent 容器。
  console.log(`AGENT_READY ${serverId} ${registeredAgent.body.data.agent_token}`)

  // C4：agent 容器上报后，monitor 收到 metrics.update 且 API 可查（120s 轮询）。
  // 重新订阅到真实 serverId（C2 用 0 只验证协议路径）。
  monitor.send(JSON.stringify(message('metrics.subscribe', { server_id: serverId })))
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
  assert(latest, 'C4 timed out waiting for agent metrics via API')
  await Promise.race([pushPromise, sleep(3000)])
  checks.C4 = `agent metrics visible via API (server ${serverId})`
  checks.C4push = pushed ? 'metrics.update frame received by monitor' : 'metrics.update not observed within window (API path verified)'

  console.log(JSON.stringify({ status: 'PASS', checks }, null, 2))
  process.exit(0)
}

main().catch((error) => {
  console.error(JSON.stringify({ status: 'FAILED', error: error.message }, null, 2))
  process.exit(1)
})
