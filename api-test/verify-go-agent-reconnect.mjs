import { spawn } from 'node:child_process'
import WebSocket from 'ws'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://127.0.0.1:18081'
const agentExecutable = process.env.SUSUMONITOR_AGENT_EXECUTABLE
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const connectionGateCount = Number(process.env.SUSUMONITOR_RECONNECT_GATE_CONNECTIONS ?? '0')

if (!agentExecutable || !adminUsername || !adminPassword) {
  throw new Error('Set SUSUMONITOR_AGENT_EXECUTABLE and validation admin environment variables.')
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

const login = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
assert(login.status === 200, 'Admin login failed')
const adminToken = login.body.data.token
const suffix = Date.now()
const created = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `go-reconnect-${suffix}`,
    host: `127.4.${Math.floor(suffix / 256) % 254 + 1}.${suffix % 254 + 1}`,
    description: 'Go Agent reconnect validation',
    ssh_host: '127.0.0.1',
    ssh_port: 22,
    ssh_user: 'root',
    ssh_auth_type: 'password',
    ssh_password: 'validation-placeholder'
  }
})
assert(created.status === 200, `Server creation failed with ${created.status}`)
const registered = await api(`/api/servers/${created.body.data.id}/agent/register`, {
  method: 'POST',
  token: adminToken
})
assert(registered.status === 200, 'Agent Token registration failed')

const gateSockets = await Promise.all(Array.from({ length: connectionGateCount }, () => new Promise((resolve, reject) => {
  const socket = new WebSocket(`${baseUrl.replace(/^http/, 'ws')}/ws/agent`)
  socket.once('open', () => resolve(socket))
  socket.once('error', reject)
})))

const agent = spawn(agentExecutable, [], {
  env: {
    ...process.env,
    SUSUMONITOR_BACKEND_URL: baseUrl.replace(/^http/, 'ws'),
    SUSUMONITOR_SERVER_ID: String(created.body.data.id),
    SUSUMONITOR_AGENT_TOKEN: registered.body.data.agent_token,
    SUSUMONITOR_COLLECT_INTERVAL_SECONDS: '60',
    SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS: '60',
    SUSUMONITOR_RECONNECT_INITIAL_SECONDS: '1',
    SUSUMONITOR_RECONNECT_MAX_SECONDS: '4',
    SUSUMONITOR_LOG_LEVEL: 'info'
  },
  stdio: ['ignore', 'pipe', 'pipe']
})

let output = ''
const events = []
let pendingLog = ''
function collectOutput(data) {
  const chunk = data.toString()
  output += chunk
  pendingLog += chunk
  const lines = pendingLog.split('\n')
  pendingLog = lines.pop()
  for (const line of lines) {
    try {
      events.push(JSON.parse(line))
    } catch {
      // Go Agent only emits JSON logs; partial chunks are retained in output for the next assertion.
    }
  }
}
agent.stdout.on('data', collectOutput)
agent.stderr.on('data', collectOutput)

function reconnectEvents() {
  return events.filter((event) => event.msg === 'connect or authenticate failed, reconnecting')
}

function backoffSeconds(event) {
  if (typeof event.backoff === 'number') return event.backoff / 1_000_000_000
  return Number.parseFloat(String(event.backoff).replace(/s$/, ''))
}

function assertBackoffSequence() {
  const retries = reconnectEvents().filter((event) => [1, 2, 4].includes(backoffSeconds(event)))
  const first = retries.find((event) => backoffSeconds(event) === 1)
  const second = retries.find((event) => backoffSeconds(event) === 2)
  const third = retries.find((event) => backoffSeconds(event) === 4)
  assert(first && second && third,
    `Agent did not emit the complete 1s -> 2s -> 4s backoff sequence: ${JSON.stringify(reconnectEvents().map((event) => event.backoff))}`)
  const firstAt = Date.parse(first.time)
  const secondAt = Date.parse(second.time)
  const thirdAt = Date.parse(third.time)
  // equal jitter 使实际等待范围为退避值的 [1/2, 1]：1s 退避下限 0.5s、2s 退避下限 1.0s，
  // 断言按下限放宽并留调度余量。
  assert(secondAt - firstAt >= 400, '1s backoff elapsed too early')
  assert(thirdAt - secondAt >= 900, '2s backoff elapsed too early')
}

try {
  const backoffDeadline = Date.now() + 12_000
  while (!output.includes('"backoff":4') && Date.now() < backoffDeadline) {
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  assert(output.includes('connect or authenticate failed, reconnecting'), 'Agent did not enter reconnect state')
  assert(output.includes('"backoff":1'), 'Agent did not use the configured initial backoff')
  assert(output.includes('"backoff":2'), 'Agent did not exponentially increase backoff')
  assertBackoffSequence()
  if (connectionGateCount > 0) {
    for (const socket of gateSockets) socket.close()
  }
  const authenticatedDeadline = Date.now() + 8_000
  while (!output.includes('agent authenticated') && Date.now() < authenticatedDeadline) {
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  assert(output.includes('agent authenticated'), 'Agent did not authenticate after retry')
} finally {
  for (const socket of gateSockets) socket.close()
  agent.kill('SIGTERM')
  await new Promise((resolve) => agent.once('exit', resolve))
}

console.log(JSON.stringify({ status: 'PASS', checks: 6, token_values_logged: false }))
