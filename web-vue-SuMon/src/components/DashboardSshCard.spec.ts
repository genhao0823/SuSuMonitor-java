import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DashboardSshCard from '@/components/DashboardSshCard.vue'
import type { SshTestResult } from '@/types/api'

/**
 * el-card / el-skeleton / el-tag / el-empty 全局组件 stub,绕过"Failed to resolve component" 警告。
 * 单测只验 props 透传 + 各分支渲染。
 */
const globalStubs = {
  'el-card': { template: '<div class="el-card-stub"><slot /></div>' },
  'el-skeleton': { template: '<div class="el-skeleton-stub" />' },
  'el-tag': { template: '<span class="el-tag-stub"><slot /></span>' },
  'el-empty': { template: '<div class="el-empty-stub">{{ $attrs.description }}<slot /></div>' }
}

function historyItem(overrides: Partial<SshTestResult> = {}): SshTestResult {
  return {
    server_id: 1,
    connected: true,
    error_code: null,
    host_key_algorithm: 'ssh-ed25519',
    host_key_fingerprint: 'SHA256:test',
    auth_type: 'password',
    duration_ms: 25,
    tested_at: '2026-08-10T10:00:00Z',
    ...overrides
  }
}

describe('DashboardSshCard', () => {
  it('loading 时渲染骨架屏', () => {
    const w = mount(DashboardSshCard, {
      props: { history: [], loading: true, error: null },
      global: { stubs: globalStubs }
    })
    expect(w.find('.el-skeleton-stub').exists()).toBe(true)
  })

  it('加载失败时显示错误消息', () => {
    const w = mount(DashboardSshCard, {
      props: { history: [], loading: false, error: '未能加载 SSH 测试历史' },
      global: { stubs: globalStubs }
    })
    expect(w.text()).toContain('未能加载 SSH 测试历史')
  })

  it('无记录时显示空态与提示', () => {
    const w = mount(DashboardSshCard, {
      props: { history: [], loading: false, error: null },
      global: { stubs: globalStubs }
    })
    expect(w.find('.el-empty-stub').exists()).toBe(true)
    expect(w.text()).toContain('暂无 SSH 测试记录')
  })

  it('成功记录渲染成功标记、耗时与时间', () => {
    const w = mount(DashboardSshCard, {
      props: {
        history: [historyItem({ duration_ms: 42, tested_at: '2026-08-10T10:00:00Z' })],
        loading: false,
        error: null
      },
      global: { stubs: globalStubs }
    })
    expect(w.text()).toContain('成功')
    expect(w.text()).toContain('42ms')
    // formatDateTime 按本地时区转换(UTC 10:00 → UTC+8 18:00)。
    expect(w.text()).toContain('2026-08-10 18:00:00')
  })

  it('失败记录渲染失败标记与错误码', () => {
    const w = mount(DashboardSshCard, {
      props: {
        history: [historyItem({ connected: false, error_code: 50400, duration_ms: 0 })],
        loading: false,
        error: null
      },
      global: { stubs: globalStubs }
    })
    expect(w.text()).toContain('失败')
    expect(w.text()).toContain('50400')
    // 首条记录本身是失败,错误码显示而非公钥信息。
    expect(w.text()).not.toContain('SHA256:test')
  })
})
