<script setup lang="ts">
/**
 * 管理端主布局：深色侧边栏 + 浅色内容区。
 *
 * 「浅色精致风」的几条落点：
 *   · 侧边栏 `#1F2733`（**不是纯黑** —— 纯黑在浅色主题里显得割裂）
 *   · 选中项用「3px 主色左条 + 16% 主色底 + 白字」，而不是整块高亮
 *   · 内容区底色 `#F5F7FA`，卡片纯白 + 1px 描边 + 极弱阴影
 *   · 顶栏与内容区之间**不加分割线**，靠底色差建立层次（加线会显得碎）
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import CoIcon from '@/components/CoIcon.vue'
import type { IconName } from '@/components/icons'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

interface MenuItem {
  path: string
  title: string
  icon: IconName
}

interface MenuGroup {
  title: string
  items: MenuItem[]
}

/**
 * 菜单结构。
 *
 * ★★ 刻意**不按 role 过滤**：★★
 *   已核实后端 `staff.role` / `staff.station_id` **从未被任何代码使用**，
 *   没有行级权限。按角色隐藏菜单会制造「我没权限」的假象，
 *   而实际上换个 URL 就能进 —— 那比全部显示更糟。
 *   详见 web/README.md 的「已知限制」。
 */
const MENU: MenuGroup[] = [
  {
    title: '',
    items: [{ path: '/dashboard', title: '工作台', icon: 'dashboard' }],
  },
  {
    title: '设备',
    items: [
      { path: '/station', title: '站点管理', icon: 'station' },
      { path: '/charger', title: '充电桩', icon: 'charger' },
      { path: '/connector', title: '充电枪', icon: 'connector' },
    ],
  },
  {
    title: '计费',
    items: [
      { path: '/tariff-template', title: '计费模板', icon: 'tariff' },
      { path: '/station-tariff', title: '站点计费绑定', icon: 'link' },
    ],
  },
  {
    title: '套餐',
    items: [
      { path: '/category', title: '套餐分类', icon: 'category' },
      { path: '/package', title: '充电套餐', icon: 'package' },
    ],
  },
  {
    title: '车主',
    items: [
      { path: '/member', title: '车主管理', icon: 'member' },
      { path: '/member-package', title: '车主权益', icon: 'ticket' },
      { path: '/wallet-adjust', title: '钱包调账', icon: 'wallet' },
    ],
  },
  {
    title: '交易',
    items: [{ path: '/order', title: '订单管理', icon: 'order' }],
  },
  // ★ 运维排在最后，和「交易」平级：AI 助手会建工单、工单会引用桩和告警，
  //   它跨的是「设备」和「交易」两块，塞进任何一边都会让人找不到。
  {
    title: '运维',
    items: [
      { path: '/ai-assistant', title: 'AI 运维助手', icon: 'ai' },
      { path: '/maintenance-ticket', title: '运维工单', icon: 'ticket' },
    ],
  },
]

/**
 * 当前高亮的菜单项。
 *
 * ★ 详情页（`/order/:id`、`/member/:id`、`/station/:id`）的高亮不能用
 *   `route.path` —— 它是 `/order/12`，不在菜单里，会导致**侧边栏没有任何项高亮**。
 *   所以路由 meta 里带 `activeMenu` 指回列表页。
 */
const activeMenu = computed(() => {
  const fromMeta = route.meta.activeMenu
  return typeof fromMeta === 'string' ? fromMeta : route.path
})

/** 顶栏的面包屑标题。 */
const pageTitle = computed(() => {
  const t = route.meta.title
  return typeof t === 'string' ? t : ''
})

const collapsed = ref(false)

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    // 用户点了取消 —— 不是错误，不要弹提示
    return
  }
  session.logout()
  void router.replace('/login')
}
</script>

<template>
  <div class="layout" :class="{ 'is-collapsed': collapsed }">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark"><CoIcon name="logo" :size="18" /></span>
        <span v-show="!collapsed" class="brand-text">
          ChargeOps
          <em>充电运营后台</em>
        </span>
      </div>

      <nav class="menu">
        <template v-for="(group, gi) in MENU" :key="gi">
          <div v-if="group.title && !collapsed" class="menu-group-title">{{ group.title }}</div>
          <div v-else-if="group.title" class="menu-group-divider" />

          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="menu-item"
            :class="{ 'is-active': activeMenu === item.path }"
            :title="collapsed ? item.title : undefined"
          >
            <CoIcon :name="item.icon" :size="17" />
            <span v-show="!collapsed" class="menu-text">{{ item.title }}</span>
          </router-link>
        </template>
      </nav>

      <button class="collapse-btn" type="button" @click="collapsed = !collapsed">
        <CoIcon :name="collapsed ? 'chevron-right' : 'chevron-left'" :size="15" />
        <span v-show="!collapsed">收起</span>
      </button>
    </aside>

    <div class="main">
      <header class="topbar">
        <h1 class="page-title">{{ pageTitle }}</h1>
        <div class="topbar-right">
          <router-link to="/profile" class="user">
            <span class="avatar">{{ session.displayName.slice(0, 1) }}</span>
            <span class="user-name">{{ session.displayName }}</span>
          </router-link>
          <button class="icon-btn" type="button" title="退出登录" @click="handleLogout">
            <CoIcon name="logout" :size="16" />
          </button>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <!-- 不加 transition：管理端是高频操作场景，
               每次切页都做动画会让「快速来回切」变得烦躁 -->
          <component :is="Component" />
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
  background: var(--co-bg-page);
}

/* ── 侧边栏 ─────────────────────────────────────────────────────────── */
.sidebar {
  display: flex;
  flex-direction: column;
  width: 220px;
  flex-shrink: 0;
  background: var(--co-sidebar-bg);
  transition: width var(--co-duration) var(--co-ease);
  position: sticky;
  top: 0;
  height: 100vh;
}

.is-collapsed .sidebar {
  width: 64px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px 16px;
  color: #fff;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: 8px;
  /* 品牌标记用主色渐变 —— 整个侧边栏唯一的高饱和色块，
     它承担「眼睛落在哪」的作用 */
  background: linear-gradient(135deg, var(--co-primary) 0%, #4d8bff 100%);
  color: #fff;
}

.brand-text {
  display: flex;
  flex-direction: column;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.2px;
  line-height: 1.2;
  white-space: nowrap;
}

.brand-text em {
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
  color: var(--co-sidebar-text);
  margin-top: 2px;
}

.menu {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 4px 8px 8px;
}

.menu-group-title {
  padding: 14px 8px 6px;
  font-size: 11px;
  letter-spacing: 0.6px;
  color: rgba(169, 180, 196, 0.55);
  white-space: nowrap;
}

.menu-group-divider {
  height: 1px;
  margin: 10px 8px;
  background: var(--co-sidebar-divider);
}

.menu-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  margin-bottom: 2px;
  border-radius: var(--co-radius-md);
  color: var(--co-sidebar-text);
  font-size: 13.5px;
  text-decoration: none;
  white-space: nowrap;
  transition: background var(--co-duration-fast) var(--co-ease),
    color var(--co-duration-fast) var(--co-ease);
}

.menu-item:hover {
  background: var(--co-sidebar-hover-bg);
  color: #fff;
}

.menu-item.is-active {
  background: var(--co-sidebar-active-bg);
  color: var(--co-sidebar-text-active);
  font-weight: 500;
}

/* ★ 选中标记是左侧 3px 主色条，不是整块高亮 ——
   整块纯色块会让侧边栏显得很重，而细条既清楚又轻 */
.menu-item.is-active::before {
  content: '';
  position: absolute;
  left: -8px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 0 3px 3px 0;
  background: var(--co-primary);
}

.is-collapsed .menu-item {
  justify-content: center;
  padding: 10px 0;
}

.collapse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 8px;
  padding: 8px;
  border: none;
  border-radius: var(--co-radius-md);
  background: transparent;
  color: var(--co-sidebar-text);
  font-size: 12px;
  cursor: pointer;
}

.collapse-btn:hover {
  background: var(--co-sidebar-hover-bg);
  color: #fff;
}

/* ── 主区 ───────────────────────────────────────────────────────────── */
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 24px;
  background: var(--co-bg-card);
  border-bottom: 1px solid var(--co-border-light);
  position: sticky;
  top: 0;
  z-index: 10;
}

.page-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--co-text-1);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px 5px 5px;
  border-radius: 999px;
  text-decoration: none;
  color: var(--co-text-2);
  font-size: 13px;
  transition: background var(--co-duration-fast) var(--co-ease);
}

.user:hover {
  background: var(--co-bg-3);
}

.avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-size: 12px;
  font-weight: 600;
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--co-radius-md);
  background: transparent;
  color: var(--co-text-3);
  cursor: pointer;
  transition: background var(--co-duration-fast) var(--co-ease),
    color var(--co-duration-fast) var(--co-ease);
}

.icon-btn:hover {
  background: var(--co-danger-weak);
  color: var(--co-danger);
}

.content {
  flex: 1;
  padding: 20px 24px 32px;
  min-width: 0;
}
</style>
