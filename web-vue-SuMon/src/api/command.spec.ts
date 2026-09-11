import { describe, it, expect, vi } from 'vitest'
import apiClient from '@/api/client'
import {
  approveCommandRun,
  createManualCommandRun,
  getAutoApprovalPolicy,
  getCommandRun,
  listCommandRuns,
  listCommandTemplates,
  rejectCommandRun,
  requestCommandSuggestions,
  updateAutoApprovalPolicy
} from '@/api/command'
import type {
  ApiResponse,
  AutoApprovalPolicy,
  CommandRun,
  CommandTemplate,
  PageResult
} from '@/types/api'

/**
 * HTTP wrapper 单测:聚焦 URL / Method / params / body / 配置,
 * 不重复 axios 内部行为(与 alert.spec.ts 同一模式)。
 */
describe('command api / HTTP wrappers', () => {
  it('requestCommandSuggestions 调 POST /ai/commands/suggestions,携带静默与超时配置', async () => {
    const run = { id: 101, status: 'pending_approval' } as CommandRun
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: [run] } as ApiResponse<CommandRun[]>
    })
    const body = { server_id: 1, intent: '查看内存占用前 5 的进程' }
    const result = await requestCommandSuggestions(body)
    expect(spy).toHaveBeenCalledWith('/ai/commands/suggestions', body, {
      timeout: 120_000,
      silent: true
    })
    expect(result.data).toEqual([run])
  })

  it('createManualCommandRun 调 POST /ai/commands/runs 与 body', async () => {
    const run = { id: 102, status: 'pending_approval' } as CommandRun
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: run } as ApiResponse<CommandRun>
    })
    const body = { server_id: 1, template_id: 'system_service_status', params: { service_name: 'nginx' } }
    await createManualCommandRun(body)
    expect(spy).toHaveBeenCalledWith('/ai/commands/runs', body, { silent: true })
  })

  it('listCommandRuns 调 GET /ai/commands/runs 与 query params', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: {
        code: 0,
        message: 'success',
        data: { items: [], total: 0, page: 1, page_size: 20 } as PageResult<CommandRun>
      }
    })
    await listCommandRuns({ server_id: 3, status: 'pending_approval', page: 2, page_size: 50 })
    expect(spy).toHaveBeenCalledWith('/ai/commands/runs', {
      params: { server_id: 3, status: 'pending_approval', page: 2, page_size: 50 }
    })
  })

  it('listCommandRuns 省略查询参数时 params 字段为 undefined', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: {
        code: 0,
        message: 'success',
        data: { items: [], total: 0, page: 1, page_size: 20 } as PageResult<CommandRun>
      }
    })
    await listCommandRuns()
    expect(spy).toHaveBeenCalledWith('/ai/commands/runs', {
      params: { server_id: undefined, status: undefined, page: undefined, page_size: undefined }
    })
  })

  it('getCommandRun 调 GET /ai/commands/runs/{id}', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { code: 0, message: 'success', data: { id: 9 } as CommandRun }
    })
    await getCommandRun(9)
    expect(spy).toHaveBeenCalledWith('/ai/commands/runs/9', { silent: true })
  })

  it('approveCommandRun 调 POST /ai/commands/runs/{id}/approve,请求体为空', async () => {
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: { id: 9, status: 'approved' } as CommandRun }
    })
    await approveCommandRun(9)
    expect(spy).toHaveBeenCalledWith('/ai/commands/runs/9/approve', undefined, { silent: true })
  })

  it('rejectCommandRun 调 POST /ai/commands/runs/{id}/reject,请求体为空', async () => {
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: { id: 9, status: 'rejected' } as CommandRun }
    })
    await rejectCommandRun(9)
    expect(spy).toHaveBeenCalledWith('/ai/commands/runs/9/reject', undefined, { silent: true })
  })

  it('listCommandTemplates 调 GET /ai/commands/templates', async () => {
    const templates = [{ id: 'top_mem_processes', argv: ['ps'], params: [] }] as CommandTemplate[]
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { code: 0, message: 'success', data: templates } as ApiResponse<CommandTemplate[]>
    })
    const result = await listCommandTemplates()
    expect(spy).toHaveBeenCalledWith('/ai/commands/templates')
    expect(result.data).toEqual(templates)
  })

  it('getAutoApprovalPolicy 调 GET /ai/commands/auto-approval-policy,携带静默配置', async () => {
    const policy = { enabled: false, max_risk_level: 'medium' } as AutoApprovalPolicy
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { code: 0, message: 'success', data: policy } as ApiResponse<AutoApprovalPolicy>
    })
    const result = await getAutoApprovalPolicy()
    expect(spy).toHaveBeenCalledWith('/ai/commands/auto-approval-policy', { silent: true })
    expect(result.data).toEqual(policy)
  })

  it('updateAutoApprovalPolicy 调 PUT /ai/commands/auto-approval-policy 与 body', async () => {
    const policy = { enabled: true, max_risk_level: 'low' } as AutoApprovalPolicy
    const spy = vi.spyOn(apiClient, 'put').mockResolvedValue({
      data: { code: 0, message: 'success', data: policy } as ApiResponse<AutoApprovalPolicy>
    })
    const body = { enabled: true, max_risk_level: 'low' as const }
    const result = await updateAutoApprovalPolicy(body)
    expect(spy).toHaveBeenCalledWith('/ai/commands/auto-approval-policy', body, { silent: true })
    expect(result.data).toEqual(policy)
  })
})
