<script setup lang="ts">
/**
 * 车主权益（会员套餐）。
 *
 * ★★ 这一页有三个「读代码看不出来、但会真的出错」的点，全部来自后端契约 ★★
 *
 *   1. **作废会真的打款退款，而且后端没有幂等键。**
 *      `POST /member-package/{id}/void` 把权益上的 `pay_amount` 快照全额退回车主余额，
 *      并写下一条 `PACKAGE_REFUND` 流水。那条流水的 `order_id` 是 NULL，而唯一索引
 *      `uk_txn_order_type` 是 `(order_id, type)` —— **唯一索引不约束 NULL**，
 *      所以数据库层也拦不住第二次。后端的 CAS 只挡并发，挡不住「隔一会儿再点一次」。
 *      → 所以这里必须 `DangerConfirm`（手打退款金额）+ `withActionLock`，缺一不可。
 *
 *   2. **`status` 是缓存，`expireTime` 才是真相。**
 *      权益过期由定时任务落库（`MemberPackageExpireJob`，30 分钟一轮），
 *      所以存在「已经过期、但 `status` 还是 1 生效中」的最长 30 分钟窗口。
 *      → 列表**必须同时显示 `expireTime`**，并在本地时钟越过它时明确指出，
 *        否则运营会照着「生效中」这个标签跟车主解释，而两边看的是同一份数据。
 *      ★ 注意后端**没有**给「永久有效」留 NULL，它写的是哨兵值
 *        `9999-12-31 23:59:59`（NULL 参与任何比较都不成立，永久权益会被
 *        `expire_time > NOW()` 的查询漏掉）。所以「永久」要认这个哨兵。
 *
 *   3. **有一部分权益作废会失败，这是正常结果不是故障。**
 *      退款口径是「这份权益没有被任何一笔非取消订单引用过」——被引用过就整份不退。
 *      → 失败时保持弹窗打开、照显示后端原文，并给出「去看引用它的订单」的动线。
 *        后端那句拒绝里带订单 id（占用中的那种情况），能提取出来就直接给链接。
 *
 * ★ 查询条件是 `memberId`，而 `listMemberPackages` 返回的是**裸数组**
 *   （`Result<List<MemberPackageVO>>`，没有分页信封）—— 所以这一页不用
 *   `usePagedList`（它接的是 `Paged`）。它也会返回已用尽/已过期/已作废的行，
 *   那是**后端刻意**的：滤掉它们等于把「这份权益为什么不能用了」的答案藏起来。
 */

import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  MAX_LEN,
  MEMBER_PACKAGE_STATUS,
  MEMBER_STATUS,
  PACKAGE_TYPE,
  PHONE_SEARCH_MIN_LENGTH,
  canSearchByPhone,
  formatMoney,
  formatServerTime,
  isActionLocked,
  isPositive,
  parseServerTime,
  percentOf,
  withActionLock,
  type Decimal,
  type MemberPackageVO,
  type MemberVO,
} from '@chargeops/shared'
import {
  getMember,
  listMemberPackages,
  searchMembersByPhone,
  voidMemberPackage,
} from '@/api/member'
import { useSubmitLock } from '@/composables/useSubmitLock'
import PagePanel from '@/components/PagePanel.vue'
import FilterBar from '@/components/FilterBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import MoneyText from '@/components/MoneyText.vue'
import DangerConfirm from '@/components/DangerConfirm.vue'
import CoIcon from '@/components/CoIcon.vue'

const route = useRoute()
const router = useRouter()

/* ── 选定车主 ─────────────────────────────────────────────────────────── */

const phoneInput = ref('')
const memberIdInput = ref('')
const resolving = ref(false)
const resolveError = ref<unknown>(null)
/** 前缀命中多位车主时列出来给运营点选 —— 自动选第一个会选错人。 */
const candidates = ref<MemberVO[]>([])
const member = ref<MemberVO | null>(null)

const packages = ref<MemberPackageVO[]>([])
const packagesLoading = ref(false)
const packagesError = ref<unknown>(null)

const dirty = computed(() => phoneInput.value.trim() !== '' || memberIdInput.value.trim() !== '')

/**
 * 手机号够不够长。
 *
 * ★ 只在输入框非空时提示：空着表示「按 id 查」，那是合法状态，
 *   给一句「请输入至少 6 位」会让人以为必填。前缀太短时后端会命中过多车主，
 *   车主侧虽然没有像订单侧那样直接拒绝，但拉回一屏无关的人也足以让人找不到目标。
 */
const phoneTooShort = computed(
  () => phoneInput.value.trim() !== '' && !canSearchByPhone(phoneInput.value),
)

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

function clearResult(): void {
  candidates.value = []
  member.value = null
  packages.value = []
  packagesError.value = null
  resolveError.value = null
}

function pickMember(m: MemberVO): void {
  member.value = m
  candidates.value = []
  void loadPackages()
}

/**
 * 解析「要查谁」，然后加载他的权益。
 *
 * ★ 两个输入框是**刻意分开**的，不做「看起来像手机号就当手机号」的猜测：
 *   11 位手机号和 6 位以上的车主 id 在字面上无法区分，猜错的表现是
 *   「查了另一个人的权益」—— 而这一页上紧接着就是一个会打款的按钮。
 *   两个框都填时以 **id 为准**（它更精确），并在页面上说明。
 */
async function resolveMember(): Promise<void> {
  clearResult()

  const idText = memberIdInput.value.trim()
  if (idText !== '') {
    const id = Number(idText)
    if (!Number.isInteger(id) || id <= 0) {
      resolveError.value = new Error('车主 id 必须是正整数。')
      return
    }
    resolving.value = true
    try {
      const found = await getMember(id)
      // 后端查不到时返回 code:0「车主不存在」，http 层会抛；这里兜住 data 为 null 的情况
      if (!found) {
        resolveError.value = new Error(`没有 id 为 ${id} 的车主。`)
        return
      }
      member.value = found
    } catch (e) {
      resolveError.value = e
      return
    } finally {
      resolving.value = false
    }
    await loadPackages()
    return
  }

  const phone = phoneInput.value.trim()
  if (phone === '') {
    resolveError.value = new Error('请输入车主手机号前缀或车主 id。')
    return
  }
  if (!canSearchByPhone(phone)) {
    resolveError.value = new Error(
      `手机号是前缀匹配，至少需要 ${PHONE_SEARCH_MIN_LENGTH} 位数字（当前是「${phone}」）。`,
    )
    return
  }

  resolving.value = true
  try {
    const list = await searchMembersByPhone(phone)
    if (list.length === 0) {
      resolveError.value = new Error(`没有手机号以「${phone}」开头的车主。`)
      return
    }
    if (list.length === 1) {
      member.value = list[0]
    } else {
      // ★ 不替用户选第一个：这一页的下一步是资金动作
      candidates.value = list
      return
    }
  } catch (e) {
    resolveError.value = e
    return
  } finally {
    resolving.value = false
  }
  await loadPackages()
}

/** 拉某个车主的**全部**权益（后端不过滤状态，这里也不再筛一遍）。 */
async function loadPackages(): Promise<void> {
  const target = member.value
  if (!target) return
  packagesLoading.value = true
  packagesError.value = null
  try {
    packages.value = await listMemberPackages(target.id)
  } catch (e) {
    packagesError.value = e
    // ★ 失败时清空：留着上一次的结果会让人以为「查询成功了，只是结果没变」
    packages.value = []
  } finally {
    packagesLoading.value = false
  }
}

function onSearch(): void {
  void resolveMember()
}

function onReset(): void {
  phoneInput.value = ''
  memberIdInput.value = ''
  clearResult()
}

/* ── 从车主详情页带过来的 `?memberId=` ─────────────────────────────────── */

/**
 * ★ 详情页的「权益」Tab 会带着 `?memberId=` 跳到这里，所以这里必须**读**它。
 *   不读的话那个链接点进来是一个空页面，而用户以为自己点错了。
 *   （反过来：订单列表页不读 query，所以那边跳转时**不能**拼筛选参数 ——
 *     拼了会被静默忽略，比不拼更糟。这里是我自己实现的读取端，所以可以。）
 */
const presetId = (() => {
  const raw = route.query.memberId
  const text = Array.isArray(raw) ? raw[0] : raw
  return typeof text === 'string' ? text.trim() : ''
})()

if (presetId !== '') {
  memberIdInput.value = presetId
  void resolveMember()
}

/* ── 权益行的展示 ─────────────────────────────────────────────────────── */

/** 「永久有效」用的是哨兵值而不是 NULL（见文件头第 2 点）。 */
const PERPETUAL_PREFIX = '9999'

function isPerpetual(t: string | null): boolean {
  return t !== null && t.startsWith(PERPETUAL_PREFIX)
}

/**
 * 本地时钟已经越过 `expireTime`，但状态还是「生效中」。
 *
 * ★ 这是定时任务 30 分钟一轮留下的窗口。**本地判断只用来提示，不用来禁用操作**：
 *   后端作废时只看 `status`，它认为这份权益此刻仍可作废 —— 前端自己拦下来
 *   会变成「后端允许而前端不让点」的假故障。
 */
function isStaleExpired(row: MemberPackageVO): boolean {
  if (row.status !== MEMBER_PACKAGE_STATUS.list[0].code) return false
  if (!row.expireTime || isPerpetual(row.expireTime)) return false
  const at = parseServerTime(row.expireTime)
  return at !== null && at.getTime() < Date.now()
}

function expireText(t: string | null): string {
  if (!t) return '未设置'
  return isPerpetual(t) ? '永久有效' : t
}

/** 额度展示：四种 `packageType` 各自只用一组字段，其余几列后端保证为 NULL。 */
interface Quota {
  /** 0~100；`null` 表示没有「剩余/总量」这个概念（折扣包）→ 不画进度条 */
  percent: number | null
  text: string
  unit: string
}

/**
 * 剩余量比例。
 *
 * ★ `percentOf` 在 `total <= 0` 时返回 0（不是 NaN），而「总量缺失」和
 *   「剩余 0」在进度条上是同一个画面 —— 所以这里先判总量，缺失就不画条，
 *   否则一条缺失数据会被读成「额度用光了」。
 */
function ratioOf(remaining: Decimal, total: Decimal): number | null {
  if (remaining === null || remaining === undefined) return null
  if (total === null || total === undefined || !isPositive(total)) return null
  return percentOf(remaining, total)
}

/** `el-progress` 只接受数字百分比 —— 把可空的那一列在这里收窄，模板里不必写类型断言。 */
function quotaPercent(row: MemberPackageVO): number {
  return quotaOf(row).percent ?? 0
}

function quotaOf(row: MemberPackageVO): Quota {
  switch (row.packageType) {
    case 1:
      return {
        percent: ratioOf(row.remainingKwh, row.totalKwh),
        text: `${formatMoney(row.remainingKwh)} / ${formatMoney(row.totalKwh)}`,
        unit: '度',
      }
    case 2:
      return {
        percent: ratioOf(row.remainingMinutes, row.totalMinutes),
        text: `${row.remainingMinutes ?? '--'} / ${row.totalMinutes ?? '--'}`,
        unit: '分钟',
      }
    case 4:
      return {
        percent: ratioOf(row.remainingTimes, row.totalTimes),
        text: `${row.remainingTimes ?? '--'} / ${row.totalTimes ?? '--'}`,
        unit: '次',
      }
    case 3:
      // ★ 折扣包没有「剩余量」：它的额度是服务费折扣率本身，画进度条是错的
      return { percent: null, text: `服务费折扣 ${formatMoney(row.serviceDiscount)}`, unit: '' }
    default:
      // 后端加了新套餐类型而前端没跟上：露出原始码，别显示成空白
      return { percent: null, text: `未知额度（类型 ${row.packageType}）`, unit: '' }
  }
}

/** 剩余比例的告警阈值：低于 20% 转 warning 色（进度条本身仍按 0~100 铺满）。 */
const LOW_QUOTA_PERCENT = 20

function quotaColor(percent: number): string {
  return percent < LOW_QUOTA_PERCENT ? 'var(--co-warning)' : 'var(--co-primary)'
}

/* ── 作废（会真的退款）────────────────────────────────────────────────── */

const voidOpen = ref(false)
const voidTarget = ref<MemberPackageVO | null>(null)
const voidRemark = ref('')
const { pending: voiding, run: runVoid } = useSubmitLock({ throttleMs: 600 })

/**
 * 上一次失败的说明。**留在弹窗里而不是只弹一下 toast**：
 *   toast 会消失，而「为什么失败、下一步去哪」必须在用户决定要不要重试的那一刻还在。
 */
const voidFailure = ref<string | null>(null)
/** 后端拒绝里带的订单 id（占用中的那种情况）—— 有就直接给一个能点的入口。 */
const voidFailureOrderId = ref<number | null>(null)

/** 退款额**只能**来自权益上的快照，不在请求里，也不允许人改。 */
const refundAmount = computed(() => voidTarget.value?.payAmount ?? null)

/**
 * 手打一遍退款金额。
 *
 * ★ 这里打的是**钱**而不是套餐名：这一页真正会出事的是「点重了 → 退两笔」，
 *   而一个必须打出来的金额会强迫操作人把那串数字读一遍（0.2 秒的点击 vs 3 秒的打字）。
 *   套餐名解决的是「选错行」，那个由弹窗里显示的套餐名 + 状态标签覆盖。
 */
const typedConfirm = computed(() =>
  refundAmount.value === null ? null : formatMoney(refundAmount.value),
)

const remarkInvalid = computed(() => voidRemark.value.trim() === '')

const canSubmitVoid = computed(
  () => voidTarget.value !== null && !remarkInvalid.value && !voiding.value,
)

/**
 * 为什么这份权益不能作废 —— 空串表示可以。
 *
 * ★ 只预判**前端确实知道**的两条（状态、金额快照），它们就是后端 ②和④ 两步：
 *   提前禁用并说明原因，比「点下去才被拒」解释力强。
 *   而「被订单引用过」「被进行中的订单锁着」前端查不到 —— 那两条留给后端拒绝，
 *   由 `voidFailure` 显示原文并给动线。**不要**在这里假装能判断。
 */
function voidBlockedReason(row: MemberPackageVO): string {
  if (row.status !== 1) {
    return `只有「生效中」的权益可以作废，这份当前是「${row.statusLabel}」。`
  }
  if (row.payAmount === null || row.payAmount === undefined) {
    return '这份权益没有实付金额快照（建于本功能上线之前），后端无法确定退款金额，会拒绝并需要人工处理。'
  }
  return ''
}

function askVoid(row: MemberPackageVO): void {
  if (voidBlockedReason(row) !== '') return
  voidTarget.value = row
  voidRemark.value = ''
  voidFailure.value = null
  voidFailureOrderId.value = null
  voidOpen.value = true
}

/**
 * 从后端拒绝里取出订单 id。
 *
 * ★ 只做**结构化**提取（原文是「…占用它的订单 id = 12」），不做中文文案匹配：
 *   抄一遍后端文案就有第二个真源，后端改一个字匹配就静默失效。
 *   提取不到就只显示原文 + 通用动线，那仍然是可用的。
 */
function extractOrderId(message: string): number | null {
  const m = /\bid\s*=\s*(\d+)/i.exec(message)
  return m ? Number(m[1]) : null
}

async function onConfirmVoid(): Promise<void> {
  const row = voidTarget.value
  if (!row || !canSubmitVoid.value) return

  const remark = voidRemark.value.trim()

  // 两层锁管的不是同一件事：
  //   · `run`（SubmitLock）—— 按钮级：这次提交的往返期间不重发
  //   · `withActionLock` —— 业务级：**弹窗关掉再打开**也拦得住（冷却期内同 key 拒绝）
  //                       ★ 它是**抛异常**语义，所以必须由 `run` 包住，再判 `status`
  // 后端没有幂等键，key 里带上 remark 是因为「换个原因再作废一次」在语义上是另一个动作，
  // 而同 id + 同原因 + 冷却期内 = 几乎必然是重复提交。
  const outcome = await runVoid(() =>
    withActionLock(
      `void-package:${row.id}:${remark}`,
      () => voidMemberPackage(row.id, { remark }),
      5000,
    ),
  )

  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    voidFailureOrderId.value = null
    if (isActionLocked(outcome.error)) {
      // 被动作锁拦下时全局 toaster 是静默的，这里必须自己说清楚
      voidFailure.value =
        '这次提交刚被执行过（冷却期内被拦下），大概率是连点或重复提交。请先刷新列表确认这份权益现在的状态，不要重复作废。'
      return
    }
    const message = errorText(outcome.error)
    voidFailure.value = message
    voidFailureOrderId.value = extractOrderId(message)
    return
  }

  // ★ 退款额以**响应里**的 `refundAmount` 为准，不用本地那个快照 ——
  //   它是后端真正打出去的数字，两个不一样的唯一可能是我们看错了行。
  const refunded = outcome.value?.refundAmount ?? row.payAmount
  voidOpen.value = false
  voidFailure.value = null
  ElMessage({
    type: 'success',
    message: `已作废「${row.packageName}」，退款 ¥${formatMoney(refunded)} 已回到车主余额。`,
    duration: 4000,
  })
  await loadPackages()
}

/** 失败后重试之前先把列表刷新到最新 —— 上一次作废有可能其实已经成功了。 */
async function refreshAfterFailure(): Promise<void> {
  voidFailure.value = null
  voidFailureOrderId.value = null
  await loadPackages()
}

const hasRows = computed(() => packages.value.length > 0)
</script>

<template>
  <div class="page">
    <PagePanel
      title="车主权益"
      subtitle="权益的查询条件是车主，所以先选定一位车主。这里会列出他的全部权益（含已用尽 / 已过期 / 已作废），因为「这份权益为什么不能用了」的答案通常就在那几条灰掉的行里。"
      flush
    >
      <FilterBar
        :dirty="dirty"
        :loading="resolving || packagesLoading"
        @search="onSearch"
        @reset="onReset"
      >
        <el-input
          v-model="phoneInput"
          class="is-wide"
          placeholder="车主手机号前缀（至少 6 位）"
          clearable
          @keyup.enter="onSearch"
        />
        <el-input
          v-model="memberIdInput"
          placeholder="或直接填车主 id"
          clearable
          @keyup.enter="onSearch"
        />
        <span v-if="phoneTooShort" class="field-hint">
          手机号是前缀匹配，请至少输入 {{ PHONE_SEARCH_MIN_LENGTH }} 位数字（位数太少会命中过多车主）
        </span>
        <span v-else-if="phoneInput.trim() && memberIdInput.trim()" class="field-hint">
          两个都填了，按车主 id 查
        </span>
      </FilterBar>

      <!-- ★ 两条口径必须常驻，不能只在出错时才出现：
           不知道「状态会晚 30 分钟」的人会照着标签跟车主解释，而数据其实已经过期；
           不知道「有一部分作废会失败」的人会把正常的产品口径当成系统故障。 -->
      <ul class="notes">
        <li>
          <b>作废会真的退款</b>，金额取这份权益的实付快照，只退完全没用过的、全额退。
          被任何一笔非取消订单引用过的权益<b>整份不退</b> ——
          所以有相当一部分作废会失败，那是口径，不是故障（失败时页面会给出下一步）。
        </li>
        <li>
          <b>过期状态最长会晚 30 分钟</b>：「已过期」由定时任务每 30 分钟落库一次，
          在那之前 <code>status</code> 还是「生效中」。请以「有效期」这一列的时间为准。
        </li>
      </ul>

      <!-- 前缀命中多位车主：点选，不替用户猜 -->
      <div v-if="candidates.length > 0" class="candidates">
        <p class="candidates-title">
          这个前缀匹配到 {{ candidates.length }} 位车主，请点选一位（命中更多时只会列出前若干条，多输几位数字可以缩小范围）：
        </p>
        <div class="candidates-list">
          <button
            v-for="m in candidates"
            :key="m.id"
            type="button"
            class="candidate"
            @click="pickMember(m)"
          >
            <span class="c-phone co-num">{{ m.phone }}</span>
            <span class="c-name">{{ m.nickname || '未设置昵称' }}</span>
            <span class="c-id co-num">id {{ m.id }}</span>
            <StatusTag
              :label="m.statusLabel ?? MEMBER_STATUS.label(m.status)"
              :tone="MEMBER_STATUS.tone(m.status)"
              size="sm"
            />
          </button>
        </div>
      </div>

      <div v-else-if="member" class="member-line">
        <span class="who">
          <b>{{ member.nickname || '未设置昵称' }}</b>
          <span class="co-num">{{ member.phone }}</span>
          <span class="sub co-num">id {{ member.id }}</span>
        </span>
        <el-button link type="primary" size="small" @click="router.push(`/member/${member.id}`)">
          车主详情
          <CoIcon name="chevron-right" :size="12" />
        </el-button>
      </div>

      <el-table v-loading="packagesLoading" :data="packages" row-key="id" size="default">
        <el-table-column label="套餐" min-width="200">
          <template #default="{ row }">
            <div class="pkg">
              <span class="pkg-name">{{ row.packageName }}</span>
              <span class="pkg-sub">
                {{ row.packageTypeLabel ?? PACKAGE_TYPE.label(row.packageType) }}
                <!-- ★ 作废原因写进的就是权益备注，它是这份权益「为什么是灰的」的唯一解释 -->
                <template v-if="row.remark">· 备注：{{ row.remark }}</template>
              </span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <div class="state-cell">
              <StatusTag
                :label="row.statusLabel ?? MEMBER_PACKAGE_STATUS.label(row.status)"
                :tone="MEMBER_PACKAGE_STATUS.tone(row.status)"
                size="sm"
              />
              <!-- ★ 状态是缓存、有效期是真相：只有本地时钟越过了 expireTime 才提示，
                   措辞避开「故障」—— 这是定时任务的间隔，不是异常 -->
              <span v-if="isStaleExpired(row)" class="stale">
                已过期，状态待刷新（最长 30 分钟）
              </span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="剩余额度" min-width="180">
          <template #default="{ row }">
            <div class="quota">
              <div class="quota-text co-num">
                {{ quotaOf(row).text }}<i v-if="quotaOf(row).unit">{{ quotaOf(row).unit }}</i>
              </div>
              <el-progress
                v-if="quotaOf(row).percent !== null"
                :percentage="quotaPercent(row)"
                :stroke-width="5"
                :show-text="false"
                :color="quotaColor(quotaPercent(row))"
              />
            </div>
          </template>
        </el-table-column>

        <el-table-column label="有效期" width="210">
          <template #default="{ row }">
            <div class="validity">
              <span class="co-num">
                {{ formatServerTime(row.effectiveFrom) ?? '未设置' }}
                <i>→</i>
                {{ expireText(row.expireTime) }}
              </span>
              <!-- ★ 绝对时间**必须**始终可见（见文件头第 2 点），
                   上面那个「已过期」标记只是把它解读了一遍 -->
              <span v-if="isStaleExpired(row)" class="stale-inline">
                本地已越过到期时间，但后端 status 仍是「生效中」
              </span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="实付金额" width="120" align="right">
          <template #default="{ row }">
            <!-- ★ 为 null 显示 `--` 而不是 `0.00`：它是「不知道」（建于本功能上线之前），
                 而 0 会被读成「赠送的」，两者的处置完全不同 -->
            <MoneyText :value="row.payAmount" size="sm" :grouping="false" />
          </template>
        </el-table-column>

        <el-table-column label="购买时间" width="160">
          <template #default="{ row }">
            <span class="co-num">{{ formatServerTime(row.createTime) ?? '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-tooltip
              :content="voidBlockedReason(row)"
              :disabled="voidBlockedReason(row) === ''"
              placement="top"
            >
              <span>
                <!-- ★ 危险动作用 plain 红而不是实心大红：确认动作已经由弹窗承担，
                     一屏里出现两个实心红会让整页看起来都很危险 -->
                <el-button
                  size="small"
                  type="danger"
                  plain
                  :disabled="voidBlockedReason(row) !== ''"
                  @click="askVoid(row)"
                >
                  作废并退款
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>

        <!-- ★ 三种「没有数据」必须分开：没选车主 / 真的没有 / 加载失败 -->
        <template #empty>
          <EmptyState
            v-if="packagesError"
            kind="error"
            :description="errorText(packagesError)"
            @retry="loadPackages"
          />
          <EmptyState
            v-else-if="resolveError"
            kind="error"
            title="没能定位到车主"
            :description="errorText(resolveError)"
            @retry="onSearch"
          />
          <EmptyState
            v-else-if="!member"
            kind="empty"
            title="先选定一位车主"
            description="权益按车主查询。输入手机号前缀（至少 6 位数字）或车主 id，然后点「查询」。"
          />
          <EmptyState
            v-else-if="!hasRows"
            kind="empty"
            title="这位车主还没有买过套餐"
            description="权益只能由车主在车主端购买产生，管理端不提供新建。"
          />
        </template>
      </el-table>
    </PagePanel>

    <!-- ── 作废（资金动作）────────────────────────────────────────────── -->
    <DangerConfirm
      v-model="voidOpen"
      :title="`作废权益「${voidTarget?.packageName ?? ''}」`"
      action-name="作废"
      confirm-text="确认作废并退款"
      description="这份操作会把实付金额退回车主余额，并写入一条「套餐退款」资金流水。服务器没有幂等键，重复提交会退两次钱，请核对后再提交。"
      :consequences="[
        `退款金额 ¥${refundAmount === null ? '未知' : formatMoney(refundAmount)}（取自权益的实付快照，不由本次操作填写）`,
        '只有完全没用过的权益才退得回来：被任何一笔非取消订单引用过就整份不退。',
        '作废不可撤销：要恢复只能让车主重新购买。',
        '原因会同时写进权益备注与资金流水 —— 那条流水没有订单号，这行字是它唯一的解释。',
      ]"
      :require-typed-value="typedConfirm"
      typed-label="请再输入一次退款金额以确认"
      :loading="voiding"
      @confirm="onConfirmVoid"
    >
      <div class="void-body">
        <div class="void-facts">
          <div class="fact">
            <dt>车主</dt>
            <dd class="co-num">{{ member?.phone ?? '-' }}</dd>
          </div>
          <div class="fact">
            <dt>状态</dt>
            <dd>
              <StatusTag
                :label="voidTarget?.statusLabel ?? '-'"
                :tone="MEMBER_PACKAGE_STATUS.tone(voidTarget?.status)"
                size="sm"
              />
            </dd>
          </div>
          <div class="fact">
            <dt>有效期至</dt>
            <dd class="co-num">{{ expireText(voidTarget?.expireTime ?? null) }}</dd>
          </div>
        </div>

        <label class="remark-label" for="void-remark">
          作废原因（必填，最多 {{ MAX_LEN.voidReason }} 字）
        </label>
        <el-input
          id="void-remark"
          v-model="voidRemark"
          type="textarea"
          :rows="3"
          :maxlength="MAX_LEN.voidReason"
          show-word-limit
          :disabled="voiding"
          placeholder="例：车主投诉该套餐与站点不匹配，全额退回"
        />
        <p v-if="remarkInvalid" class="remark-hint">
          必须填写原因：后端会把它写进权益备注和资金流水，而那条流水没有订单号可反查。
        </p>

        <!-- ★ 失败说明留在弹窗里（toast 会消失），并给出下一步动线。
             后端拒绝的三种主要情况：状态不对 / 被进行中的订单锁着 / 已经被订单用过 ——
             后两种的下一步都是「去看那些订单」。 -->
        <div v-if="voidFailure" class="void-failure">
          <p class="failure-msg">{{ voidFailure }}</p>
          <div class="failure-actions">
            <el-button
              v-if="voidFailureOrderId !== null"
              size="small"
              type="primary"
              @click="router.push(`/order/${voidFailureOrderId}`)"
            >
              查看占用它的订单
            </el-button>
            <el-button size="small" @click="router.push('/order')">
              去订单管理找引用它的订单
            </el-button>
            <el-button size="small" :loading="packagesLoading" @click="refreshAfterFailure">
              刷新权益列表
            </el-button>
          </div>
          <p class="failure-note">
            订单列表按<b>手机号前缀</b>搜索，至少 {{ PHONE_SEARCH_MIN_LENGTH }} 位数字。
            订单侧不带默认时间范围，看历史订单时不必加时间条件。
          </p>
        </div>
      </div>
    </DangerConfirm>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 输入框旁的即时提示：用 warning 而非 danger —— 它说的是「还不能查」，
   不是「你填错了」。红色会让人以为这个手机号有问题。 */
.field-hint {
  font-size: 12px;
  color: var(--co-warning-strong);
  white-space: nowrap;
}

.notes {
  margin: 0;
  padding: 10px 18px 10px 34px;
  border-bottom: 1px solid var(--co-border-light);
  background: var(--co-bg-2);
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--co-text-3);
}

.notes li + li {
  margin-top: 3px;
}

.notes b {
  color: var(--co-text-2);
  font-weight: 600;
}

.notes code {
  padding: 1px 5px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-2);
}

.candidates {
  padding: 12px 18px;
  border-bottom: 1px solid var(--co-border-light);
  background: var(--co-primary-weak);
}

.candidates-title {
  margin: 0 0 8px;
  font-size: 12.5px;
  color: var(--co-primary-strong);
}

.candidates-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.candidate {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-card);
  cursor: pointer;
  font-family: inherit;
  transition: border-color var(--co-duration-fast) var(--co-ease);
}

.candidate:hover {
  border-color: var(--co-primary);
}

.c-phone {
  font-size: 13px;
  color: var(--co-text-1);
}

.c-name {
  font-size: 12.5px;
  color: var(--co-text-2);
}

.c-id {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.member-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--co-border-light);
}

.who {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.who b {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--co-text-1);
}

.sub {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.pkg {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  line-height: 1.4;
}

.pkg-name {
  font-size: 13.5px;
  color: var(--co-text-1);
}

.pkg-sub {
  font-size: 11.5px;
  color: var(--co-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.state-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.stale {
  font-size: 11px;
  line-height: 1.4;
  color: var(--co-warning-strong);
}

.quota {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding-right: 8px;
}

.quota-text {
  font-size: 12.5px;
  color: var(--co-text-1);
}

.quota-text i {
  font-style: normal;
  margin-left: 2px;
  font-size: 11px;
  color: var(--co-text-3);
}

.validity {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: var(--co-text-2);
}

.validity i {
  font-style: normal;
  margin: 0 4px;
  color: var(--co-text-4);
}

.stale-inline {
  font-size: 11px;
  color: var(--co-warning-strong);
}

/* ── 弹窗内容 ───────────────────────────────────────────────────────── */
.void-facts {
  display: flex;
  gap: 24px;
  margin: 14px 0;
  padding: 10px 12px;
  border-radius: var(--co-radius-md);
  background: var(--co-bg-3);
}

.fact {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.fact dt {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.fact dd {
  margin: 0;
  font-size: 13px;
  color: var(--co-text-1);
}

.remark-label {
  display: block;
  margin-bottom: 7px;
  font-size: 12.5px;
  color: var(--co-text-2);
}

.remark-hint {
  margin: 7px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--co-danger);
}

.void-failure {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, var(--co-warning) 28%, transparent);
  border-radius: var(--co-radius-md);
  background: var(--co-warning-weak);
}

.failure-msg {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-warning-strong);
}

.failure-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.failure-note {
  margin: 9px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--co-text-3);
}

.failure-note b {
  color: var(--co-text-2);
  font-weight: 600;
}
</style>
