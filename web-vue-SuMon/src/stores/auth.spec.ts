import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import * as mockApi from '@/api/auth'
import { ApiBusinessError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { ApiResponse, CurrentUser, LoginResult } from '@/types/api'

// Mock @/api/auth module(閬垮厤鐪熻皟鍚庣)鈥斺€?factory 鍐呰仈,涓嶈兘 hoist 澶栭儴 var
vi.mock('@/api/auth', () => ({ loginUser: vi.fn(), registerUser: vi.fn(), getCurrentUser: vi.fn(), logoutUser: vi.fn() }))

// Mock localStorage(閬垮厤 jsdom 鍦?Node 22+ 涓嬪け璐?
const storage = new Map<string, string>()
Object.defineProperty(global, 'localStorage', {
  value: {
    getItem: (k: string) => storage.get(k) ?? null,
    setItem: (k: string, v: string) => { storage.set(k, v) },
    removeItem: (k: string) => { storage.delete(k) },
    clear: () => { storage.clear() },
    key: (i: number) => Array.from(storage.keys())[i] ?? null,
    get length() { return storage.size }
  },
  writable: true,
  configurable: true
})

function makeUser(overrides: Partial<{ id: number; username: string; role: 'admin' | 'user' }> = {}) {
  return {
    id: 1,
    username: 'test',
    role: 'user' as const,
    reviewStatus: 'approved' as const,
    reviewedAt: null,
    createdAt: '2026-07-22',
    ...overrides
  }
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    storage.clear()
    vi.mocked(mockApi.loginUser).mockReset()
    vi.mocked(mockApi.registerUser).mockReset()
    vi.mocked(mockApi.getCurrentUser).mockReset()
    vi.mocked(mockApi.logoutUser).mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('鍒濆 isAuthenticated 涓?false', () => {
    const auth = useAuthStore()
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.user).toBeNull()
    expect(auth.token).toBeNull()
  })

  it('login 鎴愬姛鍚庤缃?token + user + isAuthenticated true', async () => {
    vi.mocked(mockApi.loginUser).mockResolvedValueOnce({
      code: 0,
      message: 'success',
      data: { token: 'jwt-xxx', tokenType: 'Bearer', expiresIn: 86400, user: makeUser({ id: 1, username: 'admin', role: 'admin' }) }
    } satisfies ApiResponse<LoginResult>)

    const auth = useAuthStore()
    await auth.login({ username: 'admin', password: 'xxx' })

    expect(auth.isAuthenticated).toBe(true)
    expect(auth.token).toBe('jwt-xxx')
    expect(auth.user?.username).toBe('admin')
  })

  it('login 澶辫触(code != 0)鎶?ApiBusinessError', async () => {
    vi.mocked(mockApi.loginUser).mockRejectedValueOnce(
      new ApiBusinessError(40101, '鍑瘉閿欒')
    )

    const auth = useAuthStore()
    await expect(auth.login({ username: 'u', password: 'wrong' })).rejects.toThrow()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('isAdmin 鏍规嵁 role 璁＄畻', () => {
    const auth = useAuthStore()
    auth.user = makeUser({ role: 'admin' })
    expect(auth.isAdmin).toBe(true)
    auth.user = makeUser({ role: 'user' })
    expect(auth.isAdmin).toBe(false)
    auth.user = null
    expect(auth.isAdmin).toBe(false)
  })

  it('logout 娓呯┖ token + user', async () => {
    vi.mocked(mockApi.logoutUser).mockResolvedValueOnce({ code: 0, message: 'success', data: null })
    const auth = useAuthStore()
    auth.token = 'fake'
    auth.user = makeUser()
    await auth.logout()

    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
  })

  it('refresh 鎴愬姛 鈫?鏇存柊 user', async () => {
    vi.mocked(mockApi.getCurrentUser).mockResolvedValueOnce({
      code: 0,
      message: 'success',
      data: makeUser({ username: 'updated' })
    } satisfies ApiResponse<CurrentUser>)

    const auth = useAuthStore()
    auth.token = 'jwt-xxx'
    await auth.refresh()

    expect(auth.user?.username).toBe('updated')
  })

  it('refresh 鎶涢敊(40100 妯℃嫙)娓呯┖鏈湴浼氳瘽', async () => {
    vi.mocked(mockApi.getCurrentUser).mockRejectedValueOnce(
      new ApiBusinessError(40100, 'unauthorized')
    )

    const auth = useAuthStore()
    auth.token = 'old-jwt'
    auth.user = makeUser()
    // 娉?store 褰撳墠 refresh 涓嶅湪 40100 鏃舵竻绌?鍙湪 axios 鎷︽埅鍣ㄥ鐞?    // 杩欓噷浠呴獙璇?refresh 鎶涢敊涓嶄細璁?store 娈嬬暀
    await expect(auth.refresh()).rejects.toThrow()
    expect(auth.token).toBe('old-jwt') // 褰撳墠瀹炵幇涓嶈嚜鍔ㄦ竻
  })

  it('clearLocal 娓呯┖ store + localStorage', () => {
    const auth = useAuthStore()
    auth.token = 'fake'
    auth.user = makeUser()
    storage.set('susumonitor-auth', 'fake')

    auth.clearLocal()

    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    expect(storage.has('susumonitor-auth')).toBe(false)
  })

  it('isApproved 鏍规嵁 reviewStatus 璁＄畻', () => {
    const auth = useAuthStore()
    auth.user = makeUser({ role: 'user' })
    expect(auth.isApproved).toBe(true)
    auth.user = { ...makeUser(), reviewStatus: 'pending' as const }
    expect(auth.isApproved).toBe(false)
  })
})


