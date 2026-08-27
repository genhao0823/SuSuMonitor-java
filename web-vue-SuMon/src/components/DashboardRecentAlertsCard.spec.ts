import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DashboardRecentAlertsCard from '@/components/DashboardRecentAlertsCard.vue'

const global = {
  stubs: {
    'el-card': { template: '<div><slot /></div>' },
    'el-skeleton': { template: '<div class="skeleton" />' },
    'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
    'el-icon': { template: '<span><slot /></span>' },
    'el-empty': { template: '<div class="empty"><slot /></div>' }
  }
}

const alert = {
  id: 1,
  rule_id: 2,
  server_id: 3,
  metric: 'cpu',
  current_value: 94.25,
  threshold_value: 90,
  level: 'critical',
  status: 'unread',
  message: null,
  read_by: null,
  read_at: null,
  triggered_at: '2026-08-01T12:00:00Z',
  notified_at: '2026-08-01T12:00:05Z',
  notify_channels: 'dingtalk',
  created_at: '2026-08-01T12:00:00Z'
} as const

describe('DashboardRecentAlertsCard', () => {
  it('renders recent alert details and only emits a read-only shortcut', async () => {
    const wrapper = mount(DashboardRecentAlertsCard, { props: { alerts: [alert], loading: false, error: '' }, global })

    expect(wrapper.text()).toContain('服务器 #3 · cpu')
    expect(wrapper.text()).toContain('当前 94.25 / 阈值 90')
    expect(wrapper.text()).toContain('严重')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('view-alerts')).toBeTruthy()
    expect(wrapper.text()).not.toContain('标记已读')
  })

  it('shows the supplied local error instead of an empty state', () => {
    const wrapper = mount(DashboardRecentAlertsCard, { props: { alerts: [], loading: false, error: '未能加载近期告警' }, global })
    expect(wrapper.text()).toContain('未能加载近期告警')
  })
})
