<script setup lang="ts">
/**
 * 充电桩管理列表。**跨站点**的列表 —— 不选站点就是全平台。
 *
 * ★ 几条必须照做的后端约束：
 *
 *   1. **`stationId` 建好后不可改**。`ChargerUpdateDTO` 里根本没有这个字段：
 *      换站会让它每把枪的冗余列 `station_id` 全部失真，历史订单指向的站点也变成错的。
 *      所以编辑弹窗里「所属站点」是**只读展示** —— 做成可编辑的话，用户改完提交，
 *      后端因为关了 `FAIL_ON_UNKNOWN_PROPERTIES` 会静默忽略，用户以为改成功了。
 *
 *   2. **桩的状态没有白名单**，`0~4` 全都可以设（`@Min(0) @Max(4)`，Service 里无校验）。
 *      `ChargerStatus` 类注释里那句「管理员只能改故障/维护中」是**过时注释**，
 *      与 DTO、实现都不符。枪才有白名单，桩没有 —— 别把两边写成一个样。
 *
 *   3. **`gunCount` 只读**。它是冗余列，事实来源是 `connector` 表，
 *      由枪的增删在同一事务里重算。任何 DTO 里都没有它，表单里也不该出现。
 *
 *   4. **删除不预判**。桩下还有枪、或还有未了结的订单，后端都会拒绝，
 *      而前端拿不到订单数。让后端拒绝，再把那句话加工成引导。
 */

import { computed, nextTick, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  CHARGER_STATUS,
  CHARGER_TYPE,
  MAX_LEN,
  diffUpdate,
  formatRelative,
  invalidateOptionsByTag,
  oneOf,
  toSelectItems,
  type ChargerDTO,
  type ChargerPageQuery,
  type ChargerUpdateDTO,
  type ChargerVO,
} from '@chargeops/shared'
import CoIcon from '@/components/CoIcon.vue'
import DangerConfirm from '@/components/DangerConfirm.vue'
import EmptyState from '@/components/EmptyState.vue'
import FilterBar from '@/components/FilterBar.vue'
import MoneyText from '@/components/MoneyText.vue'
import PagePanel from '@/components/PagePanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import { TAG, activeStationOptions, stationOptions } from '@/api/options'
import {
  createCharger,
  deleteCharger,
  getCharger,
  pageChargers,
  updateCharger,
} from '@/api/charger'
import { useOptions } from '@/composables/useOptions'
import { usePagedList } from '@/composables/usePagedList'
import { useSubmitLock } from '@/composables/useSubmitLock'

// ── 列表 ───────────────────────────────────────────────────────────────────

const { rows, total, pageNum, pageSize, loading, loaded, error, filters, search, reset, reload, goPage, dirty, afterRemove } =
  usePagedList<ChargerVO, ChargerPageQuery>({
    fetch: (q) => pageChargers(q),
    initial: { stationId: undefined, deviceNo: '', name: '', type: undefined, status: undefined },
  })

/**
 * 筛选用的站点下拉。
 *
 * ★ 用 `stationOptions`（**不过滤停用站点**）而不是 `activeStationOptions`：
 *   已经绑定了停用站点的桩仍然要在列表里被筛出来，把它们从下拉里去掉会让
 *   「这个站明明有桩，却选不到」。
 */
const { options: filterStationOpts } = useOptions(stationOptions)

/** 新建用的站点下拉：只列「启用」的站点（`activeStationOptions` 已带 `status: 1`）。 */
const { options: createStationOpts } = useOptions(activeStationOptions)

/** 是否有**实质**筛选条件。`el-select` 清空给的是 `''`，而初始值是 `undefined`，两者都算没填。 */
const hasFilter = computed(() =>
  [filters.stationId, filters.deviceNo, filters.name, filters.type, filters.status].some(
    (v) => v !== undefined && v !== null && String(v).trim() !== '',
  ),
)

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

/**
 * 最后心跳。
 *
 * ★ 这是**判断桩是否在线**的关键信息，所以显示相对时间（「3 分钟前」）——
 *   一串绝对时间需要用户自己做减法。`title` 里保留绝对时间，需要精确值时悬停即可。
 *   `lastHeartbeatTime` 为 null 表示**从未上线**（不是「很久以前」），要分开说。
 */
function heartbeatText(t: string | null): string {
  return t ? formatRelative(t) : '从未上线'
}

// ── 新建 / 编辑弹窗 ────────────────────────────────────────────────────────

/**
 * ★ 用 `type` 而不是 `interface`：`diffUpdate` 的约束是
 *   `T extends Record<string, unknown>`，interface 没有隐式索引签名，会编译不过。
 */
type ChargerForm = {
  stationId: number | undefined
  deviceNo: string
  name: string
  type: number | undefined
  model: string
  ratedPowerKw: string
  status: number | undefined
  firmwareVersion: string
}

function emptyForm(): ChargerForm {
  return {
    stationId: undefined,
    deviceNo: '',
    name: '',
    type: 1,
    model: '',
    ratedPowerKw: '',
    status: undefined,
    firmwareVersion: '',
  }
}

const dialogOpen = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<ChargerForm>(emptyForm())
/** 增量提交的基准。必须是**服务端原值**，不是上一次提交的值。 */
const originalForm = ref<ChargerForm>(emptyForm())
const editingId = ref<number | null>(null)
const editingDeviceNo = ref('')
/** 编辑时只读展示的站点名（`ChargerUpdateDTO` 里没有 `stationId`，改不了）。 */
const editingStationName = ref('')

/**
 * 打开编辑弹窗那一刻服务端的 `updateTime`。
 * ★ 桩上**没有乐观锁**（`ChargerUpdateDTO` 里没有 `version`），
 *   所以只能用这个「客户端伪乐观锁」：提交前重读一次比对。它**挡不住真并发** ——
 *   校验与写入之间仍有窗口，两人同时改同一字段时后写的仍然会覆盖先写的。
 */
const openedUpdateTime = ref('')

const isEditing = computed(() => editingId.value !== null)

const { pending: submitting, run: runSubmit } = useSubmitLock({ throttleMs: 400 })

const FIELD_LABEL: Record<string, string> = {
  deviceNo: '设备编号',
  name: '充电桩名称',
  type: '类型',
  model: '型号',
  ratedPowerKw: '额定功率',
  status: '状态',
  firmwareVersion: '固件版本',
}

function fillForm(row: ChargerVO): void {
  form.stationId = row.stationId
  form.deviceNo = row.deviceNo
  form.name = row.name
  form.type = row.type
  form.model = row.model ?? ''
  form.ratedPowerKw = row.ratedPowerKw === null || row.ratedPowerKw === undefined ? '' : String(row.ratedPowerKw)
  form.status = row.status
  form.firmwareVersion = row.firmwareVersion ?? ''
}

function openCreate(): void {
  editingId.value = null
  editingDeviceNo.value = ''
  editingStationName.value = ''
  openedUpdateTime.value = ''
  Object.assign(form, emptyForm())
  originalForm.value = { ...form }
  dialogOpen.value = true
  // el-dialog 内容懒渲染，首次打开时 formRef 还没挂上
  void nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row: ChargerVO): void {
  editingId.value = row.id
  editingDeviceNo.value = row.deviceNo
  editingStationName.value = row.stationName ?? `#${row.stationId}`
  openedUpdateTime.value = row.updateTime
  fillForm(row)
  originalForm.value = { ...form }
  dialogOpen.value = true
  void nextTick(() => formRef.value?.clearValidate())
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

/** 规则逐条对应 `ChargerDTO` / `ChargerUpdateDTO` 上的注解（松紧都要一致）。 */
const rules: FormRules = {
  stationId: [{ required: true, message: '请选择所属站点', trigger: 'change' }],
  deviceNo: [
    { required: true, message: '请输入设备编号', trigger: 'blur' },
    { max: MAX_LEN.deviceNo, message: `设备编号不能超过 ${MAX_LEN.deviceNo} 个字符`, trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入充电桩名称', trigger: 'blur' },
    { max: MAX_LEN.chargerName, message: `名称不能超过 ${MAX_LEN.chargerName} 个字符`, trigger: 'blur' },
  ],
  type: [
    {
      validator: (_rule: unknown, value: number | undefined, callback: (err?: Error) => void): void => {
        callback(oneOf(value, 1, 2) ? undefined : new Error('类型只能是 1 直流快充、2 交流慢充'))
      },
      trigger: 'change',
    },
  ],
  model: [{ max: MAX_LEN.model, message: `型号不能超过 ${MAX_LEN.model} 个字符`, trigger: 'blur' }],
  // ★ 额定功率必须 > 0（后端的 `@DecimalMin(inclusive = false)`），且不能为空 ——
  //   `charger.rated_power_kw` 是 NOT NULL 且库无默认值，不传是 500 而不是 400。
  ratedPowerKw: [
    { required: true, message: '请输入额定功率', trigger: 'blur' },
    formatRule((v) => Number.isFinite(Number(v)) && Number(v) > 0, '额定功率必须大于 0'),
  ],
  firmwareVersion: [
    { max: 32, message: '固件版本不能超过 32 个字符', trigger: 'blur' },
  ],
}

function orUndefined(v: string): string | undefined {
  const t = v.trim()
  return t === '' ? undefined : t
}

function toCreateDTO(): ChargerDTO {
  return {
    // ★ 校验已保证非空，这里断言只为消掉 `number | undefined`
    stationId: form.stationId as number,
    deviceNo: form.deviceNo.trim(),
    name: form.name.trim(),
    type: form.type as number,
    model: orUndefined(form.model),
    ratedPowerKw: form.ratedPowerKw,
    firmwareVersion: orUndefined(form.firmwareVersion),
    // ★ 刻意**不传** `lastHeartbeatTime`：它只在从旧平台导入存量桩时才有意义，
    //   正常运行由设备心跳上报。管理端能设置它就等于能伪造一条「刚刚还在线」的记录，
    //   而运维恰恰靠这个字段判断桩是不是掉线了。
  }
}

/**
 * 剔除增量里「被清空」的键。
 *
 * ★ `UpdateDTO` 的语义是「不传 = 不修改」，**不是**「传空 = 清空」：
 *   型号、固件版本这类字段**填了就抹不掉**。发空值要么被后端跳过（用户以为删掉了），
 *   要么在 `@Size`/`@Pattern` 上变成另一个值。所以这里直接不发，并回报字段名。
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

type SubmitResult = 'saved' | 'nochange' | 'stale'

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const outcome = await runSubmit<SubmitResult>(async () => {
    if (editingId.value === null) {
      await createCharger(toCreateDTO())
      return 'saved'
    }

    const id = editingId.value

    const fresh = await getCharger(id)
    if (fresh && fresh.updateTime !== openedUpdateTime.value) return 'stale'

    const { payload, unsettable } = diffUpdate(originalForm.value, form)
    // ★ 编辑时**不允许**改所属站点：`stationId` 不在 `ChargerUpdateDTO` 里，
    //   留在增量里只会让后端静默忽略。这里显式摘掉，双保险。
    //
    // ★ 不要写成 `delete (payload as ChargerUpdateDTO).stationId` ——
    //   那个 `as` 会把 `stationId` **断言掉**，于是 TS 认为这个键根本不存在，
    //   类型检查直接报错；就算用 `as unknown as` 压过去，也只是把类型断言
    //   当成消音器，而不是真的删掉了什么。`payload` 本来就是 `Partial<T>`，
    //   键是可选的，直接删即可。
    delete payload.stationId

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

    await updateCharger(id, payload as ChargerUpdateDTO)
    return 'saved'
  })

  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') return // 全局 onError 已弹过后端原文

  if (outcome.value === 'stale') {
    ElMessage({
      type: 'warning',
      message: '这根桩在编辑期间被其他人改过。请关闭弹窗重新打开，确认最新内容后再提交。',
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

  ElMessage({ type: 'success', message: isEditing.value ? '已保存' : '已创建', duration: 1800 })
  // ★ 桩改名/换类型后，枪列表和订单页里的桩下拉都受影响，所以连 'connector' 一起失效
  invalidateOptionsByTag(TAG.charger, TAG.connector)
  await reload()
}

function onDialogClosed(): void {
  formRef.value?.clearValidate()
}

// ── 删除 ───────────────────────────────────────────────────────────────────

const deleteOpen = ref(false)
const deleteTarget = ref<ChargerVO | null>(null)
const { pending: removing, run: runRemove } = useSubmitLock({ throttleMs: 500 })

function askRemove(row: ChargerVO): void {
  deleteTarget.value = row
  deleteOpen.value = true
}

async function doRemove(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return

  const outcome = await runRemove(() => deleteCharger(target.id, true))
  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    ElMessage({
      type: 'warning',
      message: `${errorText(outcome.error)} 请先处理该桩下进行中的订单（或删掉它的充电枪）再试。`,
      duration: 6000,
      showClose: true,
    })
    return // 不关弹窗：保持上下文，处理完可以直接再点一次
  }

  deleteOpen.value = false
  invalidateOptionsByTag(TAG.charger, TAG.connector)
  ElMessage({ type: 'success', message: `已删除充电桩「${target.deviceNo}」`, duration: 2000 })
  await afterRemove()
}
</script>

<template>
  <div class="page">
    <PagePanel
      title="充电桩管理"
      subtitle="不选站点时是跨站点的全平台列表。桩建好后不能换站；删除只在「桩下没有充电枪」且「没有进行中的订单」时才会成功。"
      flush
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <CoIcon name="plus" :size="15" />
          新建充电桩
        </el-button>
      </template>

      <FilterBar :dirty="dirty" :loading="loading" @search="search" @reset="reset">
        <el-select v-model="filters.stationId" placeholder="所属站点" clearable>
          <el-option v-for="o in filterStationOpts" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-input
          v-model="filters.deviceNo"
          placeholder="设备编号（前缀）"
          clearable
          :maxlength="MAX_LEN.deviceNo"
          @keyup.enter="search"
        />
        <el-input
          v-model="filters.name"
          placeholder="桩位名（模糊）"
          clearable
          :maxlength="MAX_LEN.chargerName"
          @keyup.enter="search"
        />
        <el-select v-model="filters.type" placeholder="类型" clearable>
          <el-option v-for="o in CHARGER_TYPE.options()" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable>
          <el-option v-for="o in CHARGER_STATUS.options()" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </FilterBar>

      <el-table v-loading="loading" :data="rows" row-key="id" size="default">
        <el-table-column prop="deviceNo" label="设备编号" width="150" show-overflow-tooltip />
        <el-table-column prop="name" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column label="站点" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.stationName || `#${row.stationId}` }}</template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <StatusTag :label="row.typeLabel" :tone="CHARGER_TYPE.tone(row.type)" size="sm" />
          </template>
        </el-table-column>
        <el-table-column label="额定功率" width="110" align="right" class-name="co-num">
          <template #default="{ row }">
            <MoneyText :value="row.ratedPowerKw" size="sm" :grouping="false" /> kW
          </template>
        </el-table-column>
        <el-table-column label="枪数" width="70" align="right" class-name="co-num">
          <template #default="{ row }">{{ row.gunCount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <!-- 桩的状态没有白名单，`0~4` 都可能是管理端设的，所以只按字典上色 -->
            <StatusTag :label="row.statusLabel" :tone="CHARGER_STATUS.tone(row.status)" size="sm" />
          </template>
        </el-table-column>
        <el-table-column label="最后心跳" width="130">
          <template #default="{ row }">
            <span v-if="row.lastHeartbeatTime" class="co-num" :title="row.lastHeartbeatTime">
              {{ heartbeatText(row.lastHeartbeatTime) }}
            </span>
            <span v-else class="muted">从未上线</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
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
            title="还没有充电桩"
            description="先在某个站点下建一根桩，再往桩上添加充电枪。"
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
      :title="isEditing ? `编辑充电桩「${editingDeviceNo}」` : '新建充电桩'"
      width="640px"
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      @closed="onDialogClosed"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="所属站点" prop="stationId">
          <!-- ★★ 编辑时**只读展示** ★★
               `ChargerUpdateDTO` 里没有 `stationId`（换站会让每把枪的冗余列失真、
               历史订单指向的站点也变成错的），做成可编辑的话用户改完提交，
               后端会静默忽略 —— 而用户以为改成功了。 -->
          <el-input v-if="isEditing" :model-value="editingStationName" disabled />
          <el-select v-else v-model="form.stationId" placeholder="选择站点（只列出启用中的）" clearable>
            <el-option
              v-for="o in toSelectItems(createStationOpts)"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
          <p v-if="isEditing" class="field-note">
            所属站点建好后不可修改：换站会让它下面每把枪的站点归属失真，历史订单指向的站点也会变错。
            要挪站只能删掉这根桩重建。
          </p>
        </el-form-item>

        <div class="grid-2">
          <el-form-item label="设备编号" prop="deviceNo">
            <el-input
              v-model="form.deviceNo"
              placeholder="如 DEV-2026-0001"
              :maxlength="MAX_LEN.deviceNo"
              :disabled="submitting"
            />
          </el-form-item>
          <el-form-item label="名称" prop="name">
            <el-input
              v-model="form.name"
              placeholder="如 A区-01"
              :maxlength="MAX_LEN.chargerName"
              :disabled="submitting"
            />
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="类型" prop="type">
            <el-radio-group v-model="form.type" :disabled="submitting">
              <el-radio-button v-for="o in CHARGER_TYPE.options()" :key="o.value" :value="o.value">
                {{ o.label }}
              </el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="额定功率（kW）" prop="ratedPowerKw">
            <el-input
              v-model="form.ratedPowerKw"
              placeholder="如 120.00"
              :disabled="submitting"
            />
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="型号" prop="model">
            <el-input v-model="form.model" placeholder="选填" :maxlength="MAX_LEN.model" :disabled="submitting" />
          </el-form-item>
          <el-form-item label="固件版本" prop="firmwareVersion">
            <el-input
              v-model="form.firmwareVersion"
              placeholder="选填，一般由设备上报"
              :maxlength="32"
              :disabled="submitting"
            />
          </el-form-item>
        </div>

        <el-form-item v-if="isEditing" label="状态" prop="status">
          <!-- ★ 桩的状态**没有白名单**：0~4 全部可设（枪只有 4 个值，别照抄那边）。
               桩的「充电中」是派生状态（它下面有枪在充），设错了下一次心跳会纠正它。 -->
          <el-select v-model="form.status" :disabled="submitting">
            <el-option v-for="o in CHARGER_STATUS.options()" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <p class="field-note">
            「充电中」是派生状态（它下面有枪在充），手工设错不破坏约束，下一次心跳会纠正。
          </p>
        </el-form-item>
        <p v-else class="field-note">
          新建的桩状态固定为「离线」—— 它还没和设备建立过连接，说它在线需要一个从未发生过的心跳。
        </p>

        <ul class="form-notes">
          <li>枪数由充电枪的数量决定（冗余列），不在这里维护：加一把枪它才会变成 1。</li>
          <li>型号、固件版本<strong>填过之后无法清空</strong>：后端的「不传」表示「不修改」，要改就填新值覆盖。</li>
          <li>「最后心跳时间」不由管理端填写：它是设备上报的事实，手工改会让「这台桩是不是掉线了」失去依据。</li>
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
      :title="`删除充电桩「${deleteTarget?.deviceNo ?? ''}」`"
      description="删除后不可恢复。设备编号会被释放，之后可以用同一个编号重新登记。"
      :consequences="[
        '桩下还有充电枪时无法删除，需要先把那些枪删掉。',
        '桩（或它的枪）上有进行中的订单时会被后端拒绝 —— 请先在「订单管理」里处理完。',
        '历史订单不受影响：它们记的是桩和枪的 id。',
      ]"
      action-name="删除"
      :require-typed-value="deleteTarget?.deviceNo ?? null"
      typed-label="请输入设备编号以确认删除"
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

.muted {
  color: var(--co-text-4);
}

.field-note {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
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
</style>
