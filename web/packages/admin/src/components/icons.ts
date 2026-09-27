/**
 * 图标路径表。
 *
 * ★ 单独一个模块而不是写在 `CoIcon.vue` 里：`<script setup>` **不允许有
 *   export 语句**，而图标名需要在别处（菜单配置、按钮）作为类型用到。
 *   硬塞在 SFC 里只能用 `defineExpose` 绕，那更别扭。
 *
 * ★ 全部 24×24 视窗、**描边风格**、线宽统一 1.75。
 *   填充与描边混用、线宽不统一，是「看着不像一套图标」的根源。
 *   颜色一律 `currentColor`，跟着文字走。
 */

export type IconName =
  | 'dashboard'
  | 'station'
  | 'charger'
  | 'connector'
  | 'tariff'
  | 'link'
  | 'category'
  | 'package'
  | 'member'
  | 'ticket'
  | 'wallet'
  | 'order'
  | 'user'
  | 'logout'
  | 'search'
  | 'refresh'
  | 'plus'
  | 'edit'
  | 'trash'
  | 'upload'
  | 'warning'
  | 'check'
  | 'close'
  | 'chevron-left'
  | 'chevron-right'
  | 'chevron-down'
  | 'empty'
  | 'battery'
  | 'clock'
  | 'map'
  | 'logo'

export const ICON_PATHS: Record<IconName, string> = {
  dashboard: 'M4 13h6V4H4v9Zm0 7h6v-5H4v5Zm10 0h6V11h-6v9Zm0-16v5h6V4h-6Z',
  station: 'M12 21s7-5.6 7-11a7 7 0 1 0-14 0c0 5.4 7 11 7 11Zm0-8.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z',
  charger: 'M7 21V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v16M5 21h12M9 8h4M11 12v5',
  connector: 'M12 3v5a3 3 0 0 0 3 3 3 3 0 0 0 3-3V3M9 3H7m2 0h4m2 0h2m-4 8v3a4 4 0 0 0 4 4h1a3 3 0 0 0 3-3V9',
  tariff: 'M12 3v18M8 7h5.5a2.5 2.5 0 0 1 0 5H8h6a2.5 2.5 0 0 1 0 5H7',
  link: 'M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1',
  category: 'M4 5h6v6H4V5Zm10 0h6v6h-6V5ZM4 13h6v6H4v-6Zm10 3a3 3 0 1 0 6 0 3 3 0 0 0-6 0Z',
  package: 'M21 8 12 3 3 8v8l9 5 9-5V8ZM3 8l9 5 9-5M12 13v8',
  member: 'M16 20v-1a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v1M9.5 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm11 9v-1a4 4 0 0 0-3-3.9M16 4.1a4 4 0 0 1 0 7.8',
  ticket: 'M4 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v1a2 2 0 0 0 0 4v1a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-1a2 2 0 0 0 0-4V8Zm8-2v12',
  wallet: 'M3 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8Zm0 4h18M16 12h2',
  order: 'M7 4h10a1 1 0 0 1 1 1v15l-3-2-3 2-3-2-3 2V5a1 1 0 0 1 1-1Zm2 4h6M9 12h6',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm7 8v-1a5 5 0 0 0-5-5h-4a5 5 0 0 0-5 5v1',
  logout: 'M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l-5-5 5-5M5 12h11',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Zm5.5-1.5L21 21',
  refresh: 'M20 11a8 8 0 0 0-13.7-5.3L4 8M4 4v4h4m-4 5a8 8 0 0 0 13.7 5.3L20 16m0 4v-4h-4',
  plus: 'M12 5v14M5 12h14',
  edit: 'M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5Z',
  trash: 'M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2m3 0v13a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V7m4 4v6m4-6v6',
  upload: 'M12 16V4m0 0L8 8m4-4 4 4M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3',
  warning: 'M12 3 2 20h20L12 3Zm0 6v5m0 3v.5',
  check: 'M4 12.5 9 17.5 20 6.5',
  close: 'M6 6l12 12M18 6 6 18',
  'chevron-left': 'M15 5 8 12l7 7',
  'chevron-right': 'M9 5l7 7-7 7',
  'chevron-down': 'M5 9l7 7 7-7',
  empty: 'M4 7l8-4 8 4v10l-8 4-8-4V7Zm0 0 8 4 8-4M12 11v10',
  battery: 'M3 8h14v8H3V8Zm17 2h1v4h-1v-4ZM6 11v2m3-2v2m3-2v2',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-13v5l3 2',
  map: 'M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2Zm0 0v14m6-12v14',
  // 品牌标记：一个简化的充电闪电，用于侧边栏顶部与登录页
  logo: 'M13 2 4 14h6l-1 8 9-12h-6l1-8Z',
}
