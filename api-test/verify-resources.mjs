/**
 * 多盘/多网卡资源快照验收（协议 v1.5）：扩展资源随 metrics.report 上报 → 内存注册表 → resources/latest 端点。
 *
 * 对应开发计划：docs-SuMon/Develop-plans/20260915-批次4-多盘多网卡下钻与批次5-M3门禁评审实现计划.md
 *
 * 验证链（走 HTTP REST + 真实 /ws/agent WebSocket，隔离验证环境）：
 *   admin 登录 → 创建验证服务器 → 注册 Agent Token
 *   → 模拟 Agent 鉴权 → metrics.report 携带 disks/nics → 收 metrics.ack
 *   → GET /api/servers/{id}/resources/latest(200) 字段与上报一致
 *   → 旧版 Agent 形态（无扩展字段）上报 → ack，快照保持最近一次带资源的数据
 *   → 扩展字段超限（disks 33 条）→ ack（核心链路不断），快照停留在上一轮数据
 *   → 未上报资源数据的新服务器 → resources/latest(404)
 *   → 清理：软删验证服务器
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_BASE_URL=http://SERVER_IP_OR_DOMAIN \
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   node verify-resources.mjs
 *
 * 检查项：
 *   R1 携带扩展资源的 metrics.report 获得 metrics.ack
 *   R2 resources/latest 返回 200 且 disks/nics 与上报一致
 *   R3 无扩展字段的旧版上报获得 ack 且快照不被清空
 *   R4 磁盘列表超限时扩展快照被丢弃（ack 正常、快照停留在上一轮）
 *   R5 从未上报资源数据的服务器 resources/latest 返回 404/40400
 *   R6 清理：验证服务器软删成功
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
const host = `127.5.${Math.floor(suffix / 256) % 254 + 1}.${suffix % 254 + 1}`
const created = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `resources-${suffix}`,
    host,
    description: 'resource snapshot validation',
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

// ---- 载荷构造 ----
function disksPayload(count) {
  const disks = []
  for (let i = 0; i < count; i++) {
    disks.push({ mount_point: `/mnt/d${i}`, device: `/dev/sd${i}`, total: 102400 + i, free: 51200 })
  }
  return disks
}

function metricsPayload(collectedAt, overrides = {}) {
  return {
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
}

// metrics.collected_at 为秒级 DATETIME 且要求严格递增，连续上报需间隔 1 秒以上。
const reportSpacingMs = 1200
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// ---- R1 携带扩展资源的上报获得 ack ----
const ackPromise = waitForMessage(socket, 'metrics.ack')
socket.send(JSON.stringify(message('metrics.report', metricsPayload(new Date().toISOString(), {
  disks: disksPayload(2),
  nics: [
    { name: 'eth0', rx_kbps: 640.5, tx_kbps: 12.8 },
    { name: 'eth1', rx_kbps: 96.25, tx_kbps: 3.5 }
  ]
}))))
const ack = await ackPromise
check('R1', ack.payload.server_id === serverId && !!ack.payload.collected_at,
  '携带扩展资源的 metrics.report 获得 metrics.ack')

// ---- R2 resources/latest 与上报一致 ----
const latest = await api(`/api/servers/${serverId}/resources/latest`, { token: adminToken })
check('R2',
  latest.status === 200 && latest.body.code === 0
  && latest.body.data.server_id === serverId
  && latest.body.data.disks?.length === 2
  && latest.body.data.disks?.[0]?.mount_point === '/mnt/d0'
  && latest.body.data.disks?.[0]?.device === '/dev/sd0'
  && latest.body.data.disks?.[0]?.total === 102400
  && latest.body.data.disks?.[0]?.free === 51200
  && latest.body.data.nics?.length === 2
  && latest.body.data.nics?.[0]?.name === 'eth0'
  && latest.body.data.nics?.[0]?.rx_kbps === 640.5
  && latest.body.data.nics?.[1]?.name === 'eth1'
  && !!latest.body.data.collected_at,
  'resources/latest 返回快照且磁盘/网卡字段与上报一致')

// ---- R3 旧版 Agent 无扩展字段：ack 且快照不清空 ----
await sleep(reportSpacingMs)
const legacyAckPromise = waitForMessage(socket, 'metrics.ack')
socket.send(JSON.stringify(message('metrics.report', metricsPayload(new Date().toISOString()))))
const legacyAck = await legacyAckPromise
check('R3a', legacyAck.payload.server_id === serverId, '旧版形态（无扩展字段）上报获得 metrics.ack')

const latestAfterLegacy = await api(`/api/servers/${serverId}/resources/latest`, { token: adminToken })
check('R3b',
  latestAfterLegacy.status === 200 && latestAfterLegacy.body.data.disks?.[0]?.mount_point === '/mnt/d0',
  '旧版上报后快照保持最近一次带资源的数据')

// ---- R4 磁盘列表超限：扩展快照丢弃（ack 正常，快照停留在上一轮） ----
await sleep(reportSpacingMs)
const oversizedAckPromise = waitForMessage(socket, 'metrics.ack')
socket.send(JSON.stringify(message('metrics.report', metricsPayload(new Date().toISOString(), {
  disks: disksPayload(33)
}))))
const oversizedAck = await oversizedAckPromise
check('R4a', oversizedAck.payload.server_id === serverId, '磁盘列表超限（33 条）仍获得 metrics.ack（核心链路不断）')

const latestAfterOversized = await api(`/api/servers/${serverId}/resources/latest`, { token: adminToken })
check('R4b',
  latestAfterOversized.status === 200
  && latestAfterOversized.body.data.disks?.length === 2
  && latestAfterOversized.body.data.collected_at === latestAfterLegacy.body.data.collected_at,
  '超限轮次的扩展快照被丢弃，快照停留在上一轮数据')

// ---- R5 未上报资源数据的服务器 → 404 ----
const secondCreated = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `resources-empty-${suffix}`,
    host: `127.6.${Math.floor(suffix / 256) % 254 + 1}.${suffix % 254 + 1}`,
    description: 'resource snapshot validation (no data)',
    ssh_host: '127.6.0.1',
    ssh_port: 22,
    ssh_user: 'validation',
    ssh_auth_type: 'password',
    ssh_password: 'validation-placeholder'
  }
})
assert(secondCreated.status === 200, 'Second server creation failed')
const emptyServerId = secondCreated.body.data.id
const notFound = await api(`/api/servers/${emptyServerId}/resources/latest`, { token: adminToken })
check('R5', notFound.status === 404 && notFound.body.code === 40400,
  '无资源数据的服务器 resources/latest 返回 404/40400')

// ---- R6 清理 ----
const deleted = await api(`/api/servers/${serverId}`, { method: 'DELETE', token: adminToken })
const deletedSecond = await api(`/api/servers/${emptyServerId}`, { method: 'DELETE', token: adminToken })
check('R6', deleted.status === 200 && deletedSecond.status === 200, '验证服务器软删成功')

socket.close()
console.log(`\nresource snapshot validation passed: ${checks.length}/${checks.length} checks`)
