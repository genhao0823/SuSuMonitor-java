import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { WebSocketServer } from 'ws'

const agentExecutable = process.env.SUSUMONITOR_AGENT_EXECUTABLE
if (!agentExecutable) throw new Error('Set SUSUMONITOR_AGENT_EXECUTABLE to the built Agent executable.')

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function waitUntil(predicate, message, timeoutMs = 12_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await predicate()) return
    await sleep(50)
  }
  throw new Error(message)
}

function parseEvents() {
  const events = []
  let pending = ''
  return {
    events,
    append(chunk) {
      pending += chunk.toString()
      const lines = pending.split('\n')
      pending = lines.pop()
      for (const line of lines) {
        try { events.push(JSON.parse(line)) } catch { /* retain only structured Agent logs */ }
      }
    }
  }
}

async function stopAgent(agent) {
  if (!agent || agent.exitCode !== null) return
  agent.kill('SIGTERM')
  await Promise.race([once(agent, 'exit'), sleep(5_000)])
  if (agent.exitCode === null) agent.kill('SIGKILL')
}

class AckFixture {
  constructor() {
    this.dropAck = true
    this.reports = []
    this.heartbeats = 0
    this.sockets = new Set()
    this.server = new WebSocketServer({ host: '127.0.0.1', port: 0 })
    this.server.on('connection', (socket) => this.handle(socket))
  }

  async start() {
    await once(this.server, 'listening')
    const { port } = this.server.address()
    return `ws://127.0.0.1:${port}`
  }

  handle(socket) {
    this.sockets.add(socket)
    socket.on('close', () => this.sockets.delete(socket))
    socket.on('message', (raw) => {
      const message = JSON.parse(raw.toString())
      if (message.type === 'agent.authenticate') {
        socket.send(JSON.stringify({ type: 'agent.authenticated', message_id: null, timestamp: new Date().toISOString(), payload: { server_id: message.payload.server_id } }))
        return
      }
      if (message.type === 'heartbeat') {
        this.heartbeats++
        socket.send(JSON.stringify({ type: 'heartbeat.ack', message_id: message.message_id, timestamp: new Date().toISOString(), payload: {} }))
        return
      }
      if (message.type === 'metrics.report') {
        this.reports.push({ message, at: Date.now() })
        if (!this.dropAck) this.ack(socket, message)
      }
    })
  }

  ack(socket, message) {
    if (socket.readyState === socket.OPEN) {
      socket.send(JSON.stringify({ type: 'metrics.ack', message_id: message.message_id, timestamp: new Date().toISOString(), payload: { server_id: message.payload.server_id, collected_at: message.payload.collected_at } }))
    }
  }

  acknowledge(messageId) {
    const report = this.reports.findLast((item) => item.message.message_id === messageId)
    assert(report, `No report is available to acknowledge for ${messageId}.`)
    for (const socket of this.sockets) this.ack(socket, report.message)
  }

  acknowledgeLatest() {
    const latest = this.reports.at(-1)
    assert(latest, 'No report is available to acknowledge.')
    this.acknowledge(latest.message.message_id)
  }

  nack(messageId, reason) {
    const report = this.reports.findLast((item) => item.message.message_id === messageId)
    assert(report, `No report is available to reject for ${messageId}.`)
    for (const socket of this.sockets) {
      if (socket.readyState === socket.OPEN) {
        socket.send(JSON.stringify({ type: 'metrics.nack', message_id: messageId, timestamp: new Date().toISOString(),
          payload: { server_id: report.message.payload.server_id, code: 40002, reason, message: `rejected: ${reason}` } }))
      }
    }
  }

  sendError(messageId, code, message) {
    for (const socket of this.sockets) {
      if (socket.readyState === socket.OPEN) {
        socket.send(JSON.stringify({ type: 'error', message_id: messageId, timestamp: new Date().toISOString(),
          payload: { code, message } }))
      }
    }
  }

  latestReportFor(serverId) {
    return this.reports.findLast((item) => item.message.payload.server_id === serverId)?.message ?? null
  }

  async close() {
    for (const socket of this.sockets) socket.close()
    await new Promise((resolve) => this.server.close(resolve))
  }
}

function startAgent({ url, serverId, token, spool, capacity = 10, replayInterval = 1000 }) {
  const logs = parseEvents()
  const agent = spawn(agentExecutable, [], {
    env: {
      ...process.env,
      SUSUMONITOR_BACKEND_URL: url,
      SUSUMONITOR_SERVER_ID: String(serverId),
      SUSUMONITOR_AGENT_TOKEN: token,
      SUSUMONITOR_COLLECT_INTERVAL_SECONDS: '1',
      SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS: '2',
      SUSUMONITOR_RECONNECT_INITIAL_SECONDS: '1',
      SUSUMONITOR_RECONNECT_MAX_SECONDS: '2',
      SUSUMONITOR_METRICS_ACK_TIMEOUT_SECONDS: '1',
      SUSUMONITOR_METRICS_RETRY_INITIAL_SECONDS: '1',
      SUSUMONITOR_METRICS_RETRY_MAX_SECONDS: '1',
      SUSUMONITOR_METRICS_RETRY_JITTER_ENABLED: 'false',
      SUSUMONITOR_METRICS_REPLAY_MIN_INTERVAL_MILLIS: String(replayInterval),
      SUSUMONITOR_METRICS_BUFFER_PATH: spool,
      SUSUMONITOR_METRICS_BUFFER_MAX_ENTRIES: String(capacity),
      SUSUMONITOR_LOG_LEVEL: 'debug'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  agent.stdout.on('data', (chunk) => logs.append(chunk))
  agent.stderr.on('data', (chunk) => logs.append(chunk))
  return { agent, ...logs }
}

async function spoolEntries(spool) {
  try {
    return JSON.parse(await readFile(spool, 'utf8')).entries ?? []
  } catch (error) {
    if (error.code === 'ENOENT') return []
    throw error
  }
}

async function spoolDeadLetter(spool) {
  try {
    return JSON.parse(await readFile(spool, 'utf8')).dead_letter ?? []
  } catch (error) {
    if (error.code === 'ENOENT') return []
    throw error
  }
}

async function runJavaIngressScenario() {
  const baseUrl = process.env.SUSUMONITOR_RELIABLE_E2E_BASE_URL
  if (!baseUrl) return 0
  const username = process.env.SUSUMONITOR_RELIABLE_E2E_ADMIN_USERNAME
  const password = process.env.SUSUMONITOR_RELIABLE_E2E_ADMIN_PASSWORD
  assert(username && password, 'Real Java E2E requires isolated administrator credentials.')
  async function api(pathname, { method = 'GET', body, token } = {}) {
    const response = await fetch(`${baseUrl}${pathname}`, {
      method,
      headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body)
    })
    return { status: response.status, body: await response.json() }
  }
  // 批次 8：隔离空库实例仍待初始化首管理员时，注册必须携带一次性初始化令牌（缺失 403/40310）。
  // 令牌由编排器生成并双路注入（服务器 env AUTH_BOOTSTRAP_TOKEN + 本环境变量），此处按状态端点决定是否携带。
  const bootstrapStatus = await api('/api/auth/bootstrap-status')
  const bootstrapPending = bootstrapStatus.status === 200 && bootstrapStatus.body?.data?.bootstrapPending === true
  let registerBody = { username, password }
  if (bootstrapPending) {
    const bootstrapToken = process.env.SUSUMONITOR_RELIABLE_E2E_BOOTSTRAP_TOKEN
    assert(typeof bootstrapToken === 'string' && bootstrapToken.length >= 32 && bootstrapToken.length <= 128,
      'Isolated instance awaits first-admin bootstrap: the orchestrator must inject ' +
      'SUSUMONITOR_RELIABLE_E2E_BOOTSTRAP_TOKEN (32-128 chars, same value as the server AUTH_BOOTSTRAP_TOKEN).')
    registerBody = { username, password, bootstrapToken }
  }
  const registration = await api('/api/auth/register', { method: 'POST', body: registerBody })
  assert(registration.status === 200, `Isolated administrator registration failed with ${registration.status}.`)
  const login = await api('/api/auth/login', { method: 'POST', body: { username, password } })
  assert(login.status === 200, `Isolated administrator login failed with ${login.status}.`)
  const token = login.body.data.token
  const suffix = Date.now()
  const server = await api('/api/servers', {
    method: 'POST', token,
    body: { name: `reliable-e2e-${suffix}`, host: `127.31.${Math.floor(suffix / 256) % 254 + 1}.${suffix % 254 + 1}`,
      description: 'isolated reliable delivery e2e', ssh_host: '127.0.0.1', ssh_port: 22,
      ssh_user: 'root', ssh_auth_type: 'password', ssh_password: 'validation-placeholder' }
  })
  assert(server.status === 200, `Isolated server creation failed with ${server.status}.`)
  const serverId = server.body.data.id
  const registered = await api(`/api/servers/${serverId}/agent/register`, { method: 'POST', token })
  assert(registered.status === 200, `Agent registration failed with ${registered.status}.`)
  const javaWorkspace = await mkdtemp(path.join(tmpdir(), 'susumonitor-agent-reliable-java-'))
  let agent
  try {
    const spool = path.join(javaWorkspace, 'metrics-buffer.json')
    agent = startAgent({ url: baseUrl.replace(/^http/, 'ws'), serverId, token: registered.body.data.agent_token, spool, replayInterval: 1000 })
    await waitUntil(async () => {
      const endTime = new Date(Date.now() + 1_000).toISOString()
      const startTime = new Date(Date.now() - 60_000).toISOString()
      const history = await api(`/api/servers/${serverId}/metrics?start_time=${encodeURIComponent(startTime)}&end_time=${encodeURIComponent(endTime)}&page=1&page_size=20`, { token })
      return history.status === 200 && history.body.data.items.length >= 2
    }, 'Real Java Server did not persist two Agent metrics.', 20_000)
    const endTime = new Date(Date.now() + 1_000).toISOString()
    const startTime = new Date(Date.now() - 60_000).toISOString()
    const history = await api(`/api/servers/${serverId}/metrics?start_time=${encodeURIComponent(startTime)}&end_time=${encodeURIComponent(endTime)}&page=1&page_size=20`, { token })
    const ascending = [...history.body.data.items].reverse()
    assert(ascending.every((item, index) => index === 0 || item.collected_at > ascending[index - 1].collected_at), 'Real Java metrics history is not strictly increasing.')
    await waitUntil(async () => (await spoolEntries(spool)).length === 0, 'Real Java ACK did not clear Agent spool.')
    assert(!agent.events.some((event) => event.code === 42902 || event.msg === 'metrics acknowledgement timed out; retaining and retrying queued frame'), 'Real Java ingress emitted unexpected rate limit or ACK timeout.')
    // 软删除服务器:Agent 后续上报必须收到 correlated server_not_found NACK 并进入本地死信。
    const deleted = await api(`/api/servers/${serverId}`, { method: 'DELETE', token })
    assert(deleted.status === 200, `Isolated server deletion failed with ${deleted.status}.`)
    await waitUntil(async () => {
      const deadLetter = await spoolDeadLetter(spool)
      return deadLetter.some((entry) => entry.reason === 'server_not_found')
          && (await spoolEntries(spool)).length === 0
    }, 'Server deletion did not NACK the Agent head into the dead-letter.', 20_000)
    return 7
  } finally {
    await stopAgent(agent?.agent)
    await rm(javaWorkspace, { recursive: true, force: true })
  }
}

const workspace = await mkdtemp(path.join(tmpdir(), 'susumonitor-agent-reliable-fixture-'))
const fixture = new AckFixture()
const fixtureUrl = await fixture.start()
let firstAgent
let restartAgent
let capacityAgent
let nackAgent
let nackRestartAgent
let errorAgent
try {
  const firstSpool = path.join(workspace, 'first.json')
  firstAgent = startAgent({ url: fixtureUrl, serverId: 9001, token: 'fixture-token', spool: firstSpool })
  await waitUntil(() => fixture.reports.length >= 2, 'Agent did not retransmit after ACK loss.')
  const [firstAttempt, retryAttempt] = fixture.reports
  assert(firstAttempt.message.message_id === retryAttempt.message.message_id, 'ACK retry did not reuse message_id.')
  assert(firstAttempt.message.timestamp === retryAttempt.message.timestamp, 'ACK retry did not preserve outer timestamp.')
  assert(JSON.stringify(firstAttempt.message.payload) === JSON.stringify(retryAttempt.message.payload), 'ACK retry did not preserve payload.')
  assert(fixture.sockets.size === 1 && fixture.heartbeats >= 1, 'ACK loss did not preserve a live authenticated connection.')
  const retained = await spoolEntries(firstSpool)
  assert(retained[0].message_id === firstAttempt.message.message_id, 'Unacknowledged head was not retained in spool.')

  await stopAgent(firstAgent.agent)
  firstAgent = null
  restartAgent = startAgent({ url: fixtureUrl, serverId: 9001, token: 'fixture-token', spool: firstSpool })
  const priorAttempts = fixture.reports.length
  await waitUntil(() => fixture.reports.length > priorAttempts, 'Restarted Agent did not replay durable spool head.')
  const replay = fixture.reports.at(-1).message
  assert(replay.message_id === firstAttempt.message.message_id, 'Restart replay did not use original spool head UUID.')
  fixture.dropAck = false
  fixture.acknowledge(firstAttempt.message.message_id)
  await waitUntil(async () => !(await spoolEntries(firstSpool)).some((entry) => entry.message_id === firstAttempt.message.message_id), 'Spool did not remove the acknowledged restart head.')
  assert(restartAgent.events.some((event) => event.msg === 'metrics acknowledgement persisted'), 'Agent did not record persisted acknowledgement.')
  await stopAgent(restartAgent.agent)
  restartAgent = null

  fixture.dropAck = true
  const capacitySpool = path.join(workspace, 'capacity.json')
  const capacityStart = fixture.reports.length
  capacityAgent = startAgent({ url: fixtureUrl, serverId: 9002, token: 'fixture-token-2', spool: capacitySpool, capacity: 3, replayInterval: 1000 })
  await waitUntil(async () => (await spoolEntries(capacitySpool)).length === 3, 'Capacity scenario did not fill FIFO to three records.')
  await waitUntil(() => capacityAgent.events.some((event) => String(event.error ?? '').includes('metrics buffer is full (3 entries)')), 'Capacity overflow was not logged.')
  const expected = (await spoolEntries(capacitySpool)).map((entry) => entry.message_id)
  fixture.dropAck = false
  fixture.acknowledgeLatest()
  await waitUntil(() => {
    const delivered = fixture.reports.slice(capacityStart).map((report) => report.message.message_id)
    let cursor = 0
    for (const id of delivered) if (id === expected[cursor]) cursor++
    return cursor === expected.length
  }, 'Capacity FIFO prefix was not delivered after ACK forwarding.', 20_000)
  const delivered = fixture.reports.slice(capacityStart).map((report) => report.message.message_id)
  let cursor = 0
  for (const id of delivered) if (id === expected[cursor]) cursor++
  assert(cursor === expected.length, 'Retained capacity FIFO prefix was not delivered in order.')

  // metrics.nack 死信场景:被永久拒绝的队首必须移入本地死信并跨重启持久化。
  fixture.dropAck = true
  const nackSpool = path.join(workspace, 'nack.json')
  nackAgent = startAgent({ url: fixtureUrl, serverId: 9003, token: 'fixture-token-3', spool: nackSpool, capacity: 1, replayInterval: 1000 })
  await waitUntil(() => fixture.latestReportFor(9003) !== null, 'Agent did not send a report for the NACK scenario.')
  const nackTarget = fixture.latestReportFor(9003)
  fixture.nack(nackTarget.message_id, 'stale_collected_at')
  await waitUntil(async () => {
    const deadLetter = await spoolDeadLetter(nackSpool)
    return deadLetter.some((entry) => entry.frame.message_id === nackTarget.message_id)
  }, 'Rejected head was not recorded in the local dead-letter.', 10_000)
  const nackDeadLetter = await spoolDeadLetter(nackSpool)
  assert(nackDeadLetter.some((entry) => entry.frame.message_id === nackTarget.message_id
    && entry.reason === 'stale_collected_at' && entry.code === 40002), 'Dead-letter entry lacks the correlated rejection evidence.')
  assert(nackAgent.events.some((event) => event.msg === 'metrics permanently rejected; moved to local dead-letter'), 'Agent did not log the dead-letter disposition.')
  await stopAgent(nackAgent.agent)
  nackAgent = null
  nackRestartAgent = startAgent({ url: fixtureUrl, serverId: 9003, token: 'fixture-token-3', spool: nackSpool, capacity: 1, replayInterval: 1000 })
  const persistedDeadLetter = await spoolDeadLetter(nackSpool)
  assert(persistedDeadLetter.some((entry) => entry.frame.message_id === nackTarget.message_id), 'Dead-letter was lost after Agent restart.')

  // 泛化 error 帧场景:不得移除队首;随后真实 ACK 正常投递该帧。
  fixture.dropAck = true
  const errorSpool = path.join(workspace, 'error.json')
  errorAgent = startAgent({ url: fixtureUrl, serverId: 9004, token: 'fixture-token-4', spool: errorSpool, capacity: 1, replayInterval: 1000 })
  await waitUntil(() => fixture.latestReportFor(9004) !== null, 'Agent did not send a report for the error scenario.')
  const errorTarget = fixture.latestReportFor(9004)
  fixture.sendError(errorTarget.message_id, 50000, 'server internal failure')
  await sleep(300)
  const retainedAfterError = await spoolEntries(errorSpool)
  assert(retainedAfterError.some((entry) => entry.message_id === errorTarget.message_id), 'Generic error frame removed the queue head.')
  assert(errorAgent.events.some((event) => event.msg === 'server error'), 'Agent did not log the generic server error.')
  fixture.dropAck = false
  fixture.acknowledge(errorTarget.message_id)
  await waitUntil(async () => !(await spoolEntries(errorSpool)).some((entry) => entry.message_id === errorTarget.message_id), 'Acknowledged head was not removed from the spool after the error frame.')

  const javaServerChecks = await runJavaIngressScenario()
  console.log(JSON.stringify({ status: 'PASS', checks: 15 + javaServerChecks, fixture_checks: 15,
    java_server_checks: javaServerChecks, artifacts_cleaned: true }))
} catch (error) {
  console.error(JSON.stringify({ status: 'FAIL', error: error.message, workspace }))
  process.exitCode = 1
} finally {
  await stopAgent(firstAgent?.agent)
  await stopAgent(restartAgent?.agent)
  await stopAgent(capacityAgent?.agent)
  await stopAgent(nackAgent?.agent)
  await stopAgent(nackRestartAgent?.agent)
  await stopAgent(errorAgent?.agent)
  await fixture.close()
  if (process.exitCode !== 1) await rm(workspace, { recursive: true, force: true })
}
