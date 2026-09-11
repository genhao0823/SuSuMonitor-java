import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

/**
 * theme store：偏好持久化读写、html.dark 切换与系统偏好兜底。
 * localStorage / matchMedia 以 stub 模拟，store 每次用例重建。
 */

describe('theme store', () => {
  let storage: Record<string, string>
  let matchDark: boolean

  beforeEach(() => {
    vi.resetModules()
    setActivePinia(createPinia())
    storage = {}
    matchDark = false
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage[key] ?? null,
      setItem: (key: string, value: string) => {
        storage[key] = value
      },
      removeItem: (key: string) => {
        delete storage[key]
      }
    })
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockImplementation((query: string) => ({
        matches: query.includes('dark') && matchDark
      }))
    )
    document.documentElement.classList.remove('dark')
  })

  async function freshStore() {
    const { useThemeStore } = await import('./theme')
    return useThemeStore()
  }

  it('无存储偏好时跟随系统浅色，apply 不加 dark class', async () => {
    const store = await freshStore()
    store.apply()
    expect(store.mode).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('系统偏好深色时初始为 dark 并挂 dark class', async () => {
    matchDark = true
    const store = await freshStore()
    store.apply()
    expect(store.mode).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('toggle 在明暗间切换并同步 dark class', async () => {
    matchDark = false
    const store = await freshStore()
    store.apply()
    store.toggle()
    expect(store.mode).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    store.toggle()
    expect(store.mode).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('忽略非法的存储值并回退系统偏好', async () => {
    storage['susumonitor-theme'] = JSON.stringify({ mode: 'blue' })
    matchDark = true
    const store = await freshStore()
    store.apply()
    expect(store.mode).toBe('dark')
  })
})
