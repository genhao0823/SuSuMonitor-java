import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, inject, provide } from 'vue'
import ResourcesCard from '@/components/ResourcesCard.vue'
import type { ServerResourcesSnapshot } from '@/types/metrics'

/**
 * el-table 桩：向后代列桩提供行数据，自身渲染默认插槽（列桩序列）。
 * 真实 Element Plus 的 el-table 同样经内部上下文把行数据交给 el-table-column，
 * 桩用 provide/inject 复刻这一协作，使作用域插槽 #default="{ row }" 可渲染。
 */
const ElTableStub = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide('stubTableRows', props.data)
    return () => h('div', { class: 'el-table-stub' }, slots.default?.())
  }
})

/** el-table-column 桩：对每行渲染作用域插槽；无插槽时渲染 prop 字段文本。 */
const ElTableColumnStub = defineComponent({
  props: { prop: { type: String, default: '' } },
  setup(props, { slots }) {
    const rows = inject<Record<string, unknown>[]>('stubTableRows', [])
    return () =>
      h(
        'div',
        { class: 'el-table-column-stub' },
        rows.map((row) => slots.default?.({ row }) ?? String(row[props.prop] ?? ''))
      )
  }
})

/**
 * el-card / el-empty / el-tabs / el-tab-pane / el-progress 全局组件 stub，
 * 单测只验 props 分支与数值格式化渲染。
 */
const globalStubs = {
  'el-card': { template: '<div class="el-card-stub"><slot name="header" /><slot /></div>' },
  'el-empty': { template: '<div class="el-empty-stub">{{ $attrs.description }}<slot /></div>' },
  'el-tabs': { template: '<div class="el-tabs-stub"><slot /></div>' },
  'el-tab-pane': { props: ['label'], template: '<div class="el-tab-pane-stub">{{ label }}<slot /></div>' },
  'el-table': ElTableStub,
  'el-table-column': ElTableColumnStub,
  'el-progress': {
    props: ['percentage'],
    template: '<div class="el-progress-stub">{{ percentage }}%</div>'
  }
}

function snapshot(overrides: Partial<ServerResourcesSnapshot> = {}): ServerResourcesSnapshot {
  return {
    server_id: 1,
    collected_at: '2026-09-15T08:00:00Z',
    disks: [{ mount_point: '/', device: '/dev/sda1', total: 1000, free: 250 }],
    nics: [{ name: 'eth0', rx_kbps: 640, tx_kbps: 12.8 }],
    ...overrides
  }
}

describe('ResourcesCard', () => {
  it('快照为 null 时渲染空态提示', () => {
    const w = mount(ResourcesCard, {
      props: { snapshot: null },
      global: { stubs: globalStubs }
    })
    expect(w.find('.el-empty-stub').exists()).toBe(true)
    expect(w.text()).toContain('暂无数据')
  })

  it('有快照时渲染磁盘容量条与网卡速率行', () => {
    const w = mount(ResourcesCard, {
      props: { snapshot: snapshot() },
      global: { stubs: globalStubs }
    })
    expect(w.find('.el-empty-stub').exists()).toBe(false)
    expect(w.text()).toContain('/')
    expect(w.text()).toContain('/dev/sda1')
    expect(w.text()).toContain('75%')
    expect(w.text()).toContain('已用 750 B')
    expect(w.text()).toContain('eth0')
    expect(w.text()).toContain('640.00 kbps')
    expect(w.text()).toContain('12.80 kbps')
    expect(w.text()).toContain('磁盘容量')
    expect(w.text()).toContain('网卡速率')
    expect(w.text()).toContain('采样于')
  })

  it('总容量为 0 的磁盘按 0% 展示而非 NaN', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const w = mount(ResourcesCard, {
      props: {
        snapshot: snapshot({ disks: [{ mount_point: '/mnt/empty', device: 'loop0', total: 0, free: 0 }] })
      },
      global: { stubs: globalStubs }
    })
    expect(w.text()).toContain('0%')
    expect(w.text()).not.toContain('NaN')
    warnSpy.mockRestore()
  })
})
