import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DashboardServerStatusCard from '@/components/DashboardServerStatusCard.vue'

const global = {
  stubs: {
    'el-card': { template: '<div><slot /></div>' },
    'el-skeleton': { template: '<div class="skeleton" />' },
    'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' }
  }
}

describe('DashboardServerStatusCard', () => {
  it('renders all server state counts and emits the server shortcut event', async () => {
    const wrapper = mount(DashboardServerStatusCard, {
      props: { online: 6, offline: 2, unknown: 1, sampledCount: 9, complete: true, loading: false },
      global
    })

    expect(wrapper.text()).toContain('在线')
    expect(wrapper.text()).toContain('6')
    expect(wrapper.text()).toContain('已统计全部服务器')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('view-servers')).toBeTruthy()
  })

  it('explains when status counts come from a bounded sample', () => {
    const wrapper = mount(DashboardServerStatusCard, {
      props: { online: 100, offline: 0, unknown: 0, sampledCount: 100, complete: false, loading: false },
      global
    })

    expect(wrapper.text()).toContain('当前已统计前 100 台服务器')
  })
})
