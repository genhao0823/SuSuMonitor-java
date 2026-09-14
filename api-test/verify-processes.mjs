/**
 * 进程级监控验收（协议 v1.4）：进程排行随 metrics.report 上报 → 内存注册表 → processes/latest 端点。
 *
 * 对应开发计划：docs-SuMon/Develop-plans/20260915-进程级监控三端实现计划.md
 *
 * 验证链（走 HTTP REST + 真实 /ws/agent WebSocket，隔离验证环境）：
 *   admin 登录 → 创建验证服务器 → 注册 Agent Token
 *   → 模拟 Agent 鉴权 → metrics.report 携带 process_cpu_top/process_mem_top → 收 metrics.ack
 *   → GET /api/servers/{id}/processes/latest(200) 字段与上报一致
 *   → 旧版 Agent 形态（无进程字段）上报 → ack，快照保持最近一次带进程的数据
 *   → 进程字段非法（占比>100）→ metrics.nack(invalid_metrics_payload)
 *   → 未上报进程数据的新服务器 → processes/latest(404)
 *   → 清理：软删验证服务器
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_BASE_URL=http://SERVER_IP_OR_DOMAIN \
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   node verify-processes.mjs
 *
 * 检查项：
 *   P1 携带进程排行的 metrics.report 获得 metrics.ack
 *   P2 processes/latest 返回 200 且 cpu_top/mem_top 与上报一致
 *   P3 无进程字段的旧版上报获得 ack 且快照不被清空
 *   P4 进程字段非法时收到 metrics.nack(invalid_metrics_payload)
 *   P5 从未上报进程数据的服务器 processes/latest 返回 404/40400
 *   P6 清理：验证服务器软删成功
 */
import crypto from 'node:crypto'
import WebSocket from 'ws'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18080'
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
        reject(new Error(`WebSocket error while waiting for ${expectedType}: ${data}`))
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

async function openSocket() {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${wsUrl}/ws/agent`)
    socket.once('open', () => resolve(socket))
    socket.once('error', reject)
  })
}

const checks = []
function check(id, condition, description) {
  if (!condition) throw new Error(`${id} FAILED: ${description}`)
  checks.push(id)
  console.log(`✓ ${id} ${description}`)
}

// ---- 准备：管理员登录与验证服务器 ----
const login = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
assert(login.status === 200, 'Admin login failed')
const adminToken = login.body.data.token

const suffix = Date.now()
const host = `127.3.${Math.floor(suffix / 256) % 254 + 1}.${suffix % 254 + 1}`
const created = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `processes-${suffix}`,
    host,
    description: 'process snapshot validation',
    ssh_host: host,
    ssh_port: 22,
    ssh_user: 'validation',
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

const socket = await openSocket()
socket.send(JSON.stringify(message('agent.authenticate', { server_id: serverId, token: agentToken })))
await waitForMessage(socket, 'agent.authenticated')

// ---- P1 携带进程排行的上报获得 ack ----
function metricsPayload(collectedAt, withProcesses, overrides = {}) {
  const payload = {
    server_id: serverId,
    collected_at: collectedAt,
    cpu_percent: 12.5,
    memory_percent: 40,
    memory_used: 1024,
    memory_total: 4096,
    disk_percent: 55,
    disk_used: 2048,
    disk_total: 8192,
    net_rx: 100,
    net_tx: 200,
    load_avg: 0.42,
    ...overrides
  }
  if (withProcesses) {
    payload.process_cpu_top = [
      { pid: 9, name: 'java', cpu_percent: 41.2, mem_percent: 18.4 },
      { pid: 8, name: 'node', cpu_percent: 7.5, mem_percent: 9.1 }
    ]
    payload.process_mem_top = [
      { pid: 5, name: 'mysqld', cpu_percent: 0.6, mem_percent: 32.1 },
      { pid: 9, name: 'java', cpu_percent: 41.2, mem_percent: 18.4 }
    ]
  }
  return payload
}

const ackPromise = waitForMessage(socket, 'metrics.ack')
socket.send(JSON.stringify(message('metrics.report', metricsPayload(new Date().toISOString(), true))))
const ack = await ackPromise
check('P1', ack.payload.server_id === serverId && !!ack.payload.collected_at,
  '携带进程排行的 metrics.report 获得 metrics.ack')

// metrics.collected_at 为秒级 DATETIME 且要求严格递增，连续上报需间隔 1 秒以上。
const reportSpacingMs = 1200
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// ---- P2 processes/latest 与上报一致 ----
const latest = await api(`/api/servers/${serverId}/processes/latest`, { token: adminToken })
check('P2',
  latest.status === 200 && latest.body.code === 0
  && latest.body.data.server_id === serverId
  && latest.body.data.cpu_top?.[0]?.name === 'java'
  && latest.body.data.cpu_top?.[0]?.pid === 9
  && latest.body.data.cpu_top?.[0]?.cpu_percent === 41.2
  && latest.body.data.mem_top?.[0]?.name === 'mysqld'
  && latest.body.data.mem_top?.[0]?.mem_percent === 32.1
  && !!latest.body.data.collected_at,
  'processes/latest 返回快照且排行字段与上报一致')

// ---- P3 旧版 Agent 无进程字段：ack 且快照不清空 ----
await sleep(reportSpacingMs)
const legacyAckPromise = waitForMessage(socket, 'metrics.ack')
socket.send(JSON.stringify(message('metrics.report', metricsPayload(new Date().toISOString(), false))))
const legacyAck = await legacyAckPromise
check('P3a', legacyAck.payload.server_id === serverId, '旧版形态（无进程字段）上报获得 metrics.ack')

const latestAfterLegacy = await api(`/api/servers/${serverId}/processes/latest`, { token: adminToken })
check('P3b',
  latestAfterLegacy.status === 200 && latestAfterLegacy.body.data.cpu_top?.[0]?.name === 'java',
  '旧版上报后快照保持最近一次带进程的数据')

// ---- P4 进程字段非法 → metrics.nack(invalid_metrics_payload) ----
await sleep(reportSpacingMs)
const nackPromise = waitForMessage(socket, 'metrics.nack')
socket.send(JSON.stringify(message('metrics.report', metricsPayload(new Date().toISOString(), false, {
  cpu_percent: 13.5,
  process_mem_top: [{ pid: 7, name: 'bad', cpu_percent: 0, mem_percent: 100.5 }]
}))))
const nack = await nackPromise
check('P4', nack.payload.reason === 'invalid_metrics_payload', '进程占比越界收到 metrics.nack(invalid_metrics_payload)')

// ---- P5 未上报进程数据的服务器 → 404 ----
const secondCreated = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `processes-empty-${suffix}`,
    host: `127.4.${Math.floor(suffix / 256) % 254 + 1}.${suffix % 254 + 1}`,
    description: 'process snapshot validation (no data)',
    ssh_host: '127.4.0.1',
    ssh_port: 22,
    ssh_user: 'validation',
    ssh_auth_type: 'password',
    ssh_password: 'validation-placeholder'
  }
})
assert(secondCreated.status === 200, 'Second server creation failed')
const emptyServerId = secondCreated.body.data.id
const notFound = await api(`/api/servers/${emptyServerId}/processes/latest`, { token: adminToken })
check('P5', notFound.status === 404 && notFound.body.code === 40400,
  '无进程数据的服务器 processes/latest 返回 404/40400')

// ---- P6 清理 ----
const deleted = await api(`/api/servers/${serverId}`, { method: 'DELETE', token: adminToken })
const deletedSecond = await api(`/api/servers/${emptyServerId}`, { method: 'DELETE', token: adminToken })
check('P6', deleted.status === 200 && deletedSecond.status === 200, '验证服务器软删成功')

socket.close()
console.log(`\nprocess snapshot validation passed: ${checks.length}/${checks.length} checks`)
