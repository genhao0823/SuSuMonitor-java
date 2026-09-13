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
  const result = await response.json()
  return { status: response.status, body: result }
}

function message(type, payload = {}) {
  return {
    type,
    message_id: crypto.randomUUID(),
    timestamp: new Date().toISOString(),
    payload
  }
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
    const onClose = (code) => {
      cleanup()
      reject(new Error(`Connection closed with ${code} while waiting for ${expectedType}`))
    }
    const cleanup = () => {
      clearTimeout(timeout)
      socket.off('message', onMessage)
      socket.off('close', onClose)
    }
    socket.on('message', onMessage)
    socket.on('close', onClose)
  })
}

function waitForClose(socket, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for close')), timeoutMs)
    socket.once('close', (code) => {
      clearTimeout(timeout)
      resolve(code)
    })
  })
}

async function openSocket() {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${wsUrl}/ws/agent`)
    socket.once('open', () => resolve(socket))
    socket.once('error', reject)
  })
}

const login = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
assert(login.status === 200, 'Admin login failed')
const adminToken = login.body.data.token

const suffix = Date.now()
const octet3 = Math.floor(suffix / 256) % 254 + 1
const octet4 = suffix % 254 + 1
const host = `127.2.${octet3}.${octet4}`
const created = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `agent-ws-${suffix}`,
    host,
    description: 'Agent WebSocket validation',
    ssh_host: host,
    ssh_port: 22,
    ssh_user: 'root',
    ssh_auth_type: 'password',
    ssh_password: 'validation-placeholder'
  }
})
assert(created.status === 200, `Server creation failed with ${created.status}`)
const serverId = created.body.data.id

const registered = await api(`/api/servers/${serverId}/agent/register`, {
  method: 'POST',
  token: adminToken
})
assert(registered.status === 200, 'Agent Token registration failed')
const agentToken = registered.body.data.agent_token
assert(agentToken, 'Agent Token is missing')

const socket = await openSocket()
const authenticatedPromise = waitForMessage(socket, 'agent.authenticated')
socket.send(JSON.stringify(message('agent.authenticate', {
  server_id: serverId,
  token: agentToken
})))
const authenticated = await authenticatedPromise
assert(authenticated.timestamp.endsWith('Z'), 'Authenticated timestamp is not UTC')

const heartbeatPromise = waitForMessage(socket, 'heartbeat.ack')
socket.send(JSON.stringify(message('heartbeat')))
const heartbeat = await heartbeatPromise
assert(heartbeat.timestamp.endsWith('Z'), 'Heartbeat timestamp is not UTC')

const collectedAt = new Date().toISOString()
const metrics = {
  server_id: serverId,
  collected_at: collectedAt,
  cpu_percent: 35.2,
  memory_percent: 48.1,
  memory_used: 481000000,
  memory_total: 1000000000,
  disk_percent: 61.4,
  disk_used: 614000000,
  disk_total: 1000000000,
  net_rx: 123456,
  net_tx: 654321,
  temperature: 42.5,
  load_avg: 0.75
}
socket.send(JSON.stringify(message('metrics.report', metrics)))

await new Promise((resolve) => setTimeout(resolve, 500))
const latest = await api(`/api/servers/${serverId}/metrics/latest`, { token: adminToken })
assert(latest.status === 200, `Latest metrics failed with ${latest.status}`)
assert(latest.body.data.server_id === serverId, 'Latest server_id mismatch')
assert(latest.body.data.cpu_percent === metrics.cpu_percent, 'Latest cpu_percent mismatch')
assert(latest.body.data.collected_at.endsWith('Z'), 'Latest collected_at is not UTC')

const start = encodeURIComponent(new Date(Date.now() - 60_000).toISOString())
const end = encodeURIComponent(new Date(Date.now() + 60_000).toISOString())
const history = await api(`/api/servers/${serverId}/metrics?start_time=${start}&end_time=${end}&page=1&page_size=20`, {
  token: adminToken
})
assert(history.status === 200, `Metrics history failed with ${history.status}`)
assert(history.body.data.items.some((item) => item.server_id === serverId), 'Metrics history is missing the report')

const rotated = await api(`/api/servers/${serverId}/agent/rotate`, {
  method: 'POST',
  token: adminToken
})
assert(rotated.status === 200, 'Agent Token rotation failed')
socket.close()

const oldTokenSocket = await openSocket()
const closePromise = waitForClose(oldTokenSocket)
oldTokenSocket.send(JSON.stringify(message('agent.authenticate', {
  server_id: serverId,
  token: agentToken
})))
const closeCode = await closePromise
assert(closeCode === 1008, `Old token close code mismatch: ${closeCode}`)

console.log(JSON.stringify({
  status: 'PASS',
  server_id: serverId,
  checks: 14,
  token_values_logged: false
}))
