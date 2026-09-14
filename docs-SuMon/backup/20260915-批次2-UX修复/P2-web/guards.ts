import type { Router } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由守卫统一注册入口。
 *
 * 受保护路由(meta.requiresAuth/requiresAdmin = true)处理流程:
 *
 * ```
 * 用户浏览器地址栏输入 /dashboard
 *   ↓
 * 服务端返回 index.html (SPA 入口)
 *   ↓
 * Vue 启动 → router 解析 /dashboard
 *   ↓
 * beforeEach 守卫执行:
 *   - 未登录 (auth.isAuthenticated=false) → 重定向 /login?redirect=/dashboard
 *   - 已登录但非 admin (requiresAdmin)        → 重定向 /forbidden
 *   - 已登录且通过                          → 进入 DashboardView
 *   ↓
 * DashboardView onMounted → 调 API → 401 时
 *   axios 拦截器触发 onUnauthorized → 清 token + 再次跳 /login
 * ```
 *
 * 设计要点:
 * - 单一真源 = /api/auth/me 的数据库状态;Auth store 持有 JWT,守卫仅校验"是否登录"。
 * - 浏览器冷启动到直接访问 /dashboard 时,Vue Router 解析路径后立即跑守卫,
 *   此时 localStorage 已被 pinia-plugin-persistedstate 同步还原,所以
 *   `auth.isAuthenticated` 在第一次守卫执行时就是稳定值。
 * - 防开放重定向:LoginView 的 resolveRedirect 校验 redirect 必须以 "/" 开头;
 *   若被重定向回 /login 或 /register 死循环由查询默认值兜底。
 * - meta.publicOnly(登录/注册页) 已登录用户再去会被反送 /dashboard,避免重复登录。
 *
 * @param router Vue Router 实例
 */
export function installRouterGuards(router: Router): void {
  router.beforeEach((to) => {
    const auth = useAuthStore()

    if (to.meta.requiresAdmin === true && !auth.isAdmin) {
      return { name: 'forbidden' }
    }

    if (to.meta.requiresAuth === true && !auth.isAuthenticated) {
      return {
        name: 'login',
        query: to.fullPath !== '/' ? { redirect: to.fullPath } : undefined
      }
    }

    if (to.meta.publicOnly === true && auth.isAuthenticated) {
      return { name: 'dashboard' }
    }

    return true
  })
}
