import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAlertsStore } from '@/stores/alerts'
import { useAuthStore } from '@/stores/auth'
import { useMetricsStore } from '@/stores/metrics'
import * as alertApi from '@/api/alert'
import { ApiBusinessError } from '@/api/client'
import { ErrorCode } from '@/types/error-code'
import type { AlertRecord, AlertRule } from '@/types/api'

/**
 * 工厂函数:生成一个最小可用的告警规则对象,允许逐字段覆盖。
 *
 * @param overrides 字段覆盖
 */
function makeRule(overrides: Partial<AlertRule> = {}): AlertRule {
  return {
    id: 1,
    server_id: null,
    metric: 'cpu',
    operator: '>',
    threshold_value: 80,
    level: 'warning',
    confirm_count: 1,
    enabled: true,
    created_by: 1,
    created_at: '2026-07-22T00:00:00Z',
    updated_at: '2026-07-22T00:00:00Z',
    ...overrides
  }
}

/**
 * 工厂函数:生成一个最小可用的告警记录对象,允许逐字段覆盖。
 *
 * @param overrides 字段覆盖
 */
function makeRecord(overrides: Partial<AlertRecord> = {}): AlertRecord {
  return {
    id: 1,
    rule_id: 1,
    server_id: 1,
    metric: 'cpu',
    current_value: 90.5,
    threshold_value: 80,
    level: 'warning',
    status: 'unread',
    message: null,
    read_by: null,
    read_at: null,
    triggered_at: '2026-07-22T00:00:00Z',
    created_at: '2026-07-22T00:00:00Z',
    ...overrides
  }
}

describe('alerts store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('初始状态为空 / 计数为 0', () => {
    const store = useAlertsStore()
    expect(store.rules).toEqual([])
    expect(store.records).toEqual([])
    expect(store.recordsTotal).toBe(0)
    expect(store.pendingPushCount).toBe(0)
    expect(store.recordsLoading).toBe(false)
    expect(store.rulesError).toBeNull()
    expect(store.recordsError).toBeNull()
    expect(store.wsConnected).toBe(false)
  })

  it('loadRules 成功填充列表', async () => {
    const store = useAlertsStore()
    vi.spyOn(alertApi, 'listAlertRules').mockResolvedValue({
      code: 0,
      message: 'success',
      data: [makeRule({ id: 1 }), makeRule({ id: 2 })]
    })
    await store.loadRules()
    expect(store.rules).toHaveLength(2)
    expect(store.rulesLoading).toBe(false)
    expect(store.rulesError).toBeNull()
  })

  it('loadRules 失败设置错误并保留空列表', async () => {
    const store = useAlertsStore()
    vi.spyOn(alertApi, 'listAlertRules').mockRejectedValue(new Error('network down'))
    await store.loadRules()
    expect(store.rules).toEqual([])
    expect(store.rulesError).toBe('network down')
  })

  it('loadRecords 翻页参数正确透传', async () => {
    const store = useAlertsStore()
    const spy = vi
      .spyOn(alertApi, 'listAlertRecords')
      .mockResolvedValue({ code: 0, message: 'success', data: { items: [], total: 0, page: 2, page_size: 50 } })
    await store.loadRecords({ page: 2, page_size: 50, server_id: 7, status: 'unread' })
    expect(spy).toHaveBeenCalledWith({
      page: 2,
      page_size: 50,
      server_id: 7,
      status: 'unread'
    })
    expect(store.recordsQuery).toEqual({ page: 2, page_size: 50, server_id: 7, status: 'unread' })
    expect(store.recordsTotal).toBe(0)
  })

  it('loadRecords 不传参时使用默认 page=1/page_size=20', async () => {
    const store = useAlertsStore()
    const spy = vi
      .spyOn(alertApi, 'listAlertRecords')
      .mockResolvedValue({ code: 0, message: 'success', data: { items: [], total: 0, page: 1, page_size: 20 } })
    await store.loadRecords()
    expect(spy).toHaveBeenCalledWith({
      page: 1,
      page_size: 20,
      server_id: undefined,
      status: undefined
    })
  })

  it('markRead 把目标记录 status 改为 read', async () => {
    const store = useAlertsStore()
    store.records = [makeRecord({ id: 10 }), makeRecord({ id: 11 })]
    vi.spyOn(alertApi, 'markAlertRecordAsRead').mockResolvedValue({
      code: 0,
      message: 'success',
      data: null
    })
    const ok = await store.markRead(10)
    expect(ok).toBe(true)
    expect(store.records.find((r) => r.id === 10)?.status).toBe('read')
    expect(store.records.find((r) => r.id === 11)?.status).toBe('unread')
  })

  it('markRead 失败时不修改本地状态', async () => {
    const store = useAlertsStore()
    store.records = [makeRecord({ id: 10 })]
    vi.spyOn(alertApi, 'markAlertRecordAsRead').mockRejectedValue(new Error('boom'))
    const ok = await store.markRead(10)
    expect(ok).toBe(false)
    expect(store.records[0]?.status).toBe('unread')
    expect(store.recordsError).toBe('boom')
  })

  it('deleteRule 成功后从本地移除', async () => {
    const store = useAlertsStore()
    store.rules = [makeRule({ id: 1 }), makeRule({ id: 2 })]
    vi.spyOn(alertApi, 'deleteAlertRule').mockResolvedValue({ code: 0, message: 'success', data: null })
    const ok = await store.deleteRule(1)
    expect(ok).toBe(true)
    expect(store.rules.map((r) => r.id)).toEqual([2])
  })

  it('createRule 成功后插入头部', async () => {
    const store = useAlertsStore()
    store.rules = [makeRule({ id: 2 })]
    vi.spyOn(alertApi, 'createAlertRule').mockResolvedValue({
      code: 0,
      message: 'success',
      data: makeRule({ id: 99 })
    })
    const created = await store.createRule({
      metric: 'cpu',
      operator: '>',
      threshold_value: 80,
      level: 'warning'
    })
    expect(created.id).toBe(99)
    expect(store.rules.map((r) => r.id)).toEqual([99, 2])
  })

  it('createRule 失败时重抛 ApiBusinessError 并设置 rulesError', async () => {
    const store = useAlertsStore()
    const apiError = new ApiBusinessError(ErrorCode.RESOURCE_CONFLICT, 'rule conflict')
    vi.spyOn(alertApi, 'createAlertRule').mockRejectedValue(apiError)
    await expect(
      store.createRule({
        metric: 'cpu',
        operator: '>',
        threshold_value: 80,
        level: 'warning'
      })
    ).rejects.toBe(apiError)
    expect(store.rulesError).toBe('rule conflict')
  })

  it('updateRule 成功后替换列表中旧条目', async () => {
    const store = useAlertsStore()
    store.rules = [makeRule({ id: 1, threshold_value: 80 }), makeRule({ id: 2 })]
    vi.spyOn(alertApi, 'updateAlertRule').mockResolvedValue({
      code: 0,
      message: 'success',
      data: makeRule({ id: 1, threshold_value: 95 })
    })
    const updated = await store.updateRule(1, {
      threshold_value: 95,
      level: 'critical',
      enabled: false
    })
    expect(updated.threshold_value).toBe(95)
    expect(store.rules.find((r) => r.id === 1)?.threshold_value).toBe(95)
    expect(store.rules).toHaveLength(2)
  })

  it('updateRule 失败时重抛 ApiBusinessError', async () => {
    const store = useAlertsStore()
    const apiError = new ApiBusinessError(ErrorCode.RESOURCE_NOT_FOUND, 'rule gone')
    vi.spyOn(alertApi, 'updateAlertRule').mockRejectedValue(apiError)
    await expect(
      store.updateRule(99, { threshold_value: 50, level: 'warning', enabled: true })
    ).rejects.toBe(apiError)
    expect(store.rulesError).toBe('rule gone')
  })

  it('deleteRule 失败时重抛 ApiBusinessError', async () => {
    const store = useAlertsStore()
    store.rules = [makeRule({ id: 1 })]
    const apiError = new ApiBusinessError(ErrorCode.FORBIDDEN, 'no admin')
    vi.spyOn(alertApi, 'deleteAlertRule').mockRejectedValue(apiError)
    await expect(store.deleteRule(1)).rejects.toBe(apiError)
    expect(store.rulesError).toBe('no admin')
    expect(store.rules).toHaveLength(1)
  })

  it('applyAlertPush 命中合法 payload 时计数 +1', () => {
    const store = useAlertsStore()
    store.applyAlertPush({
      server_id: 1,
      alert: {
        id: 1,
        rule_id: null,
        metric: 'cpu',
        current_value: 90.5,
        threshold_value: 80,
        level: 'warning',
        status: 'unread',
        triggered_at: '2026-07-22T00:00:00Z'
      }
    })
    expect(store.pendingPushCount).toBe(1)
  })

  it('applyAlertPush 命中非法 payload 时计数不变', () => {
    const store = useAlertsStore()
    // @ts-expect-error 故意传入非法 payload 验证守卫
    store.applyAlertPush({ server_id: '1' })
    expect(store.pendingPushCount).toBe(0)
  })

  it('markPendingPushSeen 清零计数', () => {
    const store = useAlertsStore()
    store.pendingPushCount = 5
    store.markPendingPushSeen()
    expect(store.pendingPushCount).toBe(0)
  })

  it('resetRecords 不影响 auth/metrics store', () => {
    const alerts = useAlertsStore()
    const auth = useAuthStore()
    const metrics = useMetricsStore()
    alerts.records = [makeRecord({ id: 1 })]
    alerts.pendingPushCount = 3
    alerts.resetRecords()
    expect(alerts.records).toEqual([])
    expect(alerts.pendingPushCount).toBe(0)
    // 其他 store 的 state 应不受影响
    expect(auth.token).toBeNull()
    expect(metrics.latest).toBeNull()
  })
})