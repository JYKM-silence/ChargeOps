<script setup lang="ts">
/**
 * 订单管理（列表）。
 *
 * ★★ 这个页面的三个设计前提，每一个都来自后端的具体约束 ★★
 *
 *   1. **默认必须带近 7 天时间范围**
 *      后端注释原话：不带任何条件时是一次**全表 filesort**（排序按
 *      `create_time DESC`，没有 `status` 条件时 `idx_order_status_create`
 *      用不上）。所以「打开页面就裸查」不是慢，是可能直接超时。
 *      范围默认值统一从 `defaultOrderRange()` 取。
 *
 *   2. **手机号是前缀搜索，且命中过多会被后端拒绝**
 *      少于 6 位数字不发请求（`PHONE_SEARCH_MIN_LENGTH`）。
 *      这条不是"优化体验"，是避免必然触发的失败。
 *
 *   3. **列表里的四个动作按钮，可用性一律由 `orderRules` 决定**
 *      后端每个流转都是 `UPDATE ... WHERE id = ? AND status = <来源>`，
 *      来源集合抄在 shared 的 `orderRules.ts` 里。照着它渲染按钮，
 *      就不会出现「点了按钮才被拒绝」。**不要自己写 `status === 2` 这种判断。**
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { invalidateOptionsByTag } from '@chargeops/shared'
import type { AdminOrderVO, Option } from '@chargeops/shared'
import {
  ORDER_STATUS_OPTIONS,
  canDo,
  canSearchByPhone,
  defaultOrderRange,
  formatRelative,
  formatServerTime,
  whyNot,
  DEFAULT_PAGE_SIZE_UI,
} from '@chargeops/shared'
import type { OrderAction } from '@chargeops/shared'
import { pageOrders } from '@/api/order'
import { stationOptions } from '@/api/options'
import { usePagedList } from '@/composables/usePagedList'
import { useOptions } from '@/composables/useOptions'
import { useSubmitLock } from '@/composables/useSubmitLock'
import PagePanel from '@/components/PagePanel.vue'
import FilterBar from '@/components/FilterBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import MoneyText from '@/components/MoneyText.vue'
import CoIcon from '@/components/CoIcon.vue'
import StartOrderDialog from './StartOrderDialog.vue'
import EndOrderDialog from './EndOrderDialog.vue'
import RefundOrderDialog from './RefundOrderDialog.vue'

const router = useRouter()

/**
 * 近 7 天。
 *
 * ★ `defaultOrderRange()` 返回的是 `{ begin, end }`（不是 `{ from, to }`）。
 *   写错字段名在这里的后果特别隐蔽：值是 `undefined`，会被 query 清洗规则
 *   当成「没填」整个丢掉 → **请求真的就没带时间范围** →
 *   正好触发这个默认值本来要避免的那次全表 filesort。
 *   语法上完全合法，typecheck 也能过（`as never` 吞掉了），只有后端会变慢。
 *
 * ★ 每次进页面重新算一次，避免标签页挂过夜后范围是"昨天往前 7 天"。
 */
const range = defaultOrderRange()

const list = usePagedList<AdminOrderVO, Record<string, unknown>>({
  fetch: (query) => pageOrders(query as never),
  initial: {
    orderNo: '',
    status: undefined,
    stationId: undefined,
    memberPhone: '',
    createTimeFrom: range.begin,
    createTimeTo: range.end,
  },
})

const { options: stations } = useOptions<number>(stationOptions)

/** ★ 时间范围单独用 ref 接 `el-date-picker` 的数组形态，提交时再拆成两个字段。 */
const dateRange = ref<[string, string]>([range.begin, range.end])

/**
 * ★ 手机号前缀搜索的门槛。
 *   `canSearchByPhone()` 要求至少 6 位**数字** —— 少于 6 位时后端会因为
 *   命中过多直接返回 code:0，那不是"没搜到"，是"你搜得太宽"。
 */
const phoneReady = computed(() => canSearchByPhone(list.filters.memberPhone as string) || !list.filters.memberPhone)

function onSearch(): void {
  const [from, to] = dateRange.value ?? ['', '']
  list.filters.createTimeFrom = from
  list.filters.createTimeTo = to
  void list.search()
}

function onReset(): void {
  // ★ 「重置」是回到**默认的 7 天**，不是回到"什么条件都没有"——
  //   后者正是那个会超时的裸查询。
  dateRange.value = [range.begin, range.end]
  list.filters.createTimeFrom = range.begin
  list.filters.createTimeTo = range.end
  void list.reset()
}

/* ── 详情跳转 ───────────────────────────────────────────────────────── */

function openDetail(row: AdminOrderVO): void {
  void router.push(`/order/${row.id}`)
}

/* ── 三个动作弹窗（挂载在列表页，操作完原地刷新）──────────────────── */

const startTarget = ref<AdminOrderVO | null>(null)
const endTarget = ref<AdminOrderVO | null>(null)
const refundTarget = ref<AdminOrderVO | null>(null)

/**
 * 管理端能做的四个动作。
 *
 * ★ `markAbnormal` **不在这里** —— 后端**没有对应的 HTTP 端点**，
 *   异常挂账是 `end` / `settle` 内部判定后自己转的。
 *   `orderRules` 里保留那条规则是为了解释「9 是怎么来的」，
 *   不是给按钮用的。这里放进来会做一个永远点了没反应的按钮。
 */
const ADMIN_ACTIONS: readonly {
  action: OrderAction
  label: string
  open: (row: AdminOrderVO) => void
}[] = [
  { action: 'start', label: '启动', open: (row) => (startTarget.value = row) },
  { action: 'end', label: '结束', open: (row) => (endTarget.value = row) },
  { action: 'settle', label: '结算', open: (row) => void doSettle(row) },
  { action: 'refund', label: '退款', open: (row) => (refundTarget.value = row) },
]

/**
 * ★ `settle` 没有请求体，所以不需要弹窗 —— 但仍要二次确认，
 *   因为它是**不可逆的**：一旦从 3 走到 4，金额就定下来了。
 */
const { pending: settling, run: runSettle } = useSubmitLock({ throttleMs: 500 })

async function doSettle(row: AdminOrderVO): Promise<void> {
  const outcome = await runSettle(async () => {
    const { settleOrder } = await import('@/api/order')
    await settleOrder(row.id)
  })

  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') {
    // ★ 结算失败**不是死路**：电价规则有缺口时后端回滚，订单留在 3 可以重试。
    //   所以提示要指向真正的原因，而不是把后端异常原文丢出来。
    ElMessage({
      type: 'warning',
      message: '结算失败，订单仍处于待结算状态。常见原因是该站点的计费模板没有覆盖全天 24 小时，请检查后重试。',
      duration: 6000,
      showClose: true,
    })
    return
  }

  ElMessage({ type: 'success', message: '结算完成，订单已进入待支付' })
  await list.reload()
}

function onActionDone(message: string): void {
  startTarget.value = null
  endTarget.value = null
  refundTarget.value = null
  ElMessage({ type: 'success', message })
  // ★ 写成功后让依赖订单的下拉缓存失效，并原地重拉 —— 不做乐观更新。
  invalidateOptionsByTag('order')
  void list.reload()
}

const hasFilter = computed(
  () =>
    Boolean(list.filters.orderNo) ||
    list.filters.status !== undefined ||
    list.filters.stationId !== undefined ||
    Boolean(list.filters.memberPhone),
)
</script>

<template>
  <div class="page">
    <PagePanel flush>
      <FilterBar :dirty="list.dirty.value" :loading="list.loading.value" @search="onSearch" @reset="onReset">
        <el-input
          v-model="list.filters.orderNo"
          placeholder="订单号"
          clearable
          class="is-wide"
          @keyup.enter="onSearch"
        />

        <el-select v-model="list.filters.status" placeholder="全部状态" clearable>
          <el-option
            v-for="opt in ORDER_STATUS_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>

        <el-select v-model="list.filters.stationId" placeholder="全部站点" clearable filterable>
          <el-option
            v-for="opt in (stations as Option<number>[])"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>

        <el-input
          v-model="list.filters.memberPhone"
          placeholder="车主手机号（≥6 位）"
          clearable
          class="is-wide"
          @keyup.enter="onSearch"
        />

        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="建单开始"
          end-placeholder="建单结束"
          value-format="YYYY-MM-DD HH:mm:ss"
        />
      </FilterBar>

      <!-- ★ 前缀搜索的说明必须**常驻可见**，不能只在出错时出现：
           用户不知道"为什么我输 3 位搜不出来"，会以为是系统坏了。 -->
      <p v-if="!phoneReady" class="phone-hint">
        <CoIcon name="warning" :size="13" />
        手机号是<b>前缀匹配</b>，至少输入 6 位数字才能查询 —— 输得太短会一次命中过多车主，后端会直接拒绝这次查询。
      </p>

      <el-table
        v-loading="list.loading.value"
        :data="list.rows.value"
        row-key="id"
        stripe
        @row-dblclick="openDetail"
      >
        <el-table-column label="订单号" min-width="180">
          <template #default="{ row }">
            <button class="link" type="button" @click="openDetail(row)">{{ row.orderNo }}</button>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <OrderStatusTag :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="车主" min-width="150">
          <template #default="{ row }">
            <div class="member">
              <span class="phone">{{ row.memberPhone || '-' }}</span>
              <span v-if="row.memberNickname" class="nick">{{ row.memberNickname }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="stationName" label="站点" min-width="130" show-overflow-tooltip />

        <el-table-column label="车牌" width="110">
          <template #default="{ row }">{{ row.plateNo || '-' }}</template>
        </el-table-column>

        <el-table-column label="电量" width="100" align="right">
          <template #default="{ row }">
            <span class="co-num">{{ row.totalEnergyKwh ?? '--' }}<i v-if="row.totalEnergyKwh"> kWh</i></span>
          </template>
        </el-table-column>

        <el-table-column label="应付" width="110" align="right">
          <template #default="{ row }">
            <MoneyText :value="row.payAmount" size="sm" />
          </template>
        </el-table-column>

        <!-- ★ 冻结金额单独一列而不是藏在详情里：
             订单终结前这笔钱一直占着车主的余额，而"为什么我的余额少了"
             是客服最常被问的问题。列表上能看到就不用点进去。 -->
        <el-table-column label="冻结中" width="110" align="right">
          <template #default="{ row }">
            <MoneyText v-if="row.frozenAmount && Number(row.frozenAmount) > 0" :value="row.frozenAmount" size="sm" />
            <span v-else class="muted">--</span>
          </template>
        </el-table-column>

        <el-table-column label="建单时间" width="150">
          <template #default="{ row }">
            <el-tooltip :content="formatServerTime(row.createTime)" placement="top">
              <span class="time">{{ formatRelative(row.createTime) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <div class="ops">
              <!-- ★ 四个按钮的可用性全部走 canDo / whyNot，不写 `status === N`。
                   `whyNot` 会把当前状态标签一起说出来（"当前状态「已支付」不能执行此操作"），
                   比一个灰掉的按钮有解释力得多。 -->
              <el-tooltip
                v-for="act in ADMIN_ACTIONS"
                :key="act.action"
                :content="whyNot(act.action, row.status)"
                :disabled="canDo(act.action, row.status, 'admin')"
                placement="top"
              >
                <el-button
                  size="small"
                  :type="act.action === 'refund' ? 'danger' : 'default'"
                  :plain="act.action === 'refund'"
                  :loading="act.action === 'settle' && settling"
                  :disabled="!canDo(act.action, row.status, 'admin')"
                  @click="act.open(row)"
                >
                  {{ act.label }}
                </el-button>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyState
            :kind="list.error.value ? 'error' : hasFilter ? 'filtered' : 'empty'"
            :title="list.error.value ? '加载失败' : undefined"
            @retry="list.error.value ? list.reload() : onReset()"
          />
        </template>
      </el-table>

      <template #footer>
        <el-pagination
          :current-page="list.pageNum.value"
          :page-size="list.pageSize.value"
          :total="list.total.value"
          :page-sizes="[10, 20, 50, DEFAULT_PAGE_SIZE_UI]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="list.goPage"
          @size-change="(n: number) => { list.pageSize.value = n; list.goPage(1) }"
        />
      </template>
    </PagePanel>

    <StartOrderDialog
      v-if="startTarget"
      :order="startTarget"
      @close="startTarget = null"
      @done="onActionDone($event)"
    />
    <EndOrderDialog
      v-if="endTarget"
      :order="endTarget"
      @close="endTarget = null"
      @done="onActionDone($event)"
    />
    <RefundOrderDialog
      v-if="refundTarget"
      :order="refundTarget"
      @close="refundTarget = null"
      @done="onActionDone($event)"
    />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.phone-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 9px 18px;
  border-bottom: 1px solid var(--co-border-light);
  background: var(--co-warning-weak);
  color: var(--co-warning-strong);
  font-size: 12.5px;
  line-height: 1.5;
}

.phone-hint b {
  font-weight: 600;
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--co-primary);
  font-size: 13px;
  /* 订单号是等宽数字，且要能被选中复制（运维要拿去查日志） */
  font-variant-numeric: tabular-nums;
  font-family: inherit;
  cursor: pointer;
  text-align: left;
}

.link:hover {
  text-decoration: underline;
}

.member {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.phone {
  font-variant-numeric: tabular-nums;
  font-size: 13px;
  color: var(--co-text-1);
}

.nick {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.time {
  font-size: 12.5px;
  color: var(--co-text-2);
}

.muted {
  color: var(--co-text-4);
}

.ops {
  display: flex;
  gap: 6px;
}

.co-num {
  font-variant-numeric: tabular-nums;
}

.co-num i {
  font-style: normal;
  margin-left: 2px;
  font-size: 11px;
  color: var(--co-text-3);
}
</style>
