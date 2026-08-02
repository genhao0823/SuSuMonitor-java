import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DashboardProbeCard from '@/components/DashboardProbeCard.vue'

const global = {
  stubs: {
    'el-card': { template: '<div><slot name="header" /><slot /></div>' },
    'el-skeleton': { template: '<div class="skeleton" />' }
  }
}

describe('DashboardProbeCard', () => {
  it('renders the server timestamp and browser-observed response duration', () => {
    const wrapper = mount(DashboardProbeCard, {
      props: {
        title: '健康检查', ok: true, detail: 'susumonitor', hint: '后端应用标识', description: '应用存活检查；不代表依赖已就绪。',
        facts: [{ label: '检查范围', value: '应用存活' }, { label: '依赖范围', value: '不校验数据库与消息队列' }], loading: false, okLabel: 'UP',
        checkedAt: '2026-08-01T12:34:56Z', responseTimeMs: 42
      }, global
    })

    expect(wrapper.text()).toContain('检查于')
    expect(wrapper.text()).toContain('浏览器观测 42 ms')
    expect(wrapper.text()).toContain('应用存活检查；不代表依赖已就绪。')
    expect(wrapper.text()).toContain('本次检查详情')
    expect(wrapper.text()).toContain('检查范围')
    expect(wrapper.text()).toContain('不校验数据库与消息队列')
  })

  it('does not fabricate probe metadata when it is unavailable', () => {
    const wrapper = mount(DashboardProbeCard, {
      props: {
        title: '就绪检查', ok: false, detail: 'rabbitmq unavailable', hint: '数据库健康状态', description: '数据库校验；启用 RabbitMQ 时后端也校验 Broker。',
        facts: [{ label: '校验范围', value: '数据库连接与健康' }, { label: 'Broker 校验', value: '启用 RabbitMQ 时纳入结果' }], loading: false, okLabel: 'READY'
      }, global
    })

    expect(wrapper.find('.probe-card__meta').exists()).toBe(false)
    expect(wrapper.text()).toContain('rabbitmq unavailable')
    expect(wrapper.text()).toContain('Broker 校验')
  })
})
