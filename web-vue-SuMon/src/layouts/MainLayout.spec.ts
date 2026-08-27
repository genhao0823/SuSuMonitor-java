import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import MainLayout from '@/layouts/MainLayout.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ name: 'dashboard', meta: {} }),
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: { username: 'ADMIN', role: 'admin' },
    isAdmin: true,
    logout: vi.fn()
  })
}))

const global = {
  stubs: {
    'el-container': { template: '<div><slot /></div>' },
    'el-aside': { template: '<aside v-bind="$attrs"><slot /></aside>' },
    'el-menu': { template: '<nav><slot /></nav>' },
    'el-menu-item': { template: '<div><slot /><slot name="title" /></div>' },
    'el-header': { template: '<header><slot /></header>' },
    'el-main': { template: '<main><slot /></main>' },
    'el-button': { template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
    'el-tooltip': { template: '<span><slot /></span>' },
    'el-icon': { template: '<span><slot /></span>' },
    'el-dropdown': { template: '<div><slot /><slot name="dropdown" /></div>' },
    'el-dropdown-menu': { template: '<div><slot /></div>' },
    'el-dropdown-item': { template: '<button><slot /></button>' },
    'el-tag': { template: '<span><slot /></span>' },
    'router-view': { template: '<div />' }
  }
}

describe('MainLayout', () => {
  it('expands and collapses the sidebar from the header button', async () => {
    const wrapper = mount(MainLayout, { global })

    expect(wrapper.find('aside').attributes('width')).toBe('0px')
    expect(wrapper.get('button[aria-label="展开侧栏"]')).toBeTruthy()

    await wrapper.get('button[aria-label="展开侧栏"]').trigger('click')

    expect(wrapper.find('aside').attributes('width')).toBe('230px')
    expect(wrapper.get('button[aria-label="收起侧栏"]')).toBeTruthy()

    await wrapper.get('button[aria-label="收起侧栏"]').trigger('click')

    expect(wrapper.find('aside').attributes('width')).toBe('0px')
  })
})
