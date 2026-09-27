<script setup lang="ts">
/**
 * 计费模板管理（列表 + 规则编辑器 + 「模板不可变」的引导）。
 *
 * ★★ 四个读代码看不出来的坑，全在这一页里 ★★
 *
 *   1. **列表不含规则**：`GET /page` 的 `rules` 恒为 `null`（后端有意为之 ——
 *      一页 10 个模板就是 80 条规则，而列表只显示模板名）。所以
 *        · 列表里**不能**有「规则数」这一列，它永远是空的；
 *        · 「编辑」必须再 `getTariffTemplate(id)` 拿一次详情，那是**第二次请求**，
 *          所以那个按钮上要挂 loading，否则用户以为自己没点上。
 *
 *   2. **只要 PUT 里带了非空 `rules`，就会撞「模板不可变」守卫** —— 哪怕规则
 *      一个字都没改。后端 `TariffTemplateServiceImpl.saveRules` 是**先查
 *      `station_tariff` 的引用数、再比对内容**的，没有「内容相同就跳过」这一步。
 *      于是「只改个名字」的编辑一旦顺手带上原样的规则，一个已被绑定的模板
 *      连改名都会被拒绝。`rulesChanged` 就是为这件事存在的：没改就**不带** rules。
 *
 *   3. **空 `rules` 数组 = 不修改，不是清空**（`TariffTemplateUpdateDTO.rules`
 *      上刻意没有 `@NotEmpty`，Service 里判的是 `rules != null && !rules.isEmpty()`）。
 *      所以把规则行全删掉再保存，后端会说「什么都没改」。这一点必须写在表单里。
 *
 *   4. **没有「这个模板被哪些站点绑定」的反查接口**。前端**拿不到**这个事实，
 *      所以提示只能写成条件句（「如果该模板已被站点绑定…」），**不能**写成
 *      「该模板已被绑定」。真正的判定只有后端有，见第 2 点。
 *
 * ★ 24 小时自检走 `api/tariff.ts` 的 `validateTariffRules` —— 它是后端
 *   `TariffValidator` 的前端镜像（逐条对齐，连「起点用 `isTimeHM`、终点用
 *   `isTimeHMEnd`」这种细节都照抄了）。**不要在这里另写一套**：两套规则迟早
 *   会分叉，而分叉的表现是「前端放行、后端拒绝」，或者更糟的
 *   「两边都放行、但算出来的电价不一样」。
 *
 * ★ 时间字段一律是 `HH:mm` / `HH:mm:ss` **字符串**，不是 `Date`：末段必须是
 *   `24:00:00`，而 `Date` 表达不了它（会滚成次日 `00:00`，和 `00:00:00` 撞成
 *   同一个值）。所以这一页**不用 `el-time-picker`** —— 它的值模型就是 `Date`。
 */

import { computed, nextTick, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  MAX_LEN,
  PERIOD_TYPE,
  diffUpdate,
  formatPrice,
  formatServerTime,
  invalidateOptionsByTag,
  isCode,
  isTimeHM,
  isTimeHMEnd,
  periodColor,
  type TariffTemplatePageQuery,
  type TariffTemplateUpdateDTO,
  type TariffTemplateVO,
  type Tone,
} from '@chargeops/shared'
import CoIcon from '@/components/CoIcon.vue'
import DangerConfirm from '@/components/DangerConfirm.vue'
import EmptyState from '@/components/EmptyState.vue'
import FilterBar from '@/components/FilterBar.vue'
import PagePanel from '@/components/PagePanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import { TAG } from '@/api/options'
import {
  TEMPLATE_IN_USE,
  createTariffTemplate,
  deleteTariffTemplate,
  formatMinuteOfDay,
  getTariffTemplate,
  minuteOfDay,
  pageTariffTemplates,
  toRulePayloads,
  updateTariffTemplate,
  validateTariffRules,
  type TariffRuleInput,
} from '@/api/tariff'
import { usePagedList } from '@/composables/usePagedList'
import { useSubmitLock } from '@/composables/useSubmitLock'

/** 一天的分钟数。与后端 `TimeCodec.MINUTES_PER_DAY`、`api/tariff.ts` 的私有常量一致。 */
const MINUTES_PER_DAY = 1440

/**
 * 模板状态是**二值**（1 启用 / 0 停用，后端 `StatusConstant`）。
 * ★ 全项目只有模板和套餐是二值的，所以 shared 的 `dicts.ts` 里没有它 ——
 *   就地定义一处，别在筛选下拉和表单里各写一遍字面量。
 */
const STATUS_OPTIONS = [
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
] as const

/**
 * 二值状态的色调。
 * ★ 刻意**不复用** `STATION_STATUS.tone()`：那个字典是「0 停用 / 1 启用 / 2 维护中」，
 *   借过来用只是数值恰好重合，改站点状态的那天这里会静默变错。
 */
function statusTone(status: number | null | undefined): Tone {
  return status === 1 ? 'success' : 'info'
}

// ── 列表 ───────────────────────────────────────────────────────────────────

const { rows, total, pageNum, pageSize, loading, loaded, error, filters, search, reset, reload, goPage, dirty, afterRemove } =
  usePagedList<TariffTemplateVO, TariffTemplatePageQuery>({
    fetch: (q) => pageTariffTemplates(q),
    initial: { name: '', status: undefined },
  })

/** 是否有**实质**筛选条件。`el-select` 清空给的是 `''`，而初始值是 `undefined`，两者都算没填。 */
const hasFilter = computed(() =>
  [filters.name, filters.status].some((v) => v !== undefined && v !== null && String(v).trim() !== ''),
)

/** `ApiError extends Error`，所以 message 就是后端的中文文案。 */
function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

/** 时间列统一渲染。`formatServerTime` 内部是 `parseServerTime`，不是 `new Date(str)`。 */
function timeText(v: string | null | undefined): string {
  return formatServerTime(v) ?? '-'
}

/**
 * 是不是「模板已被站点绑定」那条拒绝。
 *
 * ★ 用 `startsWith` 而不是 `includes`：只要那句文案的**前缀**还在，后端把后半句
 *   改得更详细（比如补上被绑定的条数）也不会让这里失配。
 * ★ 比的是 `api/tariff.ts` 导出的那个前缀常量，**不是**在这里重抄一遍整句 ——
 *   重抄等于给同一句话加第三个真源。
 */
function isTemplateInUse(e: unknown): boolean {
  return e instanceof Error && e.message.startsWith(TEMPLATE_IN_USE)
}

/** 增量里「填了之后清不掉」的字段名 → 中文。 */
const FIELD_LABEL: Record<string, string> = {
  code: '模板编码',
  name: '模板名称',
  region: '适用地区',
  description: '模板说明',
}

/**
 * 剔除增量里被清空的键，并回报字段名。
 *
 * ★ `UpdateDTO` 的语义是「不传 = 不修改」。`TariffTemplateMapper.xml` 的 `<if>` 上
 *   也带了 `!= ''`，所以发空串**不会**清空那一列，而是「什么都没发生」——
 *   而用户在编辑框里删掉「适用地区」再保存，会以为自己清掉了。
 *   所以这里直接不发，并把字段名交给调用方去告诉他「已保持原值」。
 */
function dropCleared<T extends Record<string, unknown>>(payload: Partial<T>): string[] {
  const bag = payload as Record<string, unknown>
  const dropped: string[] = []
  for (const key of Object.keys(bag)) {
    const value = bag[key]
    if (value === null || value === undefined || (typeof value === 'string' && value.trim() === '')) {
      delete bag[key]
      dropped.push(key)
    }
  }
  return dropped
}

// ── 规则表单的模型 ─────────────────────────────────────────────────────────

/**
 * 规则行。
 *
 * ★ 单价在表单里是**字符串**（不是 number）：一是要能区分「没填」和「填了 0」
 *   （电费必填、服务费缺省补 0，两者在后端是两种不同的结果），
 *   二是 `Number('')` 是 0 —— 直接绑 number 会让空输入框静默变成「免费」。
 *
 * ★ `key` 只给 Vue 做 `:key`，**不会发给后端**（`toInput` 只挑那 5 个字段，
 *   后端要的那 5 个之一都不多）。用下标当 key 的话，删掉中间一行会让后面每一行
 *   的 DOM 节点都被复用成另一行的数据 —— 值会被 `v-model` 修正，但那一行里的
 *   焦点、下拉的展开状态会留在原地，看起来像「删错了行」。
 */
type RuleRow = {
  key: number
  periodType: number
  startTime: string
  endTime: string
  energyPrice: string
  servicePrice: string
}

/** 行的自增标识。只在浏览器里存在，刷新即重来。 */
let ruleRowSeq = 0

function nextRowKey(): number {
  ruleRowSeq += 1
  return ruleRowSeq
}

/** 空串 → null；解析不出来也返回 null（交给校验器去报「格式不对」）。 */
function numOrNull(v: string): number | null {
  const t = v.trim()
  if (t === '') return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}

function toInput(row: RuleRow): TariffRuleInput {
  return {
    periodType: row.periodType,
    startTime: row.startTime.trim(),
    endTime: row.endTime.trim(),
    energyPrice: numOrNull(row.energyPrice),
    servicePrice: numOrNull(row.servicePrice),
  }
}

/**
 * 新建时的默认规则。
 *
 * ★ 不是空表：24 小时覆盖是硬约束，从「一条 00:00:00-24:00:00 的平段」起步
 *   只需要改时间，从零填 8 行则很容易漏掉 5 分钟 —— 而漏掉的 5 分钟
 *   **纯看表格是看不出来的**（1000 和 1015 的差别不会跳进眼里）。
 */
function defaultRuleRows(): RuleRow[] {
  return [
    {
      key: nextRowKey(),
      periodType: 3,
      startTime: '00:00:00',
      endTime: '24:00:00',
      energyPrice: '',
      servicePrice: '',
    },
  ]
}

// ── 弹窗 ───────────────────────────────────────────────────────────────────

type HeaderForm = {
  code: string
  name: string
  region: string
  description: string
  status: number
}

function emptyHeader(): HeaderForm {
  return { code: '', name: '', region: '', description: '', status: 1 }
}

const dialogOpen = ref(false)
const formRef = ref<FormInstance>()
const header = reactive<HeaderForm>(emptyHeader())
/** 增量提交的基准，必须是**服务端原值**。 */
const originalHeader = ref<HeaderForm>(emptyHeader())
const ruleRows = ref<RuleRow[]>([])
/** 打开弹窗那一刻的规则快照，用来判断「规则到底改没改」（见文件头第 2 点）。 */
const originalRuleRows = ref<RuleRow[]>([])
const editingId = ref<number | null>(null)
const editingName = ref('')
/** 「另存为新模板」模式：编辑被不可变守卫拒绝后的出口，此时走的是**新建**路径。 */
const saveAs = ref(false)
/** 正在拉详情的行 id（列表不带规则，编辑要先补一次请求）。 */
const openingId = ref<number | null>(null)

/** 是否处于「编辑既有模板」模式（「另存为」会把它切成 false —— 那时走的是新建路径）。 */
const isEdit = computed(() => editingId.value !== null && !saveAs.value)

const dialogTitle = computed(() => {
  if (saveAs.value) return '另存为新模板'
  if (editingId.value === null) return '新建计费模板'
  return `编辑模板「${editingName.value}」`
})

const { pending: submitting, run: runSubmit } = useSubmitLock({ throttleMs: 400 })
/** 行内「启用/停用」用的独立提交锁（和弹窗那条提交互不影响）。 */
const { pending: toggling, run: runToggle } = useSubmitLock({ throttleMs: 300 })
const togglingId = ref<number | null>(null)

function fillHeader(vo: TariffTemplateVO): void {
  header.code = vo.code
  header.name = vo.name
  header.region = vo.region ?? ''
  header.description = vo.description ?? ''
  header.status = vo.status
}

/** 详情 VO 的规则 → 表单行。`seq` 不用带：后端本来就按 `start_time` 排序返回。 */
function rowsFromVo(vo: TariffTemplateVO): RuleRow[] {
  return (vo.rules ?? []).map((r) => ({
    // ★ 不复用后端的规则 id：同一次编辑里可能会删掉再新增一条「看起来一样」的行，
    //   而 `key` 只服务于 DOM 复用，用后端 id 反而要处理「新增行没有 id」的分支。
    key: nextRowKey(),
    periodType: r.periodType,
    startTime: r.startTime,
    endTime: r.endTime,
    // ★ 用 `formatPrice`（4 位）而不是原样的字符串：这一列是 decimal(10,4)，
    //   显示 1.2000 而不是 1.2，运营才不会以为精度只有一位。
    energyPrice: formatPrice(r.energyPrice),
    servicePrice: formatPrice(r.servicePrice),
  }))
}

function openCreate(): void {
  editingId.value = null
  editingName.value = ''
  saveAs.value = false
  Object.assign(header, emptyHeader())
  const rows = defaultRuleRows()
  ruleRows.value = rows
  originalRuleRows.value = rows.map((r) => ({ ...r }))
  originalHeader.value = { ...header }
  dialogOpen.value = true
  // el-dialog 的内容是懒渲染的，首次打开时 formRef 还没挂上
  void nextTick(() => formRef.value?.clearValidate())
}

async function openEdit(row: TariffTemplateVO): Promise<void> {
  // ★ 列表的 rules 恒为 null，必须补一次详情请求（文件头第 1 点）
  openingId.value = row.id
  let detail: TariffTemplateVO | null = null
  try {
    detail = await getTariffTemplate(row.id)
  } catch {
    return // 失败提示由 api/http.ts 的全局 onError 统一弹出，这里不重复
  } finally {
    openingId.value = null
  }

  if (!detail) {
    ElMessage({
      type: 'warning',
      message: '这个模板已经不存在了，请刷新列表后再看。',
      duration: 4000,
      showClose: true,
    })
    return
  }

  editingId.value = row.id
  editingName.value = row.name
  saveAs.value = false
  fillHeader(detail)
  const rows = rowsFromVo(detail)
  ruleRows.value = rows
  originalRuleRows.value = rows.map((r) => ({ ...r }))
  originalHeader.value = { ...header }
  dialogOpen.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

/** 格式校验：空值放行（必填由 `required` 那几条管），有值才交给 `check`。 */
function formatRule(check: (v: string) => boolean, message: string) {
  return {
    validator: (_rule: unknown, value: string, callback: (err?: Error) => void): void => {
      if (!value) return callback()
      callback(check(value) ? undefined : new Error(message))
    },
    trigger: 'blur' as const,
  }
}

/** 逐条对应 `TariffTemplateDTO` / `TariffTemplateUpdateDTO` 上的注解。 */
const rules: FormRules = {
  code: [
    { required: true, message: '请输入模板编码', trigger: 'blur' },
    { max: MAX_LEN.code, message: `编码不能超过 ${MAX_LEN.code} 个字符`, trigger: 'blur' },
    formatRule(isCode, '编码只能是字母、数字和连字符'),
  ],
  name: [
    { required: true, message: '请输入模板名称', trigger: 'blur' },
    { max: MAX_LEN.templateName, message: `名称不能超过 ${MAX_LEN.templateName} 个字符`, trigger: 'blur' },
  ],
  region: [{ max: MAX_LEN.region, message: `适用地区不能超过 ${MAX_LEN.region} 个字符`, trigger: 'blur' }],
  description: [
    { max: MAX_LEN.templateRemark, message: `模板说明不能超过 ${MAX_LEN.templateRemark} 个字符`, trigger: 'blur' },
  ],
}

// ── 规则编辑器：实时 24 小时覆盖分析 ────────────────────────────────────────

function addRuleRow(): void {
  // ★ 新行**接着上一行的结束时刻**起：这样加一行不会立刻制造一个缺口，
  //   用户只需要改结束时间。留空则会同时得到「格式错误」和「缺口」两条提示。
  const last = ruleRows.value[ruleRows.value.length - 1]
  const from = last && last.endTime !== '24:00:00' ? last.endTime : ''
  ruleRows.value.push({
    key: nextRowKey(),
    periodType: 3,
    startTime: from,
    endTime: '',
    energyPrice: '',
    servicePrice: '',
  })
}

function removeRuleRow(index: number): void {
  ruleRows.value.splice(index, 1)
}

const ruleInputs = computed<TariffRuleInput[]>(() => ruleRows.value.map((r) => toInput(r)))

/** 后端 `TariffValidator` 的前端镜像。**提交前的唯一裁判**（提交时还会再判一次）。 */
const check = computed(() => validateTariffRules(ruleInputs.value))
const rowProblems = computed(() => check.value.rowProblems)
const gaps = computed(() => check.value.gaps)
const overlaps = computed(() => check.value.overlaps)
const hasIssues = computed(
  () => rowProblems.value.length > 0 || gaps.value.length > 0 || overlaps.value.length > 0,
)

/**
 * 规则签名 —— 只取决于**真正会发出去的内容**。
 *
 * ★ 用 `toRulePayloads` 的输出来比，而不是比输入框里的字符串：
 *   `08:00` 与 `08:00:00` 是同一个时刻、`1.2` 与 `1.2000` 是同一个价格，
 *   比字符串会把「格式写法不同」误判成「改过规则」，于是带着没变的规则去撞守卫。
 */
function signatureOf(rows: readonly RuleRow[]): string {
  return JSON.stringify(toRulePayloads(rows.map((r) => toInput(r))))
}

const rulesChanged = computed(
  () => signatureOf(ruleRows.value) !== signatureOf(originalRuleRows.value),
)

/**
 * 逐分钟统计覆盖数。按**分钟**而不是按格子算：缺口和重叠都是分钟级的事实，
 * 一格聚合会把「30 分钟里缺了 5 分钟」抹平。
 */
const coverage = computed(() => {
  const count = new Array<number>(MINUTES_PER_DAY).fill(0)
  const type = new Array<number>(MINUTES_PER_DAY).fill(0)

  for (const input of ruleInputs.value) {
    // ★ 用与校验器**同一套**解析函数（起点 isTimeHM / 终点 isTimeHMEnd）。
    //   解析不出来或起点不早于终点的行不参与 —— 它们的错已经由 rowProblems 报出来了，
    //   再拿它们算出一堆假缺口只会把真正的问题淹没。
    if (!isTimeHM(input.startTime) || !isTimeHMEnd(input.endTime)) continue
    const start = minuteOfDay(input.startTime)
    const end = minuteOfDay(input.endTime)
    if (start === null || end === null || start >= end) continue
    for (let m = start; m < end && m < MINUTES_PER_DAY; m += 1) {
      count[m] += 1
      type[m] = input.periodType
    }
  }

  let covered = 0
  let overlapped = 0
  for (let m = 0; m < MINUTES_PER_DAY; m += 1) {
    if (count[m] > 0) covered += 1
    if (count[m] > 1) overlapped += 1
  }
  return { count, type, covered, overlapped, gap: MINUTES_PER_DAY - covered }
})

/** 分钟数 → 「22 小时 45 分」。★ 不用 `formatDuration`：那个按秒算，5 分钟的缺口会显示成「5分0秒」。 */
function minutesText(minutes: number): string {
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  if (h === 0) return `${m} 分钟`
  return m === 0 ? `${h} 小时` : `${h} 小时 ${m} 分`
}

const coverageLabel = computed(() => {
  if (rowProblems.value.length > 0) return '规则还没填完'
  if (overlaps.value.length > 0) return '存在重叠'
  if (gaps.value.length > 0) return '存在缺口'
  return '完整覆盖 24 小时'
})

const coverageTone = computed<Tone>(() => {
  if (rowProblems.value.length > 0) return 'warning'
  // ★ 重叠给更高的视觉级别（与 `validateTariffRules` 的取舍一致）：
  //   两者都会让后端拒绝保存，但重叠更难自己看出来 —— 两条规则单看都成立，
  //   只有把它们摆在一起比端点才看得出来，而缺口是一整段时间没人认领。
  if (overlaps.value.length > 0) return 'danger'
  if (gaps.value.length > 0) return 'warning'
  return 'success'
})

/** 时间轴的一格（半小时）。 */
interface SlotView {
  from: number
  kind: 'covered' | 'gap' | 'overlap'
  periodType: number
  hint: string
}

/**
 * 时间轴按半小时切 48 格。
 *
 * ★ 粒度取舍：1440 个格子没法画，24 个小时格又看不出「某一段缺了」。48 格是
 *   能一眼扫完、又能暴露成段问题的那个点；**精确的缺口位置由下面的文字列表给出**，
 *   两者一个负责「一眼看出有没有问题」，一个负责「问题具体在哪一分钟」。
 */
const slots = computed<SlotView[]>(() => {
  const out: SlotView[] = []
  for (let start = 0; start < MINUTES_PER_DAY; start += 30) {
    const end = start + 30
    let hasGap = false
    let hasOverlap = false
    let type = 0
    for (let m = start; m < end; m += 1) {
      if (coverage.value.count[m] === 0) hasGap = true
      else if (coverage.value.count[m] > 1) hasOverlap = true
      else type = coverage.value.type[m]
    }
    // ★ 重叠优先于缺口（与 `validateTariffRules` 的取舍一致）：覆盖关系已经乱了，
    //   此时「缺了哪一段」不是可靠信息。
    const kind = hasOverlap ? 'overlap' : hasGap ? 'gap' : 'covered'
    const range = `${formatMinuteOfDay(start)} - ${formatMinuteOfDay(end)}`
    const hint =
      kind === 'overlap'
        ? `${range}：有两套以上的价格同时生效`
        : kind === 'gap'
          ? `${range}：没有任何规则覆盖`
          : `${range}：${PERIOD_TYPE.label(type)}`
    out.push({ from: start, kind, periodType: type, hint })
  }
  return out
})

/** 时间轴的坐标标签。用 `formatMinuteOfDay` 截前 5 位，和列表页的 `hm()` 同一个做法。 */
function hm(minute: number): string {
  return formatMinuteOfDay(minute).slice(0, 5)
}

/** 某一行的字段问题（去掉「第 N 行：」前缀 —— 它就显示在那一行下面）。 */
function problemsOf(index: number): string[] {
  return rowProblems.value
    .filter((p) => p.row === index)
    .map((p) => p.message.replace(/^第 \d+ 行：/, ''))
}

// ── 提交 ───────────────────────────────────────────────────────────────────

/** `'invalid'` 不发请求：本地自检没过。 */
type SubmitResult = 'created' | 'saved' | 'nochange' | 'in-use' | 'invalid' | 'failed'

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const outcome = await runSubmit<SubmitResult>(async () => {
    const inputs = ruleInputs.value
    const changed = rulesChanged.value

    // ★ 提交前**必须**过一遍 24 小时自检（文件头最后一段）。新建和「改了规则」
    //   都要过；只是改个名字则不需要 —— 那时根本不会把规则发给后端。
    if (editingId.value === null || changed) {
      if (!check.value.ok) return 'invalid'
    }

    // 新建 / 另存为新模板。★ 不带 `silent`：创建没有不可变守卫，
    // 失败让全局 onError 弹后端原文即可。
    if (editingId.value === null) {
      await createTariffTemplate({
        code: header.code.trim(),
        name: header.name.trim(),
        region: header.region.trim() || undefined,
        description: header.description.trim() || undefined,
        status: header.status,
        rules: toRulePayloads(inputs),
      })
      return 'created'
    }

    const id = editingId.value
    // ★ 增量提交：只发改动过的键。整份 PUT 会刷新没碰过的字段的 update_time
    //   （审计日志里变成「改了 5 个字段」），而且模板表没有 version 列，
    //   整份提交 = 后写的人静默覆盖先写的人（见 shared/domain/updateDiff.ts）。
    const { payload, unsettable } = diffUpdate(originalHeader.value, header)
    // `unsettable` 是 diffUpdate 自己识别出的「清不掉」字段（description 在名单里），
    // `dropCleared` 再补上它的名单外的（code / name / region）——
    // 两处都要报，用户才不会以为自己清空了。
    const blocked = [...new Set([...unsettable, ...dropCleared(payload)])]

    if (blocked.length > 0) {
      // 提示但不中断：其余改动照常提交，用户不用为了一个清不掉的字段重填一遍
      ElMessage({
        type: 'warning',
        message: `这些字段填过之后无法清空，已保持原值：${blocked.map((k) => FIELD_LABEL[k] ?? k).join('、')}`,
        duration: 5000,
        showClose: true,
      })
    }

    const dto: TariffTemplateUpdateDTO = { ...payload }
    // ★★ 只有规则**真的改了**才带上 rules（文件头第 2 点）★★
    //   带上就是「整体替换」，而后端在替换前会无条件查一次站点绑定引用 ——
    //   哪怕内容一模一样，被绑定过的模板也会被拒绝。
    if (changed) dto.rules = toRulePayloads(inputs)

    if (Object.keys(dto).length === 0) return 'nochange'

    try {
      // ★ `silent: true` —— 「已被站点绑定」这条要自己接管（它有一个明确的出路：
      //   另存为新模板），页面要和后端原文**合起来**说，而不是先弹 toast 再弹引导框。
      await updateTariffTemplate(id, dto, { silent: true })
      return 'saved'
    } catch (e) {
      if (isTemplateInUse(e)) return 'in-use'
      // ★ 这条请求已经静默了，所以**其余错误也得自己弹** ——
      //   全局 onError 这次不会响，丢掉它就等于把失败吞掉。
      ElMessage({ type: 'error', message: errorText(e), duration: 4000, showClose: true })
      return 'failed'
    }
  })

  if (outcome.status === 'skipped') return
  // 创建路径的失败仍然由全局 onError 弹过；编辑路径的失败在上面自己弹过
  if (outcome.status === 'failed') return

  if (outcome.value === 'invalid') {
    ElMessage({
      type: 'error',
      message: '计费规则还没有完整、不重叠地覆盖 24 小时，请先按下面的提示修正。',
      duration: 5000,
      showClose: true,
    })
    return
  }

  if (outcome.value === 'in-use') {
    // 弹窗**不关**：用户填的东西一个字都不该丢，引导框就浮在它上面
    openGuide('rules-locked')
    return
  }

  dialogOpen.value = false

  if (outcome.value === 'nochange') {
    ElMessage({ type: 'info', message: '没有改动，未提交', duration: 1600 })
    return
  }

  ElMessage({
    type: 'success',
    message: outcome.value === 'created' ? '已创建模板' : '已保存',
    duration: 1800,
  })
  // ★ 模板名/状态变了，站点计费绑定页的模板下拉（`/list-enabled`）就过期了
  invalidateOptionsByTag(TAG.template)
  await reload()
}

function onDialogClosed(): void {
  formRef.value?.clearValidate()
}

// ── 启用 / 停用 ────────────────────────────────────────────────────────────

/**
 * 启用或停用。
 *
 * ★ 刻意**不走 `DangerConfirm`**：这个动作可逆、不删数据，而且它的「后果」很容易
 *   被写成一句假话 —— 停用**不会**改变任何已绑定站点的计费（值域的定义就是
 *   「这个模板还能不能被**新绑定**」，已有绑定在解析时不经过这个 status）。
 *   所以用一句把范围说清楚的提示代替二次确认。
 */
async function toggleStatus(row: TariffTemplateVO): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  togglingId.value = row.id
  const outcome = await runToggle(async () => {
    await updateTariffTemplate(row.id, { status: next })
    return next
  })
  togglingId.value = null

  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') return // 全局 onError 已弹过后端原文

  invalidateOptionsByTag(TAG.template)
  ElMessage({
    type: 'success',
    message:
      outcome.value === 1
        ? `已启用「${row.name}」`
        : `已停用「${row.name}」：只影响新建绑定，已经绑定它的站点计费不变。`,
    duration: outcome.value === 1 ? 1800 : 5000,
    showClose: outcome.value === 0,
  })
  await reload()
}

// ── 引导弹窗（「模板不可变」的出口）────────────────────────────────────────

type GuideKind = 'rules-locked' | 'delete-blocked'

const guideOpen = ref(false)
const guideKind = ref<GuideKind>('rules-locked')
/** 删除被拒时的那一行，用来提供「改为停用」这条出路。 */
const guideRow = ref<TariffTemplateVO | null>(null)

function openGuide(kind: GuideKind, row: TariffTemplateVO | null = null): void {
  guideKind.value = kind
  guideRow.value = row
  guideOpen.value = true
}

/**
 * 转入「另存为新模板」。
 *
 * ★ 编码不自动生成：模板编码上有唯一索引，任何拼接（`原名-副本`、`原名-2`）
 *   迟早会撞上已经存在的那一个，而「改一个字母」是用户能一眼判断的事。
 *   名称给一个带「新版本」的默认值 —— 那正是后端那句文案建议的做法。
 */
function enterSaveAs(): void {
  guideOpen.value = false
  saveAs.value = true
  editingId.value = null
  editingName.value = ''
  header.code = ''
  header.name = `${header.name}（新版本）`
  // ★ 规则已经是「要提交的那一份」，所以快照要跟着更新 —— 否则新建路径会
  //   带着一份「看起来改过」的签名去跑覆盖自检（结果一样，但语义是错的）。
  originalRuleRows.value = ruleRows.value.map((r) => ({ ...r }))
  originalHeader.value = { ...header }
  void nextTick(() => formRef.value?.clearValidate())
  ElMessage({
    type: 'info',
    message: '已切换为「新建模板」：请填一个新的编码（编码不能重复），规则和名称会原样带过去。',
    duration: 6000,
    showClose: true,
  })
}

/** 引导框的主按钮：删除被拒时提供「改为停用」，这是后端推荐的退役手段。 */
async function onGuidePrimary(): Promise<void> {
  if (guideKind.value === 'rules-locked') {
    enterSaveAs()
    return
  }
  const row = guideRow.value
  guideOpen.value = false
  if (row) await toggleStatus(row)
}

// ── 删除 ───────────────────────────────────────────────────────────────────

const deleteOpen = ref(false)
const deleteTarget = ref<TariffTemplateVO | null>(null)
const { pending: removing, run: runRemove } = useSubmitLock({ throttleMs: 500 })

function askRemove(row: TariffTemplateVO): void {
  deleteTarget.value = row
  deleteOpen.value = true
}

async function doRemove(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return

  const outcome = await runRemove(async () => {
    try {
      // ★ `silent: true` —— 被站点引用时后端会拒绝，那句话要配上出路一起说
      await deleteTariffTemplate(target.id, { silent: true })
      return 'done' as const
    } catch (e) {
      if (isTemplateInUse(e)) return 'in-use' as const
      // 这条请求静默了，所以其余错误自己弹（同 onSubmit 里的理由）
      ElMessage({ type: 'error', message: errorText(e), duration: 4000, showClose: true })
      return 'failed' as const
    }
  })

  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') return

  if (outcome.value === 'in-use') {
    deleteOpen.value = false
    openGuide('delete-blocked', target)
    return
  }

  if (outcome.value === 'failed') return

  deleteOpen.value = false
  invalidateOptionsByTag(TAG.template)
  ElMessage({ type: 'success', message: `已删除模板「${target.name}」`, duration: 2000 })
  // ★ 用 afterRemove 而不是 reload：末页只剩一条时它会回退一页
  await afterRemove()
}
</script>

<template>
  <div class="page">
    <PagePanel
      title="计费模板"
      subtitle="模板 = 一套「尖峰平谷 + 每个时段的电价」的规则，必须不重不漏地覆盖 24 小时。模板一旦被站点绑定，它的规则就不能再改（订单在下单时冻结了模板 id）—— 要调价请新建一个模板版本。"
      flush
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <CoIcon name="plus" :size="15" />
          新建模板
        </el-button>
      </template>

      <FilterBar :dirty="dirty" :loading="loading" @search="search" @reset="reset">
        <el-input
          v-model="filters.name"
          placeholder="模板名称（模糊）"
          clearable
          :maxlength="MAX_LEN.templateName"
          @keyup.enter="search"
        />
        <el-select v-model="filters.status" placeholder="状态" clearable>
          <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </FilterBar>

      <el-table v-loading="loading" :data="rows" row-key="id" size="default">
        <el-table-column prop="code" label="编码" width="150" show-overflow-tooltip />
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="适用地区" width="120">
          <template #default="{ row }">{{ row.region || '-' }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ muted: !row.description }">{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <!-- ★ 这里**没有**「规则数」列：`GET /page` 的 rules 恒为 null（文件头第 1 点），
             摆一列出来它永远是空的，用户会以为是数据丢了。 -->
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">
            <!-- ★ 用 `formatServerTime` 而不是原样输出：后端的时间串可能是
                 `2026-07-01T00:00:00`（带 T）这种形状，直接显示出来运维还得自己做转换 -->
            <span class="co-num">{{ timeText(row.updateTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusTag :label="row.statusLabel" :tone="statusTone(row.status)" size="sm" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              :loading="openingId === row.id"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              link
              type="primary"
              size="small"
              :loading="togglingId === row.id"
              :disabled="toggling && togglingId !== row.id"
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" size="small" @click="askRemove(row)">删除</el-button>
          </template>
        </el-table-column>

        <!-- 空态三态分开：真的没有 / 被筛掉了 / 加载失败 -->
        <template #empty>
          <EmptyState v-if="loaded && error" kind="error" :description="errorText(error)" @retry="reload" />
          <EmptyState v-else-if="loaded && hasFilter" kind="filtered" @retry="reset" />
          <EmptyState
            v-else-if="loaded"
            kind="empty"
            title="还没有计费模板"
            description="先建一个模板（一套覆盖 24 小时的尖峰平谷规则），再到「站点计费绑定」里把它绑给站点。"
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

    <!-- ── 新建 / 编辑 / 另存为 ─────────────────────────────────────────── -->
    <el-dialog
      v-model="dialogOpen"
      :title="dialogTitle"
      width="960px"
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      @closed="onDialogClosed"
    >
      <el-form ref="formRef" :model="header" :rules="rules" label-position="top">
        <div class="grid-2">
          <el-form-item label="模板编码" prop="code">
            <el-input
              v-model="header.code"
              placeholder="如 SZ-2026-GYF"
              :maxlength="MAX_LEN.code"
              :disabled="submitting"
            />
          </el-form-item>
          <el-form-item label="模板名称" prop="name">
            <el-input
              v-model="header.name"
              placeholder="如 深圳 2026 工商业分时电价"
              :maxlength="MAX_LEN.templateName"
              :disabled="submitting"
            />
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="适用地区" prop="region">
            <el-input v-model="header.region" placeholder="选填，如 深圳市" :maxlength="MAX_LEN.region" :disabled="submitting" />
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-radio-group v-model="header.status" :disabled="submitting">
              <el-radio-button v-for="o in STATUS_OPTIONS" :key="o.value" :value="o.value">
                {{ o.label }}
              </el-radio-button>
            </el-radio-group>
          </el-form-item>
        </div>

        <el-form-item label="模板说明" prop="description">
          <el-input
            v-model="header.description"
            type="textarea"
            :rows="2"
            placeholder="选填，如 按深圳市 2026 年工商业分时电价编制"
            :maxlength="MAX_LEN.templateRemark"
            show-word-limit
            :disabled="submitting"
          />
        </el-form-item>

        <!-- ★ 改动规则时的**提交前**提示（不是等后端拒绝）。

             ★★ 注意措辞是条件句 ★★ 前端拿不到「这个模板被哪些站点绑定」——
             后端没有反查接口（只有 `by-station/{stationId}`，方向是反的）。
             所以这里只能说「如果…会被拒绝」，不能断言它已经被绑定。 -->
        <el-alert
          v-if="isEdit && rulesChanged"
          class="lock-alert"
          type="warning"
          :closable="false"
          show-icon
        >
          <template #title>你改了计费规则：只有「没有被任何站点绑定」的模板才存得进规则</template>
          <p class="lock-text">
            后端收到规则列表时会先查一遍站点绑定，只要有站点在用这个模板，整条修改都会被拒绝
            —— 包括同一份请求里的名称、地区改动。本页<strong>查不到</strong>绑定关系
            （后端没有反查接口），所以没法提前告诉你结果。若真被拒绝：弹窗不会关，
            会给你一个「另存为新模板」的出口 —— 那也正是后端的建议：模板是版本化的，
            调价应当新建一个版本，再到「站点计费绑定」里用生效时刻切换到新版本。
          </p>
        </el-alert>

        <!-- ── 24 小时规则编辑器 ──────────────────────────────────────── -->
        <div class="rule-editor">
          <div class="rule-head">
            <div>
              <span class="rule-title">分时计费规则</span>
              <span class="rule-sub">半开区间：前一段的结束时刻就是后一段的开始时刻，不重叠。</span>
            </div>
            <el-button size="small" :disabled="submitting" @click="addRuleRow">
              <CoIcon name="plus" :size="14" />
              加一条规则
            </el-button>
          </div>

          <!-- 覆盖情况：一个标签 + 一串数字 + 一条时间轴 -->
          <div class="coverage">
            <div class="coverage-head">
              <StatusTag :label="coverageLabel" :tone="coverageTone" size="sm" />
              <span class="coverage-nums co-num">
                已覆盖 {{ minutesText(coverage.covered) }} / 24 小时
                <template v-if="coverage.gap > 0">，缺口 {{ minutesText(coverage.gap) }}</template>
                <template v-if="coverage.overlapped > 0">，重叠 {{ minutesText(coverage.overlapped) }}</template>
              </span>
            </div>

            <div class="timeline">
              <span
                v-for="s in slots"
                :key="s.from"
                class="slot"
                :class="`is-${s.kind}`"
                :style="s.kind === 'covered' ? { background: periodColor(s.periodType) } : undefined"
                :title="s.hint"
              />
            </div>
            <div class="axis co-num">
              <span v-for="t in [0, 360, 720, 1080, 1440]" :key="t">{{ hm(t) }}</span>
            </div>

            <div class="legend">
              <span v-for="o in PERIOD_TYPE.list" :key="o.code" class="legend-item">
                <i class="swatch" :style="{ background: periodColor(o.code) }" />{{ o.label }}
              </span>
              <span class="legend-item"><i class="swatch is-gap" />未覆盖</span>
              <span class="legend-item"><i class="swatch is-overlap" />重叠</span>
            </div>
          </div>

          <div class="rule-grid">
            <div class="rule-row rule-row-head">
              <span>时段</span>
              <span>开始（含）</span>
              <span>结束（不含）</span>
              <span class="is-num">电费单价（元/kWh）</span>
              <span class="is-num">服务费单价（元/kWh）</span>
              <span />
            </div>

            <template v-for="(row, index) in ruleRows" :key="row.key">
              <div class="rule-row" :class="{ 'is-bad': problemsOf(index).length > 0 }">
                <el-select v-model="row.periodType" :disabled="submitting" size="default">
                  <el-option
                    v-for="o in PERIOD_TYPE.options()"
                    :key="o.value"
                    :label="o.label"
                    :value="o.value"
                  />
                </el-select>
                <!-- ★ 刻意用**文本输入**而不是 `el-time-picker`：后者的值模型是 `Date`，
                     而末段必须写 `24:00:00` —— `Date` 里表达不了它（会滚成次日 00:00，
                     和 `00:00:00` 撞成同一个值）。格式由 `isTimeHM` / `isTimeHMEnd`
                     校验，那两个函数与后端 DTO 上的 `@Pattern` 逐字一致。 -->
                <el-input
                  v-model="row.startTime"
                  placeholder="HH:mm:ss"
                  :maxlength="8"
                  :disabled="submitting"
                  class="co-num"
                />
                <el-input
                  v-model="row.endTime"
                  placeholder="HH:mm:ss 或 24:00:00"
                  :maxlength="8"
                  :disabled="submitting"
                  class="co-num"
                />
                <el-input
                  v-model="row.energyPrice"
                  placeholder="如 1.2188"
                  :disabled="submitting"
                  class="co-num"
                />
                <el-input
                  v-model="row.servicePrice"
                  placeholder="不填按 0"
                  :disabled="submitting"
                  class="co-num"
                />
                <el-button
                  link
                  type="danger"
                  size="small"
                  :disabled="submitting"
                  @click="removeRuleRow(index)"
                >
                  删除
                </el-button>
              </div>
              <!-- ★ 行内错误直接挂在那一行下面：校验器给的就是「第 N 行 + 原因」，
                   把原因挪到行边上，用户不用在两处之间来回数行号。 -->
              <p v-for="(msg, i) in problemsOf(index)" :key="`${index}-${i}`" class="row-issue">
                {{ msg }}
              </p>
            </template>

            <p v-if="ruleRows.length === 0" class="row-issue">
              一条规则都没有。后端把「空规则列表」当成「不修改规则」而不是清空 ——
              要清空规则的模板只能删掉它本身。
            </p>
          </div>

          <!-- 缺口 / 重叠：精确到分钟的那一份。时间轴负责「一眼看出有没有问题」，
               这里负责「问题具体在哪一分钟」。 -->
          <div v-if="hasIssues" class="rule-issues">
            <p v-for="(p, i) in overlaps" :key="`o${i}`" class="issue is-error">{{ p.message }}</p>
            <p v-for="(g, i) in gaps" :key="`g${i}`" class="issue is-warn">{{ g.message }}</p>
          </div>
        </div>

        <ul class="form-notes">
          <li>
            规则必须<strong>不重不漏地覆盖 00:00:00 ~ 24:00:00</strong>。末段的结束时刻必须写
            <code>24:00:00</code>：写成 <code>00:00:00</code> 会变成一个零长度时段
            （<code>[23:00, 00:00)</code> 没有任何规则覆盖它），后端会以「起点必须早于终点」
            拒绝，下面的时间轴也会把它显示成一段缺口。
          </li>
          <li>
            电费单价必填、可以为 0（政府补贴园区）；服务费单价不填按 0 处理（免服务费活动）。
            两者都是 <code>decimal(10,4)</code>，四位小数是电网账单的精度。
          </li>
          <li>
            把规则行全删掉再保存，后端会当成「<strong>不修改规则</strong>」而不是清空 ——
            所以本页会直接拦住这种提交。要清空规则只能删掉整个模板。
          </li>
          <li>
            改规则前请先确认没有站点在用这个模板：被绑定过的模板<b>存不进</b>规则改动，
            届时请用引导框里的「另存为新模板」。
          </li>
          <li>适用地区、模板说明<strong>填过之后无法清空</strong>：后端的「不传」表示「不修改」。</li>
        </ul>
      </el-form>

      <template #footer>
        <el-button :disabled="submitting" @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">
          {{ saveAs || !isEdit ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ── 引导：模板被站点绑定 ─────────────────────────────────────────── -->
    <el-dialog
      v-model="guideOpen"
      :title="guideKind === 'rules-locked' ? '这个模板已被站点绑定，规则改不了' : '这个模板已被站点绑定，删不掉'"
      width="560px"
      align-center
      :close-on-click-modal="false"
    >
      <div class="guide">
        <p class="guide-lead">
          后端返回的原话：<code>{{ TEMPLATE_IN_USE }}</code>
        </p>
        <ul class="guide-list">
          <template v-if="guideKind === 'rules-locked'">
            <li>
              订单在下单那一刻把模板 id 冻结了进去，所以电价调整不影响在途订单。
              这个保证成立的前提就是<strong>规则不可变</strong> —— 规则还能改的话，
              那笔已经冻结的订单会按改过之后的规则算出另一个金额，而且不报错。
            </li>
            <li>
              <strong>出路</strong>：点下面的「另存为新模板」，把当前规则存成一个新版本，
              再到「站点计费绑定」里给站点加一条新绑定（生效时刻写你打算切换的那一刻）。
              老绑定用它的 <code>effectiveTo</code> 或「停用」收尾 —— 这正是模板版本化的用法。
            </li>
            <li>
              后端<strong>没有</strong>「这个模板被哪些站点绑定」的反查接口，所以本页也
              列不出是哪些站点在用它。要看的话只能到「站点计费绑定」里逐个站点查。
            </li>
          </template>
          <template v-else>
            <li>
              删掉一个正在被站点使用的模板，那些站点的电价会直接解析不出结果 ——
              表现成「这个站点现在充不了电」。
            </li>
            <li>
              <strong>出路一（推荐）</strong>：改为<strong>停用</strong>。停用可逆、保留历史，
              而且<strong>不影响已经绑定它的站点</strong>（status 的语义是「还能不能被新绑定」）。
              常规运维里退役一个模板就该这么做，而不是删除。
            </li>
            <li>
              <strong>出路二</strong>：先到「站点计费绑定」里把引用它的绑定删掉（或改绑别的模板）。
              没有反查接口，只能逐个站点查。
            </li>
            <li>
              删除后<strong>规则行不会被级联删除</strong>，会在库里留下孤儿行 —— 这也是
              删除应当慎用的一个理由。
            </li>
          </template>
        </ul>
      </div>

      <template #footer>
        <el-button @click="guideOpen = false">知道了</el-button>
        <el-button type="primary" @click="onGuidePrimary">
          {{ guideKind === 'rules-locked' ? '另存为新模板' : '改为停用' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ── 删除 ───────────────────────────────────────────────────────── -->
    <DangerConfirm
      v-model="deleteOpen"
      :title="`删除计费模板「${deleteTarget?.name ?? ''}」`"
      description="删除是硬删除，不可恢复。模板编码会被释放，之后可以重新使用同一个编码。"
      :consequences="[
        '常规的退役手段是「停用」而不是删除：停用保留「这个站点曾经用过这套价」这条历史，删掉之后那段时期就变成空白，事后追查一笔老订单为什么用了这个价时找不到依据。',
        '被任何站点绑定引用时会被后端拒绝 —— 那种情况下请用引导框里的「改为停用」。',
        '模板的规则行不会被级联删除，库里会留下指向不存在模板的孤儿行。',
        '历史订单不受影响：它们冻结的是模板 id。',
      ]"
      action-name="删除"
      :require-typed-value="deleteTarget?.code ?? null"
      typed-label="请输入模板编码以确认删除"
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
  color: var(--co-text-4);
}

/* ── 不可变提示 ───────────────────────────────────────────────────────── */

.lock-alert {
  margin-bottom: 16px;
}

.lock-text {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.75;
}

/* ── 规则编辑器 ───────────────────────────────────────────────────────── */

.rule-editor {
  margin-bottom: 14px;
  padding: 12px 14px 14px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-2);
}

.rule-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.rule-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--co-text-1);
}

.rule-sub {
  margin-left: 8px;
  font-size: 12.5px;
  color: var(--co-text-3);
}

.coverage {
  padding: 10px 12px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-card);
}

.coverage-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.coverage-nums {
  font-size: 12.5px;
  color: var(--co-text-2);
}

/* 时间轴：48 格 × 半小时。圆角只在外层，格子之间留 1px 缝隙，
   让「段的边界」在视觉上能看出来。 */
.timeline {
  display: grid;
  grid-template-columns: repeat(48, minmax(0, 1fr));
  gap: 1px;
  height: 26px;
  margin-top: 9px;
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-sm);
  overflow: hidden;
  background: var(--co-border-light);
}

.slot {
  display: block;
  min-width: 0;
}

/* 缺口：什么都没有的一段。浅灰底 + 红虚线边 —— 「这里没有价」 */
.slot.is-gap {
  background: var(--co-bg-3);
  box-shadow: inset 0 0 0 1px var(--co-danger);
}

/* 重叠：两套价格同时生效。用斜纹表达「被占了两次」，与缺口的灰底一眼可分 */
.slot.is-overlap {
  background: repeating-linear-gradient(
    45deg,
    var(--co-accent-weak) 0 4px,
    var(--co-accent) 4px 6px
  );
}

.axis {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--co-text-3);
}

.legend {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 8px;
  font-size: 12px;
  color: var(--co-text-3);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.swatch {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
}

.swatch.is-gap {
  background: var(--co-bg-3);
  box-shadow: inset 0 0 0 1px var(--co-danger);
}

.swatch.is-overlap {
  background: repeating-linear-gradient(
    45deg,
    var(--co-accent-weak) 0 3px,
    var(--co-accent) 3px 5px
  );
}

/* 规则行：手写网格而不是 `el-table`。
   ★ 原因有两个，都不是审美问题：
     1. 每行下面要挂一条**行内错误**（校验器给的就是「第 N 行 + 原因」），
        而 el-table 的插槽结构表达不了「行与行之间再插一段内容」；
     2. 删掉一行之后行号会平移，`el-form` 那种 `prop="rules.0.startTime"`
        的路径式校验会跟着错位 —— 而这里的错误来自共享的 24 小时校验器，
        它是唯一的裁判，不需要第二套字段级规则。 */
.rule-grid {
  margin-top: 10px;
}

.rule-row {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr) minmax(0, 1.2fr) minmax(0, 1fr) minmax(0, 1fr) 46px;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
}

.rule-row + .rule-row {
  border-top: 1px solid var(--co-border-light);
}

.rule-row-head {
  padding: 0 0 6px;
  border-bottom: 1px solid var(--co-border-light);
  font-size: 12.5px;
  color: var(--co-text-3);
}

.rule-row-head .is-num {
  text-align: right;
}

.rule-row.is-bad .el-input,
.rule-row.is-bad .el-select {
  /* 只描边不上底色：一屏红底会让「第几行有问题」变得难找 */
  --el-input-border-color: var(--co-danger);
}

.row-issue {
  margin: 0 0 6px 108px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-danger-strong);
}

.rule-issues {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-card);
}

.issue {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.8;
}

.issue + .issue {
  margin-top: 4px;
}

.issue.is-error {
  color: var(--co-danger-strong);
}

.issue.is-warn {
  color: var(--co-warning-strong);
}

/* ── 引导弹窗 ─────────────────────────────────────────────────────────── */

.guide-lead {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--co-text-2);
}

.guide-lead code {
  padding: 2px 6px;
  border-radius: var(--co-radius-sm);
  background: var(--co-danger-weak);
  color: var(--co-danger-strong);
  font-size: 12.5px;
}

.guide-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.85;
  color: var(--co-text-2);
}

.guide-list li + li {
  margin-top: 6px;
}

.guide-list code {
  padding: 1px 5px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-2);
  font-variant-numeric: tabular-nums;
}

.guide-list strong {
  color: var(--co-text-1);
  font-weight: 600;
}

/* ── 表单说明 ─────────────────────────────────────────────────────────── */

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

.form-notes strong,
.form-notes b {
  color: var(--co-text-2);
  font-weight: 600;
}
</style>
