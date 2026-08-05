// 首管理员空库并发验收：N 个并发注册请求中恰好一个成为管理员。
//
// 环境变量：
//   SUSUMONITOR_VALIDATION_CONFIRM=FIRST_ADMIN_CONCURRENCY  （硬性守卫）
//   SUSUMONITOR_VALIDATION_BASE_URL        （默认 http://127.0.0.1:18182）
//   SUSUMONITOR_VALIDATION_PREFIX          （注册用户名前缀，默认 first-admin-）
//   SUSUMONITOR_VALIDATION_CONCURRENCY     （并发数，默认 8，2-20）
//
// 由 run-first-admin-concurrency-e2e.ps1 编排：起空库 Java 服务后调用，结束后清理。

if (process.env.SUSUMONITOR_VALIDATION_CONFIRM !== 'FIRST_ADMIN_CONCURRENCY') {
  throw new Error('Set SUSUMONITOR_VALIDATION_CONFIRM=FIRST_ADMIN_CONCURRENCY to run this isolated E2E.')
}

const baseUrl = process.env.SUSUMONITOR_VALIDATION_BASE_URL ?? 'http://127.0.0.1:18182'
const prefix = process.env.SUSUMONITOR_VALIDATION_PREFIX ?? 'first-admin-'
const concurrency = Math.min(20, Math.max(2, Number(process.env.SUSUMONITOR_VALIDATION_CONCURRENCY) || 8))
const password = 'Validation@2026'

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function register(username) {
  try {
    const response = await fetch(`${baseUrl}/api/auth/register`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username, password })
    })
    return { status: response.status, body: await response.json() }
  } catch (error) {
    return { status: 0, error: error.message }
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

const results = await Promise.all(
  Array.from({ length: concurrency }, (_, index) => register(`${prefix}${Date.now()}-${index}`))
)
const succeeded = results.filter((result) => result.status === 200)
assert(succeeded.length === 1,
  `Expected exactly 1 successful first-admin registration, got ${succeeded.length}`)

const winner = succeeded[0]
const winnerUsername = winner.body.data.username
assert(typeof winnerUsername === 'string' && winnerUsername.startsWith(prefix),
  `Winning registration did not return the expected username: ${JSON.stringify(winner.body)}`)

const loginResult = await login(winnerUsername)
assert(loginResult.status === 200, `Winning admin could not log in (status ${loginResult.status}).`)
const me = await fetch(`${baseUrl}/api/auth/me`, {
  headers: { authorization: `Bearer ${loginResult.body.data.token}` }
})
const meBody = await me.json()
assert(meBody.data.role === 'admin', `Winning user role = ${meBody.data.role}, want admin`)

console.log(JSON.stringify({
  status: 'PASS',
  checks: 3,
  concurrency,
  succeeded: 1,
  winner: winnerUsername
}))
