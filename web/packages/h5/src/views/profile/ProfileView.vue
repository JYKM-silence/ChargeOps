<script setup lang="ts">
/**
 * 「我的」Tab。
 *
 * ★★ 为什么不按角色隐藏菜单 ★★
 *   后端没有行级权限 —— 鉴权拦截器只验 JWT 签名，所有登录车主能调的接口完全一样。
 *   前端按「角色」藏入口，只会制造「有权限却看不到」的假象；而且一旦后端
 *   真的加了权限，这份藏起来的规则不会有任何东西提醒它过期（它不报错，
 *   只是某天开始少显示一项）。→ 菜单全量展示，能不能用由后端说了算，
 *   拒绝时把后端文案原样显示出来。
 *
 * ★ 头像/昵称走 `session.profile` 而**不是**这里再拉一次 `/me`：
 *   路由守卫里的探针已经拉过了（30 秒节流），再拉一次既是重复请求，
 *   两次响应还可能不一致（第二次比第一次旧）。代价是探针**软失败**时
 *   `profile` 只有 localStorage 里的占位资料（id + 昵称，没有头像），
 *   所以下面全部用 `?.` 兜底，而不是假设它一定有值。
 *
 * ★ 钱包是**另一条数据源**，必须单独拉：`/me` 不返回余额，而「我的」页
 *   首屏就要显示它 —— 这就是这个页面唯一的请求。
 */

import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog } from 'vant'
import { formatMoney, type WalletVO } from '@chargeops/shared'
import { getWallet } from '@/api/wallet'
import { useSessionStore } from '@/stores/session'
import { useAsyncData } from '@/composables/useAsyncData'
import MoneyText from '@/components/MoneyText.vue'

const router = useRouter()
const session = useSessionStore()

const { data: wallet, failed: walletFailed, reload: reloadWallet } = useAsyncData<WalletVO>(getWallet)

/** 头像地址。空串/null 都表示「没设头像」（后端两种都可能给）。 */
const avatar = computed(() => session.avatar)

/** 没有头像时用昵称首字母兜底 —— 一个空白圆比一个字母更让人以为「加载挂了」。 */
const avatarLetter = computed(() => session.displayName.slice(0, 1))

/**
 * 冻结金额为 0 时不显示这一行。
 *
 * ★ 用字符串比较而不是 `Number(...) > 0`：金额是 `BigDecimal` 的字符串形态，
 *   判「是不是 0」要走 `formatMoney` 归一化后再比，避免 `"0.0000"` 被当成非零。
 */
const frozenText = computed(() => formatMoney(wallet.value?.frozenAmount))
const showFrozen = computed(() => frozenText.value !== '0.00')

async function onLogout(): Promise<void> {
  try {
    await showConfirmDialog({
      title: '退出登录',
      message: '退出后需要重新输入手机号和密码。',
      confirmButtonText: '退出',
      cancelButtonText: '取消',
    })
  } catch {
    // ★ showConfirmDialog 在「取消」时是 **reject**，不是 resolve(false)。
    //   不接住这个 rejection，控制台会多一条未捕获错误，而「取消」
    //   是一个完全正常的操作，不该产生任何错误痕迹。
    return
  }

  await session.logout()
  // ★ `replace` 而不是 `push`：退出后不该还能用浏览器返回键回到「我的」，
  //   那个页面会立刻因为未登录被守卫弹回登录页 —— 观感上像是「退出失败」。
  await router.replace('/login')
}

function go(name: string): void {
  void router.push({ name })
}
</script>

<template>
  <div class="page page-with-tabbar">
    <!-- ── 头部：头像 / 昵称 / 手机号 ────────────────────────────────── -->
    <header class="hero">
      <div class="avatar">
        <img v-if="avatar" :src="avatar" alt="头像" />
        <span v-else class="avatar-fallback">{{ avatarLetter }}</span>
      </div>
      <div class="who">
        <p class="name">{{ session.displayName }}</p>
        <p class="phone co-num">{{ session.profile?.phone || '—' }}</p>
      </div>
      <button type="button" class="edit-entry" @click="go('profile-edit')">编辑资料</button>
    </header>

    <!-- ── 资产区：余额 + 冻结 ─────────────────────────────────────── -->
    <section class="assets" @click="go('wallet')">
      <div class="asset-main">
        <p class="asset-label">账户余额（元）</p>
        <MoneyText :value="wallet?.balance" size="xl" strong />
        <p v-if="walletFailed" class="asset-retry" @click.stop="reloadWallet()">
          余额加载失败，点击重试
        </p>
      </div>
      <div v-if="showFrozen" class="asset-side">
        <p class="asset-label">冻结中</p>
        <!-- ★ 冻结要显示但别做得和余额一样重：它是「正在被订单占着」的钱，
             不是「可以花」的钱，两者等大会让人以为余额比实际多。 -->
        <span class="frozen co-money">¥{{ frozenText }}</span>
      </div>
      <van-icon name="arrow" class="asset-arrow" />
    </section>

    <!-- ── 功能列表 ─────────────────────────────────────────────────── -->
    <van-cell-group inset class="menu">
      <van-cell title="我的车辆" icon="friends-o" is-link @click="go('vehicles')" />
      <van-cell title="余额明细" icon="balance-list-o" is-link @click="go('wallet-transactions')" />
      <van-cell title="修改密码" icon="lock" is-link @click="go('profile-password')" />
      <van-cell title="编辑资料" icon="edit" is-link @click="go('profile-edit')" />
      <van-cell title="关于" icon="info-o" is-link @click="go('about')" />
    </van-cell-group>

    <div class="danger-zone">
      <!-- ★ 危险动作用 `plain` 描边红而不是实心大红：实心红在这一屏里
           会变成视觉焦点，而「退出登录」不是这一页要推荐用户做的事。 -->
      <van-button block plain type="danger" class="logout" @click="onLogout">
        退出登录
      </van-button>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--co-bg-page);
}

/* ── 头部 ─────────────────────────────────────────────────────────── */
.hero {
  display: flex;
  align-items: center;
  gap: var(--co-gap-md);
  padding: 28px var(--co-gap-lg) 20px;
  background: var(--co-bg-card);
  border-bottom: 1px solid var(--co-border-light);
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  overflow: hidden;
  border-radius: 50%;
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-size: 22px;
  font-weight: 600;
  flex: none;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.who {
  min-width: 0;
  flex: 1;
}

.name {
  margin: 0 0 4px;
  font-size: var(--co-font-lg);
  font-weight: 600;
  color: var(--co-text-1);
}

.phone {
  margin: 0;
  font-size: var(--co-font-sm);
  color: var(--co-text-3);
}

.edit-entry {
  flex: none;
  padding: 6px 12px;
  border: 1px solid var(--co-border);
  border-radius: 999px;
  background: var(--co-bg-2);
  color: var(--co-text-2);
  font-size: var(--co-font-xs);
  font-family: inherit;
  cursor: pointer;
  transition: background var(--co-duration-fast) var(--co-ease);
}

/* ★ 触摸反馈：`hover` 在手机上要么不触发、要么触发后一直粘住，
   所以状态变化一律挂在 `:active` 上。 */
.edit-entry:active {
  background: var(--co-bg-3);
}

/* ── 资产区 ───────────────────────────────────────────────────────── */
.assets {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--co-gap-lg);
  margin: var(--co-gap-md) 0 0;
  padding: var(--co-gap-lg) 32px var(--co-gap-lg) var(--co-gap-lg);
  background: var(--co-bg-card);
  border-top: 1px solid var(--co-border-light);
  border-bottom: 1px solid var(--co-border-light);
  cursor: pointer;
}

.assets:active {
  background: var(--co-bg-2);
}

.asset-main {
  flex: 1;
  min-width: 0;
}

.asset-label {
  margin: 0 0 6px;
  font-size: var(--co-font-xs);
  color: var(--co-text-3);
}

.asset-retry {
  margin: 4px 0 0;
  font-size: var(--co-font-xs);
  color: var(--co-warning-strong);
}

.asset-side {
  flex: none;
  text-align: right;
}

.frozen {
  font-size: var(--co-font-md);
  /* ★ 冻结用次要文字色，不用状态色：「扣钱」的语义已经很重了，
     再给个红色会让用户以为出事了。 */
  color: var(--co-text-2);
}

.asset-arrow {
  position: absolute;
  right: var(--co-gap-lg);
  color: var(--co-text-4);
}

/* ── 菜单 ─────────────────────────────────────────────────────────── */
.menu {
  margin-top: var(--co-gap-md);
}

.danger-zone {
  padding: var(--co-gap-xl) var(--co-gap-lg) 0;
}

.logout {
  font-family: inherit;
}
</style>
