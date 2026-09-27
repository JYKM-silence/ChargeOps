<script setup lang="ts">
/**
 * 充电枪管理列表。**跨站点**的列表 —— 不选所属桩就是全平台的枪。
 *
 * ★★ 这个页面与别的列表页有一处**本质**区别：枪是全项目唯一带乐观锁的实体 ★★
 *   `ConnectorUpdateDTO.version` 会被后端塞进条件更新
 *   （`UPDATE ... WHERE id = ? AND version = ?`），所以「打开弹窗时那一刻的 version」
 *   是承重数据。由此推出四条纪律，下面每条代码都有对应落点：
 *
 *     1. 打开编辑弹窗时记下 `version` 与 `updateTime`；
 *     2. 提交前**重读一次**做客户端预检 —— 它挡不住真并发（校验与写入之间仍有窗口），
 *        但能把「你手上这份是旧数据」在 PUT 发出去之前就告诉用户；
 *     3. 冲突时**不重复弹 toast**（全局 `onError` 已经把后端文案加工过一次了，
 *        见 api/http.ts 的 `messageOf`），改在弹窗里留一条**持久的、带动作的**提示；
 *     4. 恢复动作是「放弃我的改动，重新拉取」，**不是**「拿最新的 version 再提交一次」——
 *        后者等于把乐观锁降级成一句恒真的装饰（api/connector.ts 里写得很直接）。
 *
 * ★ 手工能设的枪状态只有 `{0 离线, 1 空闲, 4 故障, 5 维护}`：
 *   `2 已占用` / `3 充电中` 归订单状态机所有，它们与唯一索引
 *   `uk_connector_current_order` 的语义绑定，手工设会造出一把「没有订单却占着枪」的记录。
 *   所以**筛选**下拉可以用全部 6 个状态（「哪些枪正在充电」是个合法的问题），
 *   而**编辑表单**只能用 `CONNECTOR_ADMIN_SETTABLE_OPTIONS`（它就是从
 *   `CONNECTOR_ADMIN_SETTABLE` 派生出来的，别在这里手搓一份）。
 *
 * ★ 删除是**守卫式硬删**，且守卫比充电桩还多一道：枪上有 `current_order_id`、
 *   状态是 2/3、或者有订单正占着这把枪，三者任一都会被拒绝。
 *   前端预先查不到这些（`ConnectorVO` 只给了 `currentOrderId` 一个线索），
 *   所以只能让后端拒绝，再把那句中文原样加工成一条**出路**。
 */

import { computed, nextTick, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  CONNECTOR_ADMIN_SETTABLE_OPTIONS,
  CONNECTOR_STATUS,
  HOLDING_CONNECTOR_STATUSES,
  MAX_LEN,
  MSG,
  diffUpdate,
  formatServerTime,
  invalidateOptionsByTag,
  isPositive,
  msgEquals,
  orderStatusLabel,
  type ConnectorDTO,
  type ConnectorPageQuery,
  type ConnectorUpdateDTO,
  type ConnectorVO,
} from '@chargeops/shared'
import CoIcon from '@/components/CoIcon.vue'
import DangerConfirm from '@/components/DangerConfirm.vue'
import EmptyState from '@/components/EmptyState.vue'
import FilterBar from '@/components/FilterBar.vue'
import MoneyText from '@/components/MoneyText.vue'
import PagePanel from '@/components/PagePanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import { TAG, chargerOptionsOf } from '@/api/options'
import {
  createConnector,
  deleteConnector,
  getConnector,
  pageConnectors,
  updateConnector,
} from '@/api/connector'
import { useOptions } from '@/composables/useOptions'
import { usePagedList } from '@/composables/usePagedList'
import { useSubmitLock } from '@/composables/useSubmitLock'

const router = useRouter()

// ── 列表 ───────────────────────────────────────────────────────────────────

/**
 * ★ 直接把后端的分页查询类型当筛选条件（和 ChargerListView 一样）：
 *   初始值里只列真正的筛选键，所以 `dirty` 不会把 `pageNum/pageSize` 算进去，
 *   而 `fetch` 收到的 `q` 已经由 `usePagedList` 合并好分页参数，原样转发即可。
 *
 * ★ 这里**刻意没有**站点的筛选条件：后端 `ConnectorPageQuery` 里虽然有
 *   `stationId`，但枪的归属事实来源是它挂在哪根桩上，而「跨站点的枪列表」
 *   按站点筛往往只是为了缩小范围 —— 用「所属桩」下拉更准（站和桩是级联的，
 *   而且用户手上通常拿的是桩的编号）。
 */
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
} = usePagedList<ConnectorVO, ConnectorPageQuery>({
  fetch: (q) => pageConnectors(q),
  initial: { chargerId: undefined, gunNo: '', status: undefined },
})

/**
 * 所属桩下拉。
 *
 * ★ 传 `null` 表示「不限站点」—— `chargerOptionsOf` 会把 key 编成 `charger@all`
 *   并拉全平台的桩。这是必需的：枪可以在任何站点的任何桩上，而这个页面
 *   要同时**筛选**和**显示桩名**（`ConnectorVO` 里没有 `chargerName`，只有 id）。
 *
 * ★ 代价：打开这个页面会翻遍充电桩表（每页 100，最多若干页）。
 *   这是「后端 VO 不给名字」的直接后果 —— 桩表规模真正上去之后，
 *   正确做法是让后端在 VO 里 join 出 `chargerName`，而不是在前端继续加缓存。
 */
const { options: chargerOpts } = useOptions(() => chargerOptionsOf(null))

/**
 * 桩的显示文案。
 *
 * ★ 把设备编号一起带上：桩名（`A区-01`）在不同站点会重名，只显示名字的话
 *   用户在两个同名桩之间根本无从选择。
 * ★ 查不到时退回 `#id` 而不是空白 —— 下拉最多翻若干页，超出上限的桩不在这里；
 *   空白会被当成「数据丢了」，裸数字会被当成乱码。
 */
const chargerItems = computed(() =>
  chargerOpts.value.map((o) => ({
    value: o.value,
    label: o.hint ? `${o.label}（${o.hint}）` : o.label,
  })),
)

function chargerText(id: number | null | undefined): string {
  if (id === null || id === undefined) return '-'
  const hit = chargerItems.value.find((o) => o.value === id)
  return hit ? hit.label : `#${id}`
}

/**
 * 是否有**实质**筛选条件。
 *
 * ★ 与 `dirty` 分开：`el-select` 清空后 emit 的是 `''`，而初始值是 `undefined`，
 *   两者都表示「没填」。若拿 `dirty` 判断空态，用户「选了一下又清掉」之后
 *   明明是全量数据，却会看到「没有符合条件的充电枪」。
 */
const hasFilter = computed(() =>
  [filters.chargerId, filters.gunNo, filters.status].some(
    (v) => v !== undefined && v !== null && String(v).trim() !== '',
  ),
)

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

/**
 * 全部 6 个状态都可以用来筛。
 *
 * ★ 与编辑表单的下拉**故意不同**：筛选只是提问（「哪些枪在充电」），
 *   不会写回数据，所以 2/3 在这里是合法的、也是最有用的两个选项。
 */
const filterStatusOptions = CONNECTOR_STATUS.options()

/** 状态列的悬停说明：把字典里那句「这个状态意味着什么」带上，减少一次查文档。 */
function statusHint(status: number): string | undefined {
  return CONNECTOR_STATUS.byCode(status)?.hint
}

function gotoOrder(orderId: number | null): void {
  if (orderId === null) return
  void router.push(`/order/${orderId}`)
}

// ── 新建 / 编辑弹窗 ────────────────────────────────────────────────────────

/**
 * ★ 用 `type` 而不是 `interface`：`diffUpdate` 的约束是
 *   `T extends Record<string, unknown>`，interface 没有隐式索引签名，会编译不过。
 */
type ConnectorForm = {
  chargerId: number | undefined
  gunNo: string
  connectorType: string
  ratedPowerKw: string
  status: number | undefined
}

function emptyForm(): ConnectorForm {
  return {
    chargerId: undefined,
    gunNo: '',
    connectorType: '',
    ratedPowerKw: '',
    status: undefined,
  }
}

const dialogOpen = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<ConnectorForm>(emptyForm())
/** 增量提交的基准。必须是**服务端原值**，不是上一次提交的值。 */
const originalForm = ref<ConnectorForm>(emptyForm())
const editingId = ref<number | null>(null)

/**
 * ★ 打开弹窗那一刻的 `version`。提交时必须原样回传 —— 它是乐观锁的**全部**。
 *   新建时保持 0，但新建走的 DTO 里根本没有这个字段，不会被发出去。
 */
const openedVersion = ref(0)

/**
 * 打开弹窗那一刻的 `updateTime`，用于提交前的客户端预检。
 *
 * ★ 它只是**第二道**防线：真正确认并发的是 `version`。这里比对时间戳的意义在于
 *   「在发出 PUT 之前」就能告诉用户「你手上这份已经旧了」，而不是等他填完点提交
 *   才被后端拒绝 —— 少一次白填。
 */
const openedUpdateTime = ref('')

/**
 * 乐观锁冲突的提示文案；非 null 时弹窗里会显示一条警告横幅。
 * ★ 初值 null；（提交成功后）由 `onDialogClosed` 之外的路径统一清理。
 */
const conflict = ref<string | null>(null)

const isEditing = computed(() => editingId.value !== null)

const { pending: submitting, run: runSubmit } = useSubmitLock({ throttleMs: 400 })

const FIELD_LABEL: Record<string, string> = {
  chargerId: '所属充电桩',
  gunNo: '枪号',
  connectorType: '接口类型',
  ratedPowerKw: '额定功率',
  status: '状态',
}

function fillForm(row: ConnectorVO): void {
  form.chargerId = row.chargerId
  form.gunNo = row.gunNo
  form.connectorType = row.connectorType ?? ''
  form.ratedPowerKw =
    row.ratedPowerKw === null || row.ratedPowerKw === undefined ? '' : String(row.ratedPowerKw)
  form.status = row.status
}

function openCreate(): void {
  editingId.value = null
  openedVersion.value = 0
  openedUpdateTime.value = ''
  conflict.value = null
  Object.assign(form, emptyForm())
  originalForm.value = { ...form }
  dialogOpen.value = true
  // el-dialog 的内容是懒渲染的，首次打开时 formRef 还没挂上
  void nextTick(() => formRef.value?.clearValidate())
}

/**
 * 打开编辑。
 *
 * ★ `version` 与 `updateTime` **只在这一次赋值**，之后整个编辑过程中都不再刷新 ——
 *   这正是乐观锁的语义：我们提交的是「基于 v{n} 的改动」。
 *   提交前重新取一次新版本号再发，会让检查变成一句恒真的装饰。
 */
function openEdit(row: ConnectorVO): void {
  editingId.value = row.id
  openedVersion.value = row.version
  openedUpdateTime.value = row.updateTime
  conflict.value = null
  fillForm(row)
  originalForm.value = { ...form }
  dialogOpen.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

/**
 * 编辑表单的状态选项。
 *
 * ★★ 用共享的 `CONNECTOR_ADMIN_SETTABLE_OPTIONS`（`{0,1,4,5}`），
 *    不用 `CONNECTOR_STATUS.options()` ★★
 *   后者会把 2/3 也列出来，提交后被后端 `isAdminSettable()` 拒绝，
 *   而用户完全不知道自己错在哪（「已占用」看起来就是一个普通状态）。
 *
 * ★ 但下拉里可能**缺当前值**：一把正在充电（3）的枪，它的 status 不在那 4 个里，
 *   `el-select` 会退化成一个谁都看不懂的裸 `3`。所以把当前值补成一条**禁用**的选项，
 *   用户于是能看到真相（这把枪正被订单占着）。补进来的这条永远选不动，
 *   也就不会被提交 —— 提交走 diff，状态没被改过就不会出现在 payload 里。
 */
const statusItems = computed(() => {
  const items = CONNECTOR_ADMIN_SETTABLE_OPTIONS.map((o) => ({ ...o, disabled: false }))
  const current = form.status
  if (current !== undefined && !items.some((i) => i.value === current)) {
    return [
      {
        value: current,
        label: `${CONNECTOR_STATUS.label(current)}（由订单流程维护，不能手工设置）`,
        disabled: true,
      },
      ...items,
    ]
  }
  return items
})

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

/** 规则逐条对应 `ConnectorDTO` / `ConnectorUpdateDTO` 上的注解（松紧都要一致）。 */
const rules: FormRules = {
  chargerId: [{ required: true, message: '请选择所属充电桩', trigger: 'change' }],
  gunNo: [
    { required: true, message: '请输入枪号', trigger: 'blur' },
    { max: MAX_LEN.gunNo, message: `枪号不能超过 ${MAX_LEN.gunNo} 个字符`, trigger: 'blur' },
  ],
  connectorType: [
    {
      max: MAX_LEN.interfaceType,
      message: `接口类型不能超过 ${MAX_LEN.interfaceType} 个字符`,
      trigger: 'blur',
    },
  ],
  // ★ 额定功率必须 > 0（后端 `@DecimalMin(inclusive = false)`），且不能为空 ——
  //   列是 NOT NULL。这里用 `isPositive`（走 money.ts 的 BigInt 缩放，按两位小数判）：
  //   一个 `0.001` 会被库按 `decimal(6,2)` 存成 `0.00`，也就是一个「大于 0 的 0」——
  //   注释放行它，但存进去就违反了同一句话，所以按列精度判掉。
  ratedPowerKw: [
    { required: true, message: '请输入额定功率', trigger: 'blur' },
    formatRule((v) => isPositive(v), '额定功率必须是一个大于 0 的数'),
  ],
}

function orUndefined(v: string): string | undefined {
  const t = v.trim()
  return t === '' ? undefined : t
}

function toCreateDTO(): ConnectorDTO {
  return {
    // ★ 校验已保证非空，这里断言只为消掉 `number | undefined`
    chargerId: form.chargerId as number,
    gunNo: form.gunNo.trim(),
    connectorType: orUndefined(form.connectorType),
    ratedPowerKw: form.ratedPowerKw.trim(),
    // ★ 刻意**不传** `status`：`ConnectorDTO` 里没有它，后端把新枪固定为
    //   `0 离线` —— 它还没和任何硬件握过手，说它「空闲」需要一个从未发生过的状态上报。
  }
}

/**
 * 剔除增量里「被清空」的键。
 *
 * ★ `UpdateDTO` 的语义是「不传 = 不修改」，**不是**「传空 = 清空」：
 *   枪号、接口类型、额定功率**填了就抹不掉**。发空值不是被后端跳过
 *   （用户以为删掉了），就是在 `@NotBlank` 上变成另一个错。所以直接不发，并回报字段名。
 */
function dropCleared(payload: Partial<ConnectorForm>): string[] {
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

type SubmitResult = 'saved' | 'nochange' | 'stale'

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const outcome = await runSubmit<SubmitResult>(async () => {
    if (editingId.value === null) {
      await createConnector(toCreateDTO())
      return 'saved'
    }

    const id = editingId.value

    // ★ 客户端预检（伪乐观锁）。重读失败会抛异常 → 外层 `runSubmit` 归成 failed，
    //   全局 onError 弹提示，这里不再重复处理。
    const fresh = await getConnector(id)
    if (fresh && fresh.updateTime !== openedUpdateTime.value) return 'stale'

    const { payload, unsettable } = diffUpdate(originalForm.value, form)
    // ★ 编辑时**不允许**改所属桩：`ConnectorUpdateDTO` 里没有 `chargerId`，
    //   留在增量里只会让后端静默忽略（用户以为挪过去了）。显式摘掉，双保险。
    //
    // ★ 不要写成 `delete (payload as ConnectorUpdateDTO).chargerId` ——
    //   那个 `as` 会把 `chargerId` 断言掉，于是 TS 认为这个键根本不存在，
    //   类型检查直接报错；就算用 `as unknown as` 压过去，也只是把断言当消音器。
    //   `payload` 本来就是 `Partial<T>`，键是可选的，直接删即可。
    delete payload.chargerId

    const blocked = [...unsettable, ...dropCleared(payload)]
    if (blocked.length > 0) {
      ElMessage({
        type: 'warning',
        message: `这些字段填过之后无法清空，已保持原值：${blocked
          .map((k) => FIELD_LABEL[k] ?? k)
          .join('、')}`,
        duration: 5000,
        showClose: true,
      })
    }

    if (Object.keys(payload).length === 0) return 'nochange'

    // ★★ `version` 用**打开弹窗时**记下的那个，不是刚 GET 回来的 `fresh.version` ★★
    //   用 fresh 的版本号会让这次请求必然成功，而乐观锁也就白设了 ——
    //   两个管理员同时改同一把枪时，后提交的人会静默覆盖前一个。
    await updateConnector(id, { ...(payload as ConnectorUpdateDTO), version: openedVersion.value })
    return 'saved'
  })

  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    // ★ 后端 CAS 拒绝（HTTP 200 + code:0）。全局 onError 已经弹过一次
    //   「这条记录刚被其他人改过…」，所以这里**不再 toast**，只在弹窗里
    //   留一条持久的、带按钮的提示 —— 两种媒介说的是两件不同的事：
    //   toast 说「提交没成功」，这里的横幅说「你的改动还在，下一步点这里」。
    if (isConcurrentModified(outcome.error)) {
      conflict.value = CONFLICT_BODY
    }
    return
  }

  if (outcome.value === 'stale') {
    conflict.value = CONFLICT_BODY
    return
  }

  dialogOpen.value = false

  if (outcome.value === 'nochange') {
    ElMessage({ type: 'info', message: '没有改动，未提交', duration: 1600 })
    return
  }

  ElMessage({ type: 'success', message: isEditing.value ? '已保存' : '已创建', duration: 1800 })
  // ★ 枪的增删改会影响充电桩的枪数冗余列，所以 'charger' 一起失效；
  //   枪号/状态变化又会让别处的枪下拉（`connector@xxx`）过期，'connector' 也必须失效。
  invalidateOptionsByTag(TAG.connector, TAG.charger)
  await reload()
}

/**
 * 是否命中后端的乐观锁冲突。
 *
 * ★ 用**逐字相等**而不是 `includes`：后端文案是公开常量
 *   （`MessageConstant.CONCURRENT_MODIFIED`），模糊匹配会让
 *   「一句更长的话里恰好包含它」也命中（见 shared/http/messages.ts 的说明）。
 */
function isConcurrentModified(e: unknown): boolean {
  return e instanceof Error && msgEquals(e.message, MSG.CONCURRENT_MODIFIED)
}

/**
 * 冲突提示的正文。
 *
 * ★ 刻意**不复述后端原文**（那句话已经由全局 onError 弹过了），
 *   这里只回答用户真正的问题：发生了什么、现在该做什么。
 */
const CONFLICT_BODY =
  '这把枪在你打开弹窗之后被别人改过，所以这次提交没有被接受（后端按版本号做条件更新，不会静默覆盖别人的改动）。你的输入还在，但**不能**直接重试 —— 重试等于拿旧内容覆盖别人的新内容。'

/**
 * 放弃本地改动、重新拉取服务端最新内容，并把版本号刷新到最新。
 *
 * ★★ 这里是「重新拉取」而不是「只刷新版本号」★★
 *   只换版本号、保留用户的输入，请求会成功，但用户改的那几个字段会
 *   把别人刚写的值覆盖掉 —— 那正是乐观锁存在的意义被绕过的样子。
 *   所以按钮文案里写明了「放弃我的改动」，这个代价必须让用户看见。
 */
async function reloadForEdit(): Promise<void> {
  const id = editingId.value
  if (id === null) return

  const outcome = await runSubmit(async () => {
    const fresh = await getConnector(id)
    if (!fresh) return 'gone'
    fillForm(fresh)
    originalForm.value = { ...form }
    openedVersion.value = fresh.version
    openedUpdateTime.value = fresh.updateTime
    return 'ok'
  })
  if (outcome.status !== 'done') return

  if (outcome.value === 'gone') {
    conflict.value = '这把枪已经不存在了（可能刚被其他人删掉）。请关闭弹窗后刷新列表。'
    return
  }

  conflict.value = null
  void nextTick(() => formRef.value?.clearValidate())
  ElMessage({ type: 'success', message: '已拉取最新内容，请确认后再提交', duration: 2500 })
}

function onDialogClosed(): void {
  formRef.value?.clearValidate()
}

// ── 删除 ───────────────────────────────────────────────────────────────────

const deleteOpen = ref(false)
const deleteTarget = ref<ConnectorVO | null>(null)
/** 后端拒绝的原因。非空时在确认弹窗里显示 —— 那条出路就在它下面。 */
const deleteError = ref<string | null>(null)
const { pending: removing, run: runRemove } = useSubmitLock({ throttleMs: 500 })

/**
 * 后果清单。
 *
 * ★ 第一条的状态集合来自 `HOLDING_CONNECTOR_STATUSES`（`{1,2,3}` = 占着枪的那几个），
 *   **不是** `blocksDeviceDeletion()` 的 `{1,2,3,4,6,9}` —— 后者是站点/充电桩的判据。
 *   混用会说错话：一把枪即便有过「已支付」的历史订单也不影响删除，
 *   而用后者写出来就是「有历史订单所以删不掉」，让运维白等一场。
 */
const deleteConsequences = computed(() => [
  `有订单正占着这把枪时会被后端拒绝：订单处于「${HOLDING_CONNECTOR_STATUSES.map(
    orderStatusLabel,
  ).join('、')}」都算占用。`,
  '枪的状态是「已占用」或「充电中」时也会被拒绝 —— 这两个状态只能由订单流程改回去。',
  '只是想让它暂时不接单，「编辑 → 状态 → 维护」就够了，不必删除。',
  '历史订单不受影响：订单记的是枪的 id。',
])

function askRemove(row: ConnectorVO): void {
  deleteTarget.value = row
  deleteError.value = null
  deleteOpen.value = true
}

async function doRemove(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return

  // ★ `silent = true`：页面自己接管这条提示（要在弹窗里留住原因 + 出路），
  //   所以不让全局 onError 再弹一次 —— 那会在弹窗上面叠一个 toast。
  const outcome = await runRemove(() => deleteConnector(target.id, true))
  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    // 原文照显：后端那句「该充电枪当前有订单占用，不能删除」比任何转述都准确
    deleteError.value = errorText(outcome.error)
    return // 弹窗**不关**：原因和出路都在这儿，关掉就看不见了
  }

  deleteOpen.value = false
  invalidateOptionsByTag(TAG.connector, TAG.charger)
  ElMessage({ type: 'success', message: `已删除充电枪「${target.gunNo}」`, duration: 2000 })
  await afterRemove()
}

/**
 * 去订单列表。
 *
 * ★ 只能送到列表页，**不能**带上这把枪的筛选条件：订单的查询类型里
 *   是按站点/手机号/状态/时间范围筛的，没有「按枪筛」。硬拼一个
 *   `?connectorId=` 会被后端静默忽略（关了 `FAIL_ON_UNKNOWN_PROPERTIES`），
 *   用户以为筛过了、其实看到的是全部订单 —— 比不带参数更糟。
 */
function gotoOrders(): void {
  deleteOpen.value = false
  void router.push('/order')
}
</script>

<template>
  <div class="page">
    <PagePanel
      title="充电枪管理"
      subtitle="枪挂在充电桩上，不选所属桩时是全平台的枪。编辑使用乐观锁：若这把枪在编辑期间被别人改过，提交会被拒绝而不会覆盖对方的改动。"
      flush
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <CoIcon name="plus" :size="15" />
          新建充电枪
        </el-button>
      </template>

      <FilterBar :dirty="dirty" :loading="loading" @search="search" @reset="reset">
        <el-select v-model="filters.chargerId" class="is-wide" placeholder="所属充电桩" clearable>
          <el-option v-for="o in chargerItems" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-input
          v-model="filters.gunNo"
          placeholder="枪号（模糊）"
          clearable
          :maxlength="MAX_LEN.gunNo"
          @keyup.enter="search"
        />
        <!-- ★ 这里能用全部 6 个状态：筛选只是提问，不写回数据。
             「哪些枪正在充电 / 被占用」正是运维最常问的两个问题。 -->
        <el-select v-model="filters.status" placeholder="状态" clearable>
          <el-option v-for="o in filterStatusOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </FilterBar>

      <el-table v-loading="loading" :data="rows" row-key="id" size="default">
        <el-table-column prop="gunNo" label="枪号" width="90" show-overflow-tooltip />
        <el-table-column label="所属充电桩" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ chargerText(row.chargerId) }}</template>
        </el-table-column>
        <el-table-column label="接口类型" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.connectorType || '-' }}</template>
        </el-table-column>
        <el-table-column label="额定功率" width="115" align="right" class-name="co-num">
          <template #default="{ row }">
            <MoneyText :value="row.ratedPowerKw" size="sm" :grouping="false" /> kW
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <!-- ★ 全部 6 个状态都要显示得出来：`2 已占用`/`3 充电中` 虽然不能手工设，
                 但它们恰恰是「这把枪现在能不能用」的答案。
                 ★ 不用 `pulse`：组件文档写的是「同一屏最多一个」，
                 而一个列表里同时有多把在充电的枪是很正常的。 -->
            <StatusTag
              :label="row.statusLabel ?? CONNECTOR_STATUS.label(row.status)"
              :tone="CONNECTOR_STATUS.tone(row.status)"
              size="sm"
              :title="statusHint(row.status)"
            />
          </template>
        </el-table-column>
        <el-table-column label="当前订单" width="110">
          <template #default="{ row }">
            <el-button
              v-if="row.currentOrderId !== null"
              link
              type="primary"
              size="small"
              @click="gotoOrder(row.currentOrderId)"
            >
              #{{ row.currentOrderId }}
            </el-button>
            <span v-else class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="累计充电次数" width="120" align="right" class-name="co-num">
          <template #default="{ row }">{{ row.totalChargeCount }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">
            <span class="co-num">{{ formatServerTime(row.updateTime) ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
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
            title="还没有充电枪"
            description="充电枪挂在充电桩上，先在某个站点下建一根桩，再往桩上添加枪。"
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
      :title="
        isEditing ? `编辑充电枪「${chargerText(form.chargerId)} ${form.gunNo}」` : '新建充电枪'
      "
      width="660px"
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      @closed="onDialogClosed"
    >
      <!-- ★★ 乐观锁冲突的**唯一**落点 ★★
           toast 由全局 onError 弹过了（4 秒就没），这里给的是：
           一条留着的说明 + 一个能点的出路。两者不是重复，是两种媒介。 -->
      <el-alert v-if="conflict" class="conflict-alert" type="warning" :closable="false" show-icon>
        <template #title>这次提交没有被接受：这把枪刚被其他人改过</template>
        <p class="alert-text">{{ conflict }}</p>
        <el-button size="small" :disabled="submitting" @click="reloadForEdit">
          <CoIcon name="refresh" :size="14" />
          放弃我的改动，重新拉取最新内容
        </el-button>
      </el-alert>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="所属充电桩" prop="chargerId">
          <!-- ★★ 编辑时**只读展示** ★★
               `ConnectorUpdateDTO` 里没有 `chargerId`（换桩会让老桩的 `gun_count`
               多算、新桩的少算，历史订单里的枪也会指向别站的设备），
               做成可编辑的话用户改完提交，后端会静默忽略 —— 而用户以为挪过去了。 -->
          <el-input v-if="isEditing" :model-value="chargerText(form.chargerId)" disabled />
          <el-select v-else v-model="form.chargerId" placeholder="选择所属充电桩" clearable>
            <el-option v-for="o in chargerItems" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <p v-if="isEditing" class="field-note">
            所属充电桩建好后不可修改：换桩会让两根桩的枪数冗余列同时失真，历史订单里的枪也会指向别站的设备。
            要挪只能删掉这把枪重建。
          </p>
        </el-form-item>

        <div class="grid-2">
          <el-form-item label="枪号" prop="gunNo">
            <el-input
              v-model="form.gunNo"
              placeholder="如 A / 1 / 左枪"
              :maxlength="MAX_LEN.gunNo"
              :disabled="submitting"
            />
          </el-form-item>
          <el-form-item label="接口类型" prop="connectorType">
            <el-input
              v-model="form.connectorType"
              placeholder="选填，如 国标直流"
              :maxlength="MAX_LEN.interfaceType"
              :disabled="submitting"
            />
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="额定功率（kW）" prop="ratedPowerKw">
            <el-input v-model="form.ratedPowerKw" placeholder="如 120.00" :disabled="submitting" />
          </el-form-item>
          <el-form-item v-if="isEditing" label="状态" prop="status">
            <el-select v-model="form.status" :disabled="submitting">
              <el-option
                v-for="o in statusItems"
                :key="o.value"
                :label="o.label"
                :value="o.value"
                :disabled="o.disabled"
              />
            </el-select>
          </el-form-item>
        </div>

        <!-- ★ 这一段是这个页面最要紧的说明：状态白名单的**原因**。
             只写「不能选充电中」是没用的，写清代价才会有人照做。 -->
        <p v-if="isEditing" class="field-note">
          「已占用」和「充电中」由充电流程自动维护，手工设不了：这两个状态和唯一索引
          <b>uk_connector_current_order</b> 绑定，手工设成「充电中」会造出一把没有订单却在充电的枪
          —— 别的车主扫不动它，而订单里也查不到它。
        </p>
        <p v-else class="field-note">
          新的枪状态固定为「离线」—— 它还没和任何硬件握过手，说它「空闲」需要一个从未发生过的状态上报。
        </p>

        <ul class="form-notes">
          <li>
            同一根桩上的枪号不可重复（唯一索引 <span class="co-ellipsis">uk_connector_charger_gun</span>）。
            枪号填错不用删除：直接编辑改名即可；但**删除会连枪号一起释放**，删掉重建要重新配枪。
          </li>
          <li>
            <strong>乐观锁</strong>：提交时会带上打开弹窗那一刻的版本号
            <span class="co-num">v{{ openedVersion }}</span>
            。这把枪期间被别人改过，提交就会被拒绝 —— 这是刻意的，不会静默覆盖。
          </li>
          <li>累计充电次数由结算累加，管理端改不了它（也不该改：它是充电量的历史事实）。</li>
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
      :title="`删除充电枪「${deleteTarget?.gunNo ?? ''}」`"
      description="删除后不可恢复。枪号会被释放，之后可以用同一个枪号重新登记。"
      :consequences="deleteConsequences"
      action-name="删除"
      :require-typed-value="deleteTarget?.gunNo ?? null"
      typed-label="请输入枪号以确认删除"
      :loading="removing"
      @confirm="doRemove"
    >
      <!-- ★ 后端拒绝时**留在弹窗里**：原因（原文）与出路并排放在一起 ——
           关掉弹窗只留一个 toast，用户转头就忘了该先去处理哪张订单。 -->
      <el-alert v-if="deleteError" class="delete-feedback" type="warning" :closable="false" show-icon>
        <template #title>{{ deleteError }}</template>
        <p class="alert-text">
          这是后端拒绝删除的原文。占着这把枪的订单要先在「订单管理」里处理掉（按所属站点 + 近 7 天筛一圈），
          再回来删；如果只是想让这把枪暂时不接单，把它改成「维护中」更快，也更安全。
        </p>
        <el-button size="small" :disabled="removing" @click="gotoOrders">去订单管理</el-button>
      </el-alert>
    </DangerConfirm>
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

.muted {
  color: var(--co-text-4);
}

.field-note {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.field-note b {
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

.form-notes strong {
  color: var(--co-text-2);
  font-weight: 600;
}

/* 冲突横幅在弹窗顶部，和表单之间留一口气 */
.conflict-alert,
.delete-feedback {
  margin-bottom: 16px;
}

.alert-text {
  margin: 6px 0 10px;
  font-size: 12.5px;
  line-height: 1.75;
}
</style>
