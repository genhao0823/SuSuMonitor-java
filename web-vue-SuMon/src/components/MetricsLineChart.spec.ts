import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MetricsLineChart from './MetricsLineChart.vue'
import type { MetricsHistory } from '@/types/metrics'

const setOptionMock = vi.fn()
const disposeMock = vi.fn()

vi.mock('echarts', () => ({
  init: () => ({
    setOption: setOptionMock,
    resize: vi.fn(),
    dispose: disposeMock
  })
}))

function sample(overrides: Partial<MetricsHistory> = {}): MetricsHistory {
  return {
    server_id: 1,
    cpu_percent: 10,
    memory_percent: 20,
    disk_percent: 30,
    memory_used: null,
    memory_total: null,
    disk_used: null,
    disk_total: null,
    net_rx: 100,
    net_tx: 200,
    temperature: null,
    load_avg: 0.5,
    collected_at: '2026-08-03T00:00:00Z',
    ...overrides
  }
}

describe('MetricsLineChart', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setOptionMock.mockClear()
    disposeMock.mockClear()
  })

  it('初始化时按指标数量生成对应 series', () => {
    mount(MetricsLineChart, {
      props: {
        data: [sample()],
        metrics: ['cpu_percent', 'memory_percent'],
        title: '测试趋势'
      }
    })
    expect(setOptionMock).toHaveBeenCalledTimes(1)
    const option = setOptionMock.mock.calls[0][0]
    expect(option.series).toHaveLength(2)
    expect(option.series[0].name).toBe('CPU %')
    expect(option.series[1].name).toBe('内存 %')
    expect(option.xAxis.type).toBe('time')
    // 数据点 [时间戳, 值]，空值被过滤。
    expect(option.series[0].data).toEqual([[new Date('2026-08-03T00:00:00Z').getTime(), 10]])
  })

  it('百分比指标固定 y 轴 0-100，字节指标自动缩放', () => {
    mount(MetricsLineChart, {
      props: {
        data: [sample()],
        metrics: ['cpu_percent', 'disk_percent']
      }
    })
    expect(setOptionMock.mock.calls[0][0].yAxis.max).toBe(100)

    setOptionMock.mockClear()
    mount(MetricsLineChart, {
      props: {
        data: [sample()],
        metrics: ['net_rx', 'net_tx']
      }
    })
    expect(setOptionMock.mock.calls[0][0].yAxis.max).toBeUndefined()
  })

  it('数据更新时重新 setOption', async () => {
    const wrapper = mount(MetricsLineChart, {
      props: {
        data: [sample()],
        metrics: ['cpu_percent']
      }
    })
    const callsBefore = setOptionMock.mock.calls.length
    await wrapper.setProps({ data: [sample({ cpu_percent: 55 })] })
    expect(setOptionMock.mock.calls.length).toBeGreaterThan(callsBefore)
  })

  it('匹配的规则在对应 metric 系列上画阈值线，不匹配的忽略', () => {
    const rules = [
      { id: 1, server_id: null, metric: 'cpu', threshold_value: 80, level: 'warning', enabled: true },
      { id: 2, server_id: 7, metric: 'memory', threshold_value: 85, level: 'critical', enabled: true },
      { id: 3, server_id: 999, metric: 'cpu', threshold_value: 10, level: 'warning', enabled: true },
      { id: 4, server_id: null, metric: 'disk', threshold_value: 90, level: 'warning', enabled: false }
    ] as unknown as Array<{ id: number; server_id: number | null; metric: string; threshold_value: number; level: string; enabled: boolean }>
    mount(MetricsLineChart, {
      props: {
        data: [sample()],
        metrics: ['cpu_percent', 'memory_percent'],
        rules: rules as never,
        serverId: 7
      }
    })
    const option = setOptionMock.mock.calls[0][0]
    const cpuSeries = option.series.find((s: { name: string }) => s.name === 'CPU %')
    const memorySeries = option.series.find((s: { name: string }) => s.name === '内存 %')
    // cpu: 全局规则(80)命中; serverId 999 的规则不匹配当前服务器; disk 规则 metric 不匹配。
    expect(cpuSeries.markLine.data).toHaveLength(1)
    expect(cpuSeries.markLine.data[0].yAxis).toBe(80)
    // memory: serverId=7 的 critical 规则命中。
    expect(memorySeries.markLine.data).toHaveLength(1)
    expect(memorySeries.markLine.data[0].yAxis).toBe(85)
  })

  it('无规则时不生成 markLine', () => {
    mount(MetricsLineChart, {
      props: {
        data: [sample()],
        metrics: ['cpu_percent']
      }
    })
    const option = setOptionMock.mock.calls[0][0]
    expect(option.series[0].markLine).toBeUndefined()
  })

  it('卸载时销毁图表实例', () => {
    const wrapper = mount(MetricsLineChart, {
      props: {
        data: [],
        metrics: ['cpu_percent']
      }
    })
    wrapper.unmount()
    expect(disposeMock).toHaveBeenCalledTimes(1)
  })
})
