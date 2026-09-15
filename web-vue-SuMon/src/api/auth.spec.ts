import { beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient from '@/api/client'
import { getCurrentUser, getBootstrapStatus, loginUser, logoutUser, registerUser } from '@/api/auth'

/**
 * auth HTTP wrapper 单测:聚焦 URL / Method / body / 配置,
 * 不重复 axios 内部行为(与 ai-settings.spec.ts 同一模式)。
 *
 * loginUser 的配置断言是联调观察项 B2-1 的回归防线:
 * 登录请求必须携带 silent + authAttempt,由 LoginView 统一处理失败文案,
 * 全局拦截器不弹窗、不触发登录态回调。
 */
describe('auth api / HTTP wrappers', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('loginUser 调 POST /auth/login 并携带 silent + authAttempt', async () => {
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: {
        code: 0,
        message: 'success',
        data: { token: 'jwt-token', user: { id: 1, username: 'susu', role: 'admin' } }
      }
    })
    const body = { username: 'susu', password: 'secret' }
    const result = await loginUser(body)
    expect(spy).toHaveBeenCalledWith('/auth/login', body, { silent: true, authAttempt: true })
    expect(result.data?.token).toBe('jwt-token')
  })

  it('registerUser 调 POST /auth/register,无静默标记(失败走全局提示)', async () => {
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: { id: 1, username: 'susu' } }
    })
    const body = { username: 'susu', password: 'secret' }
    await registerUser(body)
    expect(spy).toHaveBeenCalledWith('/auth/register', body)
  })

  it('getCurrentUser 调 GET /auth/me', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { code: 0, message: 'success', data: { id: 1, username: 'susu' } }
    })
    await getCurrentUser()
    expect(spy).toHaveBeenCalledWith('/auth/me')
  })

  it('getBootstrapStatus 调 GET /auth/bootstrap-status(公开端点)', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { code: 0, message: 'success', data: { bootstrapPending: true } }
    })
    const result = await getBootstrapStatus()
    expect(spy).toHaveBeenCalledWith('/auth/bootstrap-status')
    expect(result.data?.bootstrapPending).toBe(true)
  })

  it('logoutUser 调 POST /auth/logout', async () => {
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: null }
    })
    await logoutUser()
    expect(spy).toHaveBeenCalledWith('/auth/logout')
  })
})
