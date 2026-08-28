// 首管理员空库并发验收：N 个并发注册请求在空库中恰好产生一个管理员，
// 其余注册用户按业务契约进入待审核状态。
//
// 业务契约（与 UserServiceImpl / UserServiceTests / 项目需求与规范六章一致）：
//   首个成功注册用户 -> role=admin, reviewStatus=approved
//   后续成功注册用户 -> role=user,   reviewStatus=pending
//   此前旧脚本断言“只有一个 HTTP 200”与上述契约冲突，已修正。
//
// 环境变量：
//   SUSUMONITOR_VALIDATION_CONFIRM=FIRST_ADMIN_CONCURRENCY  （硬性守卫）
//   SUSUMONITOR_VALIDATION_BASE_URL        （默认 http://127.0.0.1:18183，与正式 bash 编排器端口一致）
//   SUSUMONITOR_VALIDATION_PREFIX          （注册用户名前缀，默认 first_admin_）
//   SUSUMONITOR_VALIDATION_CONCURRENCY     （并发数，2-20，默认 8）
//
// 由 run-first-admin-concurrency-e2e.sh / .ps1 编排：起空库 Java 服务后调用，结束后清理。
// 服务就绪探测放在 Node 侧用 fetch 完成，避免 PowerShell 5.1 在 $ErrorActionPreference='Stop' 下
// 把健康探针的 WebException 累积成终止错误、进而污染后续变量的“未设置”误报。

const RUN_CONFIRM = process.env.SUSUMONITOR_VALIDATION_CONFIRM
if (RUN_CONFIRM !== 'FIRST_ADMIN_CONCURRENCY') {
  throw new Error('Set SUSUMONITOR_VALIDATION_CONFIRM=FIRST_ADMIN_CONCURRENCY to run this isolated E2E.')
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://127.0.0.1:18183'

// 空库首启需完成 Flyway 迁移与 Tomcat 就绪，等待最多 120 秒，每 500ms 探测一次。
async function waitForServer() {
  const deadline = Date.now() + 120000
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${baseUrl}/api/health`)
      if (response.status === 200) return
    } catch {
      // 尚未就绪，继续等待。
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`Server not ready at ${baseUrl} within 120s`)
}

const password = 'Validation@2026'

const prefix = process.env.SUSUMONITOR_VALIDATION_PREFIX ?? 'first_admin_'
assert(typeof prefix === 'string' && /^[A-Za-z0-9_]+$/.test(prefix),
  `Invalid SUSUMONITOR_VALIDATION_PREFIX, only letters/digits/underscore allowed: ${JSON.stringify(prefix)}`)

const concurrencyRaw = process.env.SUSUMONITOR_VALIDATION_CONCURRENCY ?? '8'
assert(/^\d+$/.test(concurrencyRaw), `SUSUMONITOR_VALIDATION_CONCURRENCY must be an integer, got ${JSON.stringify(concurrencyRaw)}`)
const concurrency = Number(concurrencyRaw)
assert(Number.isInteger(concurrency) && concurrency >= 2 && concurrency <= 20,
  `SUSUMONITOR_VALIDATION_CONCURRENCY must be an integer within 2..20, got ${concurrencyRaw}`)

// 固定本次运行的用户名数组，使每个返回值与其请求一一对应，便于失败诊断。
const runTimestamp = Date.now()
const usernames = Array.from({ length: concurrency }, (_, index) => {
  const username = `${prefix}${runTimestamp}_${index}`
  assert(username.length >= 3 && username.length <= 50,
    `Generated username length out of range 3..50: ${username}`)
  return username
})

async function register(username) {
  try {
    const response = await fetch(`${baseUrl}/api/auth/register`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username, password })
    })
    return { ok: response.ok, status: response.status, body: await response.json() }
  } catch (error) {
    return { ok: false, status: 0, error: error.message }
  }
}

async function login(username) {
  const response = await fetch(`${baseUrl}/api/auth/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  return { status: response.status, body: await response.json() }
}

await waitForServer()

const results = await Promise.all(usernames.map((username) => register(username)))
// 所有合法用户名都应当成功注册，业务 code=0，返回用户名与请求一致。
const indexByUsername = new Map(usernames.map((username, index) => [username, index]))
const responseByUsername = new Map()
results.forEach((result, index) => {
  const username = usernames[index]
  assert(result.ok && result.status === 200,
    `Register ${username} failed with HTTP ${result.status}: ${JSON.stringify(result.body ?? result)}`)
  assert(result.body && result.body.code === 0,
    `Register ${username} returned non-zero business code: ${JSON.stringify(result.body)}`)
  assert(result.body.data && result.body.data.username === username,
    `Register ${username} returned mismatched identity: ${JSON.stringify(result.body && result.body.data)}`)
  responseByUsername.set(username, result)
})

// 按 CurrentUserVo 的 role/reviewStatus 分类：恰好一个 admin/approved，其余 user/pending。
const adminOf = []
const pendingOf = []
for (const username of usernames) {
  const data = responseByUsername.get(username).body.data
  if (data.role === 'admin' && data.reviewStatus === 'approved') {
    adminOf.push(username)
  } else if (data.role === 'user' && data.reviewStatus === 'pending') {
    pendingOf.push(username)
  } else {
    throw new Error(`Unexpected role/reviewStatus for ${username}: ${JSON.stringify(data)}`)
  }
}
assert(adminOf.length === 1, `Expected exactly 1 admin/approved, got ${adminOf.length}`)
assert(pendingOf.length === concurrency - 1,
  `Expected ${concurrency - 1} user/pending, got ${pendingOf.length}`)

const admin = adminOf[0]

// 管理员可以登录，且 /me 返回 admin/approved。
const adminLogin = await login(admin)
assert(adminLogin.status === 200, `Admin login failed (HTTP ${adminLogin.status}).`)
const adminToken = adminLogin.body && adminLogin.body.data && adminLogin.body.data.token
assert(typeof adminToken === 'string' && adminToken.length > 0, `Admin login returned no token.`)
const me = await fetch(`${baseUrl}/api/auth/me`, {
  headers: { authorization: `Bearer ${adminToken}` }
})
const meBody = await me.json()
assert(me.status === 200 && meBody.data && meBody.data.role === 'admin' &&
  meBody.data.reviewStatus === 'approved',
  `Admin /me role = ${JSON.stringify(meBody.data)}, want admin/approved`)

// 待审核用户成功注册但不具备登录权限：抽样一个登录应为 403。
const pendingSample = pendingOf[0]
const pendingLogin = await login(pendingSample)
assert(pendingLogin.status === 403,
  `Register-only user should not be able to log in (HTTP ${pendingLogin.status}).`)

// 结果输出到 stdout；编排器以本进程退出码判定 API 层通过，并独立对数据库做持久化复核（并发数双方共用
// SUSUMONITOR_VALIDATION_CONCURRENCY 环境变量），无需跨进程传递结果文件/路径。
console.log(JSON.stringify({
  status: 'PASS',
  checks: 4,
  concurrency,
  succeeded: concurrency,
  adminApproved: 1,
  userPending: concurrency - 1,
  winner: admin
}))
