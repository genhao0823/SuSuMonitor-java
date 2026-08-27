/**
 * M4-server-put-existence-check 验收补全：PUT /api/servers/{id} 存在性检查顺序 + 更新成功 + 软删后二次 PUT。
 *
 * 对应 Bug-fix/2026-07-21-M4-server-put-existence-check.md 两条未勾选验收项：
 *   - [ ] 真实 HTTP: 合法服务器更新成功
 *   - [ ] 集成测试:连续两次 PUT 同一个 ID,第二次应 404(第一次成功后被软删除的场景)
 *
 * 验证链（走 HTTP REST，隔离验证库）：
 *   admin 登录 → POST /api/servers 创建 → PUT 合法 body 成功(200/0) → GET 核对更新生效
 *   → DELETE 软删除 → 再次 PUT 同一 ID → 404/40400 → PUT 不存在 ID → 404/40400
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_BASE_URL=http://localhost:18080 \
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   node verify-server-put.mjs
 *
 * 检查项：
 *   P1 创建服务器成功
 *   P2 合法 body 更新成功（200 / code=0）
 *   P3 更新后的字段已在 GET 详情中生效
 *   P4 软删除成功
 *   P5 软删后再次 PUT 同一 ID → 404 / 40400（存在性检查先于参数校验）
 *   P5b 对从未存在的 ID 直接 PUT → 404 / 40400
 */
import crypto from 'node:crypto'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18080'
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const validationPrefix = 'server_put'

if (!adminUsername || !adminPassword) {
  throw new Error('Set SUSUMONITOR_VALIDATION_ADMIN_USERNAME and SUSUMONITOR_VALIDATION_ADMIN_PASSWORD.')
}

const checks = []
function check(id, condition, description) {
  if (!condition) throw new Error(`${id} FAILED: ${description}`)
  checks.push(id)
  console.log(`✓ ${id} ${description}`)
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

// ---- 准备：管理员（首注册用户自动成为 approved 管理员）----
let adminLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
if (adminLogin.status !== 200) {
  const registration = await api('/api/auth/register', {
    method: 'POST',
    body: { username: adminUsername, password: adminPassword }
  })
  if (registration.status !== 200) {
    throw new Error('Admin registration failed in the isolated validation database')
  }
  adminLogin = await api('/api/auth/login', {
    method: 'POST',
    body: { username: adminUsername, password: adminPassword }
  })
}
if (adminLogin.status !== 200) throw new Error('Admin login failed')
const adminToken = adminLogin.body.data.token

const suffix = Date.now()
const host = `10.0.0.${suffix % 250 + 1}`

// ---- P1 创建服务器 ----
const created = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `${validationPrefix}-${suffix}`,
    host,
    description: 'created by verify-server-put',
    ssh_host: host,
    ssh_port: 22,
    ssh_user: 'validation',
    ssh_auth_type: 'password',
    ssh_password: 'validation-placeholder'
  }
})
check('P1', created.status === 200 && created.body.code === 0, '创建验证服务器成功')
const serverId = created.body.data.id

// ---- P2 合法 body 更新成功 ----
const updatedName = `${validationPrefix}-updated-${suffix}`
const putValid = await api(`/api/servers/${serverId}`, {
  method: 'PUT',
  token: adminToken,
  body: {
    name: updatedName,
    host,
    description: 'updated by verify-server-put',
    ssh_host: host,
    ssh_port: 2222,
    ssh_user: 'validation',
    ssh_auth_type: 'password',
    ssh_password: 'changed-placeholder'
  }
})
check('P2', putValid.status === 200 && putValid.body.code === 0,
  `合法 body 更新成功（HTTP ${putValid.status} / code=${putValid.body.code}）`)

// ---- P3 更新生效核对 ----
const detail = await api(`/api/servers/${serverId}`, { token: adminToken })
check('P3', detail.status === 200 && detail.body.data.name === updatedName
  && detail.body.data.ssh_port === 2222,
  `GET 详情显示更新后的 name/ssh_port（name=${detail.body.data?.name}）`)

// ---- P4 软删除 ----
const deleted = await api(`/api/servers/${serverId}`, { method: 'DELETE', token: adminToken })
check('P4', deleted.status === 200 && deleted.body.code === 0, 'DELETE 软删除成功')

// ---- P5 软删后再次 PUT 同一 ID → 404/40400 ----
const putAfterDelete = await api(`/api/servers/${serverId}`, {
  method: 'PUT',
  token: adminToken,
  body: {
    name: updatedName,
    host,
    description: 'should fail after soft delete',
    ssh_host: host,
    ssh_port: 22,
    ssh_user: 'validation',
    ssh_auth_type: 'password'
  }
})
check('P5', putAfterDelete.status === 404 && putAfterDelete.body.code === 40400,
  `软删后再次 PUT 同一 ID 返回 404/40400（HTTP ${putAfterDelete.status} / code=${putAfterDelete.body.code}，而非参数错误 40002）`)

// ---- P5b 对从未存在的 ID 直接 PUT → 404/40400 ----
const missingId = 999999
const putMissing = await api(`/api/servers/${missingId}`, {
  method: 'PUT',
  token: adminToken,
  body: {
    name: 'nobody',
    host: '10.1.1.1',
    description: '',
    ssh_host: '10.1.1.1',
    ssh_port: 22,
    ssh_user: 'validation',
    ssh_auth_type: 'password'
  }
})
check('P5b', putMissing.status === 404 && putMissing.body.code === 40400,
  `不存在 ID 直接 PUT 返回 404/40400（HTTP ${putMissing.status} / code=${putMissing.body.code}）`)

console.log(`\nM4-server-put 验收 PASS：${checks.length} 项检查项全部通过`)
console.log(`  验证服务器 ID：${serverId}（已软删除，作为验收证据保留）`)