<script setup lang="ts">
/**
 * 站点计费绑定（站点 ↔ 计费模板）。
 *
 * ★★ 这一页的形态和别的列表页**不一样**，原因是后端没有 `GET /page` ★★
 *   绑定的唯一查询入口是 `GET /by-station/{stationId}`（**裸数组**，不分页），
 *   它返回的就是这个站点的**调价沿革**（按生效时刻倒序，含已停用、已过期的）。
 *   所以页面是「先选站点 → 再展示列表」，而不是一个平铺的全量分页表格。
 *   `usePagedList` 仍然在用，只是把「按站点整体拉取」包进了它的 fetch 里做
 *   **本地分页** —— 为的是复用它的竞态令牌、越界校正和空态三态，
 *   而不是为了分页本身（一个站点的绑定通常个位数）。
 *
 * ★★ `effectiveTo` 是「设了之后抹不掉」的字段 ★★
 *   `UpdateDTO` 的语义是「不传 = 不修改」，所以传 null 不是清空而是「不动」。
 *   表单里因此**没有清空按钮**，编辑态连 date-picker 的 × 也关掉了 ——
 *   留一个点了不起作用的 × 比没有更糟。要回到「长期有效」只能填一个极远的
 *   未来时刻（如 9999-12-31 23:59:59）。见 `shared/domain/updateDiff.ts`。
 *
 * ★ `stationId` / `templateId` 不在 `UpdateDTO` 里：绑定关系不可改，
 *   要换模板只能删了重建。所以编辑弹窗里那两项是**只读展示** ——
 *   做成可编辑的话用户改完提交，后端会静默忽略（`FAIL_ON_UNKNOWN_PROPERTIES`
 *   是关的），而用户以为自己改成功了。
 *
 * ★ 退役一条绑定的推荐做法是 **`status = 0`（停用）而不是删除**：
 *   停用可逆、且保留「这个时期这个站点用的哪套价」这条历史；
 *   删掉之后 `station_tariff` 里那段时期变成空白，事后追查一笔老订单
 *   为什么用了这个价时就没有依据了（订单冻结的是模板 id，不是绑定 id）。
 *
 * ★ 没有「这个模板被哪些站点绑定」的反查接口。本页只能按站点看，
 *   所以「模板是不是被别处用了」这类问题在这一页上是**答不出来**的 ——
 *   不要写出假装知道的话。
 */

import { computed, nextTick, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  diffUpdate,
  formatServerTime,
  labelOf,
  parseServerTime,
  toSelectItems,
  type PageQuery,
  type StationTariffDTO,
  type StationTariffUpdateDTO,
  type StationTariffVO,
  type Tone,
} from '@chargeops/shared'
import CoIcon from '@/components/CoIcon.vue'
import DangerConfirm from '@/components/DangerConfirm.vue'
import EmptyState from '@/components/EmptyState.vue'
import FilterBar from '@/components/FilterBar.vue'
import PagePanel from '@/components/PagePanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import { stationOptions, templateOptions } from '@/api/options'
import {
  createStationTariff,
  deleteStationTariff,
  listStationTariffs,
  updateStationTariff,
} from '@/api/tariff'
import { useOptions } from '@/composables/useOptions'
import { usePagedList } from '@/composables/usePagedList'
import { useSubmitLock } from '@/composables/useSubmitLock'

/** 绑定状态是二值（1 启用 / 0 停用）。理由同模板页：没有共用字典，就地定义一处。 */
const STATUS_OPTIONS = [
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
] as const

/** ★ 不复用 `STATION_STATUS.tone()`：那个字典有第三个值（2 维护中），借来用只是数值巧合。 */
function statusTone(status: number | null | undefined): Tone {
  return status === 1 ? 'success' : 'info'
}

const { options: stationOpts } = useOptions(stationOptions)
// ★ 模板下拉走 `/list-enabled`（裸数组 → `createListOptionLoader`），
//   它**只含启用中的模板**。新建绑定本来也只允许启用中的模板（后端
//   `bind()` 里会拒绝停用模板：「该计费模板已停用，不能用于新建绑定」），
//   所以下拉的范围和后端的约束是一致的，不会出现「选了才被拒」。
const { options: templateOpts, loading: templateLoading } = useOptions(templateOptions)

// ── 列表：按站点的本地分页 ─────────────────────────────────────────────────

const DEFAULT_PAGE_SIZE = 10

/**
 * 这一页的「筛选条件」只有站点，分页那两个键由 `usePagedList` 在发起请求时并进来
 * （它内部是 `{...filters, pageNum, pageSize}`，但类型上不会替我们补，
 * 所以这里显式声明，免得在 fetch 里读 `q.pageNum` 时类型对不上）。
 */
type StationTariffQuery = PageQuery & { stationId?: number }

/**
 * 当前站点的**全部**绑定（本地分页的原始数据）。
 *
 * ★ 必须有这么一份：`rows` 只是当前这一页的切片，而「同一生效时刻是否重复」
 *   （唯一性预检）和「哪条此刻在生效」（高亮）都要看**全量**才成立 ——
 *   只看当前页的话，第 2 页上那条重复的绑定会被放过去，
 *   用户撞到的是数据库层的唯一索引报错。
 */
const allBindings = ref<StationTariffVO[]>([])

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
} = usePagedList<StationTariffVO, StationTariffQuery>({
  fetch: async (q) => {
    const size = q.pageSize ?? DEFAULT_PAGE_SIZE
    const page = q.pageNum ?? 1

    // ★ 没选站点就**一个请求都不发**。绑定是「按站点」的实体，没有
    //   「全部站点的绑定」这种查询 —— 后端也没有这个接口。
    //   返回一个空页而不是抛错：空态由页面按 `filters.stationId` 区分。
    if (q.stationId === undefined || q.stationId === null) {
      allBindings.value = []
      return { records: [], total: 0, pageNum: 1, pageSize: size, hasNext: false }
    }

    const all = await listStationTariffs(q.stationId)
    allBindings.value = all
    const start = (page - 1) * size
    return {
      records: all.slice(start, start + size),
      total: all.length,
      pageNum: page,
      pageSize: size,
      hasNext: start + size < all.length,
    }
  },
  initial: { stationId: undefined },
  pageSize: DEFAULT_PAGE_SIZE,
})

/**
 * 当前选中的站点。
 *
 * ★ 判得这么啰嗦是因为 `el-select` 清空时给的值**不一定是 `undefined`**
 *   （实测是空字符串，见 StationListView 里同样的叮嘱）。而 `'123' === 123`
 *   为 false、`Number('')` 是 0 —— 直接把筛选值当站点 id 用，
 *   「清空站点」会变成「查站点 0」，请求发出去、回来一个空列表，
 *   看起来像「这个站点没有绑定」。
 */
const currentStationId = computed<number | null>(() => {
  const v = filters.stationId
  return typeof v === 'number' && Number.isFinite(v) ? v : null
})

/** 是否已经选了站点。它同时是「重置」按钮的可用态 —— 这一页唯一的筛选条件就是它。 */
const hasStation = computed(() => currentStationId.value !== null)

/** 时间基准。每次加载刷新一次，避免页面开着一整天之后高亮还停在昨天。 */
const nowMs = ref(Date.now())

/** 选中站点的名字（下拉没加载出来时退回 `#id`）。 */
const currentStationLabel = computed(() => labelOf(stationOpts.value, currentStationId.value))

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

function timeText(v: string | null | undefined): string {
  return formatServerTime(v) ?? '-'
}

/**
 * 此刻**真正在生效**的那条绑定。
 *
 * ★ 规则是后端 `StationTariffMapper.resolveTemplateAt` 的镜像：
 *     `status = 1 AND effective_from <= at AND (effective_to IS NULL OR effective_to > at)`
 *     再 `ORDER BY priority DESC, effective_from DESC, id DESC LIMIT 1`。
 *   在半开区间 `[effective_from, effective_to)` 下，「老绑定到 7-1 为止、
 *   新绑定从 7-1 开始」在 7-1 这一刻**恰好命中新的一条**。
 *
 * ★ 为什么要照抄这个裁决顺序：同站同刻可能有多条同时命中（长期有效的 +
 *   促销期的），priority 相同时后端是靠 `effective_from DESC, id DESC`
 *   把结果从「未定义」变成「确定」的。前端若用别的顺序高亮，
 *   会出现「页面说是这条、结算用的是那条」。
 *
 * ★ 时刻基准取浏览器本地时间 —— 与 `formatRelative` 等项目内既有做法一致。
 *   它只影响这一处**展示**（哪个标签高亮），不参与任何计算。
 */
const currentBindingId = computed<number | null>(() => {
  const at = nowMs.value
  const hit = allBindings.value
    .filter((r) => {
      if (r.status !== 1) return false
      const from = parseServerTime(r.effectiveFrom)
      if (!from || from.getTime() > at) return false
      if (!r.effectiveTo) return true
      const to = parseServerTime(r.effectiveTo)
      return to !== null && to.getTime() > at
    })
    .sort((a, b) => {
      if (b.priority !== a.priority) return b.priority - a.priority
      const fa = parseServerTime(a.effectiveFrom)?.getTime() ?? 0
      const fb = parseServerTime(b.effectiveFrom)?.getTime() ?? 0
      return fb - fa
    })
  return hit.length > 0 ? hit[0].id : null
})

async function doSearch(): Promise<void> {
  nowMs.value = Date.now()
  await search()
}

function onStationChange(): void {
  // ★ 站点的选择本身就是查询条件，所以这里**不要求**再点一次「查询」——
  //   先选站点再点按钮，两步里没有任何一步能提供额外信息，纯摩擦。
  //   「查询」按钮保留，因为它对回车/键盘用户是可达的。
  void doSearch()
}

// ── 新建 / 编辑 ───────────────────────────────────────────────────────────

/**
 * 表单模型。
 *
 * ★ 必须是 `type` 而不是 `interface`：`diffUpdate<T extends Record<string, unknown>>`
 *   需要隐式索引签名，而 interface 不获得它（TS 的既有规则）。
 */
type BindForm = {
  stationId: number | null
  templateId: number | null
  effectiveFrom: string
  effectiveTo: string
  priority: number
  status: number
}

function emptyForm(): BindForm {
  return {
    stationId: null,
    templateId: null,
    effectiveFrom: '',
    effectiveTo: '',
    priority: 0,
    status: 1,
  }
}

const dialogOpen = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<BindForm>(emptyForm())
/** 增量提交的基准，必须是**服务端原值**。 */
const originalForm = ref<BindForm>(emptyForm())
const editingId = ref<number | null>(null)
/** 编辑时只读展示用（`UpdateDTO` 里没有这两项）。 */
const editingStationName = ref('')
const editingTemplateText = ref('')

const isEdit = computed(() => editingId.value !== null)

const { pending: submitting, run: runSubmit } = useSubmitLock({ throttleMs: 400 })

function openCreate(): void {
  const stationId = currentStationId.value
  if (stationId === null) return

  editingId.value = null
  const fresh = emptyForm()
  // ★ 站点直接取列表已选中的那个：能走到「新建」按钮就说明站点已经选了，
  //   再让用户在下拉里选第二遍同一个站点是纯粹的重复。
  fresh.stationId = stationId
  Object.assign(form, fresh)
  originalForm.value = { ...fresh }
  editingStationName.value = currentStationLabel.value
  editingTemplateText.value = ''
  dialogOpen.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row: StationTariffVO): void {
  editingId.value = row.id
  // ★ 不额外调 `getStationTariff(id)`：`by-station` 返回的字段已经覆盖了
  //   编辑需要的全部三项（UpdateDTO 里也**只有**这三项）。
  //   这一点和「计费模板」页不同 —— 那边的列表缺 `rules`，所以必须补一次详情。
  const fill: BindForm = {
    stationId: row.stationId,
    templateId: row.templateId,
    effectiveFrom: formatServerTime(row.effectiveFrom) ?? '',
    // ★ `formatServerTime` 而不是把后端原值直接塞进 date-picker：后端的时间字符串
    //   可能是 `2026-07-01T00:00:00`（带 T）这种形状，而 `value-format` 的解析
    //   只认 `YYYY-MM-DD HH:mm:ss`。原样塞进去会得到一个**空的选择框** ——
    //   用户一保存就把它当成「没填」，而它其实是抹不掉的区间端点。
    effectiveTo: row.effectiveTo ? (formatServerTime(row.effectiveTo) ?? '') : '',
    priority: row.priority,
    status: row.status,
  }
  Object.assign(form, fill)
  originalForm.value = { ...fill }
  editingStationName.value = labelOf(stationOpts.value, row.stationId)
  editingTemplateText.value = `${row.templateName ?? `模板 #${row.templateId}`}${
    row.templateCode ? `（${row.templateCode}）` : ''
  }`
  dialogOpen.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

/**
 * 生效时刻的本地查重。
 *
 * ★ 唯一索引 `uk_station_tariff_start` 是 `(station_id, effective_from)` ——
 *   同一站点同一生效时刻只能有一条。后端**不**显式查这个，它由数据库兜底，
 *   所以撞了之后回来的是一句数据库层的报错，对运维毫无可操作性。
 *   而这一页恰好把该站点的全部绑定都拿在手里了，本地就能提前拦住。
 *
 * ★ 它只是**提前提示**，不是权威：手里的列表可能已经过期（别人刚建了一条）。
 *   最终裁决仍是那个唯一索引。
 */
function duplicateStart(start: string): StationTariffVO | undefined {
  if (!start || currentStationId.value === null) return undefined
  const target = parseServerTime(start)?.getTime()
  if (target === undefined) return undefined
  // ★ 查 `allBindings` 而不是 `rows`：重复的那条可能不在当前页上
  return allBindings.value.find((r) => {
    if (r.id === editingId.value) return false
    return parseServerTime(r.effectiveFrom)?.getTime() === target
  })
}

const rules: FormRules = {
  stationId: [{ required: true, message: '请选择站点', trigger: 'change' }],
  templateId: [{ required: true, message: '请选择计费模板', trigger: 'change' }],
  effectiveFrom: [
    { required: true, message: '请选择生效时刻', trigger: 'change' },
    {
      validator: (_rule: unknown, value: string, callback: (err?: Error) => void): void => {
        const dup = duplicateStart(value)
        callback(dup ? new Error(`这个站点的 ${timeText(value)} 已经有一条绑定了`): undefined)
      },
      trigger: 'change',
    },
  ],
  effectiveTo: [
    {
      // ★ 后端 `assertEffectiveRange` 是拿 DTO 的 effectiveTo 和**库里的**
      //   effectiveFrom 比的（编辑态下 effectiveFrom 不可改，所以两者一致）。
      //   相等也不行：半开区间 `[from, to)` 下 `to == from` 是空区间，
      //   永远不生效，而它在列表里和一条正常绑定长得一模一样。
      validator: (_rule: unknown, value: string, callback: (err?: Error) => void): void => {
        if (!value) return callback() // 空 = 长期有效，是最常见的合法情况
        const from = parseServerTime(form.effectiveFrom)
        const to = parseServerTime(value)
        if (!from || !to) return callback()
        callback(
          to.getTime() > from.getTime()
            ? undefined
            : new Error('失效时刻必须晚于生效时刻，否则这条绑定永远不会生效'),
        )
      },
      trigger: 'change',
    },
  ],
}

type SubmitResult = 'created' | 'saved' | 'nochange' | 'failed'

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const outcome = await runSubmit<SubmitResult>(async () => {
    if (editingId.value === null) {
      const stationId = form.stationId
      const templateId = form.templateId
      // 理论上被表单校验挡住了；真漏进来就直接不发请求，别靠 `as` 蒙混过去
      if (stationId === null || templateId === null) return 'failed'

      const dto: StationTariffDTO = {
        stationId,
        templateId,
        // ★ 生效区间是**不可省**的：后端 `assertEffectiveRange(effectiveFrom, ...)`
        //   直接对 `effectiveFrom` 调 `isAfter`，传 null 会变成 NPE → HTTP 500。
        //   （`StationTariffDTO` 里这几个字段是可选的，但那个「可选」不等于可以不填。）
        effectiveFrom: form.effectiveFrom,
        effectiveTo: form.effectiveTo || null,
        priority: form.priority,
        status: form.status,
      }
      await createStationTariff(dto)
      return 'created'
    }

    const { payload, unsettable } = diffUpdate(originalForm.value, form)

    if (unsettable.length > 0) {
      // updateDiff 已经识别出 effectiveTo 是「清不掉」的字段并把它剔掉了
      ElMessage({
        type: 'warning',
        message: '生效结束时刻设置之后无法清空，已保持原值。',
        duration: 5000,
        showClose: true,
      })
    }

    // ★ 显式只挑这三个键，而不是把 `payload` 整个塞进去：
    //   `payload` 的静态类型是 `Partial<BindForm>`（含 stationId/templateId），
    //   虽然 diff 后它们不会出现，但一旦将来往表单里加了字段，
    //   这里就会开始往外发后端根本不看的属性 —— 静默忽略的属性最难查。
    const dto: StationTariffUpdateDTO = {}
    if (payload.effectiveTo !== undefined) dto.effectiveTo = payload.effectiveTo || null
    if (payload.priority !== undefined) dto.priority = payload.priority
    if (payload.status !== undefined) dto.status = payload.status

    if (Object.keys(dto).length === 0) return 'nochange'

    await updateStationTariff(editingId.value, dto)
    return 'saved'
  })

  if (outcome.status === 'skipped') return
  // 失败提示由 api/http.ts 的全局 onError 统一弹出（这里的调用都没有 silent）
  if (outcome.status === 'failed') return

  if (outcome.value === 'nochange') {
    dialogOpen.value = false
    ElMessage({ type: 'info', message: '没有改动，未提交', duration: 1600 })
    return
  }

  dialogOpen.value = false
  ElMessage({
    type: 'success',
    message: outcome.value === 'created' ? '已新增绑定' : '已保存',
    duration: 1800,
  })
  // 列表本身要重拉（新增/改区间都会动到列表内容），时间基准也跟着刷新
  nowMs.value = Date.now()
  await reload()
}

function onDialogClosed(): void {
  formRef.value?.clearValidate()
}

// ── 停用（解绑）/ 删除 ─────────────────────────────────────────────────────

const stopOpen = ref(false)
const deleteOpen = ref(false)
const stopTarget = ref<StationTariffVO | null>(null)
const deleteTarget = ref<StationTariffVO | null>(null)
const { pending: stopping, run: runStop } = useSubmitLock({ throttleMs: 400 })
const { pending: removing, run: runRemove } = useSubmitLock({ throttleMs: 500 })

function askStop(row: StationTariffVO): void {
  stopTarget.value = row
  stopOpen.value = true
}

async function doStop(): Promise<void> {
  const target = stopTarget.value
  if (!target) return

  const outcome = await runStop(async () => {
    await updateStationTariff(target.id, { status: 0 })
    return 'done' as const
  })

  if (outcome.status === 'skipped' || outcome.status === 'failed') return

  stopOpen.value = false
  ElMessage({ type: 'success', message: `已停用「${templateTextOf(target)}」的绑定`, duration: 2200 })
  nowMs.value = Date.now()
  await reload()
}

function askRemove(row: StationTariffVO): void {
  deleteTarget.value = row
  deleteOpen.value = true
}

async function doRemove(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return

  const outcome = await runRemove(async () => {
    await deleteStationTariff(target.id)
    return 'done' as const
  })

  if (outcome.status === 'skipped' || outcome.status === 'failed') return

  deleteOpen.value = false
  ElMessage({ type: 'success', message: '已删除绑定', duration: 2000 })
  nowMs.value = Date.now()
  await reload()
}

/** 模板的展示文案。`templateName` 是可空的（后端 join 不到时），所以要有退路。 */
function templateTextOf(row: StationTariffVO): string {
  return row.templateName ?? `模板 #${row.templateId}`
}

/** 「启用」按钮：把停用的绑定改回启用。可逆且不弹二次确认（理由见 danger 弹窗里那段）。 */
async function doEnable(row: StationTariffVO): Promise<void> {
  const outcome = await runStop(async () => {
    await updateStationTariff(row.id, { status: 1 })
    return 'done' as const
  })
  if (outcome.status !== 'done') return

  ElMessage({ type: 'success', message: '已重新启用这条绑定', duration: 2000 })
  nowMs.value = Date.now()
  await reload()
}
</script>

<template>
  <div class="page">
    <PagePanel
      title="站点计费绑定"
      subtitle="绑定 = 把一套计费模板挂到某个站点上，并约定它从哪一刻起生效。同一时刻有多条生效绑定时，优先级（priority）大的胜出 —— 临时促销价就是靠给促销那条更高的优先级实现的。"
      flush
    >
      <template #actions>
        <el-button type="primary" :disabled="!hasStation" @click="openCreate">
          <CoIcon name="plus" :size="15" />
          新增绑定
        </el-button>
      </template>

      <FilterBar :dirty="hasStation" :loading="loading" @search="doSearch" @reset="reset">
        <el-select
          v-model="filters.stationId"
          placeholder="选择站点（必选）"
          clearable
          filterable
          class="is-wide"
          @change="onStationChange"
        >
          <el-option v-for="o in toSelectItems(stationOpts)" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </FilterBar>

      <!-- ★ 没选站点时**不发请求**：绑定是「按站点」的实体，没有「全部绑定」这种查询。
           这个空态和「这个站点没有绑定」必须分开说 —— 合成一个会让用户
           以为站点已经配好了。 -->
      <div v-if="loaded && currentStationId === null" class="hint-block">
        <EmptyState
          kind="empty"
          title="先选一个站点"
          description="后端没有「全量绑定列表」这个接口 —— 绑定只能按站点查（它返回的就是这个站点的调价沿革）。所以这一页的入口是上面那个站点下拉框。"
        />
      </div>

      <template v-else>
        <el-table v-loading="loading" :data="rows" row-key="id" size="default">
          <el-table-column label="计费模板" min-width="220">
            <template #default="{ row }">
              <div class="cell-template">
                <StatusTag
                  v-if="row.id === currentBindingId"
                  label="当前生效"
                  tone="success"
                  size="sm"
                />
                <span class="tpl-name">{{ templateTextOf(row) }}</span>
                <span v-if="row.templateCode" class="tpl-code co-num">{{ row.templateCode }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="生效开始" width="170">
            <template #default="{ row }">
              <span class="co-num">{{ timeText(row.effectiveFrom) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="生效结束" width="170">
            <template #default="{ row }">
              <!-- ★ 空 = 长期有效，这不是「没填」，而是一个明确的业务状态，
                   所以用标签而不是一个空单元格 —— 空单元格会被读成「数据丢了」。 -->
              <StatusTag v-if="!row.effectiveTo" label="长期有效" tone="info" dashed size="sm" />
              <span v-else class="co-num">{{ timeText(row.effectiveTo) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="优先级" width="90" align="right">
            <template #default="{ row }">
              <span class="co-num">{{ row.priority }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <StatusTag :label="row.statusLabel" :tone="statusTone(row.status)" size="sm" />
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">
              <span class="co-num muted">{{ timeText(row.createTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button
                v-if="row.status === 1"
                link
                type="primary"
                size="small"
                :disabled="stopping"
                @click="askStop(row)"
              >
                停用
              </el-button>
              <el-button
                v-else
                link
                type="primary"
                size="small"
                :disabled="stopping"
                @click="doEnable(row)"
              >
                启用
              </el-button>
              <el-button link type="danger" size="small" @click="askRemove(row)">删除</el-button>
            </template>
          </el-table-column>

          <template #empty>
            <EmptyState v-if="error" kind="error" :description="errorText(error)" @retry="reload" />
            <EmptyState
              v-else-if="loaded"
              kind="empty"
              title="这个站点还没有绑定计费方案"
              description="没有生效的绑定时，该站点不能开始充电：后端会明确拒绝（而不是回退到某个默认价格）—— 一个「找不到就用默认价」的兜底会把配置缺失从立刻可见的失败变成一份错误的账单。"
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
      </template>
    </PagePanel>

    <!-- ── 新增 / 编辑 ─────────────────────────────────────────────────── -->
    <el-dialog
      v-model="dialogOpen"
      :title="isEdit ? '编辑计费绑定' : `新增绑定 · ${editingStationName}`"
      width="640px"
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      @closed="onDialogClosed"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="grid-2">
          <el-form-item label="站点" prop="stationId">
            <!-- ★★ 编辑态只读 ★★ `StationTariffUpdateDTO` 里没有 stationId，
                 改完提交后端会静默忽略（FAIL_ON_UNKNOWN 关闭），用户却以为改成了。 -->
            <el-input v-if="isEdit" :model-value="editingStationName" disabled />
            <el-input v-else :model-value="editingStationName" disabled />
            <p v-if="isEdit" class="field-note">绑定关系不可修改：换站点等于换一条绑定，请删掉重建。</p>
          </el-form-item>
          <el-form-item label="计费模板" prop="templateId">
            <!-- ★ 也只读：`templateId` 同样不在 UpdateDTO 里。
                 换模板的正确做法是「删掉这条 + 新建一条」—— 这样调价历史里
                 会留下两段边界清晰的区间，而不是一段被偷偷改写过来源的区间。 -->
            <el-input v-if="isEdit" :model-value="editingTemplateText" disabled />
            <el-select
              v-else
              v-model="form.templateId"
              placeholder="选择计费模板（只列出启用中的）"
              :loading="templateLoading"
              filterable
            >
              <!-- ★ 这里直接遍历 `templateOpts` 而不是 `toSelectItems(...)`：
                   模板名会重名（「深圳 2026」可能有好几个），而 `hint` 里的编码
                   是唯一能区分它们的东西 —— `toSelectItems` 会把 `hint` 丢掉。 -->
              <el-option
                v-for="o in templateOpts"
                :key="o.value"
                :label="o.hint ? `${o.label}（${o.hint}）` : o.label"
                :value="o.value"
              />
            </el-select>
            <p v-if="isEdit" class="field-note">模板不可修改：要换模板请删掉这条绑定再新建一条。</p>
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="生效时刻" prop="effectiveFrom">
            <!-- ★ `type="datetime"` + `value-format` 直接用后端格式，
                 拿到的就是 `yyyy-MM-dd HH:mm:ss` 字符串 —— 中间不经过 Date，
                 也就没有时区/ISO 的那一堆问题。
                 ★ 编辑态禁用：它是绑定的「身份」之一（唯一索引就用它），
                   后端也不接受修改。两边都禁掉，语义才一致。 -->
            <el-date-picker
              v-model="form.effectiveFrom"
              type="datetime"
              placeholder="从哪一刻起按这套价计费"
              value-format="YYYY-MM-DD HH:mm:ss"
              format="YYYY-MM-DD HH:mm:ss"
              :disabled="submitting || isEdit"
              class="is-wide"
            />
          </el-form-item>
          <el-form-item label="失效时刻（可留空 = 长期有效）" prop="effectiveTo">
            <!-- ★★ 编辑态 `clearable=false` ★★：`effectiveTo` 设过之后**无法清空**
                 （UpdateDTO 里「不传」表示「不修改」）。留一个点了没反应的 ×，
                 比没有 × 更糟。 -->
            <el-date-picker
              v-model="form.effectiveTo"
              type="datetime"
              placeholder="留空表示长期有效"
              value-format="YYYY-MM-DD HH:mm:ss"
              format="YYYY-MM-DD HH:mm:ss"
              :clearable="!isEdit"
              :disabled="submitting"
              class="is-wide"
            />
            <p class="field-note">
              区间是<strong>左闭右开</strong>的 <code>[生效, 失效)</code>：老绑定到 7-1 0 点为止、
              新绑定从同一时刻开始，7-1 0 点这一刻**恰好只命中新的一条**，不会两条同时生效。
              <template v-if="isEdit && originalForm.effectiveTo">
                这个字段<strong>设置后无法清空</strong>：想回到「长期有效」请填一个极远的未来时刻
                （如 9999-12-31 23:59:59）。
              </template>
            </p>
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="优先级" prop="priority">
            <el-input-number v-model="form.priority" :min="0" :max="9999" :disabled="submitting" />
            <p class="field-note">
              同站同刻有多条绑定时<b>大者胜</b>；相等时按生效时刻更晚的、再按先建的输 ——
              那个兜底只是让结果可复现，不代表它是你想要的。临时促销价应当显式给一个更高的优先级。
            </p>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-radio-group v-model="form.status" :disabled="submitting">
              <el-radio-button v-for="o in STATUS_OPTIONS" :key="o.value" :value="o.value">
                {{ o.label }}
              </el-radio-button>
            </el-radio-group>
            <p class="field-note">停用/启用的含义是「这条绑定参不参与解析」—— 停用不影响历史订单。</p>
          </el-form-item>
        </div>

        <ul class="form-notes">
          <li>
            新建时模板必须处于<strong>启用</strong>状态（下拉里已经只列启用的）。停用模板的语义就是
            「不能用于新建绑定」，而这不影响已经绑好它的站点。
          </li>
          <li>
            同一站点的<strong>同一个生效时刻只能有一条绑定</strong>（唯一索引
            <code>uk_station_tariff_start</code>）。后端不显式查它，撞了之后报的是数据库层的错误，
            所以本页会提前拦一次 —— 但那个索引才是最终裁判。
          </li>
          <li>
            生效区间<strong>可以重叠</strong>（长期有效的那条 + 一段促销期）：这不是错误，
            裁决靠优先级。真正要避免的是「两条区间重叠、优先级还相同」—— 那是数据质量问题。
          </li>
          <li>
            站点和模板<strong>建好之后都改不了</strong>：要换只能删掉这条绑定再建一条。
            这样调价历史里留下的是两段边界清晰的区间，而不是一段来源被改写过的区间。
          </li>
          <li>
            留空失效时刻 = 长期有效，这是最常见也最省事的做法：调价的时候再建一条新的绑定，
            把老的那条填上失效时刻。
          </li>
        </ul>
      </el-form>

      <template #footer>
        <el-button :disabled="submitting" @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">
          {{ isEdit ? '保存' : '新增' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ── 停用（解绑）─────────────────────────────────────────────────── -->
    <DangerConfirm
      v-model="stopOpen"
      :title="`停用绑定「${stopTarget ? templateTextOf(stopTarget) : ''}」`"
      description="停用之后，这条绑定不再参与「这个站点此刻该用哪套价」的解析。"
      :consequences="[
        '如果这个站点没有别的生效绑定，停用之后该站点会解析不出计费方案 —— 下单会被后端拒绝（提示「该站点当前没有配置计费方案」），也就是这个站点充不了电。',
        '这是可逆的：列表里的「启用」能改回来。',
        '历史订单不受影响：订单冻结的是模板 id，和这条绑定行无关。',
        '这是常规的「解绑」做法 —— 比删除好在它保留了这段时期的调价记录。',
      ]"
      action-name="停用"
      typed-label="请输入模板名称以确认停用"
      :require-typed-value="stopTarget?.templateName ?? null"
      :loading="stopping"
      @confirm="doStop"
    />

    <!-- ── 删除 ───────────────────────────────────────────────────────── -->
    <DangerConfirm
      v-model="deleteOpen"
      :title="`删除绑定「${deleteTarget ? templateTextOf(deleteTarget) : ''}」`"
      description="硬删除，不可恢复。后端对绑定没有做任何守卫 —— 它会直接删掉。"
      :consequences="[
        '删掉之后，这段时期在「这个站点用过哪套价」的历史里变成空白。事后追查一笔老订单为什么用了这个价时，就没有依据了 —— 订单只冻结了模板 id，指向不了这条被删掉的绑定。',
        '如果它是当前生效的那一条，且站点没有别的生效绑定，站点会立刻解析不出计费方案，表现为「充不了电」。',
        '常规的退役做法是「停用」而不是删除：停用可逆，也保留上面那段历史。',
        '唯一索引释放后，同一站点同一生效时刻可以再建一条新的绑定。',
      ]"
      action-name="删除"
      typed-label="请输入模板编码以确认删除"
      :require-typed-value="deleteTarget?.templateCode ?? null"
      :loading="removing"
      @confirm="doRemove"
    />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 8px;
}

.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

@media (max-width: 720px) {
  .grid-2 {
    grid-template-columns: minmax(0, 1fr);
  }
}

.muted {
  color: var(--co-text-3);
}

/* 未选站点时的占位：要和表格占据同样的视觉高度，否则页面会「跳」一下 */
.hint-block {
  padding: 8px 18px 20px;
}

.cell-template {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.tpl-name {
  font-weight: 500;
  color: var(--co-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tpl-code {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--co-text-4);
}

.field-note {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.field-note code {
  padding: 1px 5px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-2);
  font-variant-numeric: tabular-nums;
}

.field-note strong {
  color: var(--co-text-2);
  font-weight: 600;
}

.form-notes {
  margin: 4px 0 0;
  padding: 12px 14px 12px 30px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-2);
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--co-text-3);
}

.form-notes li + li {
  margin-top: 4px;
}

.form-notes code {
  padding: 1px 5px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-2);
  font-variant-numeric: tabular-nums;
}

.form-notes strong {
  color: var(--co-text-2);
  font-weight: 600;
}
</style>
