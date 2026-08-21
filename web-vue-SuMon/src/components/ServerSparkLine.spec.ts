import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ServerSparkLine from '@/components/ServerSparkLine.vue'

function mountSpark(props: { data: number[]; label?: string; showMeta?: boolean }) {
  return mount(ServerSparkLine, { props })
}

describe('ServerSparkLine', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('data < 2 时不渲染折线与填充', () => {
    const w = mountSpark({ data: [42] })
    expect(w.find('polyline').exists()).toBe(false)
    expect(w.find('path').exists()).toBe(false)
  })

  it('data >= 2 时渲染 polyline + path(填充)', () => {
    const w = mountSpark({ data: [10, 20, 30, 40, 50, 60, 70] })
    const polyline = w.find('polyline')
    const path = w.find('path')
    expect(polyline.exists()).toBe(true)
    expect(path.exists()).toBe(true)
    // polyline 的 points 应为 6 个坐标对(7 个点 -> 6 段)
    const points = polyline.attributes('points')
    expect(points?.split(' ')).toHaveLength(7)
  })

  it('delta 为末尾 - 首位,正负分别上色', () => {
    const upW = mountSpark({ data: [10, 20, 30, 40, 50] })
    expect(upW.find('.server-spark-line__delta--up').exists()).toBe(true)
    expect(upW.text()).toContain('+40')

    const downW = mountSpark({ data: [100, 80, 60, 40, 20] })
    expect(downW.find('.server-spark-line__delta--down').exists()).toBe(true)
    expect(downW.text()).toContain('-80')
  })

  it('delta 最多显示一位小数,不暴露浮点运算尾数', () => {
    const w = mountSpark({ data: [31.2, 45.8, 38.4, 52.1] })

    expect(w.text()).toContain('+20.9')
    expect(w.text()).not.toContain('20.900000000000002')
  })

  it('showMeta=false 隐藏 delta 文字', () => {
    const w = mountSpark({ data: [1, 2, 3, 4, 5], showMeta: false })
    expect(w.find('.server-spark-line__delta').exists()).toBe(false)
  })

  it('label 透传到 meta 文字', () => {
    const w = mountSpark({ data: [1, 2, 3, 4, 5], label: '7d' })
    expect(w.find('.server-spark-line__label').text()).toBe('7d')
  })
})
