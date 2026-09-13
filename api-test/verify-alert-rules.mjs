/**
 * MVP6-alert 规则写链路验收补全：POST/PUT/DELETE /api/alerts/rules 全路径 + 唯一性 40900 + 软删后重建。
 *
 * 对应两个 Bug 记录：
 *   - 2026-07-27-MVP6-alert-rules-mapper-500.md：代码已修复（@Param("rule")），真实 HTTP 4 路复验待补
 *   - 2026-07-28-MVP6-alert-rules-no-uniqueness-check.md：唯一索引（V13 生成列）+ 40900 预检查 + 软删后可重建
 *
 * 验证链（走 HTTP REST，隔离验证库）：
 *   admin 登录 → POST /api/servers 创建 → POST 规则(200/0) → 重复 POST 相同 5 元组(409/40900)
 *   → PUT 规则(200/0) → GET 详情核对 → DELETE 规则(200/0) → 再次 POST 相同 5 元组(200/0，软删后可重建)
 *   → GET 列表核对
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_BASE_URL=http://localhost:18080 \
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   node verify-alert-rules.mjs
 *
 * 检查项：
 *   R1 POST 规则成功（200 / code=0，不再 500 空响应体）
 *   R2 重复 POST 相同 5 元组 → 409 / 40900（唯一约束生效）
 *   R3 PUT 规则成功（200 / code=0）
 *   R4 GET 详情显示更新后的阈值/等级
 *   R5 DELETE 规则成功（200 / code=0）
 *   R6 软删后再次 POST 相同 5 元组成功（200 / code=0，唯一索引只约束活跃记录）
 *   R7 GET 列表核对：新规则在列表中
 */
import crypto from 'node:crypto'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18080'
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const validationPrefix = 'alert_rules'

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

// ---- 创建服务器 ----
const created = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `${validationPrefix}-${suffix}`,
    host,
    description: 'created by verify-alert-rules',
    ssh_host: host,
    ssh_port: 22,
    ssh_user: 'validation',
    ssh_auth_type: 'password',
    ssh_password: 'validation-placeholder'
  }
})
check('R0', created.status === 200 && created.body.code === 0, '创建验证服务器成功')
const serverId = created.body.data.id

// 固定 5 元组：同一 (server_id, metric, operator, threshold_value, level)
const ruleBody = {
  server_id: serverId,
  metric: 'cpu',
  operator: '>',
  threshold_value: 80,
  level: 'warning'
}

// ---- R1 POST 规则成功 ----
const createdRule = await api('/api/alerts/rules', {
  method: 'POST',
  token: adminToken,
  body: ruleBody
})
check('R1', createdRule.status === 200 && createdRule.body.code === 0,
  `POST 规则成功（HTTP ${createdRule.status} / code=${createdRule.body.code}，不再 500 空响应体）`)
const ruleId = createdRule.body.data.id
check('R1', Number.isInteger(ruleId), '返回规则 ID')

// ---- R2 重复 POST 相同 5 元组 → 409/40900 ----
const duplicateRule = await api('/api/alerts/rules', {
  method: 'POST',
  token: adminToken,
  body: ruleBody
})
check('R2', duplicateRule.status === 409 && duplicateRule.body.code === 40900,
  `重复 POST 相同 5 元组 → 409/40900（HTTP ${duplicateRule.status} / code=${duplicateRule.body.code}）`)

// ---- R3 PUT 规则成功 ----
const updatedRule = await api(`/api/alerts/rules/${ruleId}`, {
  method: 'PUT',
  token: adminToken,
  body: {
    threshold_value: 90,
    level: 'critical',
    enabled: true
  }
})
check('R3', updatedRule.status === 200 && updatedRule.body.code === 0,
  `PUT 规则成功（HTTP ${updatedRule.status} / code=${updatedRule.body.code}）`)

// ---- R4 列表接口核对更新（后端无单详情路由：GET /{id} 返回 404/40400，见 Bug-fix/2026-09-14-unmapped-api-path-500.md）----
const ruleListAfterUpdate = await api('/api/alerts/rules', { token: adminToken })
const ruleDetail = ruleListAfterUpdate.body.data.find((rule) => rule.id === ruleId)
check('R4', ruleListAfterUpdate.status === 200 && ruleDetail && Number(ruleDetail.threshold_value) === 90
  && ruleDetail.level === 'critical',
  `列表中显示更新后的 threshold_value=90 / level=critical（实际 ${ruleDetail?.threshold_value} / ${ruleDetail?.level}）`)

// ---- R5 DELETE 规则成功 ----
const deletedRule = await api(`/api/alerts/rules/${ruleId}`, { method: 'DELETE', token: adminToken })
check('R5', deletedRule.status === 200 && deletedRule.body.code === 0,
  `DELETE 规则成功（HTTP ${deletedRule.status} / code=${deletedRule.body.code}）`)

// ---- R6 软删后再次 POST 相同 5 元组成功 ----
const recreatedRule = await api('/api/alerts/rules', {
  method: 'POST',
  token: adminToken,
  body: ruleBody
})
check('R6', recreatedRule.status === 200 && recreatedRule.body.code === 0,
  `软删后再次 POST 相同 5 元组成功（HTTP ${recreatedRule.status} / code=${recreatedRule.body.code}，唯一索引只约束活跃记录）`)
const recreatedRuleId = recreatedRule.body.data.id

// ---- R7 GET 列表核对 ----
const ruleList = await api('/api/alerts/rules', { token: adminToken })
check('R7', ruleList.status === 200 && ruleList.body.code === 0, '规则列表查询成功')
check('R7', Array.isArray(ruleList.body.data) && ruleList.body.data.some((rule) => rule.id === recreatedRuleId),
  '列表中可见重建后的规则')
check('R7', !ruleList.body.data.some((rule) => rule.id === ruleId),
  '软删除的首次规则不再出现在列表中')

console.log(`\nMVP6-alert-rules 验收 PASS：${checks.length} 项检查项全部通过`)
console.log(`  验证服务器 ID：${serverId}（保留）`)
console.log(`  规则 ID：首次 ${ruleId}（已软删除）→ 重建 ${recreatedRuleId}（活跃）`)