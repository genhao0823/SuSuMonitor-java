import { beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient, { ApiBusinessError } from '@/api/client'
import {
  deleteMyProviderConfig,
  getMyProviderConfig,
  isAiDisabledError,
  saveMyProviderConfig,
  testMyProviderConfig
} from '@/api/ai-settings'
import { ErrorCode } from '@/types/error-code'

/**
 * provider-config HTTP wrapper 单测：聚焦 URL / Method / body / 配置，
 * 不重复 axios 内部行为（与 alert.spec.ts 同一模式）。
 */
describe('ai-settings api / HTTP wrappers', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('getMyProviderConfig 调 GET /ai/provider-config', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { code: 0, message: 'success', data: { configured: false } }
    })
    const result = await getMyProviderConfig()
    expect(spy).toHaveBeenCalledWith('/ai/provider-config', { silent: true })
    expect(result.data?.configured).toBe(false)
  })

  it('saveMyProviderConfig 调 PUT /ai/provider-config 与 body（空白 key 原样传递由后端判定）', async () => {
    const spy = vi.spyOn(apiClient, 'put').mockResolvedValue({
      data: { code: 0, message: 'success', data: { configured: true } }
    })
    const body = { base_url: 'https://api.example.test/v1', api_key: ' ', model: 'm' }
    await saveMyProviderConfig(body)
    expect(spy).toHaveBeenCalledWith('/ai/provider-config', body, { silent: true })
  })

  it('deleteMyProviderConfig 调 DELETE /ai/provider-config', async () => {
    const spy = vi.spyOn(apiClient, 'delete').mockResolvedValue({
      data: { code: 0, message: 'success', data: true }
    })
    const result = await deleteMyProviderConfig()
    expect(spy).toHaveBeenCalledWith('/ai/provider-config', { silent: true })
    expect(result.data).toBe(true)
  })

  it('testMyProviderConfig 调 POST /ai/provider-config/test，携带静默与放宽超时', async () => {
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: { ok: true, latency_ms: 120 } }
    })
    const body = { base_url: 'https://api.example.test/v1', api_key: null, model: 'm' }
    const result = await testMyProviderConfig(body)
    expect(spy).toHaveBeenCalledWith('/ai/provider-config/test', body, {
      timeout: 60_000,
      silent: true
    })
    expect(result.data?.ok).toBe(true)
  })
})

describe('ai-settings api / isAiDisabledError', () => {
  it('识别 40400（AI 功能未开启）错误', () => {
    const err = new ApiBusinessError(ErrorCode.RESOURCE_NOT_FOUND, 'resource not found')
    expect(isAiDisabledError(err)).toBe(true)
    expect(isAiDisabledError(new ApiBusinessError(ErrorCode.FORBIDDEN, 'forbidden'))).toBe(false)
    expect(isAiDisabledError(new Error('network'))).toBe(false)
  })
})
