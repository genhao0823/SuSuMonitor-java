import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import AdminUsersView from '@/views/AdminUsersView.vue'
import * as adminApi from '@/api/admin'
import type { CurrentUser } from '@/types/api'

/**
 * AdminUsersView 分页/搜索/批量审核回归。
 *
 * 范围:
 * - 挂载后按 page/page_size 拉取分页数据并渲染 items + total
 * - 搜索回车触发远端 keyword 查询(page 重置为 1)
 * - 翻页触发 current-change → 重新拉取
 * - 单行通过/拒绝 → 调用对应 API + 本地移除该行
 * - 批量(选择 + 批量通过) → batchApproveUsers(ids) + 提示 processed/failed
 *
 * 策略:stub PageHeader 与 Element Plus 子组件;el-table/el-pagination stub
 * 提供触发按钮以驱动 selection-change / current-change 事件。
 */

const PENDING_USER = (id: number, username: string): CurrentUser => ({
  id,
  username,
  role: 'user',
  reviewStatus: 'pending',
  reviewedAt: null,
  createdAt: '2026-07-20T00:00:00Z'
})

vi.mock('@/api/admin', () => ({
  listUsers: vi.fn(),
  approveUser: vi.fn(),
  rejectUser: vi.fn(),
  batchApproveUsers: vi.fn(),
  batchRejectUsers: vi.fn()
}))

// 捕获批量失败明细弹窗调用(vi.hoisted 保证 mock 工厂 hoist 后仍可引用)。
const { alertSpy } = vi.hoisted(() => ({ alertSpy: vi.fn().mockResolvedValue(undefined) }))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
    ElMessageBox: { alert: alertSpy }
  }
})

const globalStubs = {
  PageHeader: { template: '<div class="page-header-stub" />' },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<input class="el-input-stub" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-table': {
    props: ['data', 'emptyText'],
    emits: ['selection-change'],
    template:
      '<div class="el-table-stub" :data-rows="JSON.stringify(data)" :empty-text="emptyText">' +
      '<button class="el-table-stub__select" @click="$emit(\'selection-change\', data)">select</button>' +
      '<slot /></div>'
  },
  'el-table-column': {
    props: ['label', 'type'],
    // 静态作用域 row:驱动操作列 #default="{ row }" 渲染可点击按钮。
    template: '<div class="el-table-column-stub"><slot :row="{ id: 1, username: \'alice\' }" /></div>'
  },
  'el-button': { template: '<button class="el-button-stub"><slot /></button>' },
  'el-popconfirm': {
    emits: ['confirm'],
    template:
      '<div class="el-popconfirm-stub"><slot name="reference" />' +
      '<button class="el-popconfirm-stub__confirm" @click="$emit(\'confirm\')">confirm</button></div>'
  },
  'el-tag': { template: '<span class="el-tag-stub"><slot /></span>' },
  'el-tabs': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'tab-change'],
    template:
      '<div class="el-tabs-stub" :data-status="modelValue">' +
      '<button class="el-tabs-stub__approved" @click="$emit(\'update:modelValue\', \'approved\'); $emit(\'tab-change\')">approved</button>' +
      '<slot /></div>'
  },
  'el-tab-pane': { template: '<div class="el-tab-pane-stub"><slot /></div>' },
  'el-card': { template: '<div class="el-card-stub"><slot /></div>' },
  'el-pagination': {
    props: ['total'],
    emits: ['current-change', 'size-change'],
    template:
      '<div class="el-pagination-stub" :data-total="total">' +
      '<button class="el-pagination-stub__next" @click="$emit(\'current-change\', 2)">next</button>' +
      '<button class="el-pagination-stub__size" @click="$emit(\'size-change\', 50)">size</button></div>'
  }
}

async function flush(): Promise<void> {
  await nextTick()
  await Promise.resolve()
}

describe('AdminUsersView 分页/搜索/批量', () => {
  let wrapper: VueWrapper

  beforeEach(async () => {
    vi.clearAllMocks()
    vi.mocked(adminApi.listUsers).mockResolvedValue({
      code: 0,
      message: 'success',
      data: {
        items: [PENDING_USER(1, 'alice'), PENDING_USER(2, 'bob')],
        total: 42,
        page: 1,
        page_size: 20
      }
    })
    vi.mocked(adminApi.approveUser).mockResolvedValue({
      code: 0,
      message: 'success',
      data: PENDING_USER(1, 'alice')
    })
    vi.mocked(adminApi.rejectUser).mockResolvedValue({
      code: 0,
      message: 'success',
      data: PENDING_USER(1, 'alice')
    })
    vi.mocked(adminApi.batchApproveUsers).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { processed: 2, failed: 0, failed_ids: [] }
    })
    vi.mocked(adminApi.batchRejectUsers).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { processed: 0, failed: 2, failed_ids: [] }
    })
    wrapper = mount(AdminUsersView, { global: { stubs: globalStubs } })
    await flush()
  })

  it('挂载后按默认 page/page_size + status=pending 拉取并渲染 items + total', () => {
    expect(adminApi.listUsers).toHaveBeenCalledWith({ status: 'pending', page: 1, page_size: 20, keyword: '' })
    const table = wrapper.find('.el-table-stub')
    const rows = JSON.parse(table.attributes('data-rows') ?? '[]') as Array<{ username: string }>
    expect(rows.map((r) => r.username)).toEqual(['alice', 'bob'])
    expect(wrapper.find('.el-pagination-stub').attributes('data-total')).toBe('42')
  })

  it('搜索回车:触发远端 keyword 查询并把 page 重置为 1', async () => {
    const input = wrapper.find('.el-input-stub')
    await input.setValue('ali')
    await input.trigger('keyup.enter')
    await flush()

    expect(adminApi.listUsers).toHaveBeenLastCalledWith({ status: 'pending', page: 1, page_size: 20, keyword: 'ali' })
  })

  it('翻页:current-change 触发按新 page 重新拉取', async () => {
    await wrapper.find('.el-pagination-stub__next').trigger('click')
    await flush()

    expect(adminApi.listUsers).toHaveBeenLastCalledWith({ status: 'pending', page: 2, page_size: 20, keyword: '' })
  })

  it('切换状态 tab:按新 status 拉取并隐藏批量按钮', async () => {
    await wrapper.find('.el-tabs-stub__approved').trigger('click')
    await flush()

    expect(adminApi.listUsers).toHaveBeenLastCalledWith({ status: 'approved', page: 1, page_size: 20, keyword: '' })
    // 已通过 tab 下不渲染批量按钮(批量仅待审核)。
    const buttons = wrapper.findAll('.el-button-stub')
    expect(buttons.some((b) => b.text().includes('批量'))).toBe(false)
  })

  it('单行通过:调用 approveUser 并本地移除该行', async () => {
    const buttons = wrapper.findAll('.el-button-stub')
    const approveButton = buttons.find((b) => b.text().trim() === '通过')
    expect(approveButton).toBeDefined()
    await approveButton?.trigger('click')
    await flush()

    expect(adminApi.approveUser).toHaveBeenCalledWith(1)
    const rows = JSON.parse(wrapper.find('.el-table-stub').attributes('data-rows') ?? '[]') as Array<{ id: number }>
    expect(rows.map((r) => r.id)).toEqual([2])
    expect(wrapper.find('.el-pagination-stub').attributes('data-total')).toBe('41')
  })

  it('批量通过:选择后调用 batchApproveUsers 并刷新列表', async () => {
    // 模拟 el-table selection-change:全选当前页。
    await wrapper.find('.el-table-stub__select').trigger('click')
    await flush()

    // 找到"批量通过"按钮(文本含"批量通过(2)")。
    const buttons = wrapper.findAll('.el-button-stub')
    const batchApproveButton = buttons.find((b) => b.text().includes('批量通过'))
    expect(batchApproveButton?.attributes('disabled')).toBeUndefined()
    await batchApproveButton?.trigger('click')
    await flush()

    expect(adminApi.batchApproveUsers).toHaveBeenCalledWith([1, 2])
    // 成功(failed=0)后重新拉取。
    expect(adminApi.listUsers).toHaveBeenCalledTimes(2)
  })

  it('批量拒绝:选择后经 popconfirm 确认调用 batchRejectUsers', async () => {
    await wrapper.find('.el-table-stub__select').trigger('click')
    await flush()

    const buttons = wrapper.findAll('.el-button-stub')
    const batchRejectButton = buttons.find((b) => b.text().includes('批量拒绝'))
    await batchRejectButton?.trigger('click')
    await flush()
    // reference 按钮只打开 popconfirm,需点确认按钮才触发 @confirm。
    await wrapper.find('.el-popconfirm-stub__confirm').trigger('click')
    await flush()

    expect(adminApi.batchRejectUsers).toHaveBeenCalledWith([1, 2])
  })

  it('批量部分失败:弹出失败明细(alice/bob 用户名映射)', async () => {
    vi.mocked(adminApi.batchRejectUsers).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { processed: 0, failed: 2, failed_ids: [1, 2] }
    })
    await wrapper.find('.el-table-stub__select').trigger('click')
    await flush()

    const buttons = wrapper.findAll('.el-button-stub')
    const batchRejectButton = buttons.find((b) => b.text().includes('批量拒绝'))
    await batchRejectButton?.trigger('click')
    await flush()
    await wrapper.find('.el-popconfirm-stub__confirm').trigger('click')
    await flush()

    expect(alertSpy).toHaveBeenCalled()
    const message = alertSpy.mock.calls[0][0] as string
    expect(message).toContain('alice')
    expect(message).toContain('bob')
  })
})