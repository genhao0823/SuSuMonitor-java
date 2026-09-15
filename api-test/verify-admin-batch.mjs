/**
 * Sprint5 用户管理接口端到端验收：分页 / 状态筛选 / 关键字搜索 / 批量审核 / 失败明细。
 *
 * 验证链（走 HTTP REST，隔离验证库）：
 *   注册普通用户 → admin 登录 → listUsers(status/keyword/page) 分页断言
 *   → batchApproveUsers 批量通过 → status=approved 断言
 *   → 重复批量（已被处理）→ failed_ids 明细断言
 *   → batchRejectUsers 批量拒绝 → status=rejected + 搜索断言
 *
 * 用法：
 *   SUSUMONITOR_VALIDATION_ADMIN_USERNAME=xxx SUSUMONITOR_VALIDATION_ADMIN_PASSWORD=xxx \
 *   node verify-admin-batch.mjs
 *
 * 检查项：
 *   A1 用户注册 + pending 列表分页（含 total）
 *   A2 keyword 搜索过滤
 *   A3 批量通过：processed 计数 + approved 状态
 *   A4 重复批量：failed/failed_ids 明细（不产生二次业务效果）
 *   A5 批量拒绝 + rejected 状态筛选
 *   A6 非法 status 返回 40002
 */
import crypto from 'node:crypto'

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://localhost:18081'
const adminUsername = process.env.SUSUMONITOR_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_VALIDATION_ADMIN_PASSWORD
const validationPrefix = 'sprint5_admin'

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

// ---- 准备：管理员（批次 8 起空库注册需携带一次性初始化令牌，已初始化实例登录即过）----
// 待初始化实例的令牌经 SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN 传入
// （服务器启动横幅或 AUTH_BOOTSTRAP_TOKEN 预置值），缺失时报可操作错误而不是 40310。
async function registerAdmin() {
  const bootstrapStatus = await api('/api/auth/bootstrap-status')
  const bootstrapPending = bootstrapStatus.status === 200 && bootstrapStatus.body?.data?.bootstrapPending === true
  let registerBody = { username: adminUsername, password: adminPassword }
  if (bootstrapPending) {
    const bootstrapToken = process.env.SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN
    if (typeof bootstrapToken !== 'string' || bootstrapToken.length < 32 || bootstrapToken.length > 128) {
      throw new Error('Target stack awaits first-admin bootstrap: set SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN ' +
        '(one-time token from the server startup banner or the preset AUTH_BOOTSTRAP_TOKEN).')
    }
    registerBody = { username: adminUsername, password: adminPassword, bootstrapToken }
  }
  return api('/api/auth/register', { method: 'POST', body: registerBody })
}

let adminLogin = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
if (adminLogin.status !== 200) {
  const registration = await registerAdmin()
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

// 注册 3 个普通用户。
async function registerUser(username) {
  const response = await api('/api/auth/register', {
    method: 'POST',
    body: { username, password: `Validation-${suffix}!` }
  })
  return response.body.data?.id ?? null
}
const userIds = []
for (let i = 1; i <= 3; i++) {
  const username = `${validationPrefix}_user${i}_${suffix}`
  const id = await registerUser(username)
  check('A1', id !== null, `验证用户 ${username} 注册成功`)
  userIds.push(id)
}

// ---- A1 分页：pending 列表 ----
const pendingPage = await api('/api/admin/users?status=pending&page=1&page_size=20', { token: adminToken })
check('A1', pendingPage.status === 200 && pendingPage.body.code === 0, 'listUsers(status=pending) 返回成功')
check('A1', pendingPage.body.data.total >= 3, `pending 总数 >= 3（实际 ${pendingPage.body.data.total}）`)
check('A1', pendingPage.body.data.items.length === pendingPage.body.data.page_size
  || pendingPage.body.data.items.length === pendingPage.body.data.total,
  'items 分页尺寸与 total 一致')

// ---- A2 关键字搜索 ----
const searchUser = `${validationPrefix}_user1_${suffix}`
const searchPage = await api(
  `/api/admin/users?status=pending&keyword=${encodeURIComponent(searchUser)}&page=1&page_size=20`,
  { token: adminToken }
)
check('A2', searchPage.body.data.total === 1, `keyword 搜索精确命中 1 条（实际 ${searchPage.body.data.total}）`)
check('A2', searchPage.body.data.items[0]?.username === searchUser, '搜索结果用户名匹配')

// ---- A3 批量通过 ----
const batchApprove = await api('/api/admin/users/batch-approve', {
  method: 'PUT',
  token: adminToken,
  body: { user_ids: userIds }
})
check('A3', batchApprove.status === 200 && batchApprove.body.code === 0, 'batch-approve 返回成功')
check('A3', batchApprove.body.data.processed === 3 && batchApprove.body.data.failed === 0,
  `批量通过 processed=3 failed=0（实际 processed=${batchApprove.body.data.processed}）`)

const approvedPage = await api('/api/admin/users?status=approved&page=1&page_size=20', { token: adminToken })
check('A3', approvedPage.body.data.total >= 3, `status=approved 总数 >= 3（实际 ${approvedPage.body.data.total}）`)

// ---- A4 重复批量：已被处理 → failed 明细 ----
const duplicateApprove = await api('/api/admin/users/batch-approve', {
  method: 'PUT',
  token: adminToken,
  body: { user_ids: [userIds[0]] }
})
check('A4', duplicateApprove.body.data.processed === 0 && duplicateApprove.body.data.failed === 1,
  '重复批量 processed=0 failed=1（无二次业务效果）')
check('A4', Array.isArray(duplicateApprove.body.data.failed_ids)
  && duplicateApprove.body.data.failed_ids.includes(userIds[0]),
  'failed_ids 明细包含目标用户')

const pendingAfter = await api('/api/admin/users?status=pending&page=1&page_size=20', { token: adminToken })
check('A4', pendingPage.body.data.total - pendingAfter.body.data.total === 3,
  `重复审核后 pending 恰好减少 3 个（before=${pendingPage.body.data.total} → after=${pendingAfter.body.data.total}，库中可能有历史遗留 pending）`)

// ---- A5 批量拒绝 + rejected 筛选 ----
const rejectUsername = `${validationPrefix}_reject_${suffix}`
const rejectId = await registerUser(rejectUsername)
const batchReject = await api('/api/admin/users/batch-reject', {
  method: 'PUT',
  token: adminToken,
  body: { user_ids: [rejectId] }
})
check('A5', batchReject.body.data.processed === 1 && batchReject.body.data.failed === 0, '批量拒绝 processed=1')

const rejectedPage = await api('/api/admin/users?status=rejected&page=1&page_size=20', { token: adminToken })
check('A5', rejectedPage.body.data.total >= 1, `status=rejected 可见被拒用户（实际 ${rejectedPage.body.data.total}）`)
const rejectedSearch = await api(
  `/api/admin/users?status=rejected&keyword=${encodeURIComponent(rejectUsername)}`,
  { token: adminToken }
)
check('A5', rejectedSearch.body.data.total === 1 && rejectedSearch.body.data.items[0]?.id === rejectId,
  'rejected + keyword 搜索精确命中')

// ---- A6 非法 status ----
const invalidStatus = await api('/api/admin/users?status=banned', { token: adminToken })
check('A6', invalidStatus.status === 400 && invalidStatus.body.code === 40002, '非法 status 返回 40002')

console.log(`\n用户管理验收 PASS：${checks.length} 项检查项全部通过`)
console.log(`  批量审核用户 ID：${userIds.join(', ')}（已 approved）`)
console.log(`  批量拒绝用户 ID：${rejectId}（已 rejected）`)
