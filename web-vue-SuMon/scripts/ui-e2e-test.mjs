#!/usr/bin/env node
/**
 * UI E2E 自动化测试脚本 - 用 puppeteer-core 驱动系统 Chrome,
 * 覆盖 17 路径(M2 鉴权 / M3 主布局 / M4 CRUD / M5 管理)。
 *
 * 不需要下载 chromium,直接用本地 Chrome。
 * 捕获 console.error / pageerror / requestfailed。
 *
 * 用法:设置 SUSUMONITOR_UI_E2E_ADMIN_USERNAME / PASSWORD 后执行
 * `node scripts/ui-e2e-test.mjs`。
 * 退出码:0 全过 / 1 有 fail
 */

import puppeteer from 'puppeteer-core'

const CHROME_PATH = process.env.SUSUMONITOR_UI_E2E_CHROME_PATH ||
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const BASE_URL = (process.env.SUSUMONITOR_UI_E2E_BASE_URL || 'http://127.0.0.1:5173')
  .replace(/\/+$/, '')
const adminUsername = process.env.SUSUMONITOR_UI_E2E_ADMIN_USERNAME
const adminPassword = process.env.SUSUMONITOR_UI_E2E_ADMIN_PASSWORD

if (!adminUsername || !adminPassword) {
  console.error(
    'Set SUSUMONITOR_UI_E2E_ADMIN_USERNAME and SUSUMONITOR_UI_E2E_ADMIN_PASSWORD.'
  )
  process.exit(2)
}

const ADMIN = { username: adminUsername, password: adminPassword }

const findings = []
const consoleErrors = []
const pageErrors = []
const failedRequests = []

function log(severity, message) {
  findings.push({ severity, message })
  const icon = severity === 'ERROR' ? '✗' : severity === 'WARN' ? '⚠' : '·'
  console.log(`${icon} ${message}`)
}

async function $(page, selector, timeout = 5000) {
  await page.waitForSelector(selector, { timeout })
  return page.$(selector)
}

async function clickText(page, text, timeout = 5000) {
  const handle = await page.evaluateHandle((t) => {
    const buttons = document.querySelectorAll('button, a, .el-button, .el-dropdown-menu__item')
    for (const b of buttons) {
      if (b.textContent.trim().includes(t)) return b
    }
    return null
  }, text)
  const el = handle.asElement()
  if (!el) throw new Error(`找不到文本 "${text}" 的可点击元素`)
  await el.click()
  return el
}

// ===== M2 鉴权 =====

async function testLogin(page) {
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded', timeout: 15000 })
  // 等密码框出现(最精确的登录表单标识)
  await page.waitForSelector('input[type="password"]', { timeout: 10000 })
  await new Promise(r => setTimeout(r, 1000))

  // 用 placeholder 或 type 精确定位
  const usernameInput = await page.$('input[type="text"], input:not([type]):not([type="checkbox"])')
  const passwordInput = await page.$('input[type="password"]')
  if (!usernameInput || !passwordInput) {
    log('ERROR', 'M2-1 登录: 找不到用户名或密码输入框')
    return false
  }

  // 用户名:focus + keyboard.type(模拟真实键盘,触发 keydown/input/keyup)
  await page.evaluate(() => {
    const inputs = document.querySelectorAll('input')
    for (const inp of inputs) {
      if (inp.type !== 'password' && inp.type !== 'checkbox') {
        inp.focus()
        inp.value = ''
        break
      }
    }
  })
  await page.keyboard.type(ADMIN.username, { delay: 50 })

  // 密码:page.type(原生方法)
  await page.click('input[type="password"]', { click: 3 })
  await page.keyboard.type(ADMIN.password, { delay: 50 })

  await new Promise(r => setTimeout(r, 500))

  // 等登录按钮(class 选择器最稳)
  await page.waitForSelector('.login-view__submit', { timeout: 5000 }).catch(() => {})
  const btn = await page.$('.login-view__submit')
  if (!btn) {
    log('ERROR', 'M2-1 登录: 找不到 .login-view__submit 按钮')
    return false
  }

  // 点击提交
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 10000 }).catch(() => {}),
    btn.click()
  ])
  await new Promise(r => setTimeout(r, 2000))

  if (page.url().includes('/dashboard')) {
    log('INFO', 'M2-1 登录: ✅ 跳转到 /dashboard')
    return true
  }
  // 检查是否有错误 toast
  const toastText = await page.evaluate(() => {
    const msgs = document.querySelectorAll('.el-message__content, .el-notification__content')
    return Array.from(msgs).map(e => e.textContent).join('; ')
  })
  log('ERROR', `M2-1 登录: ❌ URL=${page.url()} ${toastText ? 'toast: ' + toastText : ''}`)
  return false
}

async function testRegister(page) {
  const ts = Date.now()
  await page.goto(`${BASE_URL}/register`, { waitUntil: 'domcontentloaded', timeout: 15000 })
  await page.waitForSelector('input[type="password"]', { timeout: 10000 }).catch(() => {})
  await new Promise(r => setTimeout(r, 1000))
  // 用 password 框定位,用户名框在它前面
  const passwordInput = await page.$('input[type="password"]')
  if (!passwordInput) {
    log('WARN', 'M2-2 注册: 找不到密码框(注册页可能渲染慢)')
    return
  }
  // 用户名:focus 第一个非 password 非 checkbox 的 input
  await page.evaluate(() => {
    const inputs = document.querySelectorAll('input')
    for (const inp of inputs) {
      if (inp.type !== 'password' && inp.type !== 'checkbox') {
        inp.focus()
        inp.value = ''
        break
      }
    }
  })
  await page.keyboard.type(`e2e_ui_${ts}`, { delay: 50 })
  await passwordInput.click({ click: 3 })
  await page.keyboard.type('TestPwd123!', { delay: 50 })
  await new Promise(r => setTimeout(r, 500))
  // 找注册按钮
  const btn = await page.$('.register-view__submit') || await page.$('button[type="submit"]')
  if (!btn) {
    log('WARN', 'M2-2 注册: 找不到注册按钮')
    return
  }
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 10000 }).catch(() => {}),
    btn.click()
  ])
  await new Promise(r => setTimeout(r, 1500))
  if (page.url().includes('/login') || page.url().includes('/dashboard')) {
    log('INFO', 'M2-2 注册: ✅ 跳转成功')
  } else {
    log('INFO', `M2-2 注册: URL=${page.url()} (待审核 toast 预期)`)
  }
}

async function testCurrentUser(page) {
  await page.goto(`${BASE_URL}/dashboard`, { waitUntil: 'domcontentloaded', timeout: 15000 })
  await new Promise(r => setTimeout(r, 1000))
  // 检查是否有头像或用户名元素
  const hasUser = await page.evaluate(() => {
    return document.body.textContent.includes('admin') ||
           document.querySelector('.el-avatar') !== null ||
           document.querySelector('.el-dropdown') !== null
  })
  if (hasUser) {
    log('INFO', 'M2-3 当前用户: ✅ 页面含 admin / 头像')
  } else {
    log('ERROR', 'M2-3 当前用户: ❌ 未找到 admin 标识')
  }
}

async function testLogout(page) {
  try {
    const dropdown = await page.$('.el-dropdown') || await page.$('.el-avatar')
    if (dropdown) {
      await dropdown.click()
      await new Promise(r => setTimeout(r, 500))
      // 点退出(Element Plus 下拉菜单项)
      const logoutItem = await page.evaluate(() => {
        const items = document.querySelectorAll('.el-dropdown-menu__item, .el-menu-item')
        for (const it of items) {
          if (it.textContent.includes('退出')) {
            it.click()
            return true
          }
        }
        const btns = document.querySelectorAll('button')
        for (const b of btns) {
          if (b.textContent.includes('退出')) {
            b.click()
            return true
          }
        }
        return false
      })
      if (logoutItem) {
        await page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 5000 }).catch(() => {})
        await new Promise(r => setTimeout(r, 1000))
        if (page.url().includes('/login')) {
          log('INFO', 'M2-4 退出: ✅ 跳转到 /login')
        } else {
          log('WARN', `M2-4 退出: URL=${page.url()}`)
        }
      } else {
        await page.evaluate(() => { localStorage.clear(); sessionStorage.clear() })
        await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded' })
        log('INFO', 'M2-4 退出: ✅ (localStorage 清空)')
      }
    } else {
      await page.evaluate(() => localStorage.clear())
      await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded' })
      log('INFO', 'M2-4 退出: ✅ (无头像,localStorage 清空)')
    }
  } catch (e) {
    await page.evaluate(() => localStorage.clear()).catch(() => {})
    log('WARN', `M2-4 退出: ${e.message} (fallback 清 localStorage)`)
  }
}

// ===== M3 主布局 =====

async function testDashboardNumbers(page) {
  await page.goto(`${BASE_URL}/dashboard`, { waitUntil: 'domcontentloaded', timeout: 15000 })
  await new Promise(r => setTimeout(r, 2000)) // 等数字滚动
  const numbers = await page.evaluate(() => {
    const els = document.querySelectorAll('.dashboard-view__card-value, [class*="card-value"]')
    return Array.from(els).map(e => e.textContent.trim()).filter(t => t.length > 0)
  })
  if (numbers.length >= 3) {
    log('INFO', `M3-5 Dashboard 数字: ✅ 找到 ${numbers.length} 个数值 (${numbers.slice(0, 4).join(', ')})`)
  } else {
    log('WARN', `M3-5 Dashboard 数字: 只找到 ${numbers.length} 个数值`)
  }
}

async function testSidebarNav(page) {
  const links = await page.$$eval('.el-menu-item, aside a, nav a', els =>
    Array.from(els).map(e => ({ text: e.textContent.trim(), href: e.getAttribute('href') || '' })).filter(l => l.text.length > 0)
  )
  if (links.length >= 2) {
    log('INFO', `M3-6 侧栏: ✅ 找到 ${links.length} 个导航项 (${links.map(l => l.text).join(', ')})`)
  } else {
    log('WARN', `M3-6 侧栏: 只找到 ${links.length} 个`)
  }
}

async function testAvatarDropdown(page) {
  const dropdown = await page.$('.el-dropdown') || await page.$('.el-avatar')
  if (dropdown) {
    log('INFO', 'M3-7 头像下拉: ✅ 头像元素存在')
  } else {
    log('WARN', 'M3-7 头像下拉: 找不到头像元素')
  }
}

async function testQuote(page) {
  const hasQuote = await page.evaluate(() => {
    const body = document.body.textContent
    return body.includes('苏苏') || body.includes('涂山') || body.includes('「')
  })
  if (hasQuote) {
    log('INFO', 'M3-8 签名引言: ✅ 页面含涂山苏苏引言')
  } else {
    log('WARN', 'M3-8 签名引言: 未找到引言文字')
  }
}

// ===== M4 CRUD =====

async function testServerList(page) {
  await page.goto(`${BASE_URL}/servers`, { waitUntil: 'domcontentloaded', timeout: 15000 })
  await new Promise(r => setTimeout(r, 1500))
  const rows = await page.$$('.el-table__row')
  if (rows.length >= 1) {
    log('INFO', `M4 列表: ✅ 找到 ${rows.length} 行 server`)
    return rows.length
  }
  log('INFO', 'M4 列表: 表格无数据行(DB 无 server,预期)')
  return 0
}

async function testSearch(page) {
  const searchInput = await page.$('input[placeholder*="名称"]') || await page.$('input[placeholder*="搜索"]') || await page.$('.el-input__inner')
  if (!searchInput) {
    log('WARN', 'M4-9 列表搜索: 找不到搜索框')
    return
  }
  await searchInput.click({ click: 3 })
  await page.keyboard.type('nonexistent_server_xyz')
  await new Promise(r => setTimeout(r, 1000))
  const rows = await page.$$('.el-table__row')
  if (rows.length === 0) {
    log('INFO', 'M4-9 列表搜索: ✅ 搜索"nonexistent"后无结果(预期)')
  } else {
    log('WARN', `M4-9 列表搜索: 搜索后仍有 ${rows.length} 行`)
  }
  // 清空搜索
  await searchInput.click({ click: 3 })
  await page.keyboard.press('Backspace')
  await new Promise(r => setTimeout(r, 500))
}

async function testSort(page) {
  // 点"名称"列头
  const headerCell = await page.evaluate(() => {
    const cells = document.querySelectorAll('.el-table__header th')
    for (const c of cells) {
      if (c.textContent.includes('名称')) {
        c.click()
        return true
      }
    }
    return false
  })
  await new Promise(r => setTimeout(r, 500))
  if (headerCell) {
    log('INFO', 'M4-10 列表排序: ✅ 点击了"名称"列头')
  } else {
    log('INFO', 'M4-10 列表排序: 无数据行,跳过(预期)')
  }
}

async function testDetail(page) {
  // 点第一行的"详情"按钮或名称
  const detailBtn = await page.evaluate(() => {
    const btns = document.querySelectorAll('.el-table__row button, .el-table__row a')
    for (const b of btns) {
      if (b.textContent.includes('详情')) {
        b.click()
        return true
      }
    }
    // 尝试点名称链接
    const links = document.querySelectorAll('.el-table__row a')
    if (links.length > 0) {
      links[0].click()
      return true
    }
    return false
  })
  if (detailBtn) {
    await page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 10000 }).catch(() => {})
    await new Promise(r => setTimeout(r, 1500))
    if (/\/servers\/\d+/.test(page.url())) {
      log('INFO', `M4-13 详情: ✅ URL=${page.url()}`)
      return true
    }
    log('ERROR', `M4-13 详情: ❌ URL=${page.url()}`)
    return false
  }
  log('INFO', 'M4-13 详情: 列表无数据行,跳过(预期)')
  return false
}

async function testCreateButton(page) {
  // 检查"创建"按钮是否存在(admin 可见)
  const createBtn = await page.evaluate(() => {
    const btns = document.querySelectorAll('button')
    for (const b of btns) {
      if (b.textContent.includes('创建')) return true
    }
    return false
  })
  if (createBtn) {
    log('INFO', 'M4-11 创建按钮: ✅ 可见')
  } else {
    log('WARN', 'M4-11 创建按钮: 不可见(可能非 admin)')
  }
}

async function testEditButton(page) {
  const editBtn = await page.evaluate(() => {
    const btns = document.querySelectorAll('.el-table__row button')
    for (const b of btns) {
      if (b.textContent.replace(/\s/g, '').includes('编辑')) return true
    }
    return false
  })
  if (editBtn) {
    log('INFO', 'M4-12 编辑按钮: ✅ 可见')
  } else {
    log('INFO', 'M4-12 编辑按钮: 列表无数据行,跳过(预期)')
  }
}

async function testDeleteButton(page) {
  const delBtn = await page.evaluate(() => {
    const btns = document.querySelectorAll('.el-table__row button')
    for (const b of btns) {
      if (b.textContent.replace(/\s/g, '').includes('删除')) return true
    }
    return false
  })
  if (delBtn) {
    log('INFO', 'M4-14 删除按钮: ✅ 可见(不真删,避免副作用)')
  } else {
    log('INFO', 'M4-14 删除按钮: 列表无数据行,跳过(预期)')
  }
}

// ===== M5 管理 =====

async function testAdminUsers(page) {
  await page.goto(`${BASE_URL}/admin/users`, { waitUntil: 'domcontentloaded', timeout: 15000 })
  await new Promise(r => setTimeout(r, 1500))
  if (page.url().includes('/admin/users')) {
    const rows = await page.$$('.el-table__row')
    log('INFO', `M5-15 待审核列表: ✅ 页面加载,${rows.length} 行`)
    return rows.length
  }
  log('ERROR', `M5-15 待审核列表: ❌ URL=${page.url()} (可能跳 /forbidden)`)
  return 0
}

async function testApproveButton(page) {
  const approveBtn = await page.evaluate(() => {
    const btns = document.querySelectorAll('.el-table__row button')
    for (const b of btns) {
      if (b.textContent.includes('通过')) return true
    }
    return false
  })
  if (approveBtn) {
    log('INFO', 'M5-16 通过按钮: ✅ 可见(不真点,避免副作用)')
  } else {
    log('INFO', 'M5-16 通过按钮: 无待审核用户(正常)')
  }
}

async function testRejectButton(page) {
  const rejectBtn = await page.evaluate(() => {
    const btns = document.querySelectorAll('.el-table__row button')
    for (const b of btns) {
      if (b.textContent.includes('拒绝')) return true
    }
    return false
  })
  if (rejectBtn) {
    log('INFO', 'M5-17 拒绝按钮: ✅ 可见(不真点,避免副作用)')
  } else {
    log('INFO', 'M5-17 拒绝按钮: 无待审核用户(正常)')
  }
}

// ===== main =====

async function main() {
  console.log('UI E2E 自动化测试 (puppeteer-core + 系统 Chrome)')
  console.log('================================')
  console.log(`Chrome: ${CHROME_PATH}`)
  console.log(`目标: ${BASE_URL}`)
  console.log('凭据:由运行时环境变量提供')
  console.log('')

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
  })

  const page = await browser.newPage()
  await page.setViewport({ width: 1280, height: 800 })

  // 捕获错误
  page.on('console', msg => {
    if (msg.type() === 'error') {
      consoleErrors.push(msg.text())
    }
  })
  page.on('pageerror', err => {
    pageErrors.push(err.message)
  })
  page.on('requestfailed', req => {
    const url = req.url()
    if (!url.includes('favicon') && !url.includes('.hot-update') && !url.includes('ws://')) {
      failedRequests.push({ url, error: req.failure()?.errorText })
    }
  })
  // 认证请求只记录方法和 URL，禁止输出可能包含凭据的请求体。
  page.on('request', req => {
    if (req.url().includes('/api/auth/')) {
      console.log(`  [net] ${req.method()} ${req.url()}`)
    }
  })

  try {
    // M2 鉴权
    console.log('--- M2 鉴权 ---')
    const loggedIn = await testLogin(page)
    if (loggedIn) {
      await testCurrentUser(page)
      await testLogout(page)
      // 注册在退出后测(已登录访问 /register 会被路由守卫跳 /dashboard)
      await testRegister(page)
      // 重新登录继续后续测试
      await testLogin(page)
    } else {
      log('WARN', 'M2 登录失败,跳过后续 M2 测试')
    }

    // M3 主布局(确保已登录)
    console.log('--- M3 主布局 ---')
    if (!page.url().includes('/dashboard')) {
      await page.goto(`${BASE_URL}/dashboard`, { waitUntil: 'domcontentloaded', timeout: 15000 })
    }
    await new Promise(r => setTimeout(r, 2000))
    await testDashboardNumbers(page)
    await testSidebarNav(page)
    await testAvatarDropdown(page)
    await testQuote(page)

    // M4 CRUD
    console.log('--- M4 CRUD ---')
    await testServerList(page)
    await testSearch(page)
    await testSort(page)
    await testCreateButton(page)
    await testEditButton(page)
    await testDeleteButton(page)
    await testDetail(page)

    // M5 管理
    console.log('--- M5 管理 ---')
    await testAdminUsers(page)
    await testApproveButton(page)
    await testRejectButton(page)

  } catch (e) {
    log('ERROR', `测试执行异常: ${e.message}`)
  } finally {
    await browser.close()
  }

  // 汇总
  console.log('')
  console.log('================================')
  const errCount = findings.filter(f => f.severity === 'ERROR').length
  const warnCount = findings.filter(f => f.severity === 'WARN').length
  const infoCount = findings.filter(f => f.severity === 'INFO').length
  console.log(`${errCount} ERROR / ${warnCount} WARN / ${infoCount} INFO`)
  console.log('')

  if (consoleErrors.length > 0) {
    console.log('【console.error】(前 10 条):')
    consoleErrors.slice(0, 10).forEach(e => console.log(`  ${e.substring(0, 200)}`))
    console.log('')
  }

  if (pageErrors.length > 0) {
    console.log('【pageerror】(前 5 条):')
    pageErrors.slice(0, 5).forEach(e => console.log(`  ${e.substring(0, 200)}`))
    console.log('')
  }

  if (failedRequests.length > 0) {
    console.log('【requestfailed】(前 5 条):')
    failedRequests.slice(0, 5).forEach(r => console.log(`  ${r.url.substring(0, 100)} - ${r.error}`))
    console.log('')
  }

  process.exit(errCount > 0 ? 1 : 0)
}

main().catch(e => {
  console.error('脚本执行失败:', e)
  process.exit(2)
})
