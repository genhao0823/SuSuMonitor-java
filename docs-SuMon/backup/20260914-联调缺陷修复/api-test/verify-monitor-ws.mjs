import crypto from 'node:crypto'
import WebSocket from 'ws'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18081'
const wsUrl = baseUrl.replace(/^http/, 'ws')
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD

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
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  return { status: response.status, body: await response.json() }
}

function message(type, payload = {}) {
  return {
    type,
    message_id: crypto.randomUUID(),
    timestamp: new Date().toISOString(),
    payload
  }
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

function waitForMessage(socket, expectedType, timeoutMs = 5000) {
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

function assertNoMetricsUpdate(socket, timeoutMs = 1500) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.off('message', onMessage)
      resolve()
    }, timeoutMs)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === 'metrics.update') {
        clearTimeout(timeout)
        socket.off('message', onMessage)
        reject(new Error('Received metrics.update after unsubscribe'))
      }
    }
    socket.on('message', onMessage)
  })
}

async function expectHandshakeRejected(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url)
    const timeout = setTimeout(() => {
      socket.terminate()
      reject(new Error('Handshake was not rejected in time'))
    }, 5000)
    socket.once('unexpected-response', (_request, response) => {
      clearTimeout(timeout)
      assert(response.statusCode === 401, `Handshake rejection status mismatch: ${response.statusCode}`)
      resolve()
    })
    socket.once('open', () => {
      clearTimeout(timeout)
      socket.close()
      reject(new Error('Reused ticket unexpectedly opened a connection'))
    })
    socket.once('error', (error) => {
      clearTimeout(timeout)
      if (error.message.includes('401')) resolve()
      else reject(error)
    })
  })
}

const adminLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
assert(adminLogin.status === 200, 'Admin login failed')
const adminToken = adminLogin.body.data.token

const suffix = Date.now()
const userUsername = `monitor_user_${suffix}`
const userPassword = `Monitor-${suffix}!`
const registeredUser = await api('/api/auth/register', {
  method: 'POST',
  body: { username: userUsername, password: userPassword }
})
assert(registeredUser.status === 200, 'Monitor user registration failed')
assert(registeredUser.body.data.reviewStatus === 'pending', 'Monitor user should start pending')

const pending = await api('/api/admin/users/pending', { token: adminToken })
assert(pending.status === 200, 'Pending user list failed')
const pendingUser = pending.body.data.find((user) => user.username === userUsername)
assert(pendingUser, 'Registered monitor user is missing from pending list')

const approved = await api(`/api/admin/users/${pendingUser.id}/approve`, {
  method: 'PUT',
  token: adminToken
})
assert(approved.status === 200, 'Monitor user approval failed')

const userLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: userUsername, password: userPassword }
})
assert(userLogin.status === 200, 'Approved monitor user login failed')
const userToken = userLogin.body.data.token

const octet3 = Math.floor(suffix / 256) % 254 + 1
const octet4 = suffix % 254 + 1
const host = `127.3.${octet3}.${octet4}`
const created = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `monitor-ws-${suffix}`,
    host,
    description: 'Monitor WebSocket validation',
    ssh_host: host,
    ssh_port: 22,
    ssh_user: 'root',
    ssh_auth_type: 'password',
    ssh_password: 'validation-placeholder'
  }
})
assert(created.status === 200, 'Monitor server creation failed')
const serverId = created.body.data.id

const agentRegistered = await api(`/api/servers/${serverId}/agent/register`, {
  method: 'POST',
  token: adminToken
})
assert(agentRegistered.status === 200, 'Agent Token registration failed')
const agentToken = agentRegistered.body.data.agent_token

const ticketResponse = await api('/api/ws/monitor-ticket', {
  method: 'POST',
  token: userToken
})
assert(ticketResponse.status === 200, 'Monitor Ticket issue failed')
assert(ticketResponse.body.data.expires_at.endsWith('Z'), 'Monitor Ticket expiry is not UTC')
const ticket = ticketResponse.body.data.ticket

const monitor = await openSocket(`${wsUrl}/ws/monitor?ticket=${encodeURIComponent(ticket)}`)
monitor.send(JSON.stringify(message('metrics.subscribe', { server_id: serverId })))

const agent = await openSocket(`${wsUrl}/ws/agent`)
const authenticatedPromise = waitForMessage(agent, 'agent.authenticated')
agent.send(JSON.stringify(message('agent.authenticate', {
  server_id: serverId,
  token: agentToken
})))
await authenticatedPromise

const metrics = {
  server_id: serverId,
  collected_at: new Date().toISOString(),
  cpu_percent: 41.7,
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
const updatePromise = waitForMessage(monitor, 'metrics.update')
agent.send(JSON.stringify(message('metrics.report', metrics)))
const update = await updatePromise
assert(update.timestamp.endsWith('Z'), 'metrics.update timestamp is not UTC')
assert(update.payload.server_id === serverId, 'metrics.update server_id mismatch')
assert(update.payload.metrics.cpu_percent === metrics.cpu_percent, 'metrics.update cpu_percent mismatch')
assert(update.payload.metrics.collected_at.endsWith('Z'), 'metrics.update collected_at is not UTC')

monitor.send(JSON.stringify(message('metrics.unsubscribe', { server_id: serverId })))
await new Promise((resolve) => setTimeout(resolve, 100))
const noUpdatePromise = assertNoMetricsUpdate(monitor)
agent.send(JSON.stringify(message('metrics.report', {
  ...metrics,
  collected_at: new Date().toISOString(),
  cpu_percent: 42.8
})))
await noUpdatePromise

await expectHandshakeRejected(`${wsUrl}/ws/monitor?ticket=${encodeURIComponent(ticket)}`)

agent.close()
monitor.close()

console.log(JSON.stringify({
  status: 'PASS',
  server_id: serverId,
  checks: 16,
  token_values_logged: false
}))
