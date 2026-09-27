<script setup lang="ts">
/**
 * 充电套餐（列表 + 上架下架 + 按类型动态表单）。
 *
 * ★★ 这个页面真正的难点只有一个：**套餐是动态表单** ★★
 *   四种 `packageType` 各自只用到**一个**额度字段（电量 / 时长 / 折扣 / 次数），
 *   而字段名与类型的对应关系**不是**可以现猜的东西 —— 它来自
 *   `api/package.ts` 的 `PACKAGE_QUOTA_FIELD`（与后端 `assertTypeFields` 的
 *   `switch` 一一对应）。所以下面模板里的显隐与校验都从 `quotaFieldOf()` 派生，
 *   不写死「电量包就是 energyKwh」这类判断。
 *
 *   为什么不能把 5 个额度字段全摆出来让运营自己填对：后端**不校验**「你填了
 *   不相关的字段」的那一半虽然会拒绝（方向二），但一旦这行数据落库
 *   （比如绕过界面直接调接口），残留的 `energyKwh = 150` 会被计费逻辑读走
 *   —— 凭空送出 150 度电，每一步都不报错，只有对账时钱对不上。
 *   界面上清空不相关字段，就是为了让这种数据根本长不出来。
 *
 * ★★ `UpdateDTO` 的语义是「不传 = 不修改」★★
 *   由此带来两件必须写进 UI 的事实（都在表单里落了地）：
 *     · `originPrice` / `image` / `description` **设置后无法清空**
 *       （传 null 等于「不修改」），所以表单里**没有「清空」按钮**，
 *       只有一行说明 —— 放一个按不动的按钮比放一行字更让人困惑；
 *     · 编辑提交走 `diffUpdate` 增量，只发真正改过的键，
 *       免得把「没碰过的字段」也写一遍（套餐没有乐观锁，见下面的注释）。
 *
 * ★ 图片上传的三个坑（字段名 `file` / 白名单无 svg / 返回的是**裸 URL 字符串**）
 *   都在 `api/upload.ts` 里收口了，这里只管调 `uploadImage()`，
 *   **不要**去读 `.url` —— 那会得到 `undefined`，表现是「上传成功但图片不显示」，
 *   不报错、不进 catch。
 */

import { computed, nextTick, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  ACCEPT_ATTR,
  AMOUNT_SCALE,
  LIMITS,
  MAX_LEN,
  PACKAGE_TYPE,
  PRICE_SCALE,
  compareDecimal,
  diffUpdate,
  formatServerTime,
  invalidateOptionsByTag,
  labelOf,
  msgStartsWith,
  toScaled,
  type ChargePackageDTO,
  type ChargePackagePageQuery,
  type ChargePackageUpdateDTO,
  type ChargePackageVO,
  type Decimal,
} from '@chargeops/shared'
import CoIcon from '@/components/CoIcon.vue'
import DangerConfirm from '@/components/DangerConfirm.vue'
import EmptyState from '@/components/EmptyState.vue'
import FilterBar from '@/components/FilterBar.vue'
import MoneyText from '@/components/MoneyText.vue'
import PagePanel from '@/components/PagePanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import { TAG, activeStationOptions, categoryOptionsOf, stationOptions } from '@/api/options'
import {
  PACKAGE_TYPE_FIELD_MISMATCH,
  createPackage,
  deletePackage,
  minutesText,
  pagePackages,
  quotaFieldOf,
  quotaTextOf,
  updatePackage,
} from '@/api/package'
import { uploadImage } from '@/api/upload'
import { useOptions } from '@/composables/useOptions'
import { usePagedList } from '@/composables/usePagedList'
import { useSubmitLock } from '@/composables/useSubmitLock'

const route = useRoute()

// ── 列表 ───────────────────────────────────────────────────────────────────

/**
 * 从地址栏带过来的分类预筛选。
 *
 * ★ 这是「分类删除被拒」那条出路的终点：分类页会在拒绝时把用户送到
 *   `/package?categoryId=xxx`，这里必须**真的读**它，否则那个按钮就是假的。
 *   套餐列表本身没有「按分类」的快捷入口，所以这个 query 是唯一来源。
 */
const queryCategoryId =
  typeof route.query.categoryId === 'string' ? Number(route.query.categoryId) : Number.NaN
const initialCategoryId = Number.isFinite(queryCategoryId) ? queryCategoryId : undefined

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
  afterRemove,
} = usePagedList<ChargePackageVO, ChargePackagePageQuery>({
  fetch: (q) => pagePackages(q),
  initial: {
    name: '',
    categoryId: initialCategoryId,
    packageType: undefined,
    status: undefined,
  },
})

/**
 * 分类下拉。
 *
 * ★★ 传 `1`（充电套餐分类），**不是** `null` ★★
 *   后端 `requireCategory` 会拒绝「挂在增值服务分类下的充电套餐」，
 *   所以下拉里出现 type=2 的分类只会让用户白选一次。
 */
const { options: categoryOpts } = useOptions(() => categoryOptionsOf(1))

/** 站点下拉（筛选用，**不过滤停用站点**）：已经绑了停用站点的套餐仍要能筛出来。 */
const { options: filterStationOpts } = useOptions(stationOptions)

/** 站点下拉（表单用，只要启用的站点）。 */
const { options: formStationOpts } = useOptions(activeStationOptions)

/**
 * 是否有实质筛选条件。
 *
 * ★ 输入框清空给的是 `''`、下拉清空给的是 `undefined`（Element Plus 的
 *   `valueOnClear` 默认就是 `undefined`），两种都表示「不过滤」。
 *   三个下拉的初值都取 `undefined`，正是为了让「清空」能把 `dirty` 变回 false
 *   （`重置` 按钮的亮灭就靠它）—— 不用 `''` 是因为那会让清空之后仍然算「改过」。
 */
const hasFilter = computed(() =>
  [filters.name, filters.categoryId, filters.packageType, filters.status].some(
    (v) => v !== undefined && v !== null && String(v).trim() !== '',
  ),
)

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

// ── 套餐状态的本地标签 ──────────────────────────────────────────────────────

/**
 * 上架状态。
 *
 * ★ `dicts.ts` 里**没有**套餐状态的字典（那里有会员态、枪态、桩态…），
 *   后端 `labelOfStatus` 是 1「上架」/ 0「下架」。
 *   文案与后端**逐字一致**，取值只有 0/1，遇到不认识的码不编造第三种状态。
 */
const PACKAGE_STATUS_OPTIONS = [
  { label: '上架', value: 1 },
  { label: '下架', value: 0 },
] as const

/** ★ 优先用后端的 `statusLabel`（有真源就用真源），为 null 时才退回本地表。 */
function statusText(row: ChargePackageVO): string {
  return row.statusLabel ?? (row.status === 1 ? '上架' : '下架')
}

/** 下架不是错误（它还能再上架），所以是 info 而不是 danger。 */
function statusTone(status: number): 'success' | 'info' {
  return status === 1 ? 'success' : 'info'
}

/** 类型标签同理：后端给了 `packageTypeLabel` 就用它。 */
function typeText(row: ChargePackageVO): string {
  return row.packageTypeLabel ?? PACKAGE_TYPE.label(row.packageType)
}

/** 适用站点：`0` 是「全平台通用」这个**具体值**，不是「没选站点」—— 不能显示成「#0」。 */
function stationText(stationId: number | null): string {
  if (stationId === null || stationId === 0) return '全平台通用'
  return labelOf(filterStationOpts.value, stationId)
}

/** 有效期：`0` 是**永久有效**（不是「0 天」）。 */
function validDaysText(validDays: number): string {
  return validDays === 0 ? '永久有效' : `${validDays} 天`
}

// ── 新建 / 编辑弹窗 ────────────────────────────────────────────────────────

/**
 * ★ 用 `type` 而不是 `interface`：`diffUpdate` 的约束是
 *   `T extends Record<string, unknown>`，interface 没有隐式索引签名，会编译不过。
 *
 * ★ 金额一律用**字符串**存在表单里（`price` / `originPrice` / `energyKwh` /
 *   `serviceDiscount`）：后端是 BigDecimal，JSON 里可能是字符串也可能是数字，
 *   用 `number` 承载会经历一次浮点往返。校验走 `money.ts` 的 BigInt 比较，
 *   全程没有 `Number(x) + y` 这种算式。
 */
type PackageForm = {
  name: string
  categoryId: number | undefined
  packageType: number | undefined
  /** ★ `0` = 全平台通用，**不是** null —— 见提交处的注释。 */
  stationId: number
  price: string
  originPrice: string
  energyKwh: string
  durationMinutes: number | undefined
  times: number | undefined
  serviceDiscount: string
  validDays: number
  description: string
  image: string
  status: number
  sort: number
}

function emptyForm(): PackageForm {
  return {
    name: '',
    categoryId: undefined,
    // ★ 和分类页不同，这里**不预选**类型：四类的额度字段不同，
    //   预选一个会让下拉与字段同时出现在用户还没想好的时候。
    //   留空 → 额度字段一项都不显示，直到用户明确选了类型。
    packageType: undefined,
    stationId: 0,
    price: '',
    originPrice: '',
    energyKwh: '',
    durationMinutes: undefined,
    times: undefined,
    serviceDiscount: '',
    validDays: 0,
    description: '',
    image: '',
    status: 1,
    sort: 0,
  }
}

const dialogOpen = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<PackageForm>(emptyForm())
/** 增量提交的基准。必须是**服务端原值**，不是上一次提交的值。 */
const originalForm = ref<PackageForm>(emptyForm())
const editingId = ref<number | null>(null)
/** 后端最近一次拒绝的原文。非空时在弹窗里显示（弹窗**不关**）。 */
const submitError = ref<string | null>(null)

const isEditing = computed(() => editingId.value !== null)

const { pending: submitting, run: runSubmit } = useSubmitLock({ throttleMs: 400 })

const FIELD_LABEL: Record<string, string> = {
  name: '套餐名称',
  categoryId: '套餐分类',
  price: '售价',
  originPrice: '划线价',
  energyKwh: '电量',
  durationMinutes: '时长',
  times: '次数',
  serviceDiscount: '服务费折扣',
  validDays: '有效期',
  description: '套餐说明',
  image: '图片',
  status: '状态',
  sort: '排序',
}

/**
 * 当前类型对应的额度字段。
 *
 * ★★ 唯一真源是 `api/package.ts` 的 `PACKAGE_QUOTA_FIELD` ★★
 *   模板里的显隐、校验规则、字段标签全部从它派生 ——
 *   在任何一处手写「类型 1 就是电量」都会多出第二个真源，
 *   而后端加了第 5 种类型时没人会记得改它。
 */
const quotaField = computed(() =>
  form.packageType === undefined ? null : quotaFieldOf(form.packageType),
)

/**
 * 切换套餐类型。
 *
 * ★★ 必须把**另外三个额度字段清空** ★★
 *   它们此刻可能还留着上一个类型里填过的值（用户先点了「电量包」填 150，
 *   又改成「时长包」）。不清的话后端方向二会拒绝，而更糟的是：
 *   这种数据一旦落库，残留的 `energyKwh` 会被计费逻辑读走。
 *   清空之后，「这次提交会带哪几个字段」在界面上是一目了然的。
 */
function onTypeChange(): void {
  form.energyKwh = ''
  form.durationMinutes = undefined
  form.times = undefined
  form.serviceDiscount = ''
  // 类型变了，表单里那三行校验错误也该跟着消失
  void nextTick(() => formRef.value?.clearValidate(['energyKwh', 'durationMinutes', 'times', 'serviceDiscount']))
}

/** 格式校验：空值放行，有值才交给 `check`（必填由 `required` 那几条管）。 */
function formatRule(check: (v: string) => boolean, message: string) {
  return {
    validator: (_rule: unknown, value: string, callback: (err?: Error) => void): void => {
      if (!value) return callback()
      callback(check(value) ? undefined : new Error(message))
    },
    trigger: 'blur' as const,
  }
}

/** 是否为十进制字面量（挡住 `1.2.3` / `abc` 这类输入，具体范围另判）。 */
function isDecimalText(v: string): boolean {
  return /^[+-]?\d+(\.\d+)?$/.test(v.trim())
}

/**
 * 金额范围校验。
 *
 * ★ 用 `money.ts` 的 BigInt 比较，**不用** `Number(v) > 0`：
 *   浮点在两位小数上不会立刻出错，但同一套写法搬到「求和」「比较相等」上就会，
 *   而这个文件里到处是金额 —— 统一写法比每处单独判断更便宜。
 */
function amountRule(check: (scaled: bigint) => boolean, message: string) {
  return formatRule((v) => isDecimalText(v) && check(toScaled(v, AMOUNT_SCALE)), message)
}

/**
 * 校验规则。
 *
 * ★ 额度字段那一条是**跟着类型动态生成的**：只有当前类型用到的那一项会被要求，
 *   另外三项连 `el-form-item` 都没渲染（v-if），所以它们的规则自然也不会跑。
 *   类型还没选时额度规则整体缺席 —— 那时用户该看到的是「先选类型」而不是
 *   「电量必填」。
 */
const rules = computed<FormRules>(() => {
  const map: FormRules = {
    name: [
      { required: true, message: '请输入套餐名称', trigger: 'blur' },
      { max: MAX_LEN.packageName, message: `名称不能超过 ${MAX_LEN.packageName} 个字符`, trigger: 'blur' },
    ],
    categoryId: [{ required: true, message: '请选择套餐分类', trigger: 'change' }],
    packageType: [{ required: true, message: '请选择套餐类型', trigger: 'change' }],
    // ★ 售价可以是 0（赠送套餐），后端刻意没加 inclusive=false —— 所以只挡负数。
    price: [{ required: true, message: '请输入售价' }, amountRule((n) => n >= 0n, '售价不能为负数')],
    // ★ 划线价可空；填了不能是负数。填 0 会渲染成「¥0.00 划线价 ¥100.00」，
    //   一个看起来很蠢的界面，所以下面字段旁单独提醒（那不是校验错误，是配置错误）。
    originPrice: [amountRule((n) => n >= 0n, '划线价不能为负数')],
    validDays: [],
    description: [
      { max: MAX_LEN.packageDescription, message: `说明不能超过 ${MAX_LEN.packageDescription} 个字符`, trigger: 'blur' },
    ],
    // ★ `MAX_LEN` 里没有 `image` 这个键（它按后端的 DTO:字段 命名），
    //   借 `avatar` 是因为它和 `ChargePackageDTO.image` 恰好是同一条约束
    //   （都是 `@Size(max = 255)` 的图片 URL）—— 这是**借**，不是同一个字段。
    image: [{ max: MAX_LEN.avatar, message: `图片地址不能超过 ${MAX_LEN.avatar} 个字符`, trigger: 'blur' }],
  }

  switch (quotaField.value) {
    case 'energyKwh':
      // 后端是 `@DecimalMin(inclusive = false)`：电量必须**严格大于** 0
      map.energyKwh = [
        { required: true, message: '请输入电量' },
        amountRule((n) => n > 0n, `电量必须大于 ${LIMITS.kwhMinExclusive}`),
      ]
      break
    case 'durationMinutes':
      map.durationMinutes = [{ required: true, message: '请输入时长', trigger: 'change' }]
      break
    case 'serviceDiscount':
      // (0, 1]：0 是「服务费全免」（后端刻意排掉：那该有一个名字说清这件事的类型），
      // 大于 1 是加价而不是折扣。四位小数，和列的 decimal(5,4) 对齐。
      map.serviceDiscount = [
        { required: true, message: '请输入服务费折扣' },
        formatRule(
          (v) =>
            isDecimalText(v) &&
            toScaled(v, PRICE_SCALE) > 0n &&
            compareDecimal(v, LIMITS.discountMax, PRICE_SCALE) <= 0,
          `折扣必须在 0 到 ${LIMITS.discountMax} 之间（不含 0，含 1）`,
        ),
      ]
      break
    case 'times':
      map.times = [{ required: true, message: '请输入次数', trigger: 'change' }]
      break
    default:
      break
  }

  return map
})

const quotaFieldHint: Record<string, string> = {
  energyKwh: '这包能充多少度电，按电量扣减。',
  durationMinutes: '这包能充多久（分钟）。人话换算见右侧预览。',
  serviceDiscount: '0.8 表示八折，1 表示不打折（等价于原价）。四位小数：它要和服务费单价相乘，精度不够会直接变成金额误差。',
  times: '这包能充多少次，按次扣减。',
}

/** 时长的人话预览：`43200` 分钟的包，运营脑子里是「30 天」。 */
const durationPreview = computed(() =>
  form.durationMinutes && form.durationMinutes > 0 ? minutesText(form.durationMinutes) : '—',
)

/**
 * 分类下拉（表单用）：补上「当前值不在启用列表里」的那一项。
 *
 * ★ `/list` 只返回**启用中**的分类，而一个套餐的分类可能已经被停用 ——
 *   那时下拉里没有当前值，`el-select` 会退化成一个裸数字 id。
 *   补成一条选项之后，「它现在挂在一个已停用的分类上」这件事就是看得见的。
 *   （后端明确允许：停用分类不阻止挂在上面的套餐被编辑。）
 */
type SelectItem = { label: string; value: number; disabled?: boolean }

function withCurrent(items: SelectItem[], current: number | undefined): SelectItem[] {
  if (current === undefined || items.some((i) => i.value === current)) return items
  return [{ value: current, label: `#${current}（不在启用分类里，可能已停用）` }, ...items]
}

const formCategoryItems = computed<SelectItem[]>(() =>
  withCurrent(
    categoryOpts.value.map((o) => ({ label: o.label, value: o.value })),
    form.categoryId,
  ),
)

const filterCategoryItems = computed<SelectItem[]>(() =>
  withCurrent(
    categoryOpts.value.map((o) => ({ label: o.label, value: o.value })),
    filters.categoryId,
  ),
)

/**
 * 站点选项。
 *
 * ★★ 第一项是**显式的** `0`，而且默认就选中它 ★★
 *   两个原因：
 *     · `charge_package.station_id` 是 NOT NULL，且库用 `0` 表达「全平台通用」
 *       （不用 NULL 是为了让 `uk_pkg_name_station` 真的生效 —— 唯一索引不约束 NULL，
 *        那会让全平台套餐的名字可以被重复占用）；
 *     · 留空（null）会让后端把显式 null 兜回 0，但界面必须让用户**看见**
 *       自己选了「全平台」，而不是靠一个空值去猜。
 */
const stationItems = computed<SelectItem[]>(() => [
  { value: 0, label: '全平台通用（不限站点）' },
  ...formStationOpts.value.map((o) => ({
    label: o.hint ? `${o.label}（${o.hint}）` : o.label,
    value: o.value,
  })),
])

/**
 * 编辑时只读展示的站点名（`stationId` 不在 `ChargePackageUpdateDTO` 里，改不了）。
 *
 * ★ 查**全部站点**（`filterStationOpts`）而不是表单用的启用列表：
 *   套餐可能挂在一个已停用的站点上，用启用列表查会退化成 `#7`，
 *   而运营需要认出这是哪个站。
 * ★ `api/options.ts` 的注释说 `stationOptions` 会在 label 上标「(已停用)」，
 *   但那段代码并没有写（文档与实现不符，已记在交付说明里）——
 *   所以这里能显示的是**原名**，看不出停用与否。
 */
const editingStationText = computed(() => {
  if (form.stationId === 0) return '全平台通用（不限站点）'
  const hit = filterStationOpts.value.find((o) => o.value === form.stationId)
  return hit ? hit.label : `#${form.stationId}`
})

// ── 图片上传 ───────────────────────────────────────────────────────────────

/**
 * 上传图片。
 *
 * ★ `uploadImage()` 已经把该做的都做了：本地校验（白名单**没有 svg**、
 *   单文件 10MB）、字段名固定 `file`、把返回的**裸 URL 字符串**
 *   归一成同源相对路径。所以这里**不要**去读 `.url`：
 *   那会拿到 `undefined` → 图片不显示、也不报错（见 shared/http/upload.ts 的长注释）。
 *
 * ★ 失败时它返回 `null` 并自己 toast（`el-upload` 的钩子里抛异常会让组件
 *   卡在 loading 上，反而看不到错误），这里只需直接返回。
 *
 * ★ 上传**立刻发生**（文件已经落到服务器上了），但只有再点「保存/创建」
 *   才会把这个 URL 挂到套餐上 —— 这一点写进了字段旁的说明里。
 */
async function doUpload(options: { file: File }): Promise<void> {
  const url = await uploadImage(options.file)
  if (url === null) return
  form.image = url
  ElMessage({ type: 'info', message: '图片已上传，记得点保存把它挂到这个套餐上', duration: 3000 })
}

/** 没有图片时的预览位。 */
const hasImage = computed(() => form.image.trim() !== '')

// ── 打开 / 提交 ────────────────────────────────────────────────────────────

/** 把后端返回的 Decimal 归一成表单里的字符串。 */
function decimalText(v: Decimal): string {
  return v === null || v === undefined ? '' : String(v)
}

function fillForm(row: ChargePackageVO): void {
  form.name = row.name
  form.categoryId = row.categoryId
  form.packageType = row.packageType
  // ★ `stationId` 为 null 时按「全平台」处理：库是 NOT NULL，
  //   但共享类型里它是 `number | null`，兜一下免得下拉出现空值。
  form.stationId = row.stationId ?? 0
  form.price = decimalText(row.price)
  form.originPrice = decimalText(row.originPrice)
  form.energyKwh = decimalText(row.energyKwh)
  form.durationMinutes = row.durationMinutes ?? undefined
  form.times = row.times ?? undefined
  form.serviceDiscount = decimalText(row.serviceDiscount)
  form.validDays = row.validDays
  form.description = row.description ?? ''
  form.image = row.image ?? ''
  form.status = row.status
  form.sort = row.sort
}

function openCreate(): void {
  editingId.value = null
  submitError.value = null
  Object.assign(form, emptyForm())
  originalForm.value = { ...form }
  dialogOpen.value = true
  // el-dialog 的内容是懒渲染的，首次打开时 formRef 还没挂上
  void nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row: ChargePackageVO): void {
  editingId.value = row.id
  submitError.value = null
  fillForm(row)
  originalForm.value = { ...form }
  dialogOpen.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

/**
 * 新建的请求体。
 *
 * ★ 默认值全部**显式给全**（`stationId: 0` / `validDays: 0` / `status` / `sort`）：
 *   后端 insert 是**全列插入**，DTO 的字段初始值挡不住显式的 JSON null，
 *   而 NOT NULL 列拿到 null 就是 500。界面上这些字段都有控件，所以天然是不缺的，
 *   这里只是别让某个字段漏在表单之外。
 */
function toCreateDTO(): ChargePackageDTO {
  const dto: ChargePackageDTO = {
    // ★ 校验已保证非空，这里断言只为消掉 `number | undefined`
    name: form.name.trim(),
    categoryId: form.categoryId as number,
    packageType: form.packageType as number,
    stationId: form.stationId,
    price: form.price.trim(),
    originPrice: orUndefined(form.originPrice),
    validDays: form.validDays,
    description: orUndefined(form.description),
    image: orUndefined(form.image),
    status: form.status,
    sort: form.sort,
  }

  // ★ 额度字段**只带当前类型那一个**，其余三个一个都不传 ——
  //   这是「不留残留值」的第一道闸门（后端方向二会拒绝有残留的请求）。
  switch (quotaField.value) {
    case 'energyKwh':
      dto.energyKwh = form.energyKwh.trim()
      break
    case 'durationMinutes':
      dto.durationMinutes = form.durationMinutes ?? null
      break
    case 'serviceDiscount':
      dto.serviceDiscount = form.serviceDiscount.trim()
      break
    case 'times':
      dto.times = form.times ?? null
      break
    default:
      break
  }

  return dto
}

function orUndefined(v: string): string | undefined {
  const t = v.trim()
  return t === '' ? undefined : t
}

/**
 * 剔除增量里「被清空」的键。
 *
 * ★ `UpdateDTO` 的语义是「不传 = 不修改」，**不是**「传空 = 清空」：
 *   售价、名称这类字段**填了就抹不掉**。发空值要么被后端跳过（用户以为删掉了），
 *   要么在 `@NotBlank` 上变成另一个错。所以直接不发，并回报字段名。
 */
function dropCleared(payload: Partial<PackageForm>): string[] {
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

type SubmitResult = 'saved' | 'nochange'

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const outcome = await runSubmit<SubmitResult>(async () => {
    if (editingId.value === null) {
      await createPackage(toCreateDTO())
      return 'saved'
    }

    const id = editingId.value
    const { payload, unsettable } = diffUpdate(originalForm.value, form)

    // ★ 两个**改不了**的字段，显式摘掉（`ChargePackageUpdateDTO` 里没有它们）：
    //   · `packageType`：换类型会让已经卖出去的权益与扣减逻辑对不上；
    //   · `stationId`：换作用域会让「这张卡在哪个站能用」悄悄变掉。
    //   不要写成 `delete (payload as ChargePackageUpdateDTO).packageType` ——
    //   那个 `as` 会把键断言掉，于是 TS 认为它不存在，编译直接报错。
    delete payload.packageType
    delete payload.stationId

    const blocked = [...unsettable, ...dropCleared(payload)]
    if (blocked.length > 0) {
      ElMessage({
        type: 'warning',
        message: `这些字段填过之后无法清空，已保持原值：${blocked
          .map((k) => FIELD_LABEL[k] ?? k)
          .join('、')}`,
        duration: 6000,
        showClose: true,
      })
    }

    if (Object.keys(payload).length === 0) return 'nochange'

    // ★ 套餐**没有乐观锁**（`ChargePackageUpdateDTO` 里没有 version），
    //   所以增量提交是这里唯一的并发保护：至少不会把「没碰过的字段」也写一遍。
    //   两个人同时改同一个字段时，后写的仍然会覆盖先写的 —— 这是表结构层面的
    //   问题，不是这个页面能解决的。
    await updatePackage(id, payload as ChargePackageUpdateDTO)
    return 'saved'
  })

  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    // ★ 失败**不关弹窗**：原因（后端原文）与出路都留在用户眼前。
    //   全局 onError 的 toast 4 秒就没了，而用户需要照着这句话去改。
    submitError.value = errorText(outcome.error)
    return
  }

  dialogOpen.value = false

  if (outcome.value === 'nochange') {
    ElMessage({ type: 'info', message: '没有改动，未提交', duration: 1600 })
    return
  }

  ElMessage({ type: 'success', message: isEditing.value ? '已保存' : '已创建', duration: 1800 })
  // ★ 套餐的增删改会影响「车主权益」等处按套餐取值的下拉，分类的缓存也可能过期
  invalidateOptionsByTag(TAG.package, TAG.category)
  await reload()
}

/**
 * 后端「类型专属字段不匹配」的判定。
 *
 * ★ 用前缀匹配：那句话后面还跟着具体字段名（`电量包必须填写 energyKwh`）。
 *   常量与后端逐字对齐（`api/package.ts` 里的 `PACKAGE_TYPE_FIELD_MISMATCH`）。
 */
const isTypeFieldMismatch = computed(
  () => submitError.value !== null && msgStartsWith(submitError.value, PACKAGE_TYPE_FIELD_MISMATCH),
)

function onDialogClosed(): void {
  formRef.value?.clearValidate()
}

// ── 上架 / 下架 ────────────────────────────────────────────────────────────

const { run: runToggle } = useSubmitLock({ throttleMs: 500 })

/**
 * 哪一行的上架/下架正在飞。
 *
 * ★ 用行 id 记，而不是把 `useSubmitLock` 的 `pending` 直接绑到按钮上：
 *   `pending` 是**这一把锁**的状态，绑上去会让整列按钮同时转圈 ——
 *   明明只点了一行，看起来像把整页都提交了。
 * ★ 判断用 `togglingId === row.id`（严格相等）而不是真值判断：
 *   id 从 JSON 来，可能是数字也可能是字符串，真值判断会在 id 为 0 或
 *   字符串 id 上出现莫名其妙的转圈。
 */
const togglingId = ref<number | null>(null)

/**
 * 上架 / 下架。
 *
 * ★ 用轻确认而不是 `DangerConfirm`：它是**可逆**的（改回来就是），
 *   而且不会动到任何存量数据 —— 已购买的权益不受影响，车主端只是买不到新的。
 *   把「可逆动作」也做成手打确认，会让真正危险的那个（删除）失去分量。
 *
 * ★ `updatePackage` **没有** `silent` 选项：失败时全局 onError 会弹后端原文，
 *   所以这里不重复弹同一句话（只在下面对「这辈子改不动」的那种失败补一句指引）。
 */
async function askToggleStatus(row: ChargePackageVO): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  const title = next === 1 ? '上架这个套餐？' : '下架这个套餐？'
  const body =
    next === 1
      ? `「${row.name}」会重新出现在车主端（以及它适用的站点上），可以被购买。`
      : `「${row.name}」会从车主端消失，不能再被购买。已经买过的权益不受影响；名字仍然被这个套餐占着（唯一键不看状态）。`

  try {
    await ElMessageBox.confirm(body, title, {
      confirmButtonText: next === 1 ? '上架' : '下架',
      cancelButtonText: '取消',
      type: 'info',
    })
  } catch {
    return // 用户点了取消 —— 不是错误
  }

  togglingId.value = row.id
  // ★ 这里**不需要** try/finally 去兜「异常」：`SubmitLock.run` 把 `fn` 抛出的
  //   一切都收进 `{status:'failed'}` 返回，它自己**从不抛**（见 shared 的
  //   `http/locks.ts`）。会抛的是另一个 `withActionLock`（被锁时抛
  //   `ActionLockedError`）—— 两个锁的失败语义刻意不同，别记混。
  const outcome = await runToggle(() => updatePackage(row.id, { status: next }))
  // 锁是全局一把：同一时刻至多一行在飞，所以直接置空不会抹掉别人的转圈
  togglingId.value = null
  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') {
    // ★ 有一种失败是「这行数据本身就是坏的」：后端校验的是**库里那行 + 本次改动**
    //   合并后的结果，所以连改个状态也会被 `assertTypeFields` 打回。
    //   那句原文已经弹过了，这里补的是**它该找谁** —— 界面改不动它。
    if (outcome.error instanceof Error && msgStartsWith(outcome.error.message, PACKAGE_TYPE_FIELD_MISMATCH)) {
      ElMessage({
        type: 'warning',
        message: '这行数据本身有问题（类型与额度字段对不上），连上架下架都过不了校验。它不是界面能修的，请把套餐 id 交给后端按 SQL 修正。',
        duration: 8000,
        showClose: true,
      })
    }
    return
  }

  ElMessage({
    type: 'success',
    message: next === 1 ? `已上架「${row.name}」` : `已下架「${row.name}」`,
    duration: 2000,
  })
  await reload()
}

// ── 删除 ───────────────────────────────────────────────────────────────────

const deleteOpen = ref(false)
const deleteTarget = ref<ChargePackageVO | null>(null)
const { pending: removing, run: runRemove } = useSubmitLock({ throttleMs: 500 })

function askRemove(row: ChargePackageVO): void {
  deleteTarget.value = row
  deleteOpen.value = true
}

async function doRemove(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return

  // ★ `deletePackage` **没有** silent 选项（签名就一个 id）：失败时全局 onError
  //   会弹后端原文，这里不重复弹。后端只校验「这行还在不在」——
  //   没有外键、也没有订单守卫，所以失败基本只有「刚被别人删掉」一种。
  const outcome = await runRemove(() => deletePackage(target.id))
  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') return // 弹窗不关，用户可以直接再试

  deleteOpen.value = false
  invalidateOptionsByTag(TAG.package, TAG.category)
  ElMessage({ type: 'success', message: `已删除套餐「${target.name}」`, duration: 2000 })
  await afterRemove()
}
</script>

<template>
  <div class="page">
    <PagePanel
      title="充电套餐"
      subtitle="套餐的种类（电量包/时长包/折扣包/次卡）决定它用哪个额度字段，也决定扣减逻辑。删除前请先想清楚：常规退役应该用「下架」。"
      flush
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <CoIcon name="plus" :size="15" />
          新建套餐
        </el-button>
      </template>

      <FilterBar :dirty="dirty" :loading="loading" @search="search" @reset="reset">
        <el-input
          v-model="filters.name"
          class="is-wide"
          placeholder="套餐名称（模糊）"
          clearable
          :maxlength="MAX_LEN.packageName"
          @keyup.enter="search"
        />
        <!-- ★ 只列「充电套餐分类」：充电套餐挂在别的类型下会被后端拒绝 -->
        <el-select v-model="filters.categoryId" placeholder="分类" clearable>
          <el-option v-for="o in filterCategoryItems" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="filters.packageType" placeholder="类型" clearable>
          <el-option v-for="o in PACKAGE_TYPE.options()" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable>
          <el-option v-for="o in PACKAGE_STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </FilterBar>

      <el-table v-loading="loading" :data="rows" row-key="id" size="default">
        <el-table-column prop="name" label="套餐名称" min-width="170" show-overflow-tooltip />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <!-- 类型决定扣减逻辑与必填的额度字段，所以用状态标签而不是纯文字：
                 它和「面值」列要一眼看出是同一类信息 -->
            <StatusTag :label="typeText(row)" :tone="PACKAGE_TYPE.tone(row.packageType)" size="sm" />
          </template>
        </el-table-column>

        <!-- ★ 面值走 `quotaTextOf()`（api/package.ts 里那张表的唯一出口）：
             四种类型各取各的字段，**不要在模板里写 switch** ——
             那是第二个真源，而且会把「服务费 × 0.8000」这类格式算错。 -->
        <el-table-column label="面值" width="150">
          <template #default="{ row }">{{ quotaTextOf(row) }}</template>
        </el-table-column>

        <el-table-column label="售价" width="120" align="right" class-name="co-num">
          <template #default="{ row }">
            <MoneyText :value="row.price" size="sm" strong />
          </template>
        </el-table-column>
        <el-table-column label="划线价" width="110" align="right" class-name="co-num">
          <template #default="{ row }">
            <!-- ★ null = 不展示划线（不是「0 元」）—— MoneyText 会显示 `--` -->
            <MoneyText :value="row.originPrice" size="sm" />
          </template>
        </el-table-column>
        <el-table-column label="适用站点" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ stationText(row.stationId) }}</template>
        </el-table-column>
        <el-table-column label="有效期" width="110">
          <template #default="{ row }">{{ validDaysText(row.validDays) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusTag :label="statusText(row)" :tone="statusTone(row.status)" size="sm" />
          </template>
        </el-table-column>
        <!-- ★ 列表按 `sort` 排，**不是**按时间排，所以「谁刚改过」只能靠这一列看。
             套餐没有乐观锁（不像充电枪），提交前的最后一道人工防线就是它。 -->
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">
            <span class="co-num">{{ formatServerTime(row.updateTime) ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button
              link
              type="primary"
              size="small"
              :loading="togglingId === row.id"
              @click="askToggleStatus(row)"
            >
              {{ row.status === 1 ? '下架' : '上架' }}
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
            title="还没有充电套餐"
            description="新建之前先在「套餐分类」里建一个分类（充电套餐只能挂在「充电套餐分类」下），再回来新建套餐。"
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

    <!-- ── 新建 / 编辑 ─────────────────────────────────────────────────── -->
    <el-dialog
      v-model="dialogOpen"
      :title="isEditing ? `编辑套餐「${originalForm.name}」` : '新建套餐'"
      width="720px"
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      @closed="onDialogClosed"
    >
      <!-- ★ 后端拒绝时留在弹窗里，原文**逐字**显示（不转述、不美化） -->
      <el-alert v-if="submitError" class="submit-alert" type="warning" :closable="false" show-icon>
        <template #title>{{ submitError }}</template>
        <p v-if="isTypeFieldMismatch" class="alert-text">
          这句话说的是：这张套餐的类型和它的额度字段对不上（比如时长包里残留了电量）。
          界面上的表单只会带当前类型该有的那一个字段，所以正常情况下碰不到它 ——
          出现它通常意味着**库里那行数据本来就是坏的**，后端校验的是「库里那行 + 本次改动」
          合并后的结果，所以连改个价格也会被打回。这不是界面能修的问题，请把套餐 id 交给后端处理。
        </p>
        <p v-else class="alert-text">这是后端返回的原文，改完上面的内容再提交一次即可，弹窗不会自动关。</p>
      </el-alert>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="grid-2">
          <el-form-item label="套餐名称" prop="name">
            <el-input
              v-model="form.name"
              placeholder="如 150度电包"
              :maxlength="MAX_LEN.packageName"
              :disabled="submitting"
            />
          </el-form-item>
          <el-form-item label="套餐类型" prop="packageType">
            <!-- ★★ 类型建好后不可修改（`ChargePackageUpdateDTO` 里没有这个字段）★★
                 做成可编辑的话用户改完提交，后端会静默忽略 —— 而用户以为改成功了。
                 要换类型只能新建一个套餐。 -->
            <el-input v-if="isEditing" :model-value="PACKAGE_TYPE.label(form.packageType)" disabled />
            <el-select v-else v-model="form.packageType" placeholder="选择类型" @change="onTypeChange">
              <el-option v-for="o in PACKAGE_TYPE.options()" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <p v-if="isEditing" class="field-note">
              类型决定扣减逻辑，建立后不可修改：换了类型，已经卖出去的权益就会按新的规则去扣。
              要换类型请新建一个套餐，再把旧的停售。
            </p>
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="套餐分类" prop="categoryId">
            <el-select v-model="form.categoryId" placeholder="选择分类" :disabled="submitting">
              <el-option v-for="o in formCategoryItems" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <p class="field-note">只能选「充电套餐分类」下的分类（下拉里不会出现别的类型）。</p>
          </el-form-item>

          <el-form-item label="适用站点" prop="stationId">
            <el-input v-if="isEditing" :model-value="editingStationText" disabled />
            <el-select v-else v-model="form.stationId" :disabled="submitting">
              <el-option v-for="o in stationItems" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <p v-if="isEditing" class="field-note">
              作用范围不可修改：改它会让「这张卡在哪个站能用」悄悄变掉，而在售的权益还按旧范围在用。
            </p>
            <p v-else class="field-note">
              「全平台通用」的套餐在任何站点都能买；选了某个站点，它就只在该站点出现。
            </p>
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="售价（元）" prop="price">
            <el-input v-model="form.price" placeholder="如 100.00" :disabled="submitting" />
            <p class="field-note">可以填 0（赠送套餐）。</p>
          </el-form-item>
          <el-form-item label="划线价（元）" prop="originPrice">
            <el-input v-model="form.originPrice" placeholder="选填，不填就不显示划线价" :disabled="submitting" />
            <p class="field-note">
              <strong>设置后无法清空</strong>（后端「不传」等于「不修改」）。也不要填 0：
              那会显示成「<s>¥0.00</s> ¥100.00」。想撤掉划线价只能把它填成和售价一样。
            </p>
          </el-form-item>
        </div>

        <!-- ── 按类型动态显示的额度字段 ──────────────────────────────────
             ★ 四个分支的条件都从 `quotaField` 派生（它来自 api/package.ts 的
               PACKAGE_QUOTA_FIELD 表），不在模板里手写「电量包就是 1」这类判断。 -->
        <div class="quota-box">
          <div class="quota-head">
            <span class="quota-title">额度字段</span>
            <span class="quota-sub">
              {{ form.packageType === undefined ? '先选套餐类型' : `${PACKAGE_TYPE.label(form.packageType)}只填下面这一项` }}
            </span>
          </div>

          <p v-if="quotaField === null" class="field-note">
            请先选择套餐类型 —— 四种类型的额度字段各不相同，选了才会出现对应的那一项。
          </p>

          <template v-else>
            <el-form-item v-if="quotaField === 'energyKwh'" label="电量（kWh）" prop="energyKwh">
              <el-input v-model="form.energyKwh" placeholder="如 150.00" :disabled="submitting" />
              <p class="field-note">{{ quotaFieldHint.energyKwh }}</p>
            </el-form-item>

            <el-form-item v-else-if="quotaField === 'durationMinutes'" label="时长（分钟）" prop="durationMinutes">
              <div class="field">
                <el-input-number
                  v-model="form.durationMinutes"
                  :min="1"
                  :step="1440"
                  :controls="false"
                  placeholder="如 43200"
                  style="width: 100%"
                  :disabled="submitting"
                />
                <p class="field-note">
                  {{ quotaFieldHint.durationMinutes }}当前是 <b>{{ durationPreview }}</b>。
                </p>
              </div>
            </el-form-item>

            <el-form-item v-else-if="quotaField === 'serviceDiscount'" label="服务费折扣" prop="serviceDiscount">
              <el-input v-model="form.serviceDiscount" placeholder="如 0.8000（八折）" :disabled="submitting" />
              <p class="field-note">{{ quotaFieldHint.serviceDiscount }}</p>
            </el-form-item>

            <el-form-item v-else-if="quotaField === 'times'" label="次数" prop="times">
              <el-input-number
                v-model="form.times"
                :min="1"
                :controls="false"
                placeholder="如 10"
                style="width: 100%"
                :disabled="submitting"
              />
              <p class="field-note">{{ quotaFieldHint.times }}</p>
            </el-form-item>

            <p class="field-note is-warn">
              另外三个额度字段属于别的套餐类型，这里一个都不会提交。这不是美观问题：
              残留值会被计费逻辑读走（一个时长包里残留的「电量 150」会白送 150 度电，
              而且全程不报错）。
            </p>
          </template>
        </div>

        <div class="grid-2">
          <el-form-item label="有效期（天）" prop="validDays">
            <div class="field">
              <el-input-number
                v-model="form.validDays"
                :min="0"
                :controls="false"
                style="width: 100%"
                :disabled="submitting"
              />
              <p class="field-note">
                {{ validDaysText(form.validDays) }}。它和上面的「时长」是两件事：
                时长是<b>买到的时长</b>（30 天随便充），有效期是<b>这单生意的期限</b>
                （买完 90 天内要用完）。填 0 = 永久有效。
              </p>
            </div>
          </el-form-item>
          <el-form-item label="排序" prop="sort">
            <div class="field">
              <el-input-number
                v-model="form.sort"
                :min="0"
                :controls="false"
                style="width: 100%"
                :disabled="submitting"
              />
              <p class="field-note">数字小的排在前面（本页列表与车主端都按它排）。</p>
            </div>
          </el-form-item>
        </div>

        <el-form-item label="套餐图片" prop="image">
          <div class="field">
            <div class="upload-row">
              <!-- ★ `accept` 只是把选文件对话框默认筛一遍，**不是**校验：
                   真正的校验在 `uploadImage()` 里（白名单没有 svg —— 上传目录是
                   `/img/**` 匿名可访问的，而 SVG 能内嵌脚本，等于一个存储型 XSS）。 -->
              <el-upload
                class="uploader"
                :accept="ACCEPT_ATTR"
                :show-file-list="false"
                :http-request="doUpload"
                :disabled="submitting"
              >
                <div class="thumb" :class="{ 'is-empty': !hasImage }">
                  <img v-if="hasImage" :src="form.image" alt="" />
                  <span v-else class="thumb-hint">
                    <CoIcon name="upload" :size="16" />
                    选择图片
                  </span>
                </div>
              </el-upload>

              <div class="upload-side">
                <p class="field-note">
                  支持 jpg / jpeg / png / gif / webp，单张不超过 10MB（<b>不支持 svg</b>）。
                </p>
                <p class="field-note">
                  点选图片会<b>立刻上传</b>，但还要点「保存」才会把它挂到这个套餐上。
                  <strong>设置后无法清空</strong>：要换图直接传新的覆盖。
                </p>
              </div>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="套餐说明" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="选填，如 全国通用，有效期永久"
            :maxlength="MAX_LEN.packageDescription"
            show-word-limit
            :disabled="submitting"
          />
          <p class="field-note"><strong>设置后无法清空</strong>：后端「不传」表示「不修改」。</p>
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status" :disabled="submitting">
            <el-radio v-for="o in PACKAGE_STATUS_OPTIONS" :key="o.value" :value="o.value">
              {{ o.label }}
            </el-radio>
          </el-radio-group>
          <p class="field-note">
            下架只是从车主端撤下来（不能再买），已经买过的权益不受影响。名字仍然被占着 ——
            唯一键不看状态。
          </p>
        </el-form-item>

        <ul class="form-notes">
          <li>
            <strong>改不动的字段</strong>：类型、适用站点。它们决定扣减逻辑与作用范围，
            改了会让在售的权益和实际行为对不上，所以后端压根没有这两个可更新字段。
          </li>
          <li>
            <strong>填过就清不掉的字段</strong>：划线价、图片、套餐说明。
            这是「不传 = 不修改」的直接后果，不是界面偷懒。
          </li>
          <li>
            套餐没有乐观锁（不像充电枪）：两个人同时改同一个字段时，后提交的会覆盖先提交的。
            提交前可以扫一眼列表里的「更新时间」—— 它按排序值排，不按时间排，所以那一列是唯一能看出「别人刚动过」的地方。
          </li>
        </ul>
      </el-form>

      <template #footer>
        <el-button :disabled="submitting" @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">
          {{ isEditing ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ── 删除 ───────────────────────────────────────────────────────── -->
    <DangerConfirm
      v-model="deleteOpen"
      :title="`删除套餐「${deleteTarget?.name ?? ''}」`"
      description="删除后不可恢复，套餐名会被释放（之后可以用同一个名字重建）。"
      :consequences="[
        '已售出的权益不受影响：它们存的是购买时的名称与额度快照，不依赖这一行。',
        '常规退役请用「下架」而不是删除 —— 下架可逆，删除不可逆。',
        '硬删存在的理由：唯一键（名称 + 站点）不看状态，名字打错一次（如「150度点包」）就永久占用，想用正确的名字只能先把错的删掉。',
      ]"
      action-name="删除"
      :require-typed-value="deleteTarget?.name ?? null"
      typed-label="请输入套餐名称以确认删除"
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

@media (max-width: 640px) {
  .grid-2 {
    grid-template-columns: minmax(0, 1fr);
  }
}

.field-note {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.field-note strong {
  color: var(--co-text-2);
  font-weight: 600;
}

.field-note.is-warn {
  color: var(--co-warning-strong);
}

/* ★ 让 form-item 里的「控件 + 提示」竖着排（同 StartOrderDialog 的 .field） */
.field {
  display: flex;
  flex-direction: column;
  width: 100%;
}

/* 额度字段单独框起来：它是这个表单里**会跟着类型变**的部分，
   框一下让「换类型会换掉这一整块」这件事在视觉上是显然的。 */
.quota-box {
  margin-bottom: 18px;
  padding: 12px 14px 4px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-2);
}

.quota-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 10px;
}

.quota-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--co-text-1);
}

.quota-sub {
  font-size: 12px;
  color: var(--co-text-3);
}

.upload-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.upload-side {
  flex: 1;
  min-width: 0;
}

.upload-side .field-note:first-child {
  margin-top: 2px;
}

.uploader :deep(.el-upload) {
  display: block;
}

.thumb {
  display: grid;
  place-items: center;
  width: 108px;
  height: 108px;
  overflow: hidden;
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-card);
  transition: border-color 0.16s var(--co-ease);
}

.thumb:hover {
  border-color: var(--co-primary);
}

.thumb.is-empty {
  border-style: dashed;
  background: var(--co-bg-2);
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--co-text-3);
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

.form-notes strong {
  color: var(--co-text-2);
  font-weight: 600;
}

.submit-alert {
  margin-bottom: 16px;
}

.alert-text {
  margin: 6px 0 10px;
  font-size: 12.5px;
  line-height: 1.75;
}
</style>
