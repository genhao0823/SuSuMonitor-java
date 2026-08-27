import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DashboardServersCard from '@/components/DashboardServersCard.vue'

/**
 * el-card / el-skeleton 全局组件 stub,绕过"Failed to resolve component" 警告。
 * 不需要完整 Element Plus 环境(单测只验 props 透传 + spark 渲染分支)。
 */
const globalStubs = {
  'el-card': { template: '<div class="el-card-stub"><slot /></div>' },
  'el-skeleton': { template: '<div class="el-skeleton-stub" />' }
}

describe('DashboardServersCard', () => {
  it('mock data 7 个点 → 渲染 count + spark + delta', () => {
    const w = mount(DashboardServersCard, {
      props: { count: 10, data: [5, 6, 7, 8, 9, 10, 11], loading: false },
      global: { stubs: globalStubs }
    })
    expect(w.text()).toContain('10')
    expect(w.find('.server-spark-line').exists()).toBe(true)
    // delta: 11 - 5 = +6
    expect(w.find('.server-spark-line__delta').text()).toContain('+6')
    expect(w.text()).toContain('在线 0')
    expect(w.text()).toContain('已统计全部服务器')
    expect(w.text()).toContain('暂无最近采集')
  })

  it('loading=true → 不渲染 spark(显示 el-skeleton-stub)', () => {
    const w = mount(DashboardServersCard, {
      props: { count: 0, data: [], loading: true },
      global: { stubs: globalStubs }
    })
    expect(w.find('.el-skeleton-stub').exists()).toBe(true)
    expect(w.find('.server-spark-line').exists()).toBe(false)
  })

  it('count=0 + data=空 → 显示 0,spark 不渲染 delta', () => {
    const w = mount(DashboardServersCard, {
      props: { count: 0, data: [], loading: false },
      global: { stubs: globalStubs }
    })
    expect(w.text()).toContain('0')
    // data < 2 → spark delta 隐藏
    expect(w.find('.server-spark-line__delta').exists()).toBe(false)
  })

  it('shows bounded server status distribution and the selected-server snapshot', () => {
    const w = mount(DashboardServersCard, {
      props: {
        count: 120, data: [30, 42], loading: false,
        online: 80, offline: 15, unknown: 5, sampledCount: 100, complete: false,
        serverName: 'prod-api-01', trendSamples: 2, trendCollectedAt: '2026-08-01T12:00:00Z',
        latestCpu: 42.3, latestMemory: 58, latestDisk: 71.25, latestCollectedAt: '2026-08-01T12:01:00Z'
      }, global: { stubs: globalStubs }
    })
    expect(w.text()).toContain('在线 80')
    expect(w.text()).toContain('当前已统计前 100 台服务器')
    expect(w.text()).toContain('示例服务器：prod-api-01')
    expect(w.text()).toContain('prod-api-01 · CPU 7d')
    expect(w.text()).toContain('2 个历史采样点')
    expect(w.text()).toContain('CPU 42.3%')
    expect(w.text()).toContain('内存 58.0%')
    expect(w.text()).toContain('磁盘 71.3%')
  })

  it('data 单点 → 不渲染 delta', () => {
    const w = mount(DashboardServersCard, {
      props: { count: 5, data: [42], loading: false },
      global: { stubs: globalStubs }
    })
    expect(w.text()).toContain('5')
    expect(w.find('.server-spark-line__delta').exists()).toBe(false)
  })
})
