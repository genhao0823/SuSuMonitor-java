import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/** 主题模式。 */
export type ThemeMode = 'light' | 'dark'

/** 持久化键，与 auth store 的 susumonitor-* 命名口径一致。 */
const PERSIST_KEY = 'susumonitor-theme'

/**
 * 深色模式状态：读写 html 根元素的 dark class（Element Plus 深色变量与
 * 项目 --susu/--glass 深色令牌都挂在该选择器下）。
 *
 * 首次访问未存储偏好时跟随系统 prefers-color-scheme；用户手动切换后持久化，
 * 之后始终以用户选择为准。
 */
export const useThemeStore = defineStore(
  'theme',
  () => {
    const stored = readStoredMode()
    const mode = ref<ThemeMode>(stored ?? systemPreferredMode())

    const isDark = computed(() => mode.value === 'dark')

    /** 把当前模式应用到 document 根元素；应在应用挂载前调用一次。 */
    function apply(): void {
      const root = document.documentElement
      root.classList.toggle('dark', mode.value === 'dark')
      root.style.colorScheme = mode.value
    }

    function setMode(next: ThemeMode): void {
      mode.value = next
      apply()
    }

    function toggle(): void {
      setMode(mode.value === 'dark' ? 'light' : 'dark')
    }

    return { mode, isDark, apply, setMode, toggle }

    function readStoredMode(): ThemeMode | null {
      try {
        const raw = localStorage.getItem(PERSIST_KEY)
        if (!raw) return null
        const parsed = JSON.parse(raw) as { mode?: unknown }
        if (parsed.mode === 'dark' || parsed.mode === 'light') {
          return parsed.mode
        }
        return null
      } catch {
        return null
      }
    }

    function systemPreferredMode(): ThemeMode {
      try {
        return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
      } catch {
        return 'light'
      }
    }
  },
  {
    persist: {
      key: PERSIST_KEY,
      storage: localStorage,
      pick: ['mode']
    }
  }
)
