/**
 * 管理端路由。
 *
 * ★★ 关于权限：**不要按 `role` 隐藏菜单** ★★
 *   已核实：后端的 `staff.role` / `staff.station_id` **从未被任何代码使用** ——
 *   没有行级权限，任何登录的运维都能改任何站点。
 *
 *   所以前端按角色隐藏菜单会造成一种**假象**：「这个按钮不存在 = 我没权限」，
 *   而实际上换个 URL 就能访问。更糟的是它会让「有权限却看不到」的人
 *   以为系统坏了。正确做法是**全部展示**，并在 README 里写明没有行级权限。
 *
 * ★ 路由懒加载：`() => import(...)`。管理端页面多，一次全打包会让首屏
 *   把 52 个接口的所有页面代码都下载下来。
 */

import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '工作台' },
      },
      {
        path: 'station',
        name: 'station',
        component: () => import('@/views/station/StationListView.vue'),
        meta: { title: '站点管理' },
      },
      {
        path: 'station/:id',
        name: 'station-detail',
        component: () => import('@/views/station/StationDetailView.vue'),
        meta: { title: '站点详情', activeMenu: '/station' },
      },
      {
        path: 'charger',
        name: 'charger',
        component: () => import('@/views/charger/ChargerListView.vue'),
        meta: { title: '充电桩管理' },
      },
      {
        path: 'connector',
        name: 'connector',
        component: () => import('@/views/connector/ConnectorListView.vue'),
        meta: { title: '充电枪管理' },
      },
      {
        path: 'tariff-template',
        name: 'tariff-template',
        component: () => import('@/views/tariff/TariffTemplateListView.vue'),
        meta: { title: '计费模板' },
      },
      {
        path: 'station-tariff',
        name: 'station-tariff',
        component: () => import('@/views/tariff/StationTariffListView.vue'),
        meta: { title: '站点计费绑定' },
      },
      {
        path: 'category',
        name: 'category',
        component: () => import('@/views/package/CategoryListView.vue'),
        meta: { title: '套餐分类' },
      },
      {
        path: 'package',
        name: 'package',
        component: () => import('@/views/package/ChargePackageListView.vue'),
        meta: { title: '充电套餐' },
      },
      {
        path: 'member',
        name: 'member',
        component: () => import('@/views/member/MemberListView.vue'),
        meta: { title: '车主管理' },
      },
      {
        path: 'member/:id',
        name: 'member-detail',
        component: () => import('@/views/member/MemberDetailView.vue'),
        meta: { title: '车主详情', activeMenu: '/member' },
      },
      {
        path: 'member-package',
        name: 'member-package',
        component: () => import('@/views/member/MemberPackageListView.vue'),
        meta: { title: '车主权益' },
      },
      {
        path: 'wallet-adjust',
        name: 'wallet-adjust',
        component: () => import('@/views/wallet/WalletAdjustView.vue'),
        meta: { title: '钱包调账' },
      },
      {
        path: 'order',
        name: 'order',
        component: () => import('@/views/order/OrderListView.vue'),
        meta: { title: '订单管理' },
      },
      {
        path: 'order/:id',
        name: 'order-detail',
        component: () => import('@/views/order/OrderDetailView.vue'),
        meta: { title: '订单详情', activeMenu: '/order' },
      },
      {
        path: 'ai-assistant',
        name: 'ai-assistant',
        component: () => import('@/views/ai/AiChatView.vue'),
        meta: { title: 'AI 运维助手' },
      },
      {
        path: 'maintenance-ticket',
        name: 'maintenance-ticket',
        component: () => import('@/views/ticket/TicketListView.vue'),
        meta: { title: '运维工单' },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/ProfileView.vue'),
        meta: { title: '我的' },
      },
    ],
  },
  // ★ 兜底路由必须是**最后一条**，且用 `:pathMatch(.*)*`：
  //   写成 `path: '*'` 在新版 vue-router 里已经被移除了。
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  // 切路由回到顶部。列表页很长时，从第 3 页点进详情再返回，
  // 如果不滚动复位会停在半空中，看起来像页面没加载完。
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  const session = useSessionStore()
  // 只做一次：把 localStorage 里的会话恢复到内存
  session.restore()

  document.title = to.meta.title ? `${to.meta.title} · ChargeOps` : 'ChargeOps 管理后台'

  if (to.meta.public) {
    // 已登录还去登录页 → 直接送进工作台，避免重复登录
    if (to.name === 'login' && session.isLoggedIn) return { path: '/dashboard' }
    return true
  }

  if (!session.isLoggedIn) {
    // ★ 带上 `redirect`，登录后回到原来想去的页面
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  return true
})
