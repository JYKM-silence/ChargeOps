<script setup lang="ts">
/**
 * 车主管理（列表）。
 *
 * ★★ 三件必须做对的事，都不是「体验优化」，是这个页面特有的陷阱 ★★
 *
 *   1. **不显示余额**。`MemberVO` 里没有 `balance`，管理端也没有任何接口能读到它
 *      （唯一的钱包接口 `POST /wallet/{id}/adjust` 只在**响应里**返回调账后的余额）。
 *      放一列永远显示 `--` 的余额，用户只会以为是 bug —— 所以干脆不放。
 *      要看钱请走「钱包调账」，那里也不会显示调账前余额（原因写在那个页面里）。
 *
 *   2. **手机号必须先够 6 位才发请求**。后端 `phone` 是**前缀**匹配（`LIKE '138%'`）：
 *      输一个 `1` 会命中几乎全部车主 —— 订单侧的同类接口会**直接拒绝**并让用户
 *      「多输入几位数字」，而车主侧虽然没拒绝，命中的量也足以让首屏变卡。
 *      所以搜索框在不足 6 位时拦截，并且把「为什么」写在输入框旁边，
 *      而不是等用户点下去才弹一句红字。
 *
 *   3. **禁用不是「立刻踢下线」**。拦截器只验签名、从不查库，member token 有效期
 *      7 天 —— 禁用之后旧 token 最长还能用 7 天（只有资金端点在 Service 层会拒绝）。
 *      确认弹窗里必须说清这个窗口，**不能**承诺「立即登出」。
 *      这也是这里用 `DangerConfirm` 而不是 `ElMessageBox.confirm` 的原因：
 *      它要求手打一遍手机号，而手打一遍是这个项目里唯一有效的手滑防线。
 */

import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  GENDER,
  MEMBER_STATUS,
  MSG,
  PHONE_SEARCH_MIN_LENGTH,
  canSearchByPhone,
  formatRelative,
  formatServerTime,
  msgStartsWith,
  resolveAssetUrl,
  type MemberVO,
} from '@chargeops/shared'
import { pageMembers, updateMemberStatus } from '@/api/member'
import { usePagedList } from '@/composables/usePagedList'
import { useSubmitLock } from '@/composables/useSubmitLock'
import PagePanel from '@/components/PagePanel.vue'
import FilterBar from '@/components/FilterBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import DangerConfirm from '@/components/DangerConfirm.vue'

const router = useRouter()

/**
 * 筛选条件。
 *
 * ★ `status` 用 `''`（而不是 `undefined`）表示「不限」：`el-select` 的 clearable
 *   清空后 emit 的是空串，用 `undefined` 当初始值会让「清空之后 dirty 仍然为真」，
 *   于是「重置」按钮一直亮着 —— 用户会以为筛选没清干净。
 */
interface MemberFilters {
  phone: string
  nickname: string
  status: number | ''
}

const {
  rows,
  total,
  pageNum,
  pageSize,
  loading,
  loaded,
  error,
  filters,
  search,
  reset,
  reload,
  goPage,
  dirty,
} = usePagedList<MemberVO, MemberFilters>({
  initial: { phone: '', nickname: '', status: '' },
  // ★ 这里**不要**再写 `pageNum: q.pageNum, pageSize: q.pageSize`：
  //   `usePagedList` 在调这个回调时已经把它们合并进 `q` 了
  //   （见 composables/usePagedList.ts 的 `load()`），
  //   而 `MemberFilters` 里刻意**没有**这两个键 ——
  //   它们不是「筛选条件」，塞进去会让 `dirty`（重置按钮的亮灭）把它们也算进去。
  //   重复传一遍在运行时无害，但类型上 `q` 没有这两个键，检查过不去。
  fetch: (q) =>
    pageMembers({
      phone: q.phone,
      nickname: q.nickname,
      status: q.status === '' ? undefined : q.status,
    }),
})

/** 有些筛选条件时才可能是「被筛掉」而不是「真的没有」。 */
const hasFilter = computed(
  () => filters.phone.trim() !== '' || filters.nickname.trim() !== '' || filters.status !== '',
)

// ── 手机号前缀的 6 位闸门 ───────────────────────────────────────────────────

/**
 * 输入了但不够 6 位数字。
 *
 * ★ 只在「输入框非空」时提示：空着表示「不按手机号筛」，那是合法状态，
 *   给一句「请输入至少 6 位」会让人以为必填。
 */
const phoneTooShort = computed(
  () => filters.phone.trim() !== '' && !canSearchByPhone(filters.phone),
)

/**
 * 搜索前的守卫。**这是「不足 6 位不发请求」的落点** ——
 * `FilterBar` 的查询按钮没法从外面禁用，所以拦截放在事件里。
 */
function onSearch(): void {
  if (phoneTooShort.value) {
    ElMessage({
      type: 'warning',
      message: `手机号是前缀匹配，请至少输入 ${PHONE_SEARCH_MIN_LENGTH} 位数字再查（当前是 ${filters.phone.trim()}）`,
      duration: 3000,
    })
    return
  }
  void search()
}

function onReset(): void {
  void reset()
}

/**
 * 空态/失败态要显示的原因。
 *
 * ★ 前缀命中过多时后端只给一句「该手机号前缀匹配到的车主过多，请多输入几位数字后重试」，
 *   而这句话没说**怎么做**。这里补上动作指引 —— 匹配的是后端常量而不是抄一遍文案
 *   （抄一遍就会有第二个真源，后端改一个字匹配就静默失效）。
 */
const errorDescription = computed(() => {
  const e = error.value
  if (e instanceof Error && e.message) {
    if (msgStartsWith(e.message, MSG.MEMBER_PREFIX_TOO_BROAD)) {
      return `${e.message}。手机号越短命中越多，再多输几位（或改用昵称）试试。`
    }
    return e.message
  }
  return undefined
})

// ── 展示小工具 ─────────────────────────────────────────────────────────────

/** 头像走 `resolveAssetUrl` 剥掉 origin：后端返回的是绝对 URL，换域名就挂。 */
function avatarOf(row: MemberVO): string {
  return resolveAssetUrl(row.avatar)
}

/** 没有头像时的兜底字：昵称首字，再退到手机号首位。 */
function initialOf(row: MemberVO): string {
  const name = row.nickname?.trim()
  return (name ? name.slice(0, 1) : row.phone.slice(0, 1)) || '?'
}

/** ★ 优先用后端的 `genderLabel`（能拿到 VO 就用它），为 null 时才退回本地字典。 */
function genderText(row: MemberVO): string {
  return row.genderLabel ?? GENDER.label(row.gender)
}

// ── 启用 / 禁用 ─────────────────────────────────────────────────────────────

const target = ref<MemberVO | null>(null)
const confirmOpen = ref(false)
const { pending, run } = useSubmitLock({ throttleMs: 500 })

/**
 * 禁用确认弹窗里的后果清单。
 *
 * ★ 第 2 条是**这个弹窗存在的理由**：不说清 7 天窗口，运营会以为「禁用 = 踢下线」，
 *   于是拿它去处置一个正在被盗用的账号 —— 而盗用者还能继续用 7 天。
 */
const DISABLE_CONSEQUENCES = [
  '该车主的资金类操作会立刻被服务端拒绝：充值、下单、支付、取消订单、查看钱包。',
  '已经登录的设备不会被立刻登出：token 一旦签发就无法撤销，最长 7 天后才失效，这期间旧设备仍能访问非资金接口。',
  '该车主的订单、钱包、车辆数据全部保留，随时可以用「启用」恢复（管理端不提供删除车主）。',
]

const confirmTitle = computed(() =>
  target.value?.status === 1 ? '禁用该车主？' : '启用该车主？',
)

function askToggle(row: MemberVO): void {
  target.value = row
  confirmOpen.value = true
}

/**
 * 开/关车主账号。
 *
 * ★ 这里**没有** `withActionLock`：把状态写成同一个值是幂等的，
 *   重复提交不会产生第二条记录。动作锁是给「没有幂等键的资金动作」用的
 *   （调账、作废权益），滥用在幂等动作上会让那把锁失去含义。
 */
async function applyStatus(next: number): Promise<void> {
  const row = target.value
  if (!row) return

  const outcome = await run(() => updateMemberStatus(row.id, next))
  if (outcome.status === 'skipped') return
  // 失败时**不关弹窗**：提示由全局 onError 弹出，用户留在原地可以直接重试
  if (outcome.status === 'failed') return

  confirmOpen.value = false
  ElMessage({
    type: 'success',
    message: next === 1 ? `已启用「${row.phone}」` : `已禁用「${row.phone}」`,
    duration: 2000,
  })
  // ★ 不做乐观更新：重拉当前页，状态列以后端为准
  await reload()
}

function onConfirmToggle(): void {
  const row = target.value
  if (!row) return
  void applyStatus(row.status === 1 ? 0 : 1)
}

/** 启用是「恢复」而不是危险动作，用轻确认就够 —— 重确认留给禁用。 */
async function askEnable(row: MemberVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `恢复「${row.phone}」的登录与资金操作？`,
      '启用车主',
      { confirmButtonText: '启用', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return // 用户点了取消 —— 不是错误
  }

  target.value = row
  await applyStatus(1)
}

function openDetail(row: MemberVO): void {
  void router.push(`/member/${row.id}`)
}
</script>

<template>
  <div class="page">
    <PagePanel
      title="车主管理"
      subtitle="车主账号由车主自助注册产生（管理端不建号、也不删号）。禁用只停掉这个账号，订单与钱包数据都会保留。"
      flush
    >
      <FilterBar :dirty="dirty" :loading="loading" @search="onSearch" @reset="onReset">
        <el-input
          v-model="filters.phone"
          class="is-wide"
          placeholder="手机号前缀（至少 6 位）"
          clearable
          @keyup.enter="onSearch"
        />
        <!-- ★ 把「为什么还不能查」写在输入框旁边：等用户点下去才弹提示，
             他会以为是系统坏了，而不是自己少输了几位。 -->
        <span v-if="phoneTooShort" class="field-hint">
          请输入至少 {{ PHONE_SEARCH_MIN_LENGTH }} 位手机号（前缀匹配，位数太少会命中过多）
        </span>

        <el-input
          v-model="filters.nickname"
          placeholder="昵称（模糊）"
          clearable
          @keyup.enter="onSearch"
        />
        <el-select v-model="filters.status" placeholder="状态" clearable @change="onSearch">
          <el-option
            v-for="o in MEMBER_STATUS.options()"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
      </FilterBar>

      <el-table v-loading="loading" :data="rows" row-key="id" size="default">
        <el-table-column label="车主" min-width="210">
          <template #default="{ row }">
            <div class="member-cell">
              <img v-if="avatarOf(row)" class="avatar" :src="avatarOf(row)" alt="" />
              <span v-else class="avatar is-fallback">{{ initialOf(row) }}</span>
              <div class="member-text">
                <span class="nickname">{{ row.nickname || '未设置昵称' }}</span>
                <span class="sub co-num">id {{ row.id }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="phone" label="手机号" width="140" class-name="co-num" />

        <el-table-column label="性别" width="80">
          <template #default="{ row }">{{ genderText(row) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusTag
              :label="row.statusLabel ?? MEMBER_STATUS.label(row.status)"
              :tone="MEMBER_STATUS.tone(row.status)"
              size="sm"
            />
          </template>
        </el-table-column>

        <el-table-column label="最后登录" width="150">
          <template #default="{ row }">
            <!-- ★ 相对时间比绝对时间好读，但它是**记账**字段：只在签发 token 时更新，
                 7 天有效期内天天充电的车主，这里可能停在 7 天前。 -->
            <span :title="formatServerTime(row.lastLoginTime) ?? '从未登录'">
              {{ formatRelative(row.lastLoginTime) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="注册时间" width="170">
          <template #default="{ row }">
            <span class="co-num">{{ formatServerTime(row.createTime) ?? '-' }}</span>
          </template>
        </el-table-column>

        <!-- ★★ 这里**没有**「余额」列，这是刻意的 ★★
             `MemberVO` 上没有 balance，管理端也没有任何接口能读它 ——
             放一列永远显示 `--` 的余额只会被当成 bug。要看钱去「钱包调账」。 -->
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 1"
              link
              type="danger"
              size="small"
              @click="askToggle(row)"
            >
              禁用
            </el-button>
            <el-button v-else link type="primary" size="small" @click="askEnable(row)">
              启用
            </el-button>
          </template>
        </el-table-column>

        <!-- ★ 三种「没有数据」必须分开：真的没有 / 被筛掉了 / 加载失败。
             合成一句「暂无数据」会让用户以为数据丢了，然后跑去问运维。 -->
        <template #empty>
          <EmptyState
            v-if="loaded && error"
            kind="error"
            :description="errorDescription"
            @retry="reload"
          />
          <EmptyState
            v-else-if="loaded && hasFilter"
            kind="filtered"
            description="当前筛选条件下没有车主。手机号是前缀匹配，换个昵称或清空条件再试。"
            @retry="onReset"
          />
          <EmptyState
            v-else-if="loaded"
            kind="empty"
            title="还没有车主"
            description="车主账号来自车主端自助注册，管理端不提供新建。可以让车主注册后再回到这里查看。"
          />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          :current-page="pageNum"
          :page-size="pageSize"
          :total="total"
          :pager-count="7"
          layout="total, prev, pager, next"
          background
          @current-change="goPage"
        />
      </div>
    </PagePanel>

    <!-- ── 禁用 / 启用确认 ────────────────────────────────────────────── -->
    <DangerConfirm
      v-model="confirmOpen"
      :title="confirmTitle"
      action-name="禁用"
      :description="
        target?.status === 1
          ? '禁用的是账号，不是删除。订单、钱包、车辆都会保留。'
          : '恢复这个车主的登录与资金操作。'
      "
      :consequences="target?.status === 1 ? DISABLE_CONSEQUENCES : undefined"
      :require-typed-value="target?.status === 1 ? target.phone : null"
      typed-label="请输入该车主的手机号以确认你没有点错人"
      :loading="pending"
      @confirm="onConfirmToggle"
    />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ★ 输入框旁的即时提示：用 warning 而非 danger —— 它表述的是「还不能查」，
   不是「你填错了」。红色会让人以为这个手机号有问题。 */
.field-hint {
  font-size: 12px;
  color: var(--co-warning-strong);
  white-space: nowrap;
}

.member-cell {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.avatar {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: 50%;
  object-fit: cover;
  background: var(--co-bg-3);
}

.avatar.is-fallback {
  display: grid;
  place-items: center;
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-size: 13px;
  font-weight: 600;
}

.member-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.nickname {
  color: var(--co-text-1);
  font-size: 13.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub {
  color: var(--co-text-3);
  font-size: 11.5px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 4px;
}
</style>
