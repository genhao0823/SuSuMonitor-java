import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import type * as ElementPlus from 'element-plus'
import { ApiBusinessError } from '@/api/client'
import { ErrorCode } from '@/types/error-code'
import ServerListView from '@/views/ServerListView.vue'
import * as metricsApi from '@/api/metrics'
import * as serverApi from '@/api/server'
import type { Server } from '@/types/api'

const { routeQuery, replaceSpy, pushSpy, messageErrorSpy } = vi.hoisted(() => ({
  routeQuery: {} as Record<string, string>,
  replaceSpy: vi.fn(),
  pushSpy: vi.fn(),
  messageErrorSpy: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: routeQuery }),
  useRouter: () => ({ replace: replaceSpy, push: pushSpy })
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAdmin: true, isApproved: true })
}))

vi.mock('@/api/server', () => ({
  listServers: vi.fn(),
  deleteServer: vi.fn(),
  testSshConnection: vi.fn()
}))

vi.mock('@/api/metrics', () => ({
  getMetricsHistory: vi.fn()
}))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof ElementPlus>('element-plus')
  return {
    ...actual,
    ElMessage: {
      success: vi.fn(),
      warning: vi.fn(),
      error: messageErrorSpy
    }
  }
})

const SERVER: Server = {
  id: 7,
  name: 'node-7',
  host: '10.0.0.7',
  description: null,
  status: 'online',
  ssh_host: '10.0.0.7',
  ssh_port: 22,
  ssh_user: 'root',
  ssh_auth_type: 'password',
  agent_id: 'agent-7',
  agent_status: 'online',
  last_heartbeat_at: '2026-08-21T00:00:00Z',
  created_at: '2026-08-21T00:00:00Z',
  updated_at: '2026-08-21T00:00:00Z'
}

const globalStubs = {
  PageHeader: { template: '<div><slot name="actions" /></div>' },
  ServerSearchBar: {
    props: ['nameValue', 'hostValue', 'pageSize', 'pageSizeOptions'],
    emits: ['update:nameValue', 'update:hostValue', 'update:pageSize', 'reload'],
    template:
      '<div>' +
      '<button class="search-name" @click="$emit(\'update:nameValue\', \' node \' )">name</button>' +
      '<button class="search-host" @click="$emit(\'update:hostValue\', \' 10.0 \' )">host</button>' +
      '<button class="search-reload" @click="$emit(\'reload\')">reload</button>' +
      '</div>'
  },
  ServerPagination: {
    emits: ['update:page', 'update:pageSize'],
    template:
      '<div><button class="page-two" @click="$emit(\'update:page\', 2)">page</button></div>'
  },
  ServerFormDialog: { template: '<div />' },
  ServerSparkLine: { template: '<div />' },
  RouterLink: { template: '<a><slot /></a>' },
  'el-card': { template: '<div><slot /></div>' },
  'el-icon': { template: '<span><slot /></span>' },
  'el-table': {
    props: ['data'],
    emits: ['sort-change'],
    template:
      '<div>' +
      '<button class="sort-name" @click="$emit(\'sort-change\', { prop: \'name\', order: \'ascending\' })">sort</button>' +
      '<button class="sort-clear" @click="$emit(\'sort-change\', { prop: null, order: null })">clear</button>' +
      '<slot />' +
      '</div>'
  },
  'el-table-column': {
    template: '<div><slot :row="row" /></div>',
    data: () => ({ row: SERVER })
  },
  'el-button': {
    template: '<button @click="$emit(\'click\')"><slot /></button>'
  },
  'el-popconfirm': {
    emits: ['confirm'],
    template:
      '<div><slot name="reference" /><button class="confirm-delete" @click="$emit(\'confirm\')">confirm</button></div>'
  }
}

async function flush(): Promise<void> {
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

function mockList(items: Server[] = [], total = items.length): void {
  vi.mocked(serverApi.listServers).mockResolvedValue({
    code: 0,
    message: 'success',
    data: { items, total, page: 1, page_size: 10 }
  })
}

function mountView(): VueWrapper {
  return mount(ServerListView, {
    global: {
      stubs: globalStubs,
      directives: { loading: () => undefined }
    }
  })
}

describe('ServerListView 查询与操作回归', () => {
  let wrapper: VueWrapper | null = null

  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    Object.keys(routeQuery).forEach((key) => delete routeQuery[key])
    mockList()
    vi.mocked(metricsApi.getMetricsHistory).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { items: [], total: 0, page: 1, page_size: 100 }
    })
    vi.mocked(serverApi.deleteServer).mockResolvedValue({ code: 0, message: 'success', data: null })
    vi.mocked(serverApi.testSshConnection).mockResolvedValue({
      code: 0,
      message: 'success',
      data: {
        server_id: 7,
        connected: true,
        error_code: null,
        host_key_algorithm: 'ssh-ed25519',
        host_key_fingerprint: 'SHA256:test',
        auth_type: 'password',
        duration_ms: 12,
        tested_at: '2026-08-21T00:00:00Z'
      }
    })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
  })

  it('默认请求仅发送 ServerQuery 契约字段', async () => {
    wrapper = mountView()
    await flush()

    expect(serverApi.listServers).toHaveBeenCalledWith({
      page: 1,
      page_size: 10,
      sort_by: 'id',
      sort_order: 'desc'
    })
    expect(serverApi.listServers).not.toHaveBeenCalledWith(expect.objectContaining({ order: expect.anything() }))
  })

  it('从 URL 恢复分页排序与筛选并同步精简后的 query', async () => {
    Object.assign(routeQuery, {
      name: 'node',
      host: '10.0',
      page: '2',
      page_size: '20',
      sort_by: 'name',
      sort_order: 'asc'
    })
    wrapper = mountView()
    await flush()
    expect(serverApi.listServers).toHaveBeenCalledTimes(1)
    expect(serverApi.listServers).toHaveBeenLastCalledWith({
      page: 2,
      page_size: 20,
      name: 'node',
      host: '10.0',
      sort_by: 'name',
      sort_order: 'asc'
    })
    expect(replaceSpy).toHaveBeenLastCalledWith({
      name: 'servers',
      query: {
        name: 'node',
        host: '10.0',
        page: '2',
        page_size: '20',
        sort_by: 'name',
        sort_order: 'asc'
      }
    })
  })

  it('搜索输入在 500ms 后重置页码并触发一次远端查询', async () => {
    wrapper = mountView()
    await flush()
    const initialCalls = vi.mocked(serverApi.listServers).mock.calls.length

    await wrapper.find('.search-name').trigger('click')
    await vi.advanceTimersByTimeAsync(499)
    expect(serverApi.listServers).toHaveBeenCalledTimes(initialCalls)
    await vi.advanceTimersByTimeAsync(1)
    await flush()

    expect(serverApi.listServers).toHaveBeenCalledTimes(initialCalls + 1)
    expect(serverApi.listServers).toHaveBeenLastCalledWith({
      page: 1,
      page_size: 10,
      name: 'node',
      sort_by: 'id',
      sort_order: 'desc'
    })
  })

  it('自定义排序和清空排序分别映射为 API 白名单值', async () => {
    wrapper = mountView()
    await flush()

    await wrapper.find('.sort-name').trigger('click')
    await flush()
    expect(serverApi.listServers).toHaveBeenLastCalledWith(
      expect.objectContaining({ sort_by: 'name', sort_order: 'asc' })
    )

    await wrapper.find('.sort-clear').trigger('click')
    await flush()
    expect(serverApi.listServers).toHaveBeenLastCalledWith(
      expect.objectContaining({ sort_by: 'id', sort_order: 'desc' })
    )
  })

  it('删除末页唯一记录后回退上一页再刷新', async () => {
    routeQuery.page = '2'
    mockList([SERVER], 11)
    wrapper = mountView()
    await flush()

    await wrapper.find('.confirm-delete').trigger('click')
    await flush()

    expect(serverApi.deleteServer).toHaveBeenCalledWith(7)
    expect(serverApi.listServers).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, sort_by: 'id', sort_order: 'desc' })
    )
  })

  it('SSH 认证失败使用细分业务错误提示', async () => {
    mockList([SERVER])
    vi.mocked(serverApi.testSshConnection).mockRejectedValue(
      new ApiBusinessError(ErrorCode.SSH_AUTHENTICATION_FAILED, 'failed')
    )
    wrapper = mountView()
    await flush()

    const testButton = wrapper.findAll('button').find((button) => button.text().trim() === '测试连接')
    expect(testButton).toBeDefined()
    await testButton?.trigger('click')
    await flush()

    expect(messageErrorSpy).toHaveBeenCalledWith('SSH 认证失败:请检查用户名密码 / 私钥')
  })
})
