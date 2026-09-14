import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import type * as ElementPlus from 'element-plus'
import apiClient, { setApiClientCallbacks } from '@/api/client'

const { messageErrorSpy } = vi.hoisted(() => ({
  messageErrorSpy: vi.fn()
}))

// ElMessage 替换为 spy 对象,拦截器是否弹窗通过该 spy 断言。
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof ElementPlus>('element-plus')
  return {
    ...actual,
    ElMessage: {
      success: vi.fn(),
      warning: vi.fn(),
      info: vi.fn(),
      error: messageErrorSpy
    }
  }
})

/**
 * client.ts 拦截器行为单测:通过注入伪 adapter 模拟后端 401/403 响应,
 * 验证 silent 弹窗开关与 authAttempt 登录态回调开关两组语义
 * (联调观察项 B2-1 的拦截器侧回归防线)。
 */
describe('api client / 拦截器错误处理', () => {
  const unauthorizedCallback = vi.fn()
  const forbiddenCallback = vi.fn()

  beforeEach(() => {
    messageErrorSpy.mockClear()
    unauthorizedCallback.mockClear()
    forbiddenCallback.mockClear()
    setApiClientCallbacks({
      onUnauthorized: unauthorizedCallback,
      onForbidden: forbiddenCallback
    })
  })

  afterEach(() => {
    setApiClientCallbacks(undefined)
    vi.restoreAllMocks()
  })

  /**
   * 将实例默认 adapter 替换为固定 HTTP 状态与业务错误码的伪实现,
   * 使响应拦截器错误分支真实走一遍(而非直接 mock 拦截器内部函数)。
   */
  function rejectWithStatus(status: number, code: number): void {
    apiClient.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      const response: AxiosResponse = {
        status,
        statusText: 'Mock Error',
        data: { code, message: 'mock business error', data: null },
        headers: {},
        config
      }
      throw new AxiosError(
        `Request failed with status code ${status}`,
        AxiosError.ERR_BAD_REQUEST,
        config,
        {},
        response
      )
    }
  }

  it('authAttempt 请求收到 403:不触发 onForbidden 也不弹窗(登录尝试失败语义)', async () => {
    rejectWithStatus(403, 40300)
    await expect(
      apiClient.post('/auth/login', {}, { silent: true, authAttempt: true })
    ).rejects.toBeInstanceOf(Error)
    expect(forbiddenCallback).not.toHaveBeenCalled()
    expect(unauthorizedCallback).not.toHaveBeenCalled()
    expect(messageErrorSpy).not.toHaveBeenCalled()
  })

  it('authAttempt 请求未静默:仍弹窗一次但不触发回调(文案由调用方之外的场景兜底)', async () => {
    rejectWithStatus(403, 40300)
    await expect(
      apiClient.post('/auth/login', {}, { authAttempt: true })
    ).rejects.toBeInstanceOf(Error)
    expect(forbiddenCallback).not.toHaveBeenCalled()
    expect(messageErrorSpy).toHaveBeenCalledTimes(1)
  })

  it('普通请求收到 401:触发 onUnauthorized 且弹窗一次(既有语义不回归)', async () => {
    rejectWithStatus(401, 40100)
    await expect(apiClient.get('/servers')).rejects.toBeInstanceOf(Error)
    expect(unauthorizedCallback).toHaveBeenCalledTimes(1)
    expect(forbiddenCallback).not.toHaveBeenCalled()
    expect(messageErrorSpy).toHaveBeenCalledTimes(1)
  })

  it('普通请求收到 403:触发 onForbidden(silent 请求只跳弹窗不跳回调)', async () => {
    rejectWithStatus(403, 40300)
    await expect(apiClient.get('/admin/only', { silent: true })).rejects.toBeInstanceOf(Error)
    expect(forbiddenCallback).toHaveBeenCalledTimes(1)
    expect(messageErrorSpy).not.toHaveBeenCalled()
  })
})
