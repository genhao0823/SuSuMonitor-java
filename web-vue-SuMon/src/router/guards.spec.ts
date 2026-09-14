import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { Router } from 'vue-router'
import { installRouterGuards } from '@/router/guards'
import { useAuthStore } from '@/stores/auth'

/**
 * 守卫处理器桩:捕获 installRouterGuards 注册的 beforeEach 处理器,
 * 直接以构造的 to 路由对象调用,断言重定向指令或放行(true)。
 */
type GuardResult = boolean | Record<string, unknown>
type GuardTo = { meta: Record<string, boolean>; fullPath: string }
type GuardHandler = (to: GuardTo) => GuardResult

function installAndCapture(): GuardHandler {
  let captured: GuardHandler | undefined
  const routerStub = {
    beforeEach: vi.fn((handler: GuardHandler) => {
      captured = handler
    })
  } as unknown as Router
  installRouterGuards(routerStub)
  if (captured === undefined) {
    throw new Error('beforeEach handler was not registered')
  }
  return captured
}

function makeUser(role: 'admin' | 'user') {
  return {
    id: 1,
    username: 'susu',
    role,
    reviewStatus: 'approved' as const,
    reviewedAt: null,
    createdAt: '2026-01-01'
  }
}

/**
 * 路由守卫单测:锁定"认证 → 角色 → publicOnly"的判定顺序。
 * B2-2 修复点:未登录访问 admin 路由必须落 /login?redirect=,
 * 而不是被角色分支抢先导向 /forbidden。
 */
describe('router guards / 认证与角色判定顺序', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('未登录访问 requiresAuth 路由 → /login?redirect=fullPath', () => {
    const guard = installAndCapture()
    expect(guard({ meta: { requiresAuth: true }, fullPath: '/servers' })).toEqual({
      name: 'login',
      query: { redirect: '/servers' }
    })
  })

  it('未登录访问 requiresAdmin 路由 → /login?redirect=B2-2 修复点', () => {
    const guard = installAndCapture()
    expect(guard({ meta: { requiresAuth: true, requiresAdmin: true }, fullPath: '/admin/users' })).toEqual({
      name: 'login',
      query: { redirect: '/admin/users' }
    })
  })

  it('已登录非 admin 访问 requiresAdmin 路由 → /forbidden', () => {
    const auth = useAuthStore()
    auth.token = 'jwt-token'
    auth.user = makeUser('user')
    const guard = installAndCapture()
    expect(guard({ meta: { requiresAuth: true, requiresAdmin: true }, fullPath: '/admin/users' })).toEqual({
      name: 'forbidden'
    })
  })

  it('已登录 admin 访问 requiresAdmin 路由 → 放行', () => {
    const auth = useAuthStore()
    auth.token = 'jwt-token'
    auth.user = makeUser('admin')
    const guard = installAndCapture()
    expect(guard({ meta: { requiresAuth: true, requiresAdmin: true }, fullPath: '/admin/users' })).toBe(true)
  })

  it('已登录访问 publicOnly 路由 → /dashboard', () => {
    const auth = useAuthStore()
    auth.token = 'jwt-token'
    auth.user = makeUser('user')
    const guard = installAndCapture()
    expect(guard({ meta: { publicOnly: true }, fullPath: '/login' })).toEqual({ name: 'dashboard' })
  })

  it('无 meta 路由放行', () => {
    const guard = installAndCapture()
    expect(guard({ meta: {}, fullPath: '/dashboard' })).toBe(true)
  })
})
