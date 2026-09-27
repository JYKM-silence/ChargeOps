<script setup lang="ts">
/**
 * 站点详情 —— 一个**组装页**：它自己不拥有任何数据，只是把三处已经存在的接口
 * 拼在一个站点上展示：
 *
 *     GET /api/admin/station/{id}                       站点本身
 *     GET /api/admin/charger/page?stationId={id}        这个站点下的桩
 *     GET /api/admin/station-tariff/by-station/{id}     这个站点的计费绑定（调价沿革）
 *
 * 站点 id 来自路由 `:id`（`router/index.ts` 里 `station/:id` → `name: 'station-detail'`）。
 *
 * ★★ 三条必须写在页面上的事实（不能只写在代码注释里）★★
 *
 *   1. **没有乐观锁**。站点和充电桩都**没有 `version` 字段**（全项目只有充电枪有），
 *      所以编辑时唯一能做的是「客户端伪乐观锁」：打开表单时记下服务端的
 *      `updateTime`，提交前重读一次详情比对。它**挡不住真正的并发** ——
 *      校验和写入之间仍然有窗口，两个人同时改同一个字段时，后写的照样覆盖先写的。
 *      要彻底解决必须给表加 `version` 列（DDL 变更）。页面上的小字说明见
 *      `.caveat` 那一段 —— 不写出来的话，用户会把「没提示冲突」理解成「没冲突」。
 *
 *   2. **`UpdateDTO` 的语义是「不传 = 不修改」**，不是「传空 = 清空」。
 *      所以编辑表单里**没有任何「清空」按钮**：删掉运营商/地址再保存，
 *      后端那一列动都不会动，而用户以为自己清掉了。
 *      提交走 `diffUpdate` 的增量，只发改动过的键。
 *
 *   3. **计费绑定这一页只读**。新增/编辑绑定在「站点计费绑定」页做 ——
 *      因为新建绑定要同时选站点、选模板（`/list-enabled` 那批）、填生效区间，
 *      还要用**该站点的全部绑定**去查「同一生效时刻是否重复」（唯一索引
 *      `uk_station_tariff_start`）。那套逻辑已经在那边了，在这里再实现一份
 *      等于把那条唯一性预检做成两个真源。本页给出入口和现状，不给表单。
 *
 * ★ 表单为什么是「抄一遍 StationListView 的那一份」：本任务只允许改这三个文件，
 *   没法把它抽成共用组件。为了把两份的差异压到最小，字段、校验文案、
 *   `MAX_LEN` 限制都与列表页逐条对齐 —— 将来抽组件时可以直接对折。
 */

import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  CHARGER_STATUS,
  CHARGER_TYPE,
  MAX_LEN,
  STATION_STATUS,
  diffUpdate,
  formatServerTime,
  invalidateOptionsByTag,
  isCode,
  isTimeHM,
  isTimeHMEnd,
  type ChargerDTO,
  type ChargerPageQuery,
  type ChargerVO,
  type StationTariffVO,
  type StationUpdateDTO,
  type StationVO,
} from '@chargeops/shared'
import CoIcon from '@/components/CoIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import FilterBar from '@/components/FilterBar.vue'
import MoneyText from '@/components/MoneyText.vue'
import PagePanel from '@/components/PagePanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import { TAG } from '@/api/options'
import { createCharger, pageChargers } from '@/api/charger'
import { getStation, updateStation } from '@/api/station'
import { listStationTariffs } from '@/api/tariff'
import { usePagedList } from '@/composables/usePagedList'
import { useSubmitLock } from '@/composables/useSubmitLock'

const route = useRoute()
const router = useRouter()

/**
 * 路由里的站点 id。
 *
 * ★ 三处防御都是必要的：`route.params.id` 的类型是 `string | string[]`（重复参数
 *   会变成数组），而 `Number('')` 是 0、`Number(undefined)` 是 NaN ——
 *   直接把参数当 id 用，「路由写错了」会静默变成「查站点 0 / NaN」，
 *   然后得到一个空详情页而不是一句明确的错误。
 */
const stationId = computed<number | null>(() => {
  const raw = route.params.id
  const text = Array.isArray(raw) ? raw[0] : raw
  const n = Number(text)
  return Number.isInteger(n) && n > 0 ? n : null
})

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

/** `HH:mm:ss` → `HH:mm`。列表里秒没有信息量，而 `24:00:00` 要保留成 `24:00`。 */
function hm(t: string | null | undefined): string {
  return t ? t.slice(0, 5) : '-'
}

/** 营业时间。两个字段一起看才有意义，所以合成一句而不是拆成两项。 */
function businessHours(row: StationVO | null): string {
  if (!row) return '-'
  if (!row.openTime && !row.closeTime) return '未设置'
  if (hm(row.openTime) === '00:00' && hm(row.closeTime) === '24:00') return '全天 24 小时'
  return `${hm(row.openTime)} ~ ${hm(row.closeTime)}`
}

/** 省市区拼成一行。三段都可能为空，所以要丢掉空段而不是留下 ` -  - `。 */
function regionText(row: StationVO | null): string {
  if (!row) return ''
  return [row.province, row.city, row.district].filter((s) => s && s.trim() !== '').join(' ')
}

function timeText(v: string | null | undefined): string {
  return formatServerTime(v) ?? '-'
}

// ── 站点本身 ───────────────────────────────────────────────────────────────

const station = ref<StationVO | null>(null)
const stationLoading = ref(false)
const stationLoaded = ref(false)
const stationError = ref<unknown>(null)

async function loadStation(): Promise<void> {
  const id = stationId.value
  stationError.value = null

  if (id === null) {
    station.value = null
    stationError.value = new Error('地址栏里的站点 id 不合法（应为正整数）。')
    stationLoaded.value = true
    return
  }

  stationLoading.value = true
  try {
    const data = await getStation(id)
    station.value = data
    // ★ `null` 不是异常，但也不是好事：它表示信封里没有 data。
    //   不翻成一句明确的话，页面就会显示一张全是 `-` 的空卡，看起来像后端返回了空数据。
    if (!data) stationError.value = new Error('这个站点不存在，可能已经被删除。')
  } catch (e) {
    stationError.value = e
    station.value = null
  } finally {
    stationLoading.value = false
    stationLoaded.value = true
  }
}

// ── Tab：充电桩 ────────────────────────────────────────────────────────────

const activeTab = ref<'charger' | 'tariff'>('charger')

const DEFAULT_PAGE_SIZE = 10

const {
  rows: chargers,
  total: chargerTotal,
  pageNum: chargerPage,
  pageSize: chargerPageSize,
  loading: chargersLoading,
  loaded: chargersLoaded,
  error: chargersError,
  filters: chargerFilters,
  search: searchChargers,
  reset: resetChargers,
  reload: reloadChargers,
  goPage: goChargerPage,
  dirty: chargerDirty,
} = usePagedList<ChargerVO, ChargerPageQuery>({
  fetch: async (q) => {
    const id = stationId.value
    const size = q.pageSize ?? DEFAULT_PAGE_SIZE
    // 路由 id 不合法 → 一个请求都不发（理由同 stationId 的注释）
    if (id === null) {
      return { records: [], total: 0, pageNum: 1, pageSize: size, hasNext: false }
    }
    // ★ `stationId` 由**路由**决定，不从筛选条件里取：这一页的桩列表被钉死在
    //   这个站点上（它是「站点详情」，没有「换站点」的概念 —— 换站点就是换页面）。
    return pageChargers({ ...q, stationId: id })
  },
  initial: { deviceNo: '', type: undefined, status: undefined },
  pageSize: DEFAULT_PAGE_SIZE,
})

const hasChargerFilter = computed(() =>
  [chargerFilters.deviceNo, chargerFilters.type, chargerFilters.status].some(
    (v) => v !== undefined && v !== null && String(v).trim() !== '',
  ),
)

/**
 * ★★ 决策：新增充电桩做成**就地弹窗**，而不是跳到 `/charger` ★★
 *
 *   1. 站点 id 在这一页是**已知的**（路由参数）。`/charger` 的新建表单里
 *      「所属站点」要用户再选一次 —— 我们手里已经有一个确定的答案，
 *      让用户重选一遍等于把这个答案丢掉再让他猜。
 *   2. `/charger` 的站点筛选来自页面内的下拉，**不读 URL 参数**（当前的路由约定
 *      没有 query 支持），所以「跳过去并自动筛上这个站点」做不到 ——
 *      跳过去只会看到一个全平台列表。
 *   3. 新建之后要接着做的事（给这根桩加充电枪）本来也不在 `/charger` 上，
 *      所以跳过去一步都省不掉。
 *
 *   ★ 代价要说清楚：这个弹窗和 `/charger` 的新建表单是**两份**（本任务只允许
 *     改这三个文件，抽不出共用组件）。所以这里只做**新增**这一个动作，
 *     字段与校验跟那边逐条对齐；**编辑/删除仍然只在 `/charger` 里做** ——
 *     一个动作两份实现是可控的，一套 CRUD 两份实现必然会分叉。
 */
type ChargerForm = {
  deviceNo: string
  name: string
  type: number
  model: string
  ratedPowerKw: string
}

function emptyChargerForm(): ChargerForm {
  return { deviceNo: '', name: '', type: 1, model: '', ratedPowerKw: '' }
}

const chargerDialogOpen = ref(false)
const chargerFormRef = ref<FormInstance>()
const chargerForm = reactive<ChargerForm>(emptyChargerForm())
const { pending: chargerSubmitting, run: runChargerSubmit } = useSubmitLock({ throttleMs: 400 })

function openChargerCreate(): void {
  Object.assign(chargerForm, emptyChargerForm())
  chargerDialogOpen.value = true
  void nextTick(() => chargerFormRef.value?.clearValidate())
}

function onChargerDialogClosed(): void {
  chargerFormRef.value?.clearValidate()
}

function formatRule(check: (v: string) => boolean, message: string) {
  return {
    validator: (_rule: unknown, value: string, callback: (err?: Error) => void): void => {
      if (!value) return callback()
      callback(check(value) ? undefined : new Error(message))
    },
    trigger: 'blur' as const,
  }
}

function orUndefined(v: string): string | undefined {
  const t = v.trim()
  return t === '' ? undefined : t
}

/** 规则逐条对应 `ChargerDTO` 上的注解（松紧都要和 `/charger` 一致）。 */
const chargerRules: FormRules = {
  deviceNo: [
    { required: true, message: '请输入设备编号', trigger: 'blur' },
    { max: MAX_LEN.deviceNo, message: `设备编号不能超过 ${MAX_LEN.deviceNo} 个字符`, trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入充电桩名称', trigger: 'blur' },
    { max: MAX_LEN.chargerName, message: `名称不能超过 ${MAX_LEN.chargerName} 个字符`, trigger: 'blur' },
  ],
  // ★ 额定功率必须 > 0（后端 `@DecimalMin(inclusive = false)`）且**不能为空**：
  //   `charger.rated_power_kw` 是 NOT NULL 且库无默认值，不传拿到的是 500 而不是 400。
  ratedPowerKw: [
    { required: true, message: '请输入额定功率', trigger: 'blur' },
    formatRule((v) => Number.isFinite(Number(v)) && Number(v) > 0, '额定功率必须大于 0'),
  ],
}

async function onSubmitCharger(): Promise<void> {
  const valid = await chargerFormRef.value?.validate().catch(() => false)
  if (!valid) return

  const outcome = await runChargerSubmit(async () => {
    const id = stationId.value
    if (id === null) return 'failed' as const
    const dto: ChargerDTO = {
      stationId: id,
      deviceNo: chargerForm.deviceNo.trim(),
      name: chargerForm.name.trim(),
      type: chargerForm.type,
      model: orUndefined(chargerForm.model),
      ratedPowerKw: chargerForm.ratedPowerKw,
      // ★ 刻意不传 `lastHeartbeatTime`：管理端能设它就等于能伪造一条
      //   「刚刚还在线」的记录，而运维恰恰靠这个字段判断桩是不是掉线了。
      //   （理由与 `/charger` 那边逐字一致。）
    }
    await createCharger(dto)
    return 'saved' as const
  })

  if (outcome.status === 'skipped' || outcome.status === 'failed') return

  chargerDialogOpen.value = false
  ElMessage({
    type: 'success',
    message: '已新增充电桩。它还只有 0 把枪 —— 要到「充电枪」里加一把枪，这个桩才能开始充电。',
    duration: 6000,
    showClose: true,
  })
  // 桩的选项缓存过期（新建的桩要立刻能在别处的下拉里选到）
  invalidateOptionsByTag(TAG.charger, TAG.connector)
  // ★ 用 reset 而不是 reload：新桩必须**可见**。如果用户当前正好开着
  //   「设备编号」筛选（或停在第 3 页），reload 之后新桩不在结果里，
  //   他只会看到「保存成功了，但列表里没有」—— 然后以为没建上。
  await resetChargers()
}

// ── Tab：计费绑定（只读）──────────────────────────────────────────────────

const bindings = ref<StationTariffVO[]>([])
const bindingsLoading = ref(false)
const bindingsLoaded = ref(false)
const bindingsError = ref<unknown>(null)

async function loadBindings(): Promise<void> {
  const id = stationId.value
  if (id === null) {
    bindings.value = []
    bindingsLoaded.value = true
    return
  }

  bindingsLoading.value = true
  bindingsError.value = null
  try {
    // 后端按生效时刻倒序返回 —— 也就是这个站点的**调价沿革**（含已停用/已过期的）
    bindings.value = await listStationTariffs(id)
  } catch (e) {
    bindingsError.value = e
    // 失败时清空：留着上一次的结果会让人以为「查询成功了，只是没变」
    bindings.value = []
  } finally {
    bindingsLoading.value = false
    bindingsLoaded.value = true
  }
}

/**
 * 切到「计费绑定」时才加载。
 *
 * ★ 两个 tab 的数据来自完全不同的接口，进页面就都拉一遍的话，
 *   「只想看一眼这个站点的桩」会顺带打一次它一辈子都用不到的请求。
 *   用一个 `bindingsLoaded` 守卫而不是每次切换都重拉：价格变动频率极低，
 *   而且在别的页面改完之后再回来时，这个 tab 会重新挂载（组件重进）——
 *   真正需要「刷新」的时候，用户手上有的是比「切 tab」更明确的动作。
 */
function onTabChange(name: string | number): void {
  if (name === 'tariff' && !bindingsLoaded.value) void loadBindings()
}

/** 站点的绑定页在另一个页面：这里只给入口，不给表单（理由见文件头第 3 点）。 */
function goBindings(): void {
  // ★ 不拼 `?stationId=` 这种参数：`/station-tariff` 不读 query（路由约定如此），
  //   带上一个被忽略的参数只会让「能不能预选站点」看起来像坏了。
  //   所以文案里明确写「到那边选这个站点」—— 说清楚比假装能省一步好。
  void router.push('/station-tariff')
  ElMessage({
    type: 'info',
    message: `到「站点计费绑定」后请选择站点「${station.value?.name ?? ''}」—— 那一页不支持从地址里预选站点。`,
    duration: 5000,
    showClose: true,
  })
}

// ── 编辑站点（增量提交 + 客户端伪乐观锁）────────────────────────────────────

/** ★ `type` 而不是 `interface`：`diffUpdate` 的约束是 `T extends Record<string, unknown>`。 */
type StationForm = {
  code: string
  name: string
  operator: string
  province: string
  city: string
  district: string
  address: string
  longitude: string
  latitude: string
  servicePhone: string
  openTime: string
  closeTime: string
  parkingFee: string
  status: number
}

function emptyStationForm(): StationForm {
  return {
    code: '',
    name: '',
    operator: '',
    province: '',
    city: '',
    district: '',
    address: '',
    longitude: '',
    latitude: '',
    servicePhone: '',
    openTime: '00:00',
    closeTime: '24:00',
    parkingFee: '',
    status: 1,
  }
}

const dialogOpen = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<StationForm>(emptyStationForm())
const originalForm = ref<StationForm>(emptyStationForm())

/**
 * 打开弹窗那一刻服务端的 `updateTime`。
 *
 * ★★ 这是「客户端伪乐观锁」，**挡不住真并发** ★★
 *   站点没有 `version` 字段（全项目只有充电枪有），所以只能靠「提交前重读详情、
 *   比对 `updateTime`」把覆盖窗口从几分钟压缩到一次往返。校验与写入之间
 *   仍然有窗口 —— 真正的并发依然会有一方被静默覆盖。页面上的小字也这么写。
 */
const openedUpdateTime = ref('')

const { pending: submitting, run: runSubmit } = useSubmitLock({ throttleMs: 400 })

const FIELD_LABEL: Record<string, string> = {
  code: '站点编码',
  name: '站点名称',
  operator: '运营商',
  province: '省',
  city: '市',
  district: '区/县',
  address: '详细地址',
  longitude: '经度',
  latitude: '纬度',
  servicePhone: '服务电话',
  openTime: '开始营业时间',
  closeTime: '结束营业时间',
  parkingFee: '停车费',
  status: '状态',
}

function fillForm(row: StationVO): void {
  form.code = row.code
  form.name = row.name
  form.operator = row.operator ?? ''
  form.province = row.province ?? ''
  form.city = row.city ?? ''
  form.district = row.district ?? ''
  form.address = row.address ?? ''
  // ★ `String()` 而不是 `Number()`：经纬度和金额是 `string | number` 两种形态都可能来，
  //   转成数字再转回去会丢精度（`113.943000` → `113.943`），而这一列是
  //   `decimal(10,6)`，用来在地图上定位的。
  form.longitude = row.longitude === null || row.longitude === undefined ? '' : String(row.longitude)
  form.latitude = row.latitude === null || row.latitude === undefined ? '' : String(row.latitude)
  form.servicePhone = row.servicePhone ?? ''
  form.openTime = row.openTime ?? ''
  form.closeTime = row.closeTime ?? ''
  form.parkingFee = row.parkingFee === null || row.parkingFee === undefined ? '' : String(row.parkingFee)
  form.status = row.status
}

function openEdit(): void {
  const row = station.value
  if (!row) return
  openedUpdateTime.value = row.updateTime
  fillForm(row)
  originalForm.value = { ...form }
  dialogOpen.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

function rangeRule(min: number, max: number, message: string) {
  return formatRule((v) => {
    const n = Number(v)
    return Number.isFinite(n) && n >= min && n <= max
  }, message)
}

/** 规则逐条对应 `StationDTO` / `StationUpdateDTO` 上的注解（和列表页一致）。 */
const rules: FormRules = {
  code: [
    { required: true, message: '请输入站点编码', trigger: 'blur' },
    { max: MAX_LEN.code, message: `编码不能超过 ${MAX_LEN.code} 个字符`, trigger: 'blur' },
    formatRule(isCode, '编码只能是字母、数字和连字符'),
  ],
  name: [
    { required: true, message: '请输入站点名称', trigger: 'blur' },
    { max: MAX_LEN.stationName, message: `名称不能超过 ${MAX_LEN.stationName} 个字符`, trigger: 'blur' },
  ],
  operator: [{ max: MAX_LEN.operator, message: `运营商不能超过 ${MAX_LEN.operator} 个字符`, trigger: 'blur' }],
  province: [{ max: 32, message: '省不能超过 32 个字符', trigger: 'blur' }],
  city: [{ max: 32, message: '市不能超过 32 个字符', trigger: 'blur' }],
  district: [{ max: 32, message: '区/县不能超过 32 个字符', trigger: 'blur' }],
  address: [{ max: MAX_LEN.address, message: `地址不能超过 ${MAX_LEN.address} 个字符`, trigger: 'blur' }],
  longitude: [rangeRule(-180, 180, '经度必须在 -180 ~ 180 之间')],
  latitude: [rangeRule(-90, 90, '纬度必须在 -90 ~ 90 之间')],
  servicePhone: [
    { max: MAX_LEN.servicePhone, message: `电话不能超过 ${MAX_LEN.servicePhone} 个字符`, trigger: 'blur' },
  ],
  // ★ 开始时间**不能**是 24 点（`00:00`~`23:59`），结束时间可以精确到 `24:00:00`
  openTime: [
    { required: true, message: '请输入开始营业时间', trigger: 'blur' },
    formatRule(isTimeHM, '格式应为 HH:mm 或 HH:mm:ss（不能是 24 点）'),
  ],
  closeTime: [
    { required: true, message: '请输入结束营业时间', trigger: 'blur' },
    formatRule(isTimeHMEnd, '格式应为 HH:mm、HH:mm:ss，或 24:00:00'),
  ],
  parkingFee: [rangeRule(0, Number.MAX_SAFE_INTEGER, '停车费不能为负数')],
}

/**
 * 剔除增量里「被清空」的键，并回报字段名。
 *
 * ★ `UpdateDTO` 的语义是「不传 = 不修改」，**不是**「传空 = 清空」：运营商、
 *   地址、经纬度、停车费这些字段**填了就抹不掉**。发空值后端会跳过那一列，
 *   结果是「什么都没发生」，而用户以为自己清掉了。所以这里直接不发，
 *   并把字段名交给调用方去告诉他「已保持原值」。表单里也没有清空按钮。
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

type SubmitResult = 'saved' | 'nochange' | 'stale' | 'failed'

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const outcome = await runSubmit<SubmitResult>(async () => {
    const id = stationId.value
    // 路由 id 不合法（不可能走到这里：按钮在 `station` 为 null 时就是禁用的）
    if (id === null) return 'failed'

    // ★ 伪乐观锁：提交前重读一次详情。读不到（`null`）就直接往下走 ——
    //   真有问题让后端去报，不要在客户端编一个理由。
    const fresh = await getStation(id)
    if (fresh && fresh.updateTime !== openedUpdateTime.value) return 'stale'

    const { payload, unsettable } = diffUpdate(originalForm.value, form)
    const blocked = [...unsettable, ...dropCleared(payload)]

    if (blocked.length > 0) {
      // 提示但不中断：其余改动照常提交，用户不用为了一个清不掉的字段重填一遍
      ElMessage({
        type: 'warning',
        message: `这些字段填过之后无法清空，已保持原值：${blocked
          .map((k) => FIELD_LABEL[k] ?? k)
          .join('、')}`,
        duration: 5000,
        showClose: true,
      })
    }

    // 一个键都没改 → 不打这次空 PUT（后端会刷新 update_time，审计里凭空多一条记录）
    if (Object.keys(payload).length === 0) return 'nochange'

    await updateStation(id, payload as StationUpdateDTO)
    return 'saved'
  })

  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') return // 全局 onError 已弹过后端原文
  if (outcome.value === 'failed') return // 路由 id 不合法，没发请求

  if (outcome.value === 'stale') {
    ElMessage({
      type: 'warning',
      message: '这个站点在编辑期间被其他人改过。请关闭弹窗重新打开，确认最新内容后再提交。',
      duration: 5000,
      showClose: true,
    })
    return
  }

  dialogOpen.value = false

  if (outcome.value === 'nochange') {
    ElMessage({ type: 'info', message: '没有改动，未提交', duration: 1600 })
    return
  }

  ElMessage({ type: 'success', message: '已保存', duration: 1800 })
  // ★ 站点改名后，桩列表、订单页里显示站名的地方都会变，所以连 'charger' 一起失效
  invalidateOptionsByTag(TAG.station, TAG.charger)
  // 顶部的信息卡就是这份数据，改完必须重拉
  await loadStation()
}

function onDialogClosed(): void {
  formRef.value?.clearValidate()
}

// ── 生命周期 ───────────────────────────────────────────────────────────────

// 首次进入：站点详情立刻拉（它就是这一页的标题）。
// ★ 桩列表**不在这里拉** —— `usePagedList` 自己已经 `immediate` 拉过一次了，
//   再走一遍 watch 的立刻执行会变成同一次查询发两个请求。
void loadStation()

/**
 * 路由参数变化时重新加载。
 *
 * ★ 必须显式监听：`/station/1` → `/station/2` 是**同一个组件**，
 *   vue-router 会复用它而不会重新挂载 —— 没有这个 watch 的话，
 *   从一个详情页跳到另一个详情页会看到上一个站点的数据（连标题都是）。
 *
 * ★ 也不能写成 `{ immediate: true }`：那样首次挂载会和
 *   `usePagedList` 的自动加载打成一个重复请求（理由同上）。
 */
watch(stationId, () => {
  void loadStation()
  // 桩列表被钉在这个 id 上，所以整表重拉（顺带清掉上一个站点的本地筛选）
  void resetChargers()
  // 绑定列表标记为「未加载」，让用户切过去时按新 id 重新拉一次
  bindings.value = []
  bindingsLoaded.value = false
  bindingsError.value = null
  if (activeTab.value === 'tariff') void loadBindings()
})
</script>

<template>
  <div class="page">
    <!-- ── 站点信息卡 ─────────────────────────────────────────────────── -->
    <PagePanel
      :title="station ? station.name : '站点详情'"
      :subtitle="station ? `${station.code}${regionText(station) ? ' · ' + regionText(station) : ''}` : undefined"
    >
      <template #actions>
        <el-button :disabled="!station" @click="router.push('/station')">返回列表</el-button>
        <el-button type="primary" :disabled="!station" @click="openEdit">
          <CoIcon name="edit" :size="15" />
          编辑站点
        </el-button>
      </template>

      <div v-loading="stationLoading" class="info-wrap">
        <EmptyState
          v-if="stationLoaded && stationError"
          kind="error"
          :description="errorText(stationError)"
          @retry="loadStation"
        />

        <template v-else-if="station">
          <div class="info-grid">
            <div class="info-item">
              <span class="info-label">状态</span>
              <span class="info-value">
                <StatusTag :label="station.statusLabel" :tone="STATION_STATUS.tone(station.status)" size="sm" />
              </span>
            </div>
            <div class="info-item">
              <span class="info-label">运营商</span>
              <span class="info-value">{{ station.operator || '-' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">服务电话</span>
              <span class="info-value co-num">{{ station.servicePhone || '-' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">营业时间</span>
              <span class="info-value co-num">{{ businessHours(station) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">停车费（元/小时）</span>
              <span class="info-value co-num">
                <!-- ★ 不进数字运算：`MoneyText` 内部是 BigInt 缩放 -->
                <MoneyText :value="station.parkingFee" size="sm" :grouping="false" />
              </span>
            </div>
            <div class="info-item">
              <span class="info-label">经纬度</span>
              <span class="info-value co-num">
                {{ station.longitude ?? '-' }}, {{ station.latitude ?? '-' }}
              </span>
            </div>
            <div class="info-item is-wide">
              <span class="info-label">详细地址</span>
              <span class="info-value">{{ [regionText(station), station.address].filter(Boolean).join(' ') || '-' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">创建时间</span>
              <span class="info-value co-num">{{ timeText(station.createTime) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">最近修改</span>
              <span class="info-value co-num">{{ timeText(station.updateTime) }}</span>
            </div>
          </div>

          <!-- ★★ 这段小字是必须的，不是免责声明 ★★
               站点和桩都没有 version 字段，编辑时的并发只能靠客户端比对
               `updateTime`，它挡不住真正的并发。不写出来的话，用户会把
               「没人提示冲突」理解成「没有冲突」—— 而实际上他可能刚刚
               静默覆盖了同事的改动。 -->
          <p class="caveat">
            本站点<strong>没有版本号（乐观锁）</strong>：编辑时的「数据已被他人修改」提示只是
            <strong>客户端比对</strong>（打开表单那刻的 <code>updateTime</code> 与提交前重读的值）。
            它把覆盖窗口从几分钟压缩到一次请求往返，但<strong>挡不住真正的并发</strong> ——
            两个人同时改同一个字段时，后提交的仍然会覆盖先提交的。要紧的改动请改完刷新确认。
          </p>
        </template>
      </div>
    </PagePanel>

    <!-- ── 桩 / 计费绑定 ──────────────────────────────────────────────── -->
    <PagePanel flush>
      <el-tabs v-model="activeTab" class="detail-tabs" @tab-change="onTabChange">
        <el-tab-pane label="充电桩" name="charger">
          <div class="tab-actions">
            <el-button type="primary" size="small" :disabled="stationId === null" @click="openChargerCreate">
              <CoIcon name="plus" :size="14" />
              新增充电桩
            </el-button>
            <span class="tab-hint">
              这里只做「新增」；<strong>编辑 / 删除 / 加充电枪</strong>请到
              <el-button link type="primary" size="small" @click="router.push('/charger')">
                「充电桩管理」
              </el-button>
              里操作（那里不会把这个站点钉住，但可以按站点筛）。
            </span>
          </div>

          <FilterBar
            :dirty="chargerDirty"
            :loading="chargersLoading"
            @search="searchChargers"
            @reset="resetChargers"
          >
            <!-- 一个站点可能有几十根桩，按设备编号找一根是最常见的动作 -->
            <el-input
              v-model="chargerFilters.deviceNo"
              placeholder="设备编号（前缀）"
              clearable
              :maxlength="MAX_LEN.deviceNo"
              @keyup.enter="searchChargers"
            />
            <el-select v-model="chargerFilters.type" placeholder="类型" clearable>
              <el-option v-for="o in CHARGER_TYPE.options()" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-select v-model="chargerFilters.status" placeholder="状态" clearable>
              <el-option v-for="o in CHARGER_STATUS.options()" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </FilterBar>

          <el-table v-loading="chargersLoading" :data="chargers" row-key="id" size="default">
            <el-table-column prop="deviceNo" label="设备编号" width="160" show-overflow-tooltip />
            <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <StatusTag :label="row.typeLabel" :tone="CHARGER_TYPE.tone(row.type)" size="sm" />
              </template>
            </el-table-column>
            <el-table-column label="额定功率" width="120" align="right" class-name="co-num">
              <template #default="{ row }">
                <MoneyText :value="row.ratedPowerKw" size="sm" :grouping="false" /> kW
              </template>
            </el-table-column>
            <el-table-column label="枪数" width="80" align="right" class-name="co-num">
              <template #default="{ row }">{{ row.gunCount }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <!-- 桩的状态没有白名单（0~4 都可能是管理端设的），所以只按字典上色 -->
                <StatusTag :label="row.statusLabel" :tone="CHARGER_STATUS.tone(row.status)" size="sm" />
              </template>
            </el-table-column>
            <el-table-column label="固件版本" width="110">
              <template #default="{ row }">
                <span :class="{ muted: !row.firmwareVersion }" class="co-num">
                  {{ row.firmwareVersion || '-' }}
                </span>
              </template>
            </el-table-column>

            <!-- 空态三态分开：真的没有 / 被筛掉了 / 加载失败 -->
            <template #empty>
              <EmptyState
                v-if="chargersLoaded && chargersError"
                kind="error"
                :description="errorText(chargersError)"
                @retry="reloadChargers"
              />
              <EmptyState
                v-else-if="chargersLoaded && hasChargerFilter"
                kind="filtered"
                @retry="resetChargers"
              />
              <EmptyState
                v-else-if="chargersLoaded"
                kind="empty"
                title="这个站点下还没有充电桩"
                description="点右上角的「新增充电桩」建一根；建完还要到「充电枪」里给它加一把枪，它才能开始充电。"
              />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              :current-page="chargerPage"
              :page-size="chargerPageSize"
              :total="chargerTotal"
              :pager-count="7"
              layout="total, prev, pager, next"
              background
              @current-change="goChargerPage"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="计费绑定" name="tariff">
          <div class="tab-actions">
            <el-button type="primary" size="small" :disabled="stationId === null" @click="goBindings">
              <CoIcon name="link" :size="14" />
              去绑定
            </el-button>
            <span class="tab-hint">
              这里<strong>只读展示</strong>这个站点用过的计费模板（含已停用/已过期的旧绑定，就是它的调价沿革）。
              新增或修改绑定请到「站点计费绑定」页 —— 那边会用这个站点的全部绑定去查「同一生效时刻是否重复」。
            </span>
          </div>

          <el-table v-loading="bindingsLoading" :data="bindings" row-key="id" size="default">
            <el-table-column label="计费模板" min-width="200">
              <template #default="{ row }">
                <span class="tpl-name">{{ row.templateName ?? `模板 #${row.templateId}` }}</span>
                <span v-if="row.templateCode" class="tpl-code co-num">{{ row.templateCode }}</span>
              </template>
            </el-table-column>
            <el-table-column label="生效开始" width="170">
              <template #default="{ row }">
                <span class="co-num">{{ timeText(row.effectiveFrom) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="生效结束" width="170">
              <template #default="{ row }">
                <!-- 空 = 长期有效，这是一个明确的业务状态，不是「数据丢了」 -->
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
                <StatusTag
                  :label="row.statusLabel"
                  :tone="row.status === 1 ? 'success' : 'info'"
                  size="sm"
                />
              </template>
            </el-table-column>

            <template #empty>
              <EmptyState
                v-if="bindingsLoaded && bindingsError"
                kind="error"
                :description="errorText(bindingsError)"
                @retry="loadBindings"
              />
              <EmptyState
                v-else-if="bindingsLoaded"
                kind="empty"
                title="这个站点还没有绑定计费方案"
                description="没有生效的绑定时，该站点不能开始充电 —— 后端会明确拒绝，而不是回退到某个默认价格（一个「找不到就用默认价」的兜底会把配置缺失变成一份错误的账单）。点上面的「去绑定」配一条。"
              />
            </template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </PagePanel>

    <!-- ── 新增充电桩 ─────────────────────────────────────────────────── -->
    <el-dialog
      v-model="chargerDialogOpen"
      :title="`新增充电桩 · ${station?.name ?? ''}`"
      width="640px"
      :close-on-click-modal="false"
      :close-on-press-escape="!chargerSubmitting"
      :show-close="!chargerSubmitting"
      @closed="onChargerDialogClosed"
    >
      <el-form ref="chargerFormRef" :model="chargerForm" :rules="chargerRules" label-position="top">
        <!-- ★ 站点是**只读**的：它就是这一页的路由参数，没什么可选的 -->
        <el-form-item label="所属站点">
          <el-input :model-value="station?.name ?? ''" disabled />
        </el-form-item>

        <div class="grid-2">
          <el-form-item label="设备编号" prop="deviceNo">
            <el-input
              v-model="chargerForm.deviceNo"
              placeholder="如 CP-440300-0001"
              :maxlength="MAX_LEN.deviceNo"
              :disabled="chargerSubmitting"
            />
          </el-form-item>
          <el-form-item label="充电桩名称" prop="name">
            <el-input
              v-model="chargerForm.name"
              placeholder="如 A 区 01 号桩"
              :maxlength="MAX_LEN.chargerName"
              :disabled="chargerSubmitting"
            />
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="类型" prop="type">
            <el-radio-group v-model="chargerForm.type" :disabled="chargerSubmitting">
              <el-radio-button v-for="o in CHARGER_TYPE.options()" :key="o.value" :value="o.value">
                {{ o.label }}
              </el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="额定功率（kW）" prop="ratedPowerKw">
            <el-input
              v-model="chargerForm.ratedPowerKw"
              placeholder="如 120"
              :disabled="chargerSubmitting"
            />
          </el-form-item>
        </div>

        <el-form-item label="型号" prop="model">
          <el-input
            v-model="chargerForm.model"
            placeholder="选填"
            :maxlength="MAX_LEN.model"
            :disabled="chargerSubmitting"
          />
        </el-form-item>

        <ul class="form-notes">
          <li>
            新建的桩状态是「离线」，枪数是 0：它还没和设备建立过连接，说它在线需要一个
            从未发生过的心跳。要让它能充电，建完还得去「充电枪」里加一把枪。
          </li>
          <li>枪数由充电枪的数量决定（冗余列），不在这里维护。</li>
          <li>
            「最后心跳时间」不由管理端填写：它是设备上报的事实，手工改会让
            「这台桩是不是掉线了」失去依据。
          </li>
          <li>
            桩建好后<strong>不能换站</strong>（`ChargerUpdateDTO` 里没有站点字段）——
            换站会让它下面每把枪的站点归属失真。要挪站只能删掉重建。
          </li>
        </ul>
      </el-form>

      <template #footer>
        <el-button :disabled="chargerSubmitting" @click="chargerDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="chargerSubmitting" @click="onSubmitCharger">创建</el-button>
      </template>
    </el-dialog>

    <!-- ── 编辑站点 ───────────────────────────────────────────────────── -->
    <el-dialog
      v-model="dialogOpen"
      :title="`编辑站点「${station?.name ?? ''}」`"
      width="720px"
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      @closed="onDialogClosed"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="grid-2">
          <el-form-item label="站点编码" prop="code">
            <el-input
              v-model="form.code"
              placeholder="如 ST-440300-0001"
              :maxlength="MAX_LEN.code"
              :disabled="submitting"
            />
          </el-form-item>
          <el-form-item label="站点名称" prop="name">
            <el-input
              v-model="form.name"
              placeholder="如 科技园充电站"
              :maxlength="MAX_LEN.stationName"
              :disabled="submitting"
            />
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="运营商" prop="operator">
            <el-input v-model="form.operator" placeholder="选填" :maxlength="MAX_LEN.operator" :disabled="submitting" />
          </el-form-item>
          <el-form-item label="服务电话" prop="servicePhone">
            <el-input
              v-model="form.servicePhone"
              placeholder="选填"
              :maxlength="MAX_LEN.servicePhone"
              :disabled="submitting"
            />
          </el-form-item>
        </div>

        <div class="grid-3">
          <el-form-item label="省" prop="province">
            <el-input v-model="form.province" placeholder="选填" :maxlength="32" :disabled="submitting" />
          </el-form-item>
          <el-form-item label="市" prop="city">
            <el-input v-model="form.city" placeholder="选填" :maxlength="32" :disabled="submitting" />
          </el-form-item>
          <el-form-item label="区/县" prop="district">
            <el-input v-model="form.district" placeholder="选填" :maxlength="32" :disabled="submitting" />
          </el-form-item>
        </div>

        <el-form-item label="详细地址" prop="address">
          <el-input
            v-model="form.address"
            placeholder="选填，如 科技园南路 1 号"
            :maxlength="MAX_LEN.address"
            :disabled="submitting"
          />
        </el-form-item>

        <div class="grid-3">
          <el-form-item label="经度" prop="longitude">
            <el-input v-model="form.longitude" placeholder="如 113.943000" :disabled="submitting" />
          </el-form-item>
          <el-form-item label="纬度" prop="latitude">
            <el-input v-model="form.latitude" placeholder="如 22.528000" :disabled="submitting" />
          </el-form-item>
          <el-form-item label="停车费（元/小时）" prop="parkingFee">
            <el-input v-model="form.parkingFee" placeholder="0 表示免费" :disabled="submitting" />
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="开始营业时间" prop="openTime">
            <el-input v-model="form.openTime" placeholder="HH:mm 或 HH:mm:ss" :disabled="submitting" />
          </el-form-item>
          <el-form-item label="结束营业时间（不含）" prop="closeTime">
            <el-input v-model="form.closeTime" placeholder="HH:mm 或 24:00:00" :disabled="submitting" />
          </el-form-item>
        </div>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status" :disabled="submitting">
            <el-radio-button v-for="o in STATION_STATUS.options()" :key="o.value" :value="o.value">
              {{ o.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <ul class="form-notes">
          <li>
            营业时间用 <code>HH:mm</code> 格式。结束时间可以写 <code>24:00:00</code> 表示「到当天结束」，
            但开始时间不能是 24 点；结束时间必须晚于开始时间，<strong>不支持跨夜营业</strong>。
          </li>
          <li>
            运营商、省/市/区、地址、经纬度、停车费<strong>填过之后无法清空</strong>：后端的
            「不传」表示「不修改」。要改就直接填新值覆盖，表单里没有「清空」按钮。
          </li>
          <li>
            本站点<strong>没有乐观锁</strong>：提交前后的 <code>updateTime</code> 比对只是客户端行为，
            挡不住真正的并发。两个人同时编辑时，后提交的会覆盖先提交的。
          </li>
          <li>经纬度写反是一个经典事故（两个方向仍各自合法，只是落到了地球另一边），提交前请对一眼。</li>
        </ul>
      </el-form>

      <template #footer>
        <el-button :disabled="submitting" @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.grid-2,
.grid-3 {
  display: grid;
  gap: 0 16px;
}

.grid-2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.grid-3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

@media (max-width: 720px) {
  .grid-2,
  .grid-3 {
    grid-template-columns: minmax(0, 1fr);
  }
}

.muted {
  color: var(--co-text-4);
}

/* ── 信息卡 ───────────────────────────────────────────────────────────── */

.info-wrap {
  min-height: 80px;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px 20px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.info-item.is-wide {
  grid-column: 1 / -1;
}

.info-label {
  font-size: 12px;
  color: var(--co-text-4);
}

.info-value {
  font-size: 13.5px;
  color: var(--co-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ★ 这不是免责声明，是用户必须知道的操作约束（见文件头第 1 点） */
.caveat {
  margin: 16px 0 0;
  padding: 10px 12px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-warning-weak);
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--co-text-2);
}

.caveat strong {
  color: var(--co-warning-strong);
  font-weight: 600;
}

.caveat code {
  padding: 1px 5px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-card);
  color: var(--co-text-2);
  font-variant-numeric: tabular-nums;
}

/* ── Tabs ─────────────────────────────────────────────────────────────── */

/* `PagePanel` 传了 flush（表格自带 12px 边距，否则双层留白），
   所以 tab 头部的内边距要在这里自己补回来 */
.detail-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 14px 18px 0;
  border-bottom: 1px solid var(--co-border-light);
}

.tab-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 14px 18px 0;
}

.tab-hint {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--co-text-3);
}

.tab-hint strong {
  color: var(--co-text-2);
  font-weight: 600;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 8px;
}

.tpl-name {
  margin-right: 8px;
  font-weight: 500;
  color: var(--co-text-1);
}

.tpl-code {
  font-size: 12px;
  color: var(--co-text-4);
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
