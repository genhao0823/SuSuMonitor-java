import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, h, inject, nextTick, provide, type ComputedRef, type PropType, type SetupContext } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import AiHealthReportsView from '@/views/AiHealthReportsView.vue'
import { generateHealthReport, listHealthReports } from '@/api/healthReport'
import type { AiHealthReport, PageResult } from '@/types/api'

/**
 * AI 健康报告页关键交互回归。
 *
 * 范围:
 * - 列表:挂载即加载,状态列按 succeeded/degraded 渲染文案
 * - 详情:点击「详情」复用行数据打开弹窗,不二次请求
 * - 手动生成:ElMessageBox 二次确认后提交,缺省日期提交空对象,成功后刷新并打开详情
 * - 生成失败:错误经 describeAiError 映射为中文提示
 */

vi.mock('@/api/healthReport', () => ({
  generateHealthReport: vi.fn(),
  getHealthReport: vi.fn(),
  listHealthReports: vi.fn()
}))

vi.mock('@/api/ai', () => ({
  describeAiError: vi.fn(() => '映射后的错误文案')
}))

const listHealthReportsMock = vi.mocked(listHealthReports)
const generateHealthReportMock = vi.mocked(generateHealthReport)

const makeReport = (overrides: Partial<AiHealthReport> = {}): AiHealthReport => ({
  id: 1,
  report_date: '2026-09-11',
  status: 'succeeded',
  provider: 'openai-compatible',
  model: 'test-model',
  prompt_version: 'ai-health-report-v1',
  summary: '整体健康\n磁盘峰值值得关注。',
  top_concerns: ['db-7 磁盘峰值 91%'],
  limitations: ['离线清单为快照口径'],
  facts: {
    report_date: '2026-09-11',
    server_inventory: { total_count: 3, online_count: 2, offline_count: 1, online_rate: 66.7 },
    metric_peaks: {
      avg_cpu_percent: 41.5,
      max_cpu_percent: 88,
      max_cpu_server_id: 7,
      avg_memory_percent: 55,
      max_memory_percent: 70,
      max_memory_server_id: 8,
      avg_disk_percent: 60,
      max_disk_percent: 91,
      max_disk_server_id: 7
    },
    alert_statistics: {
      total_triggered: 2,
      critical_count: 1,
      warning_count: 1,
      resolved_count: 1,
      unresolved_count: 1,
      top_servers: [
        { server_id: 7, server_name: 'db-7', alert_count: 2, critical_count: 1 }
      ]
    },
    offline_servers: [
      { server_id: 9, server_name: 'edge-9', agent_status: 'offline', last_heartbeat_at: null }
    ]
  },
  error_code: null,
  usage: { input_tokens: 20, output_tokens: 10, total_tokens: 30, estimated_cost: 0, currency: 'USD' },
  duration_ms: 1200,
  created_at: '2026-09-12T07:30:00Z',
  ...overrides
})

const makePage = (items: AiHealthReport[]): PageResult<AiHealthReport> => ({
  items,
  total: items.length,
  page: 1,
  page_size: 20
})

/* ------------------------- Element Plus 通用 stub ------------------------- */

/** el-table 通过 provide 把行数据交给 el-table-column 的 scoped slot。 */
const TABLE_DATA_KEY = 'stub-table-data'

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
  'el-card': { template: '<div class="el-card-stub"><slot /></div>' },
  'el-date-picker': {
    props: { modelValue: { type: String, default: '' } },
    emits: ['update:modelValue'],
    template: '<input class="el-date-picker-stub" :value="modelValue" />'
  },
  'el-table': ElTableStub,
  'el-table-column': ElTableColumnStub,
  'el-pagination': { template: '<div class="el-pagination-stub" />' },
  'el-dialog': { template: '<div class="el-dialog-stub"><slot /><slot name="footer" /></div>' },
  'el-alert': {
    props: { title: { type: String, default: '' } },
    template: '<div class="el-alert-stub">{{ title }}</div>'
  },
  'el-tag': { template: '<span class="el-tag-stub"><slot /></span>' },
  'el-button': {
    emits: ['click'],
    template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
  }
}

async function flush(): Promise<void> {
  for (let i = 0; i < 6; i += 1) {
    await Promise.resolve()
    await nextTick()
  }
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(AiHealthReportsView, {
    global: {
      stubs: globalStubs,
      directives: { loading: {} }
    }
  })
  await flush()
  await flush()
  return wrapper
}

function findButton(wrapper: VueWrapper, text: string): ReturnType<VueWrapper['find']> | undefined {
  return wrapper.findAll('.el-button-stub').find((b) => b.text().trim() === text)
}

describe('AiHealthReportsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(ElMessage, 'error').mockImplementation((() => ({})) as never)
    vi.spyOn(ElMessage, 'success').mockImplementation((() => ({})) as never)
    listHealthReportsMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makePage([makeReport()])
    })
  })

  it('挂载即加载报告列表,降级行渲染降级文案与告警条', async () => {
    listHealthReportsMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makePage([
        makeReport({ id: 2, status: 'degraded', summary: null, error_code: 42906 })
      ])
    })

    const wrapper = await mountView()

    expect(listHealthReportsMock).toHaveBeenCalledWith({ page: 1, page_size: 20 })
    expect(wrapper.text()).toContain('2026-09-11')
    expect(wrapper.text()).toContain('降级')
  })

  it('点击详情复用行数据打开弹窗,不二次请求', async () => {
    const wrapper = await mountView()

    await findButton(wrapper, '详情')?.trigger('click')
    await flush()

    expect(wrapper.text()).toContain('整体健康')
    expect(wrapper.text()).toContain('值得关注')
    expect(wrapper.text()).toContain('db-7 磁盘峰值 91%')
    expect(wrapper.text()).toContain('离线服务器(生成时刻快照)')
  })

  it('手动生成经二次确认后提交,缺省日期提交空对象,成功后刷新并打开详情', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const generated = makeReport({ id: 3, report_date: '2026-09-11' })
    generateHealthReportMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: generated
    })

    const wrapper = await mountView()
    await findButton(wrapper, '手动生成')?.trigger('click')
    await flush()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(generateHealthReportMock).toHaveBeenCalledWith({})
    // 初次加载 + 生成后刷新,共两次
    expect(listHealthReportsMock).toHaveBeenCalledTimes(2)
    // 详情弹窗复用生成结果直接打开
    expect(wrapper.text()).toContain('值得关注')
    expect(wrapper.text()).toContain('离线清单为快照口径')
  })

  it('取消二次确认时不调用生成接口', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)
    const wrapper = await mountView()
    await findButton(wrapper, '手动生成')?.trigger('click')
    await flush()
    expect(generateHealthReportMock).not.toHaveBeenCalled()
  })

  it('生成失败时经 describeAiError 映射并以错误消息提示', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    generateHealthReportMock.mockRejectedValue(new Error('rate limited'))
    const wrapper = await mountView()
    await findButton(wrapper, '手动生成')?.trigger('click')
    await flush()
    expect(ElMessage.error).toHaveBeenCalledWith('映射后的错误文案')
  })
})
