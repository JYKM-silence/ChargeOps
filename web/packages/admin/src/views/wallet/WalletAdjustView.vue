<script setup lang="ts">
/**
 * 管理员调账（加钱 / 扣钱）。
 *
 * ★★ 这一页有三条必须立住的规矩，全部来自后端事实 ★★
 *
 *   1. **调账前的余额在管理端不可知。**
 *      全项目唯一的钱包接口就是 `POST /api/admin/wallet/{memberId}/adjust`，
 *      它**只在响应里**返回调账后的 `WalletVO`。没有「查车主钱包」的端点，
 *      `MemberVO` 上也没有余额字段。
 *      → 所以这一页**绝不画「当前余额 ¥x」那张卡片**：那个数字只能靠编，
 *        或者靠一次调账去「顺手读出来」—— 后者会真的动钱。
 *        页面常驻说明这句话，`EmptyState` 也不假装在加载一个余额。
 *      ★ 只有一个例外，而且它是**算出来的**而不是读来的：调账成功后
 *        `调账前余额 = 调账后余额 − 本次金额`。这个反推值有并发风险（车主可能同时在充电、
 *        或者另一个运维也在调账），所以显示时必须写明它是反推的（见结果卡）。
 *
 *   2. **没有幂等键，重复提交就是两笔。**
 *      `ADMIN_ADJUST` 流水的 `order_id` 是 NULL，而唯一索引 `uk_txn_order_type`
 *      是 `(order_id, type)` —— **唯一索引不约束 NULL**，数据库层拦不住。
 *      这一页的防重因此比别处更狠：
 *        · `useSubmitLock(1000)` 按钮级（别处是 500~600ms）
 *        · `withActionLock(..., 10000)` 业务级，冷却期**翻倍**
 *        · key 里带上完整 payload（车主 + 带符号金额 + 原因），
 *          因为「同一个车主、同一笔钱、同一个原因」在 10 秒内出现两次，
 *          几乎不可能是故意要调两次 —— 而漏拦一次的代价是双倍金额
 *        · 成功后**立刻清空金额与原因**：不清的话输入框还留着值、焦点还在上面，
 *          一次回车就是第二次调账；而清空之后 key 也跟着变了（remark 空），
 *          动作锁**兜不住**，所以这一步是必须的
 *
 *   3. **后端拦不住 0。**
 *      `@DecimalMin(-100000)/@DecimalMax(100000)` 拦不住落在区间内的 0
 *      （后端 DTO 里自己标注了这一点），0 会在 Service 层被拒绝，但那时已经白跑一次请求。
 *      → 前端必须用 `isAdjustableAmount()` 拦住 0：一条金额为 0 的流水什么都不改变，
 *        却会污染对账。
 */

import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  AMOUNT_SCALE,
  LIMITS,
  MAX_LEN,
  MEMBER_STATUS,
  PHONE_SEARCH_MIN_LENGTH,
  canSearchByPhone,
  formatMoney,
  fromScaled,
  isActionLocked,
  isAdjustableAmount,
  isPartialAmountInput,
  subDecimal,
  toScaled,
  withActionLock,
  type MemberVO,
  type WalletVO,
} from '@chargeops/shared'
import { getMember, searchMembersByPhone } from '@/api/member'
import { adjustWallet } from '@/api/wallet'
import { useSubmitLock } from '@/composables/useSubmitLock'
import PagePanel from '@/components/PagePanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import DangerConfirm from '@/components/DangerConfirm.vue'
import CoIcon from '@/components/CoIcon.vue'

const route = useRoute()
const router = useRouter()

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

/* ── ① 选车主 ─────────────────────────────────────────────────────────── */

const phoneInput = ref('')
const resolving = ref(false)
const resolveError = ref<unknown>(null)
const candidates = ref<MemberVO[]>([])
const member = ref<MemberVO | null>(null)

const phoneTooShort = computed(
  () => phoneInput.value.trim() !== '' && !canSearchByPhone(phoneInput.value),
)

/**
 * 按手机号前缀定位车主。
 *
 * ★ 前缀 ≥6 位才发请求：短前缀会命中一大片人，后端订单侧会**直接拒绝**这类查询，
 *   而这里即便能返回，把 20 个同前缀的人倒进候选区也等于让运营自己去挑错人 ——
 *   下一步是动钱，挑错的代价不是刷新一下。
 */
async function resolveByPhone(): Promise<void> {
  candidates.value = []
  resolveError.value = null
  const phone = phoneInput.value.trim()
  if (phone === '') {
    resolveError.value = new Error('请输入车主手机号前缀。')
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
    // ★ 命中一位才自动选中；多位一律列出来点选 —— 调账不能靠猜
    if (list.length === 1) pickMember(list[0])
    else candidates.value = list
  } catch (e) {
    resolveError.value = e
  } finally {
    resolving.value = false
  }
}

function pickMember(m: MemberVO): void {
  member.value = m
  candidates.value = []
  resolveError.value = null
}

/** 换一位车主：金额/原因一起清掉 —— 它们只对刚才那个人有意义。 */
function changeMember(): void {
  member.value = null
  candidates.value = []
  amount.value = ''
  remark.value = ''
  failure.value = null
}

/**
 * `?memberId=` 预选（车主详情页的「去调账」按钮带过来的）。
 * 不读它的话，那个入口点进来还要重新输一次手机号，等于把入口做废了。
 */
const presetId = (() => {
  const raw = route.query.memberId
  const text = Array.isArray(raw) ? raw[0] : raw
  return typeof text === 'string' ? text.trim() : ''
})()

if (presetId !== '') {
  const id = Number(presetId)
  if (Number.isInteger(id) && id > 0) {
    void (async () => {
      resolving.value = true
      try {
        const found = await getMember(id)
        if (found) member.value = found
        else resolveError.value = new Error(`没有 id 为 ${id} 的车主。`)
      } catch (e) {
        resolveError.value = e
      } finally {
        resolving.value = false
      }
    })()
  } else {
    resolveError.value = new Error('地址里的车主 id 不是正整数。')
  }
}

/* ── ② 金额与原因 ─────────────────────────────────────────────────────── */

/** 方向。★ 符号**由代码拼**，不让用户手输负号（`-` 和全角 `－` 必然混淆）。 */
const direction = ref<'add' | 'deduct'>('add')

const amount = ref('')
const remark = ref('')
/** 输入了第三位小数被丢掉时提示一句 —— 静默丢弃会让人以为数字输进去了。 */
const decimalsRejected = ref(false)

const { pending, run } = useSubmitLock({ throttleMs: 1000 })

const amountInvalid = computed(() => amount.value !== '' && invalidReason.value !== '')

/**
 * 带符号的金额。**这是唯一进入请求的形态**（`WalletAdjustDTO.amount` 带符号）。
 */
const signedAmount = computed(() => {
  const v = amount.value.trim()
  if (v === '') return ''
  return direction.value === 'deduct' ? `-${v}` : v
})

/**
 * 归一到「分」。
 *
 * ★ 金额一律走 `money.ts` 的整数缩放（BigInt），**不做浮点运算**：
 *   输入 `5.5` 送出去 `5.50`，输入 `0.1` 送出去 `0.10` —— 与后端 BigDecimal 对齐。
 *   直接把用户原样输入的字符串递上去虽然也能跑，但 `5.5` 和 `5.50` 会变成两个
 *   不同的动作 key（动作锁按字符串比对），防重就会被绕过。
 */
const normalizedAmount = computed(() => {
  if (signedAmount.value === '') return ''
  return fromScaled(toScaled(signedAmount.value, AMOUNT_SCALE), AMOUNT_SCALE)
})

/**
 * 不合法的原因。**按种类分开说** —— 合成一句「金额不合法」会让人不知道改哪里。
 */
const invalidReason = computed(() => {
  if (amount.value === '') return ''
  const n = Number(amount.value)
  if (!Number.isFinite(n)) return '请输入一个数字。'
  if (n === 0) {
    return '调账金额不能为 0。服务器拦不住 0（0 落在 ±10 万区间内），所以由这里拦住 —— 一条金额为 0 的流水什么都不会变，却会污染对账。'
  }
  if (n > 100000) {
    return `单笔调账上限 ¥${formatMoney(LIMITS.adjustMax)}，请确认是否多打了几个 0。`
  }
  if (!isAdjustableAmount(normalizedAmount.value)) {
    // 走到这里只剩「带了符号但数值本身不合法」这类情况，兜底提示不要留空白
    return '这个金额不在允许范围内。'
  }
  return ''
})

/** 输入过滤：只放行数字与小数点，且小数最多两位（分）。 */
function onAmountInput(v: string): void {
  if (!isPartialAmountInput(v)) return
  const dot = v.indexOf('.')
  if (dot >= 0 && v.length - dot - 1 > AMOUNT_SCALE) {
    decimalsRejected.value = true
    return
  }
  decimalsRejected.value = false
  amount.value = v
}

const remarkInvalid = computed(() => remark.value.trim() === '')

const canSubmit = computed(
  () =>
    member.value !== null &&
    amount.value !== '' &&
    !amountInvalid.value &&
    !remarkInvalid.value &&
    !pending.value,
)

/**
 * 手打一遍的比对值：**带符号**的金额。
 *
 * ★ 用带符号的形态而不是绝对值：这一页唯一会搞反的事情就是方向，
 *   而手打 `-100.00` 时那个负号必须被亲眼看到、亲手打出来。
 *   只打绝对值的话，方向只在弹窗正文里出现过一次，读漏了就调反。
 */
const typedConfirm = computed(() => (normalizedAmount.value === '' ? null : formatMoney(normalizedAmount.value)))

const directionLabel = computed(() => (direction.value === 'add' ? '增加' : '扣减'))

/* ── ③ 提交 ───────────────────────────────────────────────────────────── */

const confirmOpen = ref(false)
/** 失败说明留在弹窗里：toast 会消失，而用户要在「要不要再试一次」的那一刻看到它。 */
const failure = ref<string | null>(null)

/** 上一次调账的结果。`null` = 还没调过，**不要**用零值钱包去占位（那会是编数据）。 */
const result = ref<WalletVO | null>(null)
/** 结果卡要复述「这是谁、调了多少、往哪个方向」，所以把当时的上下文存下来。 */
const resultContext = ref<{ phone: string; signed: string; label: string } | null>(null)

function askConfirm(): void {
  if (!canSubmit.value) return
  failure.value = null
  confirmOpen.value = true
}

async function onConfirm(): Promise<void> {
  const target = member.value
  if (!target || !canSubmit.value) return

  const signed = normalizedAmount.value
  const reason = remark.value.trim()

  // 冷却期 10 秒（别处是 5 秒），key 里带完整 payload。
  // 理由见文件头第 2 点：这一页没有幂等键，而漏拦一次的代价是钱调了两倍。
  const outcome = await run(() =>
    withActionLock(
      `wallet-adjust:${target.id}:${signed}:${reason}`,
      () => adjustWallet(target.id, { amount: signed, remark: reason }),
      10000,
    ),
  )

  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    // 动作锁拦下（说明刚刚提交过一次几乎一样的请求）与业务失败**不是一回事**，
    // 给的下一步也完全不同，不能合成一句。
    failure.value = isActionLocked(outcome.error)
      ? '这次提交刚被执行过（10 秒冷却期内被拦下），大概率是重复提交。请先核对车主的余额是否已经变过（本页看不到调账前的余额，所以对不了账 —— 见下方说明），确认没有重复入账后再重新提交。'
      : errorText(outcome.error)
    return
  }

  const wallet = outcome.value
  confirmOpen.value = false
  failure.value = null
  result.value = wallet
  resultContext.value = { phone: target.phone, signed, label: directionLabel.value }

  // ★★ 立刻清空金额与原因 ★★
  //   不清的话：输入框还留着刚才的值、焦点也在上面，一次回车就是第二次调账；
  //   而且清空后动作锁的 key（含 remark）也跟着变了，锁**兜不住**这一下 ——
  //   所以「清空」不是体验优化，是防重的一部分。
  amount.value = ''
  remark.value = ''
  decimalsRejected.value = false

  ElMessage({
    type: 'success',
    message:
      wallet === null
        ? '调账已提交，但响应里没有带回钱包数据 —— 请刷新车主余额确认。'
        : `已${resultContext.value.label} ¥${formatMoney(signed)}，调账后余额 ¥${formatMoney(wallet.balance)}`,
    duration: 5000,
  })
}

/** 反推的调账前余额 —— 见文件头第 1 点的例外说明。 */
const inferredBefore = computed(() => {
  if (!result.value || !resultContext.value) return null
  return subDecimal(result.value.balance, resultContext.value.signed)
})

function dismissResult(): void {
  result.value = null
  resultContext.value = null
}
</script>

<template>
  <div class="page">
    <PagePanel
      title="钱包调账"
      subtitle="人工把一笔账改平：多收了退给车主、少收了补扣回来。这是全项目唯一一笔「背后没有业务对象」的资金变动 —— 流水上只有类型、金额和这一行原因。"
      flush
    >
      <!-- ★★ 常驻说明。这一页最容易被误解的三件事，必须在动手之前就写出来，
           而不是等用户找不到余额、或者点了两次之后才说。 -->
      <ul class="notes">
        <li>
          <b>本页看不到调账前的余额。</b>后端没有查询车主钱包的接口，唯一能拿到余额的时机是
          <b>调账成功之后</b> —— 响应里带回调账后的余额。所以这里不画「当前余额」卡片：
          那个数字只能是编的，而拿一次真实调账去「读」它是要动钱的。
        </li>
        <li>
          <b>没有幂等键，提交两次就是两笔。</b>同一个车主、同一笔钱、同一个原因，
          在 10 秒内重复提交会被前端拦下；但超过冷却期、或者换个原因再点一次，
          系统里就是两条独立的调账记录。<b>请只用一次点击完成一笔调账。</b>
        </li>
        <li>
          <b>管理端查不到车主流水。</b>要核对这笔调账是否真的入账，目前只能请车主看他的
          余额明细，或者直接查库 —— 这是已知限制（没有这个接口，不是没做）。
        </li>
      </ul>

      <!-- ── 上一次调账的结果：全页唯一出现的余额数字 ─────────────────── -->
      <div v-if="result && resultContext" class="result">
        <div class="result-head">
          <span class="result-title">
            <CoIcon name="check" :size="15" />
            调账成功 · 已{{ resultContext.label }}
          </span>
          <el-button link size="small" @click="dismissResult">知道了</el-button>
        </div>

        <div class="result-balance">
          <span class="label">调账后可用余额</span>
          <span class="value co-num">¥{{ formatMoney(result.balance) }}</span>
        </div>

        <dl class="result-facts">
          <div class="r-fact">
            <dt>车主</dt>
            <dd class="co-num">{{ resultContext.phone }}</dd>
          </div>
          <div class="r-fact">
            <dt>本次金额</dt>
            <dd class="co-num strong" :class="{ 'is-minus': resultContext.signed.startsWith('-') }">
              {{ resultContext.signed.startsWith('-') ? '' : '+' }}¥{{ formatMoney(resultContext.signed) }}
            </dd>
          </div>
          <div class="r-fact">
            <dt>冻结金额</dt>
            <dd class="co-num">¥{{ formatMoney(result.frozenAmount) }}</dd>
          </div>
          <div class="r-fact">
            <dt>累计充值</dt>
            <dd class="co-num">¥{{ formatMoney(result.totalRecharge) }}</dd>
          </div>
          <div class="r-fact is-wide">
            <dt>调账前余额（反推）</dt>
            <dd class="co-num">
              ≈ ¥{{ formatMoney(inferredBefore) }}
              <span class="r-note">
                = 调账后 − 本次金额。<b>这是算出来的，不是读出来的</b>：
                如果车主同时在充电结算、或者另一位运维也在调账，这个值会有偏差。
              </span>
            </dd>
          </div>
        </dl>
      </div>

      <!-- ── ① 选车主 ────────────────────────────────────────────────── -->
      <section class="step">
        <header class="step-head">
          <span class="step-no">1</span>
          <h3>选择车主</h3>
          <span class="step-tip">手机号是前缀匹配，至少 {{ PHONE_SEARCH_MIN_LENGTH }} 位数字</span>
        </header>

        <div v-if="!member" class="picker">
          <div class="picker-row">
            <el-input
              v-model="phoneInput"
              class="phone-input"
              placeholder="车主手机号前缀（至少 6 位）"
              clearable
              :disabled="resolving"
              @keyup.enter="resolveByPhone"
            />
            <el-button type="primary" :loading="resolving" @click="resolveByPhone">
              查找车主
            </el-button>
          </div>

          <p v-if="phoneTooShort" class="hint is-warn">
            还差几位：前缀太短会命中一大片车主，请至少输入 {{ PHONE_SEARCH_MIN_LENGTH }} 位数字。
          </p>

          <!-- 命中多位：列出来点选。自动选第一个等于替用户挑人，而下一步是动钱。 -->
          <div v-if="candidates.length > 0" class="candidates">
            <p class="candidates-title">匹配到 {{ candidates.length }} 位车主，请点选要调账的那一位：</p>
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

          <EmptyState
            v-else-if="resolveError"
            kind="error"
            title="没能定位到车主"
            :description="errorText(resolveError)"
            @retry="resolveByPhone"
          />
        </div>

        <!-- 已选中：把身份信息摊开，并要求在这一步就核对 -->
        <div v-else class="picked">
          <div class="picked-main">
            <span class="picked-name">{{ member.nickname || '未设置昵称' }}</span>
            <span class="co-num picked-phone">{{ member.phone }}</span>
            <span class="co-num picked-id">id {{ member.id }}</span>
            <StatusTag
              :label="member.statusLabel ?? MEMBER_STATUS.label(member.status)"
              :tone="MEMBER_STATUS.tone(member.status)"
              size="sm"
            />
          </div>
          <div class="picked-actions">
            <el-button link type="primary" size="small" @click="router.push(`/member/${member.id}`)">
              车主详情
            </el-button>
            <el-button link size="small" @click="changeMember">换一位</el-button>
          </div>
        </div>
      </section>

      <!-- ── ② 金额与原因 ────────────────────────────────────────────── -->
      <section class="step" :class="{ 'is-locked': !member }">
        <header class="step-head">
          <span class="step-no">2</span>
          <h3>金额与原因</h3>
          <span class="step-tip">
            单笔上限 ±¥{{ formatMoney(LIMITS.adjustMax) }}（{{ formatMoney(LIMITS.adjustMin) }} ~
            {{ formatMoney(LIMITS.adjustMax) }}），不能为 0
          </span>
        </header>

        <div class="form">
          <div class="field">
            <label>方向</label>
            <el-radio-group v-model="direction" :disabled="!member || pending">
              <el-radio-button value="add">增加（退给车主）</el-radio-button>
              <el-radio-button value="deduct">扣减（从车主扣回）</el-radio-button>
            </el-radio-group>
          </div>

          <div class="field">
            <label for="adjust-amount">金额（元）</label>
            <el-input
              id="adjust-amount"
              :model-value="amount"
              placeholder="0.00"
              :disabled="!member || pending"
              @update:model-value="onAmountInput"
            >
              <!-- ★ 前缀跟着方向变：让「这次是加还是扣」在输入的时候也一直在视野里 -->
              <template #prepend>{{ direction === 'add' ? '+' : '-' }} ¥</template>
            </el-input>
            <p v-if="decimalsRejected" class="hint is-warn">
              金额只到分（两位小数），第三位起不会被输入。请四舍五入后再填。
            </p>
            <p v-else-if="invalidReason" class="hint is-error">{{ invalidReason }}</p>
            <p v-else class="hint">
              只需填正数，符号由上面的方向决定 —— 手输负号容易和全角字符混淆。
            </p>
          </div>

          <div class="field">
            <label for="adjust-remark">
              原因（必填，最多 {{ MAX_LEN.adjustReason }} 字）
            </label>
            <el-input
              id="adjust-remark"
              v-model="remark"
              type="textarea"
              :rows="3"
              :maxlength="MAX_LEN.adjustReason"
              show-word-limit
              :disabled="!member || pending"
              placeholder="例：6 月 12 日订单多收了服务费，经客服核实后全额退回"
            />
            <p v-if="remarkInvalid && remark !== ''" class="hint is-error">
              这里不能只有空格。
            </p>
            <p v-else class="hint">
              原因会写进车主钱包的那条「管理员调账」流水。那条流水没有订单号、没有权益、
              没有套餐 —— 半年后审计的人能拿到的全部信息就是这行字，请写清楚是什么事。
            </p>
          </div>

          <div class="submit-row">
            <el-button
              type="danger"
              plain
              size="default"
              :disabled="!canSubmit"
              @click="askConfirm"
            >
              提交调账
            </el-button>
            <span v-if="!member" class="submit-hint">先在上面选定一位车主。</span>
            <span v-else-if="remarkInvalid" class="submit-hint">还差一个原因。</span>
            <span v-else-if="amount === ''" class="submit-hint">还差一个金额。</span>
            <span v-else-if="amountInvalid" class="submit-hint">金额填写有误，见上面的说明。</span>
            <span v-else class="submit-hint is-ready">
              下一步会要求你手工再打一遍带符号的金额（¥{{ typedConfirm }}）。
            </span>
          </div>
        </div>
      </section>
    </PagePanel>

    <!-- ── ③ 二次确认（手打带符号金额）──────────────────────────────── -->
    <DangerConfirm
      v-model="confirmOpen"
      title="确认调账"
      action-name="调账"
      confirm-text="确认调账"
      :loading="pending"
      description="这会立刻改变车主的可用余额，并且写入一条资金流水。服务器没有幂等键：重复提交就是两条独立的调账，请核对车主、方向和金额。"
      :consequences="[
        `车主：${member?.phone ?? '-'}（id ${member?.id ?? '-'}）`,
        `方向与金额：${directionLabel} ¥${normalizedAmount === '' ? '-' : formatMoney(normalizedAmount)}`,
        '调账不可撤销：调错了只能反向再调一次，而两条流水都会留在账上。',
        '本页看不到调账前的余额，所以提交前无法用余额数字做最后一次核对。',
      ]"
      :require-typed-value="typedConfirm"
      typed-label="请再输入一次带符号的调账金额以确认方向与数值"
      @confirm="onConfirm"
    >
      <div class="confirm-body">
        <div class="confirm-line">
          <span class="label">车主</span>
          <span class="co-num">{{ member?.phone ?? '-' }}（id {{ member?.id ?? '-' }}）</span>
        </div>
        <div class="confirm-line">
          <span class="label">本次调账</span>
          <span class="co-num amount" :class="{ 'is-minus': signedAmount.startsWith('-') }">
            {{ directionLabel }} ¥{{ normalizedAmount === '' ? '-' : formatMoney(normalizedAmount) }}
          </span>
        </div>
        <div class="confirm-line">
          <span class="label">原因</span>
          <span class="reason">{{ remark || '-' }}</span>
        </div>

        <!-- 失败说明留在弹窗里，并且把「可能是重复入账」这句话说全 -->
        <div v-if="failure" class="confirm-failure">
          <p class="failure-msg">{{ failure }}</p>
          <p class="failure-note">
            调账前的余额在本页看不到。要确认有没有重复入账，请刷新车主端余额，
            或者直接查库（管理端没有查车主流水的接口）。
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

/* ── 结果卡：全页唯一的余额数字 ───────────────────────────────────────── */
.result {
  margin: 14px 18px 0;
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--co-success) 30%, transparent);
  border-radius: var(--co-radius-lg);
  background: var(--co-success-weak);
}

.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.result-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--co-success-strong);
}

.result-balance {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-top: 10px;
}

.result-balance .label {
  font-size: 12.5px;
  color: var(--co-text-3);
}

.result-balance .value {
  font-size: var(--co-font-money);
  font-weight: 600;
  color: var(--co-text-1);
}

.result-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px 16px;
  margin: 14px 0 0;
  padding-top: 12px;
  border-top: 1px dashed color-mix(in srgb, var(--co-success) 25%, transparent);
}

.r-fact.is-wide {
  grid-column: 1 / -1;
}

.r-fact dt {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.r-fact dd {
  margin: 3px 0 0;
  font-size: 13px;
  color: var(--co-text-1);
}

.r-fact dd.strong {
  font-weight: 600;
}

.r-note {
  display: block;
  margin-top: 3px;
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--co-text-3);
}

/* ── 步骤 ─────────────────────────────────────────────────────────────── */
.step {
  padding: 16px 18px;
  border-bottom: 1px solid var(--co-border-light);
}

.step:last-of-type {
  border-bottom: none;
}

.step.is-locked {
  opacity: 0.55;
}

.step-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
}

.step-no {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-size: 12px;
  font-weight: 600;
}

.step-head h3 {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--co-text-1);
}

.step-tip {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.picker-row {
  display: flex;
  gap: 8px;
  max-width: 520px;
}

.phone-input {
  flex: 1;
}

.candidates {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: var(--co-radius-md);
  background: var(--co-primary-weak);
}

.candidates-title {
  margin: 0 0 8px;
  font-size: 12.5px;
  color: var(--co-primary-strong);
}

.candidate {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 7px 10px;
  border: 1px solid transparent;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-card);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--co-duration-fast) var(--co-ease);
}

.candidate + .candidate {
  margin-top: 6px;
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
  margin-right: auto;
  font-size: 11.5px;
  color: var(--co-text-3);
}

.picked {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 11px 14px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-2);
}

.picked-main {
  display: flex;
  align-items: center;
  gap: 10px;
}

.picked-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--co-text-1);
}

.picked-phone {
  font-size: 13px;
  color: var(--co-text-2);
}

.picked-id {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.picked-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 18px;
  max-width: 640px;
}

.field label {
  display: block;
  margin-bottom: 7px;
  font-size: 12.5px;
  color: var(--co-text-2);
}

.hint {
  margin: 7px 0 0;
  font-size: 12px;
  line-height: 1.65;
  color: var(--co-text-3);
}

.hint.is-warn {
  color: var(--co-warning-strong);
}

.hint.is-error {
  color: var(--co-danger);
}

.submit-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 2px;
}

.submit-hint {
  font-size: 12px;
  color: var(--co-text-3);
}

.submit-hint.is-ready {
  color: var(--co-text-2);
}

/* ── 弹窗内容 ─────────────────────────────────────────────────────────── */
.confirm-body {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: var(--co-radius-md);
  background: var(--co-bg-3);
}

.confirm-line {
  display: flex;
  gap: 10px;
  font-size: 12.5px;
  line-height: 1.8;
}

.confirm-line .label {
  flex-shrink: 0;
  width: 68px;
  color: var(--co-text-3);
}

.confirm-line .amount {
  font-weight: 600;
  color: var(--co-danger-strong);
}

.confirm-line .amount.is-minus {
  color: var(--co-danger);
}

.confirm-line .reason {
  color: var(--co-text-1);
  word-break: break-word;
}

.confirm-failure {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--co-border);
}

.failure-msg {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-danger-strong);
}

.failure-note {
  margin: 8px 0 0;
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--co-text-3);
}

.co-num {
  font-variant-numeric: tabular-nums;
}
</style>
