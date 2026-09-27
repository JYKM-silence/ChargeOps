<script setup lang="ts">
/**
 * 站点管理列表。
 *
 * ★ 这个页面里几乎所有「多写了几句」的地方都对应一条已核实的后端行为：
 *
 *   1. **删除不做预判**。后端有两道守卫（站点下还有桩 / 还有未了结的订单），
 *      而前端拿不到订单数 —— 任何「能不能删」的前端判断都会把其实能删的站点
 *      一起挡掉。所以照常给删除按钮，让后端拒绝，再把那句拒绝加工成一句引导。
 *      注意守卫用的是 `blocksDeviceDeletion(status)`（`{1,2,3,4,6,9}`），
 *      **不是** `!isTerminal(status)` —— 后者会让「完成过一次充电的站点永久不可删」。
 *
 *   2. **编辑走增量提交**。所有 `UpdateDTO` 的语义是「不传 = 不修改」，
 *      原样 PUT 整份表单等于把没碰过的字段也「改」了一遍（`update_time` 被刷新，
 *      审计里看起来像改了 20 个字段，而用户只改了电话）。
 *
 *   3. **营业时间是字符串**，`HH:mm` / `HH:mm:ss`：关门时间允许精确的 `24:00:00`
 *      （表示到当天结束），开始时间**不允许** 24 点 —— 所以两个字段用的是
 *      两个不同的校验函数（`isTimeHM` / `isTimeHMEnd`）。另外后端要求
 *      `close > open`，也就是**不支持跨夜营业**（`22:00`–`06:00` 表达不了）。
 *
 *   4. **金额与经纬度是 `BigDecimal`**。表单里全程按字符串收发表单（不 `parseFloat`），
 *      展示走 `MoneyText`（内部 BigInt 缩放），避免浮点把经度漂移几十米、
 *      或把停车费算出分位误差。
 */

import { computed, nextTick, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  MAX_LEN,
  STATION_STATUS,
  diffUpdate,
  invalidateOptionsByTag,
  isCode,
  isTimeHM,
  isTimeHMEnd,
  type StationDTO,
  type StationPageQuery,
  type StationUpdateDTO,
  type StationVO,
} from '@chargeops/shared'
import CoIcon from '@/components/CoIcon.vue'
import DangerConfirm from '@/components/DangerConfirm.vue'
import EmptyState from '@/components/EmptyState.vue'
import FilterBar from '@/components/FilterBar.vue'
import MoneyText from '@/components/MoneyText.vue'
import PagePanel from '@/components/PagePanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import { TAG } from '@/api/options'
import {
  createStation,
  deleteStation,
  getStation,
  pageStations,
  updateStation,
} from '@/api/station'
import { usePagedList } from '@/composables/usePagedList'
import { useSubmitLock } from '@/composables/useSubmitLock'

const router = useRouter()

// ── 列表 ───────────────────────────────────────────────────────────────────

const { rows, total, pageNum, pageSize, loading, loaded, error, filters, search, reset, reload, goPage, dirty, afterRemove } =
  usePagedList<StationVO, StationPageQuery>({
    fetch: (q) => pageStations(q),
    initial: { name: '', code: '', city: '', status: undefined },
  })

/**
 * 是否有**实质**筛选条件。
 *
 * ★ 不用 `dirty` 来判断空态：`el-select` 清空时给的是 `''`，而初始值是 `undefined`，
 *   于是「选了状态又清掉」会让 `dirty` 为 true —— 空态就会说「被筛掉了」，
 *   而用户屏幕上根本没有筛选条件。两件事分开判：`dirty` 只管「重置」按钮能不能点。
 */
const hasFilter = computed(() =>
  [filters.name, filters.code, filters.city, filters.status].some(
    (v) => v !== undefined && v !== null && String(v).trim() !== '',
  ),
)

/** 空态/删除失败要显示的原因。`ApiError extends Error`，所以 message 就是后端的中文文案。 */
function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

/** `HH:mm:ss` → `HH:mm`。列表里秒没有信息量，而 `24:00:00` 要保留成 `24:00`。 */
function hm(t: string | null | undefined): string {
  return t ? t.slice(0, 5) : '-'
}

/** 营业时间。两个字段一起看才有意义，所以合成一列而不是拆成两列。 */
function businessHours(row: StationVO): string {
  if (!row.openTime && !row.closeTime) return '未设置'
  if (hm(row.openTime) === '00:00' && hm(row.closeTime) === '24:00') return '全天 24 小时'
  return `${hm(row.openTime)} ~ ${hm(row.closeTime)}`
}

// ── 新建 / 编辑弹窗 ────────────────────────────────────────────────────────

/**
 * 表单结构。
 *
 * ★ 刻意用 `type` 而不是 `interface`：`diffUpdate` 的约束是
 *   `T extends Record<string, unknown>`，而 **interface 没有隐式索引签名**，
 *   用 interface 声明会让 `diffUpdate(original, form)` 直接编译不过。
 */
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

/**
 * 空表单。
 *
 * ★ 营业时间默认「全天」：后端的字段初始值就是 `00:00:00` / `24:00:00`。
 *   写成 `00:00` / `00:00` 会让新建的站点**默认零点就关门** —— 一个不报错、
 *   只是永远打不开的配置。
 */
function emptyForm(): StationForm {
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
const form = reactive<StationForm>(emptyForm())
/** 打开编辑弹窗时的表单快照，增量提交的基准。必须是**服务端原值**，不是上一次提交的值。 */
const originalForm = ref<StationForm>(emptyForm())
const editingId = ref<number | null>(null)
const editingName = ref('')

/**
 * 打开编辑弹窗那一刻服务端的 `updateTime`。
 *
 * ★★ 这是「客户端伪乐观锁」，**挡不住真并发** ★★
 *   站点（和桩）**没有 `version` 字段** —— 全项目只有充电枪有乐观锁。
 *   所以我们唯一能做的是：提交前重新读一次详情，`updateTime` 变了就中止。
 *   它把「两个人同时开着编辑框，后提交的人静默覆盖前一个人」的窗口
 *   从几分钟压缩到一次请求往返，但**校验与写入之间仍然有窗口**，
 *   真正的并发依然会有一方被覆盖。要彻底解决必须给表加 `version` 列。
 */
const openedUpdateTime = ref('')

const isEditing = computed(() => editingId.value !== null)

const { pending: submitting, run: runSubmit } = useSubmitLock({ throttleMs: 400 })

/** 字段名 → 中文，用于「这些字段清不掉」的提示。 */
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
  //   转成数字再转回去会丢精度（`113.943000` → `113.943`），
  //   而这一列是 `decimal(10,6)`，用来在地图上定位的。
  form.longitude = row.longitude === null || row.longitude === undefined ? '' : String(row.longitude)
  form.latitude = row.latitude === null || row.latitude === undefined ? '' : String(row.latitude)
  form.servicePhone = row.servicePhone ?? ''
  form.openTime = row.openTime ?? ''
  form.closeTime = row.closeTime ?? ''
  form.parkingFee = row.parkingFee === null || row.parkingFee === undefined ? '' : String(row.parkingFee)
  form.status = row.status
}

function openCreate(): void {
  editingId.value = null
  editingName.value = ''
  openedUpdateTime.value = ''
  Object.assign(form, emptyForm())
  originalForm.value = { ...form }
  dialogOpen.value = true
  // el-dialog 的内容是懒渲染的，首次打开时 formRef 还没挂上
  void nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row: StationVO): void {
  editingId.value = row.id
  editingName.value = row.name
  openedUpdateTime.value = row.updateTime
  fillForm(row)
  originalForm.value = { ...form }
  dialogOpen.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

/**
 * 生成一条「格式校验」规则：**空值放行**（必填由 `required` 那几条管），
 * 有值才交给 `check`。这样「不填」和「填错」是两种不同的提示。
 */
function formatRule(check: (v: string) => boolean, message: string) {
  return {
    validator: (_rule: unknown, value: string, callback: (err?: Error) => void): void => {
      if (!value) return callback()
      callback(check(value) ? undefined : new Error(message))
    },
    trigger: 'blur' as const,
  }
}

/** 数字区间校验：空值放行。 */
function rangeRule(min: number, max: number, message: string) {
  return formatRule((v) => {
    const n = Number(v)
    return Number.isFinite(n) && n >= min && n <= max
  }, message)
}

/**
 * 校验规则**逐条对应 `StationDTO` / `StationUpdateDTO` 上的注解**。
 * 松了白跑一次请求；紧了会把合法值挡在门外 —— 后者严重得多。
 */
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

/** 空字符串不参与提交：后端的可选字段是「不传 = 不修改」，空串在 `@Size` 上是另一个值。 */
function orUndefined(v: string): string | undefined {
  const t = v.trim()
  return t === '' ? undefined : t
}

function toCreateDTO(): StationDTO {
  return {
    code: form.code.trim(),
    name: form.name.trim(),
    operator: orUndefined(form.operator),
    province: orUndefined(form.province),
    city: orUndefined(form.city),
    district: orUndefined(form.district),
    address: orUndefined(form.address),
    longitude: orUndefined(form.longitude),
    latitude: orUndefined(form.latitude),
    servicePhone: orUndefined(form.servicePhone),
    openTime: form.openTime,
    closeTime: form.closeTime,
    parkingFee: orUndefined(form.parkingFee),
    status: form.status,
  }
}

/**
 * 剔除增量里「被清空」的键，并返回这些键的名字。
 *
 * ★★ 为什么必须剔：`UpdateDTO` 的语义是「不传 = 不修改」，**不是**
 *   「传 null / 空串 = 清空」。站点/桩/枪上这些字段属于**填了就抹不掉** ——
 *   用户在编辑框里删掉运营商再保存，后端收到 `''` 会跳过这一列，
 *   结果是「什么都没发生」，而他以为删掉了。
 *
 * ★ shared 的 `UNSETTABLE_FIELDS` 只列了套餐/计费绑定那几个字段
 *   （它由 `diffUpdate` 消费），本域的字段不在那张表里，所以这里就地判一次：
 *   不发空值，同时把字段名交给调用方去告诉用户「已保持原值」。
 *   表单里**没有**任何「清空」按钮，是同一件事的另一半。
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

/** 提交结果。用字符串枚举而不是抛异常：`useSubmitLock.run` 会把异常吞成 `'failed'`。 */
type SubmitResult = 'saved' | 'nochange' | 'stale'

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const outcome = await runSubmit<SubmitResult>(async () => {
    if (editingId.value === null) {
      await createStation(toCreateDTO())
      return 'saved'
    }

    const id = editingId.value

    // ★ 伪乐观锁：提交前重读一次详情，`updateTime` 变了说明有人在编辑期间改过。
    //   读不到（`null`）就直接往下走 —— 真有问题让后端去报，不要在客户端编一个理由。
    const fresh = await getStation(id)
    if (fresh && fresh.updateTime !== openedUpdateTime.value) return 'stale'

    const { payload, unsettable } = diffUpdate(originalForm.value, form)
    const cleared = dropCleared(payload)
    const blocked = [...unsettable, ...cleared]

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

  if (outcome.status === 'failed') {
    // 失败提示由 api/http.ts 的全局 onError 统一弹出（后端的中文 msg 就是文案），
    // 这里不重复弹一次。
    return
  }

  if (outcome.value === 'stale') {
    ElMessage({
      type: 'warning',
      message: '这条站点在编辑期间被其他人改过。请关闭弹窗重新打开，确认最新内容后再提交。',
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
  // ★ 站点改名后，桩/枪列表里显示站名的地方也会变，所以连 'charger' 一起失效
  invalidateOptionsByTag(TAG.station, TAG.charger)
  await reload()
}

function onDialogClosed(): void {
  formRef.value?.clearValidate()
}

// ── 删除 ───────────────────────────────────────────────────────────────────

const deleteOpen = ref(false)
const deleteTarget = ref<StationVO | null>(null)
const { pending: removing, run: runRemove } = useSubmitLock({ throttleMs: 500 })

function askRemove(row: StationVO): void {
  deleteTarget.value = row
  deleteOpen.value = true
}

async function doRemove(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return

  // ★ `silent: true` —— 这条失败提示要自己接管，把后端的原文和下一步动作说在一起
  const outcome = await runRemove(() => deleteStation(target.id, true))
  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    ElMessage({
      type: 'warning',
      message: `${errorText(outcome.error)} 若该站点下还有充电桩或进行中的订单，请先在「充电桩」或「订单管理」里处理完再删除。`,
      duration: 6000,
      showClose: true,
    })
    return // 弹窗**不关**：保持上下文，用户处理完可以直接再点一次
  }

  deleteOpen.value = false
  invalidateOptionsByTag(TAG.station, TAG.charger)
  ElMessage({ type: 'success', message: `已删除站点「${target.name}」`, duration: 2000 })
  // ★ 用 afterRemove 而不是 reload：末页只剩一条时它会回退一页，
  //   否则会停在上一页显示空列表（后端 reasonable:true 把页码改掉但不告诉我们）
  await afterRemove()
}
</script>

<template>
  <div class="page">
    <PagePanel
      title="站点管理"
      subtitle="站点是充电桩与计费绑定的归属。删除只在「站点下没有充电桩」且「没有进行中的订单」时才会成功。"
      flush
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <CoIcon name="plus" :size="15" />
          新建站点
        </el-button>
      </template>

      <FilterBar :dirty="dirty" :loading="loading" @search="search" @reset="reset">
        <el-input
          v-model="filters.name"
          placeholder="站点名称（模糊）"
          clearable
          :maxlength="MAX_LEN.stationName"
          @keyup.enter="search"
        />
        <el-input
          v-model="filters.code"
          placeholder="站点编码（前缀）"
          clearable
          :maxlength="MAX_LEN.code"
          @keyup.enter="search"
        />
        <el-input
          v-model="filters.city"
          placeholder="城市（精确）"
          clearable
          :maxlength="32"
          @keyup.enter="search"
        />
        <el-select v-model="filters.status" placeholder="状态" clearable>
          <el-option v-for="o in STATION_STATUS.options()" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </FilterBar>

      <el-table v-loading="loading" :data="rows" row-key="id" size="default">
        <el-table-column prop="code" label="编码" width="150" show-overflow-tooltip />
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="city" label="城市" width="100">
          <template #default="{ row }">{{ row.city || '-' }}</template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.address || '-' }}</template>
        </el-table-column>
        <el-table-column label="营业时间" width="150">
          <template #default="{ row }">
            <span class="co-num">{{ businessHours(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="停车费" width="100" align="right" class-name="co-num">
          <template #default="{ row }">
            <!-- ★ 不进数字运算：`MoneyText` 内部是 BigInt 缩放 -->
            <MoneyText :value="row.parkingFee" size="sm" :grouping="false" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <StatusTag :label="row.statusLabel" :tone="STATION_STATUS.tone(row.status)" size="sm" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="176" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="router.push(`/station/${row.id}`)">
              详情
            </el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="askRemove(row)">删除</el-button>
          </template>
        </el-table-column>

        <!-- ★ 空态要区分三种「没有数据」：真的没有 / 被筛掉了 / 加载失败。
             合成一句「暂无数据」会让用户以为数据丢了，然后跑去问运维。 -->
        <template #empty>
          <EmptyState
            v-if="loaded && error"
            kind="error"
            :description="errorText(error)"
            @retry="reload"
          />
          <EmptyState v-else-if="loaded && hasFilter" kind="filtered" @retry="reset" />
          <EmptyState
            v-else-if="loaded"
            kind="empty"
            title="还没有站点"
            description="先建一个站点，再往里面添加充电桩和计费绑定。"
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
      :title="isEditing ? `编辑站点「${editingName}」` : '新建站点'"
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

        <!-- ★ 下面两条是**必须让用户看到的约束**，不是客套话：
             不知道「清不掉」的人会在编辑框里删掉电话然后反复保存，然后报 bug。 -->
        <ul class="form-notes">
          <li>
            营业时间用 <code>HH:mm</code> 格式。结束时间可以写 <code>24:00:00</code> 表示「到当天结束」，
            但开始时间不能是 24 点；结束时间必须晚于开始时间，<strong>不支持跨夜营业</strong>。
          </li>
          <li>
            运营商、省/市/区、地址、经纬度、停车费<strong>填过之后无法清空</strong>：后端的
            「不传」表示「不修改」。要改就直接填新值覆盖，表单里没有「清空」按钮。
          </li>
          <li>经纬度写反是一个经典事故（两个方向仍各自合法，只是落到了地球另一边），提交前请对一眼。</li>
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
      :title="`删除站点「${deleteTarget?.name ?? ''}」`"
      description="删除是硬删除，不可恢复。站点编码会被释放，之后可以重新使用同一个编码。"
      :consequences="[
        '站点下还有充电桩时无法删除，需要先处理那些桩（或把它们删掉）。',
        '站点下有进行中的订单时会被后端拒绝 —— 请先在「订单管理」里处理完。',
        '历史订单不受影响：它们记的是站点 id，删除后仍能追溯到这条记录。',
      ]"
      action-name="删除"
      :require-typed-value="deleteTarget?.name ?? null"
      typed-label="请输入站点名称以确认删除"
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

/* 表格下方留一点呼吸；表格本身自带 12px 边距，所以这里不再加左右内边距 */
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 8px;
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
