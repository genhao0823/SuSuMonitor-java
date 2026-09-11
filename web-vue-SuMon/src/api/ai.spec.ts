import { describe, it, expect, vi } from 'vitest'
import apiClient, { ApiBusinessError } from '@/api/client'
import { describeAiError, getAlertExplanation, requestAiDiagnosis, requestAiQa } from '@/api/ai'
import type { AiDiagnosis, AiQa, ApiResponse } from '@/types/api'
import { ErrorCode } from '@/types/error-code'

/**
 * HTTP wrapper 单测:聚焦 URL / Method / body / 配置四件事,
 * 不重复 axios 内部行为(与 alert.spec.ts 同一模式)。
 */
describe('ai api / HTTP wrappers', () => {
  it('requestAiDiagnosis 调 POST /ai/diagnoses,携带静默与超时配置', async () => {
    const diagnosis = { summary: 'ok', severity: 'info' } as AiDiagnosis
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: diagnosis } as ApiResponse<AiDiagnosis>
    })
    const body = { server_id: 1, question: 'CPU 为何升高?', history_minutes: 30 }
    const result = await requestAiDiagnosis(body)
    expect(spy).toHaveBeenCalledWith('/ai/diagnoses', body, {
      timeout: 120_000,
      silent: true
    })
    expect(result.data).toEqual(diagnosis)
  })

  it('requestAiQa 调 POST /ai/qa,携带静默与超时配置', async () => {
    const qa: AiQa = {
      answer: 'ok',
      tool_calls: [],
      model_used: true,
      degraded: false,
      provider: 'openai-compatible',
      model: 'deepseek-chat',
      prompt_version: 'ai-qa-v1',
      usage: { input_tokens: 1, output_tokens: 1, total_tokens: 2, estimated_cost: 0, currency: 'USD' }
    }
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: qa } as ApiResponse<AiQa>
    })
    const body = { server_id: null, question: '现在整体状态如何?' }
    const result = await requestAiQa(body)
    expect(spy).toHaveBeenCalledWith('/ai/qa', body, { timeout: 120_000, silent: true })
    expect(result.data).toEqual(qa)
  })

  it('getAlertExplanation 调 GET /alerts/records/{id}/explanation', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { code: 0, message: 'success', data: { record_id: 5 } }
    })
    const result = await getAlertExplanation(5)
    expect(spy).toHaveBeenCalledWith('/alerts/records/5/explanation', { silent: true })
    expect(result.data?.record_id).toBe(5)
  })

  it('getAlertExplanation 遇 40400 时折叠为 data=null 的空态,不抛错', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      new ApiBusinessError(ErrorCode.RESOURCE_NOT_FOUND, 'resource not found')
    )
    const result = await getAlertExplanation(5)
    expect(result.code).toBe(ErrorCode.RESOURCE_NOT_FOUND)
    expect(result.data).toBeNull()
  })

  it('getAlertExplanation 其余错误码照常向上抛出', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      new ApiBusinessError(ErrorCode.FORBIDDEN, 'forbidden')
    )
    await expect(getAlertExplanation(5)).rejects.toMatchObject({ code: ErrorCode.FORBIDDEN })
  })
})

describe('ai api / describeAiError', () => {
  it('按错误码映射中文文案', () => {
    expect(describeAiError(new ApiBusinessError(ErrorCode.AI_RATE_LIMIT_REACHED, 'x'))).toContain(
      '过于频繁'
    )
    expect(
      describeAiError(new ApiBusinessError(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED, 'x'))
    ).toContain('未开启')
    expect(describeAiError(new ApiBusinessError(ErrorCode.COMMAND_RUN_STATE_CONFLICT, 'x'))).toContain(
      '刷新列表'
    )
    expect(describeAiError(new ApiBusinessError(ErrorCode.COMMAND_AGENT_OFFLINE, 'x'))).toContain(
      'Agent 离线'
    )
  })

  it('未识别业务码回退后端原始 message,非业务错误回退网络文案', () => {
    expect(describeAiError(new ApiBusinessError(99999, 'boom'))).toBe('boom')
    expect(describeAiError(new Error('network down'))).toContain('网络异常')
  })
})
