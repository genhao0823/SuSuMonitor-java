import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { installRouterGuards } from './guards'
import { installRouterLoading } from '@/composables/useRouterLoading'
import MainLayout from '@/layouts/MainLayout.vue'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    requiresAuth?: boolean
    requiresAdmin?: boolean
    publicOnly?: boolean
  }
}

/**
 * 公开路由:登录、注册、403、404 无需认证,也不使用 MainLayout。
 */
const publicRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', publicOnly: true }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册', publicOnly: true }
  },
  {
    path: '/forbidden',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { title: '无权限', publicOnly: true }
  }
]

/**
 * 受保护路由的子内容(仪表盘、服务器、用户审核等)。
 * 这些组件会被 MainLayout 的 <router-view /> 渲染,
 * 因此继承主布局的顶栏 + 侧栏 + 退出登录等公共 UI。
 */
const protectedChildren: RouteRecordRaw[] = [
  {
    path: 'dashboard',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '仪表盘', requiresAuth: true }
  },
  {
    path: 'servers',
    name: 'servers',
    component: () => import('@/views/ServerListView.vue'),
    meta: { title: '服务器列表', requiresAuth: true }
  },
  {
    path: 'servers/:serverId(\\d+)',
    name: 'server-detail',
    component: () => import('@/views/ServerDetailView.vue'),
    meta: { title: '服务器详情', requiresAuth: true }
  },
  {
    path: 'servers/:serverId(\\d+)/metrics',
    name: 'server-metrics',
    component: () => import('@/views/MetricsView.vue'),
    meta: { title: '服务器监控', requiresAuth: true }
  },
  {
    path: '/admin/users',
    name: 'admin-users',
    component: () => import('@/views/AdminUsersView.vue'),
    meta: { title: '用户审核', requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/alerts/records',
    name: 'alert-records',
    component: () => import('@/views/AlertRecordsView.vue'),
    meta: { title: '告警记录', requiresAuth: true }
  },
  {
    path: '/alerts/rules',
    name: 'alert-rules',
    component: () => import('@/views/AlertRulesView.vue'),
    meta: { title: '告警规则', requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/ai/qa',
    name: 'ai-qa',
    component: () => import('@/views/AiQaView.vue'),
    meta: { title: 'AI 运维问答', requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/ai/commands',
    name: 'ai-commands',
    component: () => import('@/views/AiCommandsView.vue'),
    meta: { title: 'AI 命令域', requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/ai/settings',
    name: 'ai-settings',
    component: () => import('@/views/AiProviderSettingsView.vue'),
    meta: { title: 'AI 设置', requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/terminal/:serverId(\\d+)',
    name: 'terminal',
    component: () => import('@/views/TerminalView.vue'),
    meta: { title: 'Web 终端', requiresAuth: true }
  }
]

/**
 * 受保护路由的父路由:空路径 / 套上 MainLayout。
 * 子路由继承父组件,实现顶栏+侧栏+内容区布局。
 */
const protectedLayout: RouteRecordRaw = {
  path: '/',
  component: MainLayout,
  redirect: '/dashboard',
  children: protectedChildren
}

const routes: RouteRecordRaw[] = [
  ...publicRoutes,
  protectedLayout,
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue'), meta: { title: '页面未找到', publicOnly: true } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

installRouterGuards(router)
installRouterLoading(router)

export default router
