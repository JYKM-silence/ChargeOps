/**
 * 车主端路由。
 *
 * ★★ 结构与「4 个 Tab + 子页」严格对应 ★★
 *   底部 Tab 只有 4 个（首页 / 订单 / 套餐 / 我的），其余全是子页。
 *   子页的 `meta.activeMenu` 指向它所属的 Tab —— 这样从「我的 → 车辆管理」
 *   进来时，底部 Tab 仍然高亮「我的」，用户不会失去方位感。
 *
 * ★ `meta.requiresAuth` 默认 **true**，`meta.public` 显式标注免登录页。
 *   反过来写（默认公开、需要登录的才标）会在新增页面时忘标，
 *   而那个漏标的后果是**未登录也能进**，不是「进不去」—— 更难发现。
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
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { public: true, title: '注册' },
  },

  /* ── 4 个 Tab（共用底部导航）───────────────────────────────────────── */
  {
    path: '/',
    component: () => import('@/layouts/TabLayout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '首页', tab: 'home' },
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/order/OrderListView.vue'),
        meta: { title: '我的订单', tab: 'orders' },
      },
      {
        path: 'packages/mine',
        name: 'my-packages',
        component: () => import('@/views/package/MyPackagesView.vue'),
        meta: { title: '我的套餐', tab: 'packages' },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/profile/ProfileView.vue'),
        meta: { title: '我的', tab: 'profile' },
      },
    ],
  },

  /* ── 子页：全屏，不带 Tabbar ───────────────────────────────────────── */
  {
    path: '/station/:id',
    name: 'station-detail',
    component: () => import('@/views/station/StationDetailView.vue'),
    meta: { title: '选择充电枪' },
  },
  {
    path: '/orders/:id',
    name: 'order-detail',
    component: () => import('@/views/order/OrderDetailView.vue'),
    meta: { title: '订单详情' },
  },
  {
    path: '/packages',
    name: 'available-packages',
    component: () => import('@/views/package/AvailablePackagesView.vue'),
    meta: { title: '购买套餐' },
  },
  {
    path: '/profile/edit',
    name: 'profile-edit',
    component: () => import('@/views/profile/ProfileEditView.vue'),
    meta: { title: '编辑资料' },
  },
  {
    path: '/profile/password',
    name: 'profile-password',
    component: () => import('@/views/profile/PasswordView.vue'),
    meta: { title: '修改密码' },
  },
  {
    path: '/profile/vehicles',
    name: 'vehicles',
    component: () => import('@/views/profile/VehicleListView.vue'),
    meta: { title: '我的车辆' },
  },
  {
    path: '/profile/vehicles/new',
    name: 'vehicle-new',
    component: () => import('@/views/profile/VehicleEditView.vue'),
    meta: { title: '添加车辆' },
  },
  {
    path: '/profile/vehicles/:id/edit',
    name: 'vehicle-edit',
    component: () => import('@/views/profile/VehicleEditView.vue'),
    meta: { title: '编辑车辆' },
  },
  {
    path: '/profile/wallet',
    name: 'wallet',
    component: () => import('@/views/profile/WalletView.vue'),
    meta: { title: '我的钱包' },
  },
  {
    path: '/profile/wallet/transactions',
    name: 'wallet-transactions',
    component: () => import('@/views/profile/TransactionsView.vue'),
    meta: { title: '余额明细' },
  },
  {
    path: '/profile/about',
    name: 'about',
    component: () => import('@/views/profile/AboutView.vue'),
    meta: { title: '关于' },
  },

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
  scrollBehavior(_to, _from, saved) {
    // ★ 返回时恢复原滚动位置：H5 上从「订单列表」划到一半点进详情，
    //   返回后跳回顶部等于让用户重新找位置。
    return saved ?? { top: 0 }
  },
})

router.beforeEach((to) => {
  const session = useSessionStore()

  // ★★ 必须在守卫里恢复，**不能只靠 App.vue** ★★
  //   `main.ts` 的顺序是 `router.isReady().then(() => app.mount('#app'))` ——
  //   也就是**首次导航的守卫跑在 App.vue 之前**。会话的镜像字段只有 `restore()`
  //   会填，App.vue 里那次调用要到挂载时才发生 → 首个受保护页面判定必然为「未登录」，
  //   带着有效会话也被弹回 `/login`（刷新任意页面都中招）。
  //   管理端的守卫本来就有这一句，这里是补齐，两端口径保持一致。
  //   ★ 幂等：`restore()` 内部有 `if (this.profile) return`，每次导航调用没有额外开销。
  session.restore()

  if (to.meta.public) {
    // 已登录还去登录页 → 送回首页，避免「登录成功后又回登录页」的循环观感
    if (session.isLoggedIn && (to.name === 'login' || to.name === 'register')) {
      return { path: '/' }
    }
    return true
  }

  if (!session.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // ★ 进受保护页时**顺带**跑一次禁用探针（30 秒节流，见 stores/session）。
  //   放在守卫里而不是每个页面自己调：漏调一次就等于这个页面永远发现不了禁用。
  session.probeIfStale()

  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  if (title) document.title = `${title} · ChargeOps`
})
