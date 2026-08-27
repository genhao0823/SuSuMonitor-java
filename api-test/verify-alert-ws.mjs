import crypto from 'node:crypto'
import WebSocket from 'ws'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18081'
const wsUrl = baseUrl.replace(/^http/, 'ws')
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const waitTimeoutMs = 5000
const validationPrefix = 'mvp6_alert_validation'
let lastCollectedAtMillis = 0

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

function message(type, payload = {}, messageId = crypto.randomUUID()) {
  return {
    type,
    message_id: messageId,
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

async function waitForCondition(condition, description, timeoutMs = waitTimeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const value = await condition()
    if (value) return value
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  throw new Error(`Timed out waiting for ${description}`)
}

function nextCollectedAt() {
  lastCollectedAtMillis = Math.max(Date.now(), lastCollectedAtMillis + 1000)
  return new Date(lastCollectedAtMillis).toISOString()
}

function metricPayload(serverId, cpuPercent, collectedAt = nextCollectedAt()) {
  return {
    server_id: serverId,
    collected_at: collectedAt,
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
}

function validateAlertPush(frame, serverId, ruleId, expectedCpu) {
  assert(frame.timestamp.endsWith('Z'), 'alert.push timestamp is not UTC')
  assert(frame.payload.server_id === serverId, 'alert.push server_id mismatch')
  const alert = frame.payload.alert
  assert(alert.rule_id === ruleId, 'alert.push rule_id mismatch')
  assert(alert.metric === 'cpu', 'alert.push metric mismatch')
  assert(alert.current_value === expectedCpu, 'alert.push current_value mismatch')
  assert(alert.threshold_value === 80, 'alert.push threshold_value mismatch')
  assert(alert.level === 'warning', 'alert.push level mismatch')
  assert(alert.status === 'unread', 'alert.push status mismatch')
  assert(alert.triggered_at.endsWith('Z'), 'alert.push triggered_at is not UTC')
}

let adminLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
if (adminLogin.status !== 200) {
  const registration = await api('/api/auth/register', {
    method: 'POST',
    body: { username: adminUsername, password: adminPassword }
  })
  assert(registration.status === 200, 'Admin registration failed in the isolated validation database')
  adminLogin = await api('/api/auth/login', {
    method: 'POST',
    body: { username: adminUsername, password: adminPassword }
  })
}
assert(adminLogin.status === 200, 'Admin login failed')
const adminToken = adminLogin.body.data.token

const suffix = Date.now()
const userUsername = `${validationPrefix}_user_${suffix}`
const userPassword = `Validation-${suffix}!`
const registeredUser = await api('/api/auth/register', {
  method: 'POST',
  body: { username: userUsername, password: userPassword }
})
assert(registeredUser.status === 200, 'Validation user registration failed')

const pending = await api('/api/admin/users?status=pending&page=1&page_size=100', { token: adminToken })
assert(pending.status === 200, 'Pending user list failed')
const pendingUser = pending.body.data.items.find((user) => user.username === userUsername)
assert(pendingUser, 'Validation user is missing from pending list')

const approved = await api(`/api/admin/users/${pendingUser.id}/approve`, {
  method: 'PUT',
  token: adminToken
})
assert(approved.status === 200, 'Validation user approval failed')

const userLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: userUsername, password: userPassword }
})
assert(userLogin.status === 200, 'Approved validation user login failed')
const userToken = userLogin.body.data.token

const octet3 = Math.floor(suffix / 256) % 254 + 1
const octet4 = suffix % 254 + 1
const host = `127.4.${octet3}.${octet4}`
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
assert(createdServer.status === 200, 'Validation server creation failed')
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
assert(createdRule.status === 200, 'Alert rule creation failed')
const ruleId = createdRule.body.data.id

const duplicateRule = await api('/api/alerts/rules', {
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
assert(duplicateRule.status === 409, 'Duplicate alert rule did not return HTTP 409')
assert(duplicateRule.body.code === 40900, 'Duplicate alert rule did not return code 40900')

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
assert(ticketResponse.status === 200, 'Monitor ticket issue failed')
const monitor = await openSocket(`${wsUrl}/ws/monitor?ticket=${encodeURIComponent(ticketResponse.body.data.ticket)}`)
monitor.send(JSON.stringify(message('metrics.subscribe', { server_id: serverId })))

const agent = await openSocket(`${wsUrl}/ws/agent`)
const authenticatedPromise = waitForMessage(agent, 'agent.authenticated')
agent.send(JSON.stringify(message('agent.authenticate', { server_id: serverId, token: agentToken })))
await authenticatedPromise

const firstMetric = metricPayload(serverId, 90)
const firstMetricId = crypto.randomUUID()
const firstUpdatePromise = waitForMessage(monitor, 'metrics.update')
const firstAlertPromise = waitForMessage(monitor, 'alert.push')
agent.send(JSON.stringify(message('metrics.report', firstMetric, firstMetricId)))
const [firstUpdate, firstAlert] = await Promise.all([firstUpdatePromise, firstAlertPromise])
assert(firstUpdate.payload.server_id === serverId, 'first metrics.update server_id mismatch')
validateAlertPush(firstAlert, serverId, ruleId, 90)

const recordsAfterFirst = await waitForCondition(async () => {
  const response = await api(`/api/alerts/records?server_id=${serverId}&page=1&page_size=20`, { token: userToken })
  assert(response.status === 200, 'Alert record query failed')
  return response.body.data.total === 1 ? response.body.data.items : null
}, 'one alert record after first breach')
assert(recordsAfterFirst.length === 1, 'First breach record list mismatch')

const continuedMetric = metricPayload(serverId, 91)
const continuedUpdatePromise = waitForMessage(monitor, 'metrics.update')
const noContinuedAlertPromise = assertNoMessage(monitor, 'alert.push')
agent.send(JSON.stringify(message('metrics.report', continuedMetric)))
await continuedUpdatePromise
await noContinuedAlertPromise

const recoveryMetric = metricPayload(serverId, 20)
const recoveryUpdatePromise = waitForMessage(monitor, 'metrics.update')
agent.send(JSON.stringify(message('metrics.report', recoveryMetric)))
await recoveryUpdatePromise
await waitForCondition(async () => {
  const response = await api(`/api/alerts/records?server_id=${serverId}&page=1&page_size=20`, { token: userToken })
  assert(response.status === 200, 'Alert record query after recovery failed')
  return response.body.data.items.some((record) => record.id === firstAlert.payload.alert.id && record.status === 'resolved')
}, 'resolved alert record')

const secondMetric = metricPayload(serverId, 92)
const secondMetricId = crypto.randomUUID()
const secondUpdatePromise = waitForMessage(monitor, 'metrics.update')
const secondAlertPromise = waitForMessage(monitor, 'alert.push')
agent.send(JSON.stringify(message('metrics.report', secondMetric, secondMetricId)))
const [, secondAlert] = await Promise.all([secondUpdatePromise, secondAlertPromise])
validateAlertPush(secondAlert, serverId, ruleId, 92)
assert(secondAlert.payload.alert.id !== firstAlert.payload.alert.id, 'Second breach reused the first alert record')

const noDuplicateEventPromise = assertNoMessage(monitor, 'alert.push')
agent.send(JSON.stringify(message('metrics.report', secondMetric, secondMetricId)))
await noDuplicateEventPromise

const recordsAfterSecond = await waitForCondition(async () => {
  const response = await api(`/api/alerts/records?server_id=${serverId}&page=1&page_size=20`, { token: userToken })
  assert(response.status === 200, 'Final alert record query failed')
  return response.body.data.total === 2 ? response.body.data.items : null
}, 'two alert records after recovery and second breach')
assert(recordsAfterSecond.length === 2, 'Final alert record list mismatch')

monitor.send(JSON.stringify(message('metrics.unsubscribe', { server_id: serverId })))
await new Promise((resolve) => setTimeout(resolve, 100))
const noUnsubscribedAlertPromise = assertNoMessage(monitor, 'alert.push')
const unsubscribeRecoveryMetric = metricPayload(serverId, 10)
agent.send(JSON.stringify(message('metrics.report', unsubscribeRecoveryMetric, crypto.randomUUID())))
await waitForCondition(async () => {
  const response = await api(`/api/alerts/records?server_id=${serverId}&page=1&page_size=20`, { token: userToken })
  assert(response.status === 200, 'Alert record query after unsubscribe recovery failed')
  return response.body.data.items.some((record) => record.id === secondAlert.payload.alert.id && record.status === 'resolved')
}, 'resolved alert record after unsubscribe')
agent.send(JSON.stringify(message('metrics.report', metricPayload(serverId, 95))))
await noUnsubscribedAlertPromise

agent.close()
monitor.close()

console.log(JSON.stringify({
  status: 'PASS',
  server_id: serverId,
  rule_id: ruleId,
  checks: 24,
  token_values_logged: false
}))
