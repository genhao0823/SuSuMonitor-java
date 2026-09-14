import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ProcessTopCard from '@/components/ProcessTopCard.vue'
import type { ProcessSnapshot } from '@/types/metrics'

/**
 * el-card / el-empty / el-tabs / el-tab-pane / el-table 全局组件 stub，
 * 单测只验 props 分支渲染。
 */
const globalStubs = {
  'el-card': { template: '<div class="el-card-stub"><slot name="header" /><slot /></div>' },
  'el-empty': { template: '<div class="el-empty-stub">{{ $attrs.description }}<slot /></div>' },
  'el-tabs': { template: '<div class="el-tabs-stub"><slot /></div>' },
  'el-tab-pane': { props: ['label'], template: '<div class="el-tab-pane-stub">{{ label }}<slot /></div>' },
  'el-table': {
    props: ['data'],
    template: '<div class="el-table-stub">'
      + '<div v-for="row in data" :key="row.pid" class="el-table-row-stub">'
      + 'pid={{ row.pid }} name={{ row.name }} cpu={{ row.cpu_percent }} mem={{ row.mem_percent }}'
      + '</div></div>'
  }
}

function snapshot(overrides: Partial<ProcessSnapshot> = {}): ProcessSnapshot {
  return {
    server_id: 1,
    collected_at: '2026-09-15T08:00:00Z',
    cpu_top: [{ pid: 9, name: 'java', cpu_percent: 41.2, mem_percent: 18.4 }],
    mem_top: [{ pid: 5, name: 'mysqld', cpu_percent: 0.6, mem_percent: 32.1 }],
    ...overrides
  }
}

describe('ProcessTopCard', () => {
  it('快照为 null 时渲染空态提示', () => {
    const w = mount(ProcessTopCard, {
      props: { snapshot: null },
      global: { stubs: globalStubs }
    })
    expect(w.find('.el-empty-stub').exists()).toBe(true)
    expect(w.text()).toContain('暂无数据')
  })

  it('有快照时渲染 CPU 与内存排行条目', () => {
    const w = mount(ProcessTopCard, {
      props: { snapshot: snapshot() },
      global: { stubs: globalStubs }
    })
    expect(w.find('.el-empty-stub').exists()).toBe(false)
    expect(w.text()).toContain('name=java')
    expect(w.text()).toContain('name=mysqld')
    expect(w.text()).toContain('cpu=41.2')
    expect(w.text()).toContain('mem=32.1')
    expect(w.text()).toContain('CPU 排行')
    expect(w.text()).toContain('内存排行')
    expect(w.text()).toContain('采样于')
  })

  it('快照列表为空数组时仍渲染表格区而非空态（Agent 在线上报但无可见进程）', () => {
    const w = mount(ProcessTopCard, {
      props: { snapshot: snapshot({ cpu_top: [], mem_top: [] }) },
      global: { stubs: globalStubs }
    })
    expect(w.find('.el-empty-stub').exists()).toBe(false)
    expect(w.findAll('.el-table-stub').length).toBe(2)
  })
})
