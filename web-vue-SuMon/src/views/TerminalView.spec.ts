import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import TerminalView from '@/views/TerminalView.vue'

const fit = vi.fn()
const dispose = vi.fn()
const disconnect = vi.fn()
const resize = vi.fn()
let resizeObserverCallback: ResizeObserverCallback | undefined

class ResizeObserverMock {
  constructor(callback: ResizeObserverCallback) {
    resizeObserverCallback = callback
  }

  observe = vi.fn()
  disconnect = disconnect
}

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { serverId: '4' } })
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isApproved: true })
}))

vi.mock('@/stores/metrics', () => ({
  useMetricsStore: () => ({ applyRealtime: vi.fn() })
}))

vi.mock('@/services/websocket', () => ({
  MonitorWebSocket: class {
    connect = vi.fn()
    disconnect = disconnect
  }
}))

vi.mock('@/services/terminal-ws', () => ({
  TerminalWebSocket: class {
    open = vi.fn()
    close = vi.fn()
    resize = resize
  }
}))

vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    cols = 80
    rows = 24
    loadAddon = vi.fn()
    open = vi.fn()
    onData = vi.fn()
    writeln = vi.fn()
    write = vi.fn()
    dispose = dispose
  }
}))

vi.mock('@xterm/addon-fit', () => ({
  FitAddon: class {
    fit = fit
  }
}))

const global = {
  stubs: {
    PageHeader: { template: '<div><slot name="actions" /></div>' },
    'el-tag': { template: '<span><slot /></span>' },
    'el-button': { template: '<button><slot /></button>' },
    'el-alert': { template: '<div />' },
    'el-card': { template: '<div><slot /></div>' },
    'el-icon': { template: '<span><slot /></span>' },
    'el-text': { template: '<span><slot /></span>' }
  }
}

describe('TerminalView', () => {
  it('refits when the terminal host width changes and cleans up the observer', async () => {
    vi.stubGlobal('ResizeObserver', ResizeObserverMock)
    const wrapper = mount(TerminalView, { global })
    await flushPromises()

    expect(fit).toHaveBeenCalled()
    expect(resizeObserverCallback).toBeTypeOf('function')

    resizeObserverCallback?.([], {} as ResizeObserver)
    expect(fit).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    expect(disconnect).toHaveBeenCalled()
    expect(dispose).toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})
