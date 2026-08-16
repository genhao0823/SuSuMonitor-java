/**
 * MVP-9 性能基线：告警链路端到端压测（7 个固定场景）
 *
 * 链路口径：metrics.report（Agent WS 发送）-> MySQL 事务提交 -> AFTER_COMMIT 本地事件
 *          -> Alert 评估/状态机 -> metrics.update / alert.push（Monitor WS 收到）
 *
 * 指标：p50/p95/p99（毫秒）、均值、吞吐（reports/s）、错误帧数、幂等命中比例、正确性断言。
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   node bench-alert-chain.mjs [--scenario=1|2|...|all] [--duration=30000] [--interval=500]
 *
 * 参数：
 *   --scenario=all   场景 1-7（默认 all）
 *   --duration=ms    每个场景测量时长（默认 30000）
 *   --interval=ms    每个 Agent 上报间隔（默认 500）
 *   --agents=N       场景 3 的并发 Agent 数（默认 10）
 *   --rules=N        场景 2 的规则数（默认 100）
 *   --monitors=M     场景 7 的 Monitor 订阅者数（默认 10）
 *   --cycles=N       场景 5/7 的越界-恢复循环数（默认 10）
 *   --duplicates=N   场景 6 的重复 message_id 测试对数（默认 50）
 *   --variant=...    场景 2：no-breach（默认）| breach（全部越界，含告警写入）
 *
 * 注意：压测需在隔离 validation 库实例上执行（默认 18081 端口）。
 * 后端实例需放开 Agent 指标限流：AGENT_METRICS_RATE_PER_MINUTE=6000 AGENT_METRICS_BURST=600。
 */
import crypto from 'node:crypto'
import WebSocket from 'ws'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18081'
const wsUrl = baseUrl.replace(/^http/, 'ws')
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD

if (!adminUsername || !adminPassword) {
  throw new Error('Set SUSUMONITOR_VALIDATION_ADMIN_USERNAME and SUSUMONITOR_VALIDATION_ADMIN_PASSWORD.')
}

function argValue(name, fallback) {
  const match = process.argv.find((value) => value.startsWith(`--${name}=`))
  return match ? match.split('=')[1] : fallback
}
const SCENARIO = argValue('scenario', 'all')
const DURATION_MS = Number(argValue('duration', 30000))
const INTERVAL_MS = Number(argValue('interval', 500))
const AGENT_COUNT = Number(argValue('agents', 10))
const RULE_COUNT = Number(argValue('rules', 100))
const MONITOR_COUNT = Number(argValue('monitors', 10))
const CYCLE_COUNT = Number(argValue('cycles', 10))
const DUPLICATE_PAIRS = Number(argValue('duplicates', 50))
const VARIANT = argValue('variant', 'no-breach')

const THRESHOLD = 80
const BREACH_VALUE = 90
const NORMAL_VALUE = 50
const prefix = `bench_alert_${Date.now()}`

class LatencyStats {
  constructor() { this.samples = [] }
  add(value) { this.samples.push(value) }
  merge(other) { this.samples.push(...other.samples) }
  get count() { return this.samples.length }
  percentile(p) {
    if (this.samples.length === 0) return null
    const sorted = [...this.samples].sort((a, b) => a - b)
    const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))
    return Number(sorted[index].toFixed(2))
  }
  summary() {
    if (this.samples.length === 0) return { count: 0 }
    const mean = this.samples.reduce((a, b) => a + b, 0) / this.samples.length
    return {
      count: this.samples.length,
      meanMs: Number(mean.toFixed(2)),
      p50Ms: this.percentile(50),
      p95Ms: this.percentile(95),
      p99Ms: this.percentile(99),
      minMs: Number(Math.min(...this.samples).toFixed(2)),
      maxMs: Number(Math.max(...this.samples).toFixed(2))
    }
  }
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
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10000)
  })
  return { status: response.status, body: await response.json() }
}

function message(type, payload = {}, messageId = crypto.randomUUID()) {
  return { type, message_id: messageId, timestamp: new Date().toISOString(), payload }
}

function openSocket(url, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url)
    const timeout = setTimeout(() => {
      cleanup()
      socket.terminate()
      reject(new Error(`Timed out opening ${url}`))
    }, timeoutMs)
    const cleanup = () => clearTimeout(timeout)
    socket.once('open', () => { cleanup(); resolve(socket) })
    socket.once('unexpected-response', (_request, response) => {
      cleanup()
      reject(new Error(`Handshake rejected with ${response.statusCode}`))
    })
    socket.once('error', (error) => { cleanup(); reject(error) })
  })
}

function waitForMessage(socket, expectedType, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { cleanup(); reject(new Error(`Timed out waiting for ${expectedType}`)) }, timeoutMs)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === expectedType) { cleanup(); resolve(value) }
    }
    const cleanup = () => { clearTimeout(timeout); socket.off('message', onMessage) }
    socket.on('message', onMessage)
  })
}

async function assertNoMessage(socket, unexpectedType, timeoutMs = 500) {
  let received = null
  await new Promise((resolve) => {
    const timeout = setTimeout(() => { cleanup(); resolve() }, timeoutMs)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === unexpectedType) { received = value; cleanup(); resolve() }
    }
    const cleanup = () => { clearTimeout(timeout); socket.off('message', onMessage) }
    socket.on('message', onMessage)
  })
  return received
}

async function waitForCondition(condition, description, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await condition()) return
    await sleep(100)
  }
  throw new Error(`Timed out waiting for ${description}`)
}

async function sleep(ms) { await new Promise((resolve) => setTimeout(resolve, ms)) }

// 每个服务器独立的单调采样时钟（与 verify-alert-ws.mjs 同口径）
function makeCollectedAt() {
  let last = 0
  return () => {
    last = Math.max(Date.now(), last + 1000)
    return new Date(last).toISOString()
  }
}

function metricPayload(serverId, cpuPercent, collectedAt, messageId = crypto.randomUUID()) {
  return {
    type: 'metrics.report',
    message_id: messageId,
    timestamp: new Date().toISOString(),
    payload: {
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
}

// ---------- 通用装配 ----------

async function setupAdmin() {
  let login = await api('/api/auth/login', { method: 'POST', body: { username: adminUsername, password: adminPassword } })
  if (login.status !== 200) {
    await api('/api/auth/register', { method: 'POST', body: { username: adminUsername, password: adminPassword } })
    login = await api('/api/auth/login', { method: 'POST', body: { username: adminUsername, password: adminPassword } })
  }
  assert(login.status === 200, 'Admin login failed')
  return login.body.data.token
}

let hostCounter = 0
// 每次运行取独立子网基址（127.128-127.226），避免与上次运行残留的服务器 host 冲突（软删除才释放唯一约束）。
const hostBase = 127 + 1 + Math.floor(Math.random() * 100)
async function createServer(adminToken) {
  const octet3 = Math.floor(hostCounter / 254) + 1
  const octet4 = (hostCounter % 254) + 1
  hostCounter += 1
  const host = `${hostBase}.${octet3}.${octet4}`
  const response = await api('/api/servers', {
    method: 'POST', token: adminToken,
    body: { name: `${prefix}_server_${hostCounter}`, host, description: 'bench', ssh_host: host, ssh_port: 22, ssh_user: 'bench', ssh_auth_type: 'password', ssh_password: 'bench-placeholder' }
  })
  assert(response.status === 200, `Server creation failed: ${response.body.code} ${response.body.message}`)
  return response.body.data.id
}

async function createRule(adminToken, serverId, threshold) {
  const response = await api('/api/alerts/rules', {
    method: 'POST', token: adminToken,
    body: { server_id: serverId, metric: 'cpu', operator: '>', threshold_value: threshold, level: 'warning' }
  })
  assert(response.status === 200, `Rule creation failed: ${response.body.code} ${response.body.message}`)
  return response.body.data.id
}

async function registerAgent(adminToken, serverId) {
  const response = await api(`/api/servers/${serverId}/agent/register`, { method: 'POST', token: adminToken })
  assert(response.status === 200, 'Agent token registration failed')
  return response.body.data.agent_token
}

async function openAgent(serverId, agentToken) {
  const agent = await openSocket(`${wsUrl}/ws/agent`)
  const authenticated = waitForMessage(agent, 'agent.authenticated')
  agent.send(JSON.stringify(message('agent.authenticate', { server_id: serverId, token: agentToken })))
  await authenticated
  return agent
}

async function openMonitor(userToken, serverId) {
  const ticketResponse = await api('/api/ws/monitor-ticket', { method: 'POST', token: userToken })
  assert(ticketResponse.status === 200, 'Monitor ticket issue failed')
  const monitor = await openSocket(`${wsUrl}/ws/monitor?ticket=${encodeURIComponent(ticketResponse.body.data.ticket)}`)
  monitor.send(JSON.stringify(message('metrics.subscribe', { server_id: serverId })))
  return monitor
}

async function setupUser(adminToken) {
  const username = `${prefix}_user`
  const password = `Bench-${Date.now()}!`
  const registered = await api('/api/auth/register', { method: 'POST', body: { username, password } })
  assert(registered.status === 200, 'Bench user registration failed')
  const pending = await api('/api/admin/users?status=pending&page=1&page_size=100', { token: adminToken })
  const user = pending.body.data.items.find((item) => item.username === username)
  assert(user, 'Bench user missing from pending list')
  const approved = await api(`/api/admin/users/${user.id}/approve`, { method: 'PUT', token: adminToken })
  assert(approved.status === 200, 'Bench user approval failed')
  const login = await api('/api/auth/login', { method: 'POST', body: { username, password } })
  return login.body.data.token
}

async function recordCount(adminToken, serverId) {
  const response = await api(`/api/alerts/records?server_id=${serverId}&page=1&page_size=1`, { token: adminToken })
  assert(response.status === 200, 'Alert record query failed')
  return response.body.data.total
}

async function cleanup(adminToken, resources) {
  for (const ruleId of resources.ruleIds ?? []) {
    await api(`/api/alerts/rules/${ruleId}`, { method: 'DELETE', token: adminToken })
  }
  for (const serverId of resources.serverIds ?? []) {
    await api(`/api/servers/${serverId}`, { method: 'DELETE', token: adminToken })
  }
}

// ---------- 定时上报助手 ----------

/**
 * 以固定间隔持续上报。
 * 延迟口径：metrics.report 发送时刻 -> Monitor 收到该服务器的 metrics.update 帧。
 * 每个服务器一个 FIFO 队列关联发送与到达（同连接消息有序，队列顺序成立）。
 */
function startReporter(agent, monitor, serverId, collectedAt) {
  const stats = new LatencyStats()
  const queue = [] // FIFO: sentAt
  let errorFrames = 0
  monitor.on('message', (data) => {
    const value = JSON.parse(data.toString())
    if (value.type === 'metrics.update' && value.payload.server_id === serverId) {
      const sentAt = queue.shift()
      if (sentAt !== undefined) stats.add(performance.now() - sentAt)
    }
  })
  agent.on('message', (data) => {
    const value = JSON.parse(data.toString())
    if (value.type === 'error') errorFrames += 1
  })
  const send = (cpuPercent) => {
    queue.push(performance.now())
    agent.send(JSON.stringify(metricPayload(serverId, cpuPercent, collectedAt())))
  }
  return { stats, send, errorFrames: () => errorFrames }
}

/** 定时循环：每 intervalMs 调一次 send()，共 durationMs。 */
async function runSustained(send, durationMs, intervalMs) {
  const deadline = Date.now() + durationMs
  while (Date.now() < deadline) {
    send()
    await sleep(intervalMs)
  }
}

// ---------- 场景 ----------

async function scenario1(adminToken, userToken) {
  const serverId = await createServer(adminToken)
  const ruleId = await createRule(adminToken, serverId, THRESHOLD)
  const agentToken = await registerAgent(adminToken, serverId)
  const agent = await openAgent(serverId, agentToken)
  const monitor = await openMonitor(userToken, serverId)
  const collectedAt = makeCollectedAt()
  const reporter = startReporter(agent, monitor, serverId, collectedAt)

  // 开头一次越界+恢复，取 alert.push 触发基线样本
  const alertLatency = new LatencyStats()
  let breachSentAt = 0
  monitor.on('message', (data) => {
    const value = JSON.parse(data.toString())
    if (value.type === 'alert.push' && value.payload.server_id === serverId) {
      alertLatency.add(performance.now() - breachSentAt)
    }
  })
  breachSentAt = performance.now()
  const firstPushPromise = waitForMessage(monitor, 'alert.push')
  agent.send(JSON.stringify(metricPayload(serverId, BREACH_VALUE, collectedAt())))
  await firstPushPromise
  agent.send(JSON.stringify(metricPayload(serverId, NORMAL_VALUE, collectedAt())))
  await sleep(300)

  // 正常值持续上报：metrics 链路基线
  await runSustained(() => reporter.send(NORMAL_VALUE), DURATION_MS, INTERVAL_MS)

  const records = await recordCount(adminToken, serverId)
  assert(records === 1, `Scenario 1: expected 1 record, got ${records}`)
  agent.close(); monitor.close()
  await cleanup(adminToken, { serverIds: [serverId], ruleIds: [ruleId] })
  return {
    scenario: 1, description: '单服务器少量规则基线',
    metricLatency: reporter.stats.summary(), alertPushLatency: alertLatency.summary(),
    throughputPerSecond: Number((reporter.stats.count / (DURATION_MS / 1000)).toFixed(2)),
    errorFrames: reporter.errorFrames(), correctness: { records }
  }
}

async function scenario2(adminToken, userToken) {
  const serverId = await createServer(adminToken)
  const ruleIds = []
  for (let k = 1; k <= RULE_COUNT; k += 1) {
    // no-breach：阈值 51..(50+N)，cpu=50 不越界；breach：阈值 99..(100-N)，cpu=95 越界其中 N-5 条
    ruleIds.push(await createRule(adminToken, serverId, VARIANT === 'breach' ? (100 - k) : (50 + k)))
  }
  const agentToken = await registerAgent(adminToken, serverId)
  const agent = await openAgent(serverId, agentToken)
  const monitor = await openMonitor(userToken, serverId)
  const reporter = startReporter(agent, monitor, serverId, makeCollectedAt())

  if (VARIANT === 'breach') {
    // 首次上报触发所有越界规则：测量规则写入 + alert.push fan-out
    const pushLatency = new LatencyStats()
    let breachReportAt = 0
    monitor.on('message', (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === 'alert.push' && value.payload.server_id === serverId) {
        pushLatency.add(performance.now() - breachReportAt)
      }
    })
    const deadline = Date.now() + DURATION_MS
    let first = true
    while (Date.now() < deadline) {
      if (first) { breachReportAt = performance.now(); first = false }
      reporter.send(BREACH_VALUE + 5)
      await sleep(INTERVAL_MS)
    }
    await sleep(500)
    const records = await recordCount(adminToken, serverId)
    const expectedBreaches = RULE_COUNT - 5 // 阈值 < 95 的规则数
    assert(records === expectedBreaches, `Scenario 2 breach: expected ${expectedBreaches} records, got ${records}`)
    assert(pushLatency.count === expectedBreaches, `Scenario 2 breach: expected ${expectedBreaches} pushes, got ${pushLatency.count}`)
    const result = {
      scenario: 2, description: `单服务器多规则（breach 变体，${RULE_COUNT} 条规则）`,
      ruleCount: RULE_COUNT, variant: 'breach',
      metricLatency: reporter.stats.summary(), alertPushLatency: pushLatency.summary(),
      errorFrames: reporter.errorFrames(), correctness: { records, expectedBreaches }
    }
    agent.close(); monitor.close()
    await cleanup(adminToken, { serverIds: [serverId], ruleIds })
    return result
  }

  // no-breach：纯规则评估扩展性
  await runSustained(() => reporter.send(NORMAL_VALUE), DURATION_MS, INTERVAL_MS)
  const records = await recordCount(adminToken, serverId)
  assert(records === 0, `Scenario 2 no-breach: expected 0 records, got ${records}`)
  const result = {
    scenario: 2, description: `单服务器多规则（no-breach 变体，${RULE_COUNT} 条规则）`,
    ruleCount: RULE_COUNT, variant: 'no-breach',
    metricLatency: reporter.stats.summary(), alertPushLatency: { count: 0 },
    throughputPerSecond: Number((reporter.stats.count / (DURATION_MS / 1000)).toFixed(2)),
    errorFrames: reporter.errorFrames(), correctness: { records }
  }
  agent.close(); monitor.close()
  await cleanup(adminToken, { serverIds: [serverId], ruleIds })
  return result
}

async function scenario3(adminToken, userToken, agentCount) {
  const servers = []
  const ruleIds = []
  const agents = []
  const monitors = []
  const reporters = []
  const merged = new LatencyStats()
  for (let i = 0; i < agentCount; i += 1) {
    const serverId = await createServer(adminToken)
    ruleIds.push(await createRule(adminToken, serverId, THRESHOLD))
    const agentToken = await registerAgent(adminToken, serverId)
    const agent = await openAgent(serverId, agentToken)
    const monitor = await openMonitor(userToken, serverId)
    reporters.push(startReporter(agent, monitor, serverId, makeCollectedAt()))
    agents.push(agent); monitors.push(monitor); servers.push(serverId)
  }
  // 交错启动，避免全部同时冲击
  await Promise.all(reporters.map(async (reporter, index) => {
    await sleep(index * 50)
    await runSustained(() => reporter.send(NORMAL_VALUE), DURATION_MS, INTERVAL_MS)
  }))
  await sleep(300)
  for (const reporter of reporters) merged.merge(reporter.stats)
  const totalReports = reporters.reduce((sum, reporter) => sum + reporter.stats.count, 0)
  const errorFrames = reporters.reduce((sum, reporter) => sum + reporter.errorFrames(), 0)
  const result = {
    scenario: 3, description: `多服务器并发上报（${agentCount} Agent 连接）`,
    agentCount, totalReports,
    metricLatency: merged.summary(),
    throughputPerSecond: Number((totalReports / (DURATION_MS / 1000)).toFixed(2)),
    errorFrames
  }
  for (const agent of agents) agent.close()
  for (const monitor of monitors) monitor.close()
  await cleanup(adminToken, { serverIds: servers, ruleIds })
  return result
}

async function scenario4(adminToken, userToken) {
  const serverId = await createServer(adminToken)
  const ruleId = await createRule(adminToken, serverId, THRESHOLD)
  const agentToken = await registerAgent(adminToken, serverId)
  const agent = await openAgent(serverId, agentToken)
  const monitor = await openMonitor(userToken, serverId)
  const reporter = startReporter(agent, monitor, serverId, makeCollectedAt())

  let pushCount = 0
  monitor.on('message', (data) => {
    const value = JSON.parse(data.toString())
    if (value.type === 'alert.push' && value.payload.server_id === serverId) pushCount += 1
  })

  await runSustained(() => reporter.send(BREACH_VALUE), DURATION_MS, INTERVAL_MS)
  await sleep(300)
  const records = await recordCount(adminToken, serverId)
  assert(records === 1, `Scenario 4: expected 1 record, got ${records}`)
  assert(pushCount === 1, `Scenario 4: expected 1 alert.push, got ${pushCount}`)
  const result = {
    scenario: 4, description: '持续越界（不重复告警）',
    metricLatency: reporter.stats.summary(),
    throughputPerSecond: Number((reporter.stats.count / (DURATION_MS / 1000)).toFixed(2)),
    errorFrames: reporter.errorFrames(), correctness: { records, pushCount }
  }
  agent.close(); monitor.close()
  await cleanup(adminToken, { serverIds: [serverId], ruleIds: [ruleId] })
  return result
}

async function scenario5(adminToken, userToken) {
  const serverId = await createServer(adminToken)
  const ruleId = await createRule(adminToken, serverId, THRESHOLD)
  const agentToken = await registerAgent(adminToken, serverId)
  const agent = await openAgent(serverId, agentToken)
  const monitor = await openMonitor(userToken, serverId)
  const collectedAt = makeCollectedAt()
  const reporter = startReporter(agent, monitor, serverId, collectedAt)
  const alertLatency = new LatencyStats()
  let breachSentAt = 0
  monitor.on('message', (data) => {
    const value = JSON.parse(data.toString())
    if (value.type === 'alert.push' && value.payload.server_id === serverId) {
      alertLatency.add(performance.now() - breachSentAt)
    }
  })

  for (let cycle = 0; cycle < CYCLE_COUNT; cycle += 1) {
    const pushPromise = waitForMessage(monitor, 'alert.push')
    breachSentAt = performance.now()
    agent.send(JSON.stringify(metricPayload(serverId, BREACH_VALUE, collectedAt())))
    await pushPromise
    agent.send(JSON.stringify(metricPayload(serverId, NORMAL_VALUE, collectedAt())))
    await sleep(200) // 等恢复评估完成（同连接消息有序）
  }
  await sleep(300)
  const records = await recordCount(adminToken, serverId)
  assert(records === CYCLE_COUNT, `Scenario 5: expected ${CYCLE_COUNT} records, got ${records}`)
  assert(alertLatency.count === CYCLE_COUNT, `Scenario 5: expected ${CYCLE_COUNT} alert latencies, got ${alertLatency.count}`)
  const result = {
    scenario: 5, description: `恢复后再次越界（${CYCLE_COUNT} 个完整状态机循环）`,
    cycles: CYCLE_COUNT, alertPushLatency: alertLatency.summary(),
    errorFrames: reporter.errorFrames(), correctness: { records }
  }
  agent.close(); monitor.close()
  await cleanup(adminToken, { serverIds: [serverId], ruleIds: [ruleId] })
  return result
}

async function scenario6(adminToken, userToken) {
  const serverId = await createServer(adminToken)
  const ruleId = await createRule(adminToken, serverId, THRESHOLD)
  const agentToken = await registerAgent(adminToken, serverId)
  const agent = await openAgent(serverId, agentToken)
  const monitor = await openMonitor(userToken, serverId)
  const collectedAt = makeCollectedAt()
  const firstLatency = new LatencyStats()
  let pushCount = 0
  let errorFrames = 0
  monitor.on('message', (data) => {
    const value = JSON.parse(data.toString())
    if (value.type === 'alert.push' && value.payload.server_id === serverId) pushCount += 1
  })
  agent.on('message', (data) => {
    if (JSON.parse(data.toString()).type === 'error') errorFrames += 1
  })

  let duplicatesHit = 0
  for (let pair = 0; pair < DUPLICATE_PAIRS; pair += 1) {
    const value = pair % 2 === 0 ? BREACH_VALUE : NORMAL_VALUE
    const report = metricPayload(serverId, value, collectedAt(), crypto.randomUUID())
    // 发帧前先注册监听器，避免推送先于监听器到达而丢失（与 verify-alert-ws.mjs 同模式）。
    const updatePromise = waitForMessage(monitor, 'metrics.update')
    const pushPromise = value === BREACH_VALUE ? waitForMessage(monitor, 'alert.push') : null
    const sentAt = performance.now()
    agent.send(JSON.stringify(report))
    await updatePromise
    firstLatency.add(performance.now() - sentAt)
    if (pushPromise) await pushPromise

    // 相同 message_id 重放：必须被幂等拒绝（无 metrics.update、无重复 alert.push）
    agent.send(JSON.stringify(report))
    const duplicateUpdate = await assertNoMessage(monitor, 'metrics.update', 400)
    if (duplicateUpdate === null) duplicatesHit += 1
  }
  await sleep(300)
  const expectedBreaches = Math.ceil(DUPLICATE_PAIRS / 2)
  const records = await recordCount(adminToken, serverId)
  assert(records === expectedBreaches, `Scenario 6: expected ${expectedBreaches} records, got ${records}`)
  assert(pushCount === expectedBreaches, `Scenario 6: expected ${expectedBreaches} pushes, got ${pushCount}`)
  const result = {
    scenario: 6, description: `重复 message_id 幂等（${DUPLICATE_PAIRS} 对）`,
    pairs: DUPLICATE_PAIRS,
    firstReportLatency: firstLatency.summary(),
    idempotentHitRatio: Number((duplicatesHit / DUPLICATE_PAIRS).toFixed(3)),
    errorFrames, correctness: { records, pushCount }
  }
  agent.close(); monitor.close()
  await cleanup(adminToken, { serverIds: [serverId], ruleIds: [ruleId] })
  return result
}

async function scenario7(adminToken, userToken, monitorCount) {
  const serverId = await createServer(adminToken)
  const ruleId = await createRule(adminToken, serverId, THRESHOLD)
  const agentToken = await registerAgent(adminToken, serverId)
  const agent = await openAgent(serverId, agentToken)
  const monitors = []
  for (let i = 0; i < monitorCount; i += 1) monitors.push(await openMonitor(userToken, serverId))
  const collectedAt = makeCollectedAt()
  const deliveryLatency = new LatencyStats()
  let lastSentAt = 0
  const perMonitorPushes = monitors.map(() => 0)
  monitors.forEach((monitor, index) => {
    monitor.on('message', (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === 'alert.push' && value.payload.server_id === serverId) {
        perMonitorPushes[index] += 1
        deliveryLatency.add(performance.now() - lastSentAt)
      }
    })
  })

  for (let cycle = 0; cycle < CYCLE_COUNT; cycle += 1) {
    const firstPushPromise = waitForMessage(monitors[0], 'alert.push')
    lastSentAt = performance.now()
    agent.send(JSON.stringify(metricPayload(serverId, BREACH_VALUE, collectedAt())))
    await firstPushPromise
    await waitForCondition(() => perMonitorPushes.every((count) => count >= cycle + 1),
      `all ${monitorCount} monitors received push ${cycle + 1} (counts=${perMonitorPushes.join(',')})`)
    agent.send(JSON.stringify(metricPayload(serverId, NORMAL_VALUE, collectedAt())))
    await sleep(200)
  }
  await sleep(300)
  const records = await recordCount(adminToken, serverId)
  assert(records === CYCLE_COUNT, `Scenario 7: expected ${CYCLE_COUNT} records, got ${records}`)
  const expectedDeliveries = CYCLE_COUNT * monitorCount
  assert(deliveryLatency.count === expectedDeliveries,
    `Scenario 7: expected ${expectedDeliveries} deliveries, got ${deliveryLatency.count}`)
  const result = {
    scenario: 7, description: `Monitor 订阅者数量（${monitorCount} 订阅，${CYCLE_COUNT} 次触发）`,
    monitorCount, cycles: CYCLE_COUNT,
    alertPushFanoutLatency: deliveryLatency.summary(),
    perMonitorDeliveries: CYCLE_COUNT, correctness: { records }
  }
  agent.close()
  for (const monitor of monitors) monitor.close()
  await cleanup(adminToken, { serverIds: [serverId], ruleIds: [ruleId] })
  return result
}

// ---------- 主流程 ----------

async function main() {
  const adminToken = await setupAdmin()
  const userToken = await setupUser(adminToken)
  const selected = SCENARIO === 'all' ? [1, 2, 3, 4, 5, 6, 7] : SCENARIO.split(',').map(Number)
  const results = []
  console.log(`[bench] start=${new Date().toISOString()} scenario=${SCENARIO} duration=${DURATION_MS}ms interval=${INTERVAL_MS}ms base=${baseUrl}`)

  for (const scenario of selected) {
    const scenarioStart = performance.now()
    try {
      // 场景级看门狗：预计耗时（setup + duration + 余量）的 2 倍封顶，防止单个场景挂死阻塞全量。
      const maxSetupSeconds = scenario === 3 ? AGENT_COUNT * 2 : 30
      const watchdogMs = 2 * (DURATION_MS + maxSetupSeconds * 1000 + 30000)
      const result = await Promise.race([
        {
          1: () => scenario1(adminToken, userToken),
          2: () => scenario2(adminToken, userToken),
          3: () => scenario3(adminToken, userToken, AGENT_COUNT),
          4: () => scenario4(adminToken, userToken),
          5: () => scenario5(adminToken, userToken),
          6: () => scenario6(adminToken, userToken),
          7: () => scenario7(adminToken, userToken, MONITOR_COUNT)
        }[scenario](),
        new Promise((_resolve, reject) => setTimeout(() => reject(new Error(`Scenario ${scenario} watchdog timeout`)), watchdogMs))
      ])
      result.elapsedSeconds = Number(((performance.now() - scenarioStart) / 1000).toFixed(1))
      results.push(result)
      console.log(`[bench] scenario ${scenario} PASS in ${result.elapsedSeconds}s`)
    } catch (error) {
      console.error(`[bench] scenario ${scenario} FAILED: ${error.message}`)
      results.push({ scenario, status: 'FAILED', error: error.message })
    }
  }

  console.log(JSON.stringify({ status: 'DONE', results }, null, 2))
  // 压测工具显式退出：失败路径可能残留未关闭的 WS 连接，避免进程挂住。
  process.exit(0)
}

main().catch((error) => {
  console.error('[bench] fatal:', error)
  process.exit(1)
})
