import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, h, provide, inject, type ComputedRef, type SetupContext } from 'vue'
import type { PropType } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import MetricsView from '@/views/MetricsView.vue'
import type { ApiResponse } from '@/types/api'
import type { ProcessSnapshot, ServerResourcesSnapshot } from '@/types/metrics'

/** WS 桩类经 vi.hoisted 提前声明，供 vi.mock 工厂与断言共用。 */
const { storeMock, routeParams, MockMonitorWebSocket } = vi.hoisted(() => {
  const storeMock = {
    latest: null,
    history: [] as unknown[],
    loading: false,
    connected: false,
    error: null,
    timeRange: [new Date('2026-09-15T00:00:00Z'), new Date('2026-09-15T01:00:00Z')],
    load: vi.fn(),
    reset: vi.fn(),
    applyRealtime: vi.fn(),
    setConnected: vi.fn()
  }
  const routeParams = { serverId: '7' }
  class MockMonitorWebSocket {
    static lastInstance: InstanceType<typeof MockMonitorWebSocket> | null = null
    onProcesses?: (value: unknown) => void
    onResources?: (value: unknown) => void
    connectCalls = 0
    disconnectCalls = 0
    constructor(
      _onMetrics: unknown,
      _onConnected: unknown,
      _onAlertPush: unknown,
      _onSocketReady: unknown,
      _onTerminalMessage: unknown,
      _onServerStatus: unknown,
      onProcesses?: (value: unknown) => void,
      onResources?: (value: unknown) => void
    ) {
      this.onProcesses = onProcesses
      this.onResources = onResources
      MockMonitorWebSocket.lastInstance = this
    }
    connect(): void {
      this.connectCalls += 1
    }
    disconnect(): void {
      this.disconnectCalls += 1
    }
  }
  return { storeMock, routeParams, MockMonitorWebSocket }
})

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: routeParams })
}))

vi.mock('@/stores/metrics', () => ({
  useMetricsStore: () => storeMock
}))

vi.mock('@/api/metrics', () => ({
  getLatestProcesses: vi.fn(),
  getResourcesLatest: vi.fn()
}))

vi.mock('@/api/server', () => ({
  getServerStatus: vi.fn()
}))

vi.mock('@/api/alert', () => ({
  listAlertRules: vi.fn()
}))

vi.mock('@/services/websocket', () => ({
  MonitorWebSocket: MockMonitorWebSocket
}))

import { getLatestProcesses, getResourcesLatest } from '@/api/metrics'

const mockedGetLatestProcesses = vi.mocked(getLatestProcesses)
const mockedGetResourcesLatest = vi.mocked(getResourcesLatest)

/* ------------------------- Element Plus 通用 stub ------------------------- */

/** el-table 通过 provide 把行数据交给 el-table-column 的 scoped slot（与 AiCommandsView.spec 同约定）。 */
const TABLE_DATA_KEY = 'metrics-view-spec-table-data'

const ElTableStub = {
  name: 'ElTableStub',
  props: { data: { type: Array as PropType<unknown[]>, default: () => [] } },
  setup(props: Record<string, unknown>, { slots }: SetupContext) {
    provide(TABLE_DATA_KEY, computed(() => (props.data ?? []) as unknown[]))
    return () => h('div', { class: 'el-table-stub' }, slots.default?.())
  }
}

const ElTableColumnStub = {
  name: 'ElTableColumnStub',
  props: {
    prop: { type: String, default: undefined },
    label: { type: String, default: '' }
  },
  setup(props: Record<string, unknown>, { slots }: SetupContext) {
    const rows = inject<ComputedRef<unknown[]>>(TABLE_DATA_KEY, computed(() => []))
    const colKey = typeof props.prop === 'string' ? props.prop : String(props.label ?? '')
    return () => {
      if (slots.default === undefined) return null
      return h(
        'div',
        { class: 'el-table-column-stub', 'data-col': colKey },
        rows.value.map((row, index) => slots.default?.({ row, index }))
      )
    }
  }
}

const globalStubs = {
  PageHeader: { template: '<div><slot name="actions" /></div>' },
  MetricsLineChart: { template: '<div class="chart-stub" />' },
  ProcessTopCard: {
    props: ['snapshot'],
    template: '<div class="process-stub">{{ snapshot ? snapshot.collected_at : "empty" }}</div>'
  },
  ResourcesCard: {
    props: ['snapshot'],
    template: '<div class="resources-stub">{{ snapshot ? snapshot.collected_at : "empty" }}</div>'
  },
  'el-tag': { template: '<div class="el-tag-stub"><slot /></div>' },
  'el-alert': {
    props: { title: { type: String, default: '' } },
    template: '<div class="el-alert-stub">{{ title }}</div>'
  },
  'el-row': { template: '<div class="el-row-stub"><slot /></div>' },
  'el-col': { template: '<div class="el-col-stub"><slot /></div>' },
  'el-card': { template: '<div class="el-card-stub"><slot name="header" /><slot /></div>' },
  'el-tabs': { template: '<div class="el-tabs-stub"><slot /></div>' },
  'el-tab-pane': { template: '<div class="el-tab-pane-stub"><slot /></div>' },
  'el-date-picker': { template: '<div class="el-date-picker-stub" />' },
  'el-empty': {
    props: { description: { type: String, default: '' } },
    template: '<div class="el-empty-stub">{{ description }}</div>'
  },
  'el-table': ElTableStub,
  'el-table-column': ElTableColumnStub
}

/** 构造一份指定采集时间的进程快照。 */
function processSnapshotAt(collectedAt: string): ProcessSnapshot {
  return { server_id: 7, collected_at: collectedAt, cpu_top: [], mem_top: [] }
}

/** 构造一份指定采集时间的资源快照。 */
function resourcesSnapshotAt(collectedAt: string): ServerResourcesSnapshot {
  return {
    server_id: 7,
    collected_at: collectedAt,
    disks: [{ mount_point: '/', device: '/dev/sda1', total: 1024, free: 512 }],
    nics: [{ name: 'eth0', rx_kbps: 1.5, tx_kbps: 0.5 }]
  }
}

/** 构造成功态 API 响应。 */
function apiResponse<T>(data: T): ApiResponse<T> {
  return { code: 0, message: 'ok', data }
}

/** 受控 Promise：先挂起 REST 响应，模拟"WS 先到、REST 后返回"的时序，再按需放行。 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

/** 挂载视图并等待 onMounted 内全部初载异步任务完成。 */
async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(MetricsView, {
    global: { stubs: globalStubs, directives: { loading: {} } }
  })
  await flushPromises()
  return wrapper
}

describe('MetricsView 快照初载与 WS 推送竞态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    storeMock.load.mockResolvedValue(undefined)
    mockedGetLatestProcesses.mockRejectedValue(new Error('not loaded'))
    mockedGetResourcesLatest.mockRejectedValue(new Error('not loaded'))
    MockMonitorWebSocket.lastInstance = null
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('REST 先返回、WS 后推送：初载生效且随后被 WS 快照覆盖', async () => {
    mockedGetResourcesLatest.mockResolvedValue(apiResponse(resourcesSnapshotAt('2026-09-15T00:00:00Z')))
    const wrapper = await mountView()
    expect(wrapper.find('.resources-stub').text()).toBe('2026-09-15T00:00:00Z')

    MockMonitorWebSocket.lastInstance?.onResources?.(resourcesSnapshotAt('2026-09-15T00:00:05Z'))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.resources-stub').text()).toBe('2026-09-15T00:00:05Z')
  })

  it('WS 先推送、REST 后返回：迟到的 REST 初载不得覆盖更新的 WS 快照', async () => {
    const pending = deferred<ApiResponse<ServerResourcesSnapshot>>()
    mockedGetResourcesLatest.mockReturnValue(pending.promise)
    const wrapper = await mountView()
    expect(wrapper.find('.resources-stub').text()).toBe('empty')

    MockMonitorWebSocket.lastInstance?.onResources?.(resourcesSnapshotAt('2026-09-15T00:00:05Z'))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.resources-stub').text()).toBe('2026-09-15T00:00:05Z')

    pending.resolve(apiResponse(resourcesSnapshotAt('2026-09-15T00:00:00Z')))
    await flushPromises()
    expect(wrapper.find('.resources-stub').text()).toBe('2026-09-15T00:00:05Z')
  })

  it('进程快照同样受保护：WS 先到后，迟到的 REST 初载不覆盖', async () => {
    const pending = deferred<ApiResponse<ProcessSnapshot>>()
    mockedGetLatestProcesses.mockReturnValue(pending.promise)
    const wrapper = await mountView()
    expect(wrapper.find('.process-stub').text()).toBe('empty')

    MockMonitorWebSocket.lastInstance?.onProcesses?.(processSnapshotAt('2026-09-15T00:00:05Z'))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.process-stub').text()).toBe('2026-09-15T00:00:05Z')

    pending.resolve(apiResponse(processSnapshotAt('2026-09-15T00:00:00Z')))
    await flushPromises()
    expect(wrapper.find('.process-stub').text()).toBe('2026-09-15T00:00:05Z')
  })

  it('卸载时断开 WS 并复位 store', async () => {
    mockedGetResourcesLatest.mockResolvedValue(apiResponse(resourcesSnapshotAt('2026-09-15T00:00:00Z')))
    const wrapper = await mountView()
    const instance = MockMonitorWebSocket.lastInstance
    wrapper.unmount()
    expect(instance?.disconnectCalls).toBe(1)
    expect(storeMock.reset).toHaveBeenCalled()
  })
})
