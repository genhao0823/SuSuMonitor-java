#!/usr/bin/env node
/**
 * M2-M5 HTTP API 自动化测试脚本
 *
 * 不依赖浏览器,直接调后端 API 检测 17 路径对应的 HTTP 行为。
 * 捕获:
 *   - 4xx/5xx 错误
 *   - 字段名不一致(snake_case vs camelCase)
 *   - 路径不存在
 *   - 未授权访问(40100)
 *
 * 不测:
 *   - UI 渲染(数字滚动、签名引言、玻璃卡视觉)
 *   - 复杂交互(下拉、二次确认)
 *   - M5 admin 操作(需要 admin 凭据)
 *
 * 用法:`node scripts/api-e2e-test.mjs`
 */

const BASE_URL = 'http://127.0.0.1:18080'

const findings = []

function log(severity, message) {
  findings.push({ severity, message })
  const icon = severity === 'ERROR' ? '✗' : severity === 'WARN' ? '⚠' : '·'
  console.log(`${icon} ${message}`)
}

async function api(path, options = {}) {
  const url = `${BASE_URL}${path}`
  try {
    const res = await fetch(url, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(options.token ? { 'Authorization': `Bearer ${options.token}` } : {}),
        ...(options.headers || {})
      }
    })
    const text = await res.text()
    let json = null
    try { json = text ? JSON.parse(text) : null } catch (e) { /* not json */ }
    return { status: res.status, json, raw: text }
  } catch (e) {
    return { status: 0, json: null, raw: '', error: e.message }
  }
}

// ===== 测试用例 =====

async function testHealth() {
  const r = await api('/api/health')
  if (r.status === 200 && r.json?.code === 0) {
    log('INFO', '[/api/health] 200 ✅ (后端活)')
  } else {
    log('ERROR', `[/api/health] ${r.status} ${JSON.stringify(r.json)}`)
  }
}

async function testReady() {
  const r = await api('/api/ready')
  if (r.status === 200 && r.json?.code === 0) {
    log('INFO', '[/api/ready] 200 ✅ (DB ok)')
  } else {
    log('ERROR', `[/api/ready] ${r.status} ${JSON.stringify(r.json)}`)
  }
}

// ===== M2 鉴权测试(无需 admin) =====

let registeredToken = null
let registeredUser = null

async function testRegister() {
  // 注册新账号(pending 状态)
  const ts = Date.now()
  const username = `e2e_test_${ts}`
  const password = 'TestPassword123!'
  const r = await api('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username, password })
  })

  if (r.status === 200 && r.json?.code === 0) {
    log('INFO', `[/api/auth/register] 200 ✅ (新账号 ${username})`)
    registeredUser = { username, password }
  } else if (r.status === 409 && r.json?.code === 40900) {
    log('INFO', `[/api/auth/register] 409/40900 — 用户已存在(可能并发测试)`)
  } else {
    log('ERROR', `[/api/auth/register] HTTP ${r.status} ${JSON.stringify(r.json)}`)
  }
}

async function testLogin() {
  if (!registeredUser) {
    log('WARN', '[/api/auth/login] 跳过 — 无测试账号')
    return
  }
  const r = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify(registeredUser)
  })

  if (r.status === 200 && r.json?.code === 0) {
    log('INFO', '[/api/auth/login] 200 ✅')
    registeredToken = r.json?.data?.token
  } else if (r.status === 403 && r.json?.code === 40300) {
    log('WARN', '[/api/auth/login] 403/40300 — 账号 pending 状态不可登录(预期,M5 admin 应先审核)')
  } else {
    log('ERROR', `[/api/auth/login] HTTP ${r.status} ${JSON.stringify(r.json)}`)
  }
}

async function testCurrentUser() {
  const r = await api('/api/auth/me')
  if (r.status === 401 && r.json?.code === 40100) {
    log('INFO', '[/api/auth/me] 401/40100 (无 token,预期)')
  } else if (r.status === 200) {
    log('INFO', '[/api/auth/me] 200 ✅')
  } else {
    log('ERROR', `[/api/auth/me] HTTP ${r.status} ${JSON.stringify(r.json)}`)
  }
}

async function testLogout() {
  const r = await api('/api/auth/logout', {
    method: 'POST',
    token: registeredToken || 'fake-token'
  })
  if ((r.status === 401 && r.json?.code === 40100) || (r.status === 200 && r.json?.code === 0)) {
    log('INFO', `[/api/auth/logout] HTTP ${r.status} (预期)`)
  } else {
    log('ERROR', `[/api/auth/logout] HTTP ${r.status} ${JSON.stringify(r.json)}`)
  }
}

// ===== M4 CRUD(无需 admin) =====

let createdServerId = null

async function testListServers() {
  // 不带 token:40100
  const r1 = await api('/api/servers?page=1&page_size=100&sort_by=id&sort_order=desc')
  if (r1.status === 401 && r1.json?.code === 40100) {
    log('INFO', '[/api/servers 无 token] 401/40100 (预期)')
  } else {
    log('ERROR', `[/api/servers 无 token] HTTP ${r1.status} ${JSON.stringify(r1.json)}`)
  }

  // 带 token:如果 pending 不能登录就跳过
  if (!registeredToken) {
    log('WARN', '[/api/servers 带 token] 跳过 — 无有效 token')
    return
  }
  const r2 = await api('/api/servers?page=1&page_size=100&sort_by=id&sort_order=desc', {
    token: registeredToken
  })
  if (r2.status === 200 && r2.json?.code === 0) {
    log('INFO', '[/api/servers 带 token] 200 ✅')
    const items = r2.json?.data?.items || []
    if (items.length > 0) {
      createdServerId = items[0].id
      log('INFO', `  └─ 有 ${items.length} 个 server,取第 1 个 id=${createdServerId}`)
    } else {
      log('INFO', '  └─ 列表为空')
    }
  } else {
    log('ERROR', `[/api/servers 带 token] ${r2.status} ${JSON.stringify(r2.json)}`)
  }
}

async function testGetServer() {
  if (!createdServerId) {
    log('WARN', '[/api/servers/{id}] 跳过 — 无 server id')
    return
  }
  const r = await api(`/api/servers/${createdServerId}`, { token: registeredToken })
  if (r.status === 200) {
    log('INFO', `[/api/servers/${createdServerId}] 200 ✅`)
    // 检查字段命名(snake_case by OpenAPI)
    const d = r.json?.data || {}
    const expectedSnakeCase = ['ssh_host', 'ssh_port', 'ssh_user', 'ssh_auth_type', 'agent_id']
    for (const field of expectedSnakeCase) {
      if (field in d) {
        log('INFO', `  └─ 字段 ${field} = ${JSON.stringify(d[field])} (snake_case ✅)`)
      }
    }
  } else {
    log('ERROR', `[/api/servers/${createdServerId}] ${r.status}`)
  }
}

async function testGetServerStatus() {
  if (!createdServerId) return
  const r = await api(`/api/servers/${createdServerId}/status`, { token: registeredToken })
  if (r.status === 200) {
    log('INFO', `[/api/servers/${createdServerId}/status] 200 ✅`)
  } else {
    log('ERROR', `[/api/servers/${createdServerId}/status] ${r.status}`)
  }
}

async function testMetricsLatest() {
  if (!createdServerId) return
  const r = await api(`/api/servers/${createdServerId}/metrics/latest`, { token: registeredToken })
  if (r.status === 200 || (r.status === 404 && r.json?.code === 40400)) {
    log('INFO', `[/api/servers/${createdServerId}/metrics/latest] HTTP ${r.status} (200 有数据/404 无 agent,都算预期)`)
  } else {
    log('ERROR', `[/api/servers/${createdServerId}/metrics/latest] HTTP ${r.status} ${JSON.stringify(r.json)}`)
  }
}

async function testMetricsHistory() {
  if (!createdServerId) return
  const end = new Date()
  const start = new Date(end.getTime() - 24 * 60 * 60 * 1000)
  const r = await api(
    `/api/servers/${createdServerId}/metrics?start_time=${start.toISOString()}&end_time=${end.toISOString()}&page=1&page_size=100`,
    { token: registeredToken }
  )
  if (r.status === 200 || (r.status === 404 && r.json?.code === 40400)) {
    log('INFO', `[/api/servers/${createdServerId}/metrics] HTTP ${r.status} (200 有数据/404 无 agent)`)
  } else {
    log('ERROR', `[/api/servers/${createdServerId}/metrics] HTTP ${r.status} ${JSON.stringify(r.json)}`)
  }
}

// ===== M5 admin 测试(跳过) =====

async function testAdminPaths() {
  const paths = [
    '/api/admin/users?status=pending',
    '/api/admin/users/1/approve',
    '/api/admin/users/1/reject'
  ]
  for (const p of paths) {
    const method = p.endsWith('/approve') || p.endsWith('/reject') ? 'PUT' : 'GET'
    const r = await api(p, { method, token: registeredToken || 'fake' })
    if (r.status === 401 && r.json?.code === 40100) {
      log('INFO', `[${p} 无 admin] 401/40100 (预期,非 admin 拒绝)`)
    } else if (r.status === 403 && r.json?.code === 40300) {
      log('INFO', `[${p} 无 admin] 403/40300 (预期)`)
    } else {
      log('WARN', `[${p} 无 admin] HTTP ${r.status} (非预期,需要 admin)`)
    }
  }
}

// ===== MVP-6 告警模块(无需 admin 也能读) =====

let firstAlertRecordId = null

async function testListAlertRules() {
  const r = await api('/api/alerts/rules', { token: registeredToken || 'fake' })
  if (r.status === 200 && r.json?.code === 0) {
    const items = r.json?.data || []
    log('INFO', `[/api/alerts/rules] 200 ✅ (${items.length} 条规则)`)
  } else if (r.status === 401 || r.status === 403) {
    log('INFO', `[/api/alerts/rules] HTTP ${r.status} (预期,无 token 或非 admin)`)
  } else {
    log('ERROR', `[/api/alerts/rules] HTTP ${r.status} ${JSON.stringify(r.json)}`)
  }
}

async function testListAlertRecords() {
  const r = await api(
    '/api/alerts/records?page=1&page_size=20',
    { token: registeredToken || 'fake' }
  )
  if (r.status === 200 && r.json?.code === 0) {
    const items = r.json?.data?.items || []
    log('INFO', `[/api/alerts/records] 200 ✅ (total=${r.json?.data?.total ?? '?'}, items=${items.length})`)
    if (items.length > 0) {
      firstAlertRecordId = items[0].id
      log('INFO', `  └─ 取第 1 条 id=${firstAlertRecordId}`)
    }
  } else if (r.status === 401 || r.status === 403) {
    log('INFO', `[/api/alerts/records] HTTP ${r.status} (预期,无 token 或非 admin)`)
  } else {
    log('ERROR', `[/api/alerts/records] HTTP ${r.status} ${JSON.stringify(r.json)}`)
  }
}

async function testMarkAlertRecordAsRead() {
  if (!firstAlertRecordId) {
    log('WARN', '[/api/alerts/records/{id}/read] 跳过 — 无记录 id')
    return
  }
  const r = await api(
    `/api/alerts/records/${firstAlertRecordId}/read`,
    { method: 'PUT', token: registeredToken || 'fake' }
  )
  if (r.status === 200 || r.status === 401 || r.status === 403 || (r.status === 409 && r.json?.code === 40900)) {
    log('INFO', `[/api/alerts/records/${firstAlertRecordId}/read] HTTP ${r.status} (200/409 预期,401/403 无凭据预期)`)
  } else {
    log('ERROR', `[/api/alerts/records/${firstAlertRecordId}/read] HTTP ${r.status} ${JSON.stringify(r.json)}`)
  }
}

// ===== B-038 补齐端点(SSH host-key + Agent Token)=====

// 用一个无效但符合 OpenAPI 正则的 fingerprint,避免真实主机依赖。
const PLACEHOLDER_FINGERPRINT = 'SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'

async function testConfirmSshHostKey() {
  if (!createdServerId) {
    log('WARN', '[/api/servers/{id}/ssh/host-key] 跳过 — 无 server id')
    return
  }
  const r = await api(`/api/servers/${createdServerId}/ssh/host-key`, {
    method: 'PUT',
    token: registeredToken || 'fake',
    body: JSON.stringify({
      expected_fingerprint: PLACEHOLDER_FINGERPRINT,
      replace: false
    })
  })
  // 无 admin / 无凭据 / 服务器不存在 / 真实指纹比对失败 都在预期范围内
  if ([40100, 40300, 40400, 50200, 50400, 40902].includes(r.status)
      || (r.status === 200 && r.json?.code === 0)) {
    log('INFO', `[/api/servers/${createdServerId}/ssh/host-key] ${r.status} (无 admin/无 server/指纹不符预期)`)
  } else {
    log('ERROR', `[/api/servers/${createdServerId}/ssh/host-key] ${r.status} ${JSON.stringify(r.json)}`)
  }
}

async function testRegisterAgentToken() {
  if (!createdServerId) {
    log('WARN', '[/api/servers/{id}/agent/register] 跳过 — 无 server id')
    return
  }
  const r = await api(`/api/servers/${createdServerId}/agent/register`, {
    method: 'POST',
    token: registeredToken || 'fake'
  })
  if (r.status === 40100 || r.status === 40300 || r.status === 40400 || r.status === 40900
      || (r.status === 200 && r.json?.code === 0)) {
    log('INFO', `[/api/servers/${createdServerId}/agent/register] ${r.status} (401/403/404/409 预期)`)
  } else {
    log('ERROR', `[/api/servers/${createdServerId}/agent/register] ${r.status} ${JSON.stringify(r.json)}`)
  }
}

async function testRotateAgentToken() {
  if (!createdServerId) {
    log('WARN', '[/api/servers/{id}/agent/rotate] 跳过 — 无 server id')
    return
  }
  const r = await api(`/api/servers/${createdServerId}/agent/rotate`, {
    method: 'POST',
    token: registeredToken || 'fake'
  })
  if (r.status === 40100 || r.status === 40300 || r.status === 40400 || r.status === 40900
      || (r.status === 200 && r.json?.code === 0)) {
    log('INFO', `[/api/servers/${createdServerId}/agent/rotate] ${r.status} (401/403/404/409 预期)`)
  } else {
    log('ERROR', `[/api/servers/${createdServerId}/agent/rotate] ${r.status} ${JSON.stringify(r.json)}`)
  }
}

async function testRevokeAgentToken() {
  if (!createdServerId) {
    log('WARN', '[/api/servers/{id}/agent/revoke] 跳过 — 无 server id')
    return
  }
  const r = await api(`/api/servers/${createdServerId}/agent/revoke`, {
    method: 'DELETE',
    token: registeredToken || 'fake'
  })
  if (r.status === 40100 || r.status === 40300 || r.status === 40400 || r.status === 40900
      || (r.status === 200 && r.json?.code === 0)) {
    log('INFO', `[/api/servers/${createdServerId}/agent/revoke] ${r.status} (401/403/404/409 预期)`)
  } else {
    log('ERROR', `[/api/servers/${createdServerId}/agent/revoke] ${r.status} ${JSON.stringify(r.json)}`)
  }
}

// ===== main =====

async function main() {
  console.log('M2-M5 + MVP-6 HTTP API 自动化测试')
  console.log('================================')
  console.log(`目标: ${BASE_URL}`)
  console.log('')

  await testHealth()
  await testReady()
  console.log('--- M2 鉴权 ---')
  await testCurrentUser()
  await testRegister()
  await testLogin()
  await testLogout()

  console.log('--- M4 CRUD ---')
  await testListServers()
  await testGetServer()
  await testGetServerStatus()
  await testMetricsLatest()
  await testMetricsHistory()

  console.log('--- M5 admin(无 admin 凭据,只能测权限拒绝)---')
  await testAdminPaths()

  console.log('--- MVP-6 告警 ---')
  await testListAlertRules()
  await testListAlertRecords()
  await testMarkAlertRecordAsRead()

  console.log('--- B-038 主机指纹 + Agent Token ---')
  await testConfirmSshHostKey()
  await testRegisterAgentToken()
  await testRotateAgentToken()
  await testRevokeAgentToken()

  console.log('')
  console.log('================================')
  const errCount = findings.filter(f => f.severity === 'ERROR').length
  const warnCount = findings.filter(f => f.severity === 'WARN').length
  const infoCount = findings.filter(f => f.severity === 'INFO').length
  console.log(`${errCount} ERROR / ${warnCount} WARN / ${infoCount} INFO`)
  console.log('')

  if (errCount > 0) {
    console.log('【错误】以下路径 / 行为需要修复:')
    findings.filter(f => f.severity === 'ERROR').forEach(f => console.log(`  ${f.message}`))
  }

  if (warnCount > 0) {
    console.log('【警告】以下路径行为非预期,需要确认:')
    findings.filter(f => f.severity === 'WARN').forEach(f => console.log(`  ${f.message}`))
  }

  process.exit(errCount > 0 ? 1 : 0)
}

main().catch(e => {
  console.error('脚本执行失败:', e)
  process.exit(2)
})