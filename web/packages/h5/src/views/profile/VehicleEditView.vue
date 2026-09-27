<script setup lang="ts">
/**
 * 车辆的「新增」与「编辑」—— 同一个组件两种模式。
 *
 * ★ 路由有两条（`/profile/vehicles/new` 与 `/profile/vehicles/:id/edit`），
 *   用 `route.params.id` 区分。合成一个组件而不是拆两个：两种模式**只有
 *   提交时调的函数不同**，拆开必然把表单布局、校验、颜色选择器各复制一份，
 *   然后其中一份先被改（比如车牌长度上限变了），另一份静默地旧着。
 *
 * ★★ 一、表单里**没有**「设为默认」★★
 *
 *   `VehicleUpdateDTO` 里**没有** `isDefault` —— 改默认车只有
 *   `PUT /{id}/default` 一个入口（理由见那个 DTO 的类注释：两条写入路径
 *   通向同一个不变量，迟早有一条忘了清旧的，结果是**两辆默认车**，
 *   而且不报错、不冲突）。
 *
 *   往 update 里塞一个 `isDefault` 是**最坏的一种失败**：后端关掉了
 *   `FAIL_ON_UNKNOWN_PROPERTIES`，它不报错、直接丢弃 —— 用户勾了「设为默认」、
 *   保存、看到「保存成功」、然后发现没有生效。所以「设为默认」是列表页上的
 *   一个独立动作（见 `VehicleListView`），不是这个表单里的一个勾。
 *
 * ★★ 二、只提交**改动过**的字段（`diffUpdate`）★★
 *   `*UpdateDTO` 的语义是「传了就改、不传就不动」。整表单原样 PUT 会把没碰过的
 *   字段也「改」一遍（`update_time` / `update_user` 被全量刷新），而车辆没有
 *   version 字段，读-改-写会静默覆盖别人的改动。
 *
 * ★★ 三、清空字段：后端「做不到」，所以必须说出来 ★★
 *   `VehicleMapper.xml` 的 `<if test="brand != null and brand != ''">` 让空串和
 *   null **都表示「不修改」**。于是「把品牌删干净再保存」的结果是
 *   **保存成功、什么都没变** —— 一次静默的假成功，用户以为删掉了。
 *   → 提交前先告诉他哪些字段清不掉，让他自己决定要不要继续。
 *     （`diffUpdate` 的 `UNSETTABLE_FIELDS` 只收了 `remark`；同一个后端机制
 *      还罩着 `plateNo` / `brand` / `model` / `batteryCapacityKwh`，这里补齐。）
 */

import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import {
  MAX_LEN,
  PLATE_COLOR,
  diffUpdate,
  fromScaled,
  isActionLocked,
  isPartialAmountInput,
  toScaled,
  withActionLock,
  type Decimal,
  type VehicleDTO,
  type VehicleUpdateDTO,
  type VehicleVO,
} from '@chargeops/shared'
import { createVehicle, getVehicle, updateVehicle } from '@/api/vehicle'
import { useAsyncData } from '@/composables/useAsyncData'
import { useSubmitLock } from '@/composables/useSubmitLock'
import PageState from '@/components/PageState.vue'

/**
 * `decimal(8,2)` 的物理上限：999999.99。放进「分」就是 99999999n。
 * ★ 后端**没有**给电池容量设 `@Max`（`VehicleDTO` 说它只用于估算、不参与计费），
 *   所以这不是一条业务规则，只是把「超出列宽 → 数据库报 500」提前变成一个
 *   能看懂的提示。判据用 BigInt 缩放，不碰浮点。
 */
const BATTERY_MAX_CENTS = 99_999_999n

/** 车辆编辑的动作锁窗口：与列表页的删除/设默认用同一档（3 秒）。 */
const SAVE_LOCK_MS = 3_000

/** 表单字段名与 DTO 字段名**逐字对齐** —— `diffUpdate` 的产物直接就是 DTO。 */
interface VehicleForm {
  plateNo: string
  plateColor: number
  brand: string
  model: string
  batteryCapacityKwh: string
  remark: string
}

/**
 * 后端「设了就没法清空」的字段（`<if test="x != null and x != ''">`）。
 * ★ 值的来源是 `VehicleMapper.xml` 的 update 语句，不是猜的。
 */
const UNCLEARABLE: Record<string, string> = {
  plateNo: '车牌号',
  brand: '品牌',
  model: '型号',
  batteryCapacityKwh: '电池容量',
  remark: '备注',
}

const route = useRoute()
const router = useRouter()
const { pending, run } = useSubmitLock()

const isEdit = computed(() => {
  const id = route.params.id
  return typeof id === 'string' && id !== ''
})
const vehicleId = computed(() => String(route.params.id ?? ''))

const form = reactive<VehicleForm>({
  plateNo: '',
  plateColor: 1,
  brand: '',
  model: '',
  batteryCapacityKwh: '',
  remark: '',
})
/** 服务端原值快照 —— 增量提交的基准（**必须**是服务端的值，不是上次提交的值）。 */
const original = ref<VehicleForm>({ ...form })

/**
 * 服务端的十进制值 → 输入框文本。
 *
 * ★ 刻意**不用** `formatMoney`：它带千分位（`1234.56` → `"1,234.56"`），
 *   而带逗号的字符串回填进输入框后，用户直接点保存时会被
 *   `isPartialAmountInput` 判为非法 —— 一个只有「值大于一千」时才会出现的 bug。
 *   这里只要「按 2 位小数归一」，不要给人看的格式。
 */
function toInput(value: Decimal): string {
  if (value === null || value === undefined || value === '') return ''
  return fromScaled(toScaled(value, 2), 2)
}

/** 编辑模式下这一辆车的加载。新增模式不请求，直接给 null。 */
const {
  data: vehicle,
  loading,
  failed,
  reload,
} = useAsyncData<VehicleVO>(() =>
  isEdit.value ? getVehicle(vehicleId.value) : Promise.resolve(null),
)

watch(vehicle, (vo) => {
  if (!vo) return
  const next: VehicleForm = {
    plateNo: vo.plateNo,
    plateColor: vo.plateColor,
    brand: vo.brand ?? '',
    model: vo.model ?? '',
    // 归一成两位小数再进输入框：后端可能给 `85` / `85.0` / `85.0000`，
    // 直接回填的话，用户看到的小数位数取决于数据是怎么进库的。
    batteryCapacityKwh: toInput(vo.batteryCapacityKwh),
    remark: vo.remark ?? '',
  }
  Object.assign(form, next)
  original.value = { ...next }
})

const colorVisible = ref(false)
const colorLabel = computed(() => PLATE_COLOR.label(form.plateColor))

function chooseColor(code: number): void {
  form.plateColor = code
  colorVisible.value = false
}

// ── 校验 ──────────────────────────────────────────────────────────────

/** 是否已经点过一次「保存」。空字段的报错只在**点过之后**才出现。 */
const submitted = ref(false)

const plateError = computed(() => {
  const value = form.plateNo.trim()
  if (value === '') return submitted.value ? '请输入车牌号' : ''
  if (value.length > MAX_LEN.plateNo) return `车牌号不能超过 ${MAX_LEN.plateNo} 个字符`
  // ★ 刻意**不做**车牌格式正则 —— 后端也不做（`VehicleDTO` 的注释：新能源、
  //   使馆、港澳、教练、警车等格式太多，写死正则只会挡住合法输入，
  //   而它挡住的东西**没有一条是数据库拦不住的**，唯一性由
  //   `uk_vehicle_member_plate` 守）。前端比后端严 = 合法车牌填不进来。
  return ''
})

const batteryError = computed(() => {
  const value = form.batteryCapacityKwh.trim()
  if (value === '') return '' // 可空，只用于估算
  // ★ 用「合法中间态」判断而不是 `Number()`：用户在输入 `8.` 的瞬间
  //   `Number('8.')` 是 8 但 `Number('.')` 是 NaN，此时标红是错的。
  if (!isPartialAmountInput(value)) return '只能填数字，例如 85 或 85.5'
  const cents = toScaled(value, 2)
  if (cents < 0n) return '电池容量不能为负数'
  if (cents > BATTERY_MAX_CENTS) return '电池容量超出了可存储的范围'
  return ''
})

const canSubmit = computed(() => plateError.value === '' && batteryError.value === '')

// ── 提交 ──────────────────────────────────────────────────────────────

/** 找出「用户清空了、但后端清不掉」的字段。 */
function clearedFields(from: VehicleForm, to: VehicleForm): string[] {
  return (Object.keys(UNCLEARABLE) as (keyof VehicleForm)[])
    .filter((key) => key !== 'plateColor')
    .filter((key) => String(from[key]).trim() !== '' && String(to[key]).trim() === '')
}

/** 归一化：去掉首尾空格，电池容量的 `85` → `85.00`（与列的 2 位小数对齐）。 */
function normalized(): VehicleForm {
  const battery = form.batteryCapacityKwh.trim()
  return {
    plateNo: form.plateNo.trim(),
    plateColor: form.plateColor,
    brand: form.brand.trim(),
    model: form.model.trim(),
    batteryCapacityKwh: battery === '' ? '' : fromScaled(toScaled(battery, 2), 2),
    remark: form.remark.trim(),
  }
}

async function onSubmit(): Promise<void> {
  submitted.value = true
  if (!canSubmit.value) return

  if (!isEdit.value) {
    await submitCreate()
    return
  }
  await submitUpdate()
}

async function submitCreate(): Promise<void> {
  const value = normalized()

  // ★ 新增用整表提交（没有「原值」可比），空值给 `null` 而不是空串 ——
  //   「没填」在库里是 NULL，空串是另一个值。
  // ★ 刻意**不传** `isDefault`：第一辆车后端会强制置 1，之后新增的默认非默认
  //   （见 `VehicleDTO` 的注释）。传一个显式的 null 反而会撞 `NOT NULL` 列。
  const dto: VehicleDTO = {
    plateNo: value.plateNo,
    plateColor: value.plateColor,
    brand: value.brand || null,
    model: value.model || null,
    batteryCapacityKwh: value.batteryCapacityKwh || null,
    remark: value.remark || null,
  }

  const outcome = await run(() =>
    withActionLock(`vehicle-create:${value.plateNo}`, () => createVehicle(dto), SAVE_LOCK_MS),
  )
  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') {
    if (isActionLocked(outcome.error)) showToast('刚刚已提交过，请稍候几秒再试')
    return
  }

  // ★ 后端返回的是新车的 **id**（`Result<Long>`），不是 VO —— 所以这里没有
  //   「新车的完整信息」可用。也不需要：返回列表后列表会重拉。
  showSuccessToast('已添加')
  router.back()
}

async function submitUpdate(): Promise<void> {
  const value = normalized()

  // ★ 前端先拦一次「清空」：这类请求后端**不会报错**，只会静默不生效 ——
  //   让用户带着「我删掉了」的错觉离开页面是最坏的结局。
  const blocked = Array.from(
    new Set([
      ...clearedFields(original.value, value).map((key) => UNCLEARABLE[key]),
      // `diffUpdate` 自己也会报 remark（它在 UNSETTABLE_FIELDS 里），去重后一起说
      ...diffUpdate({ ...original.value }, { ...value })
        .unsettable.map((key) => UNCLEARABLE[key] ?? key),
    ]),
  ).filter((x): x is string => typeof x === 'string')

  if (blocked.length > 0) {
    try {
      await showConfirmDialog({
        title: '这些字段清不掉',
        message: `${blocked.join('、')}留空表示「不修改」，后端不会把它清空。其余改动仍然可以保存。`,
        confirmButtonText: '保存其它改动',
        cancelButtonText: '返回修改',
      })
    } catch {
      return // 用户选择回去改
    }
  }

  const { payload } = diffUpdate({ ...original.value }, { ...value })
  // 把清不掉的字段从 payload 里摘掉：留着它们只会让 `update_time` 白刷一次。
  // ★ 尤其是电池容量：给 BigDecimal 传空串是**参数解析错误**（4xx），
  //   而不是「不修改」—— 它比其它几个字段更硬。
  for (const name of Object.keys(UNCLEARABLE)) {
    if (!(name in payload)) continue
    if (String((value as unknown as Record<string, unknown>)[name]).trim() === '') {
      delete payload[name as keyof typeof payload]
    }
  }

  if (Object.keys(payload).length === 0) {
    showToast('没有需要保存的修改')
    return
  }

  const outcome = await run(() =>
    withActionLock(`vehicle-update:${vehicleId.value}`, () => updateVehicle(vehicleId.value, payload as VehicleUpdateDTO), SAVE_LOCK_MS),
  )
  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') {
    if (isActionLocked(outcome.error)) showToast('刚刚已提交过，请稍候几秒再试')
    return
  }

  showSuccessToast('已保存')
  router.back()
}
</script>

<template>
  <div class="page">
    <van-nav-bar
      :title="isEdit ? '编辑车辆' : '添加车辆'"
      left-arrow
      @click-left="router.back()"
    />

    <PageState v-if="isEdit && loading && vehicle === null" kind="loading" />

    <PageState
      v-else-if="isEdit && failed && vehicle === null"
      kind="error"
      description="没拿到这辆车的信息，无法编辑。"
      @retry="reload()"
    />

    <van-form v-else @submit="onSubmit">
      <van-cell-group inset class="group">
        <van-field
          v-model="form.plateNo"
          label="车牌号"
          placeholder="如 粤BD12345"
          :maxlength="MAX_LEN.plateNo"
          clearable
          :error="plateError !== ''"
          :error-message="plateError"
        />
        <van-cell
          title="车牌颜色"
          :value="colorLabel"
          is-link
          @click="colorVisible = true"
        />
        <van-field
          v-model="form.brand"
          label="品牌"
          placeholder="如 比亚迪"
          :maxlength="MAX_LEN.vehicleBrand"
          clearable
        />
        <van-field
          v-model="form.model"
          label="型号"
          placeholder="如 汉 EV"
          :maxlength="MAX_LEN.vehicleBrand"
          clearable
        />
        <van-field
          v-model="form.batteryCapacityKwh"
          type="number"
          label="电池容量"
          placeholder="选填，单位 kWh"
          :error="batteryError !== ''"
          :error-message="batteryError"
          clearable
        />
        <van-field
          v-model="form.remark"
          type="textarea"
          rows="2"
          autosize
          label="备注"
          placeholder="选填"
          :maxlength="MAX_LEN.vehicleRemark"
          show-word-limit
        />
      </van-cell-group>

      <p class="note">
        电池容量只用于估算充满需要多少电，<b>不参与计费</b>。
      </p>
      <p v-if="isEdit" class="note warn">
        留空表示「不修改」：后端不支持把这些字段清空（车牌号、品牌、型号、电池容量、备注）。
      </p>
      <p class="note">
        改默认车不在这里 —— 请回列表页用「设为默认」。表单里放这个开关是不会生效的。
      </p>

      <div class="actions">
        <van-button
          block
          round
          type="primary"
          native-type="submit"
          :loading="pending"
          :disabled="!canSubmit"
          loading-text="保存中…"
        >
          {{ isEdit ? '保存' : '添加' }}
        </van-button>
      </div>
    </van-form>

    <!-- 车牌颜色：用弹层 + 列表而不是 picker —— 只有 4 个选项，
         选择器（带滚轮）在这个数量级上比列表更麻烦。 -->
    <van-popup v-model:show="colorVisible" position="bottom" round>
      <div class="picker-head">车牌颜色</div>
      <van-cell
        v-for="item in PLATE_COLOR.list"
        :key="item.code"
        :title="item.label"
        is-link
        :class="{ current: item.code === form.plateColor }"
        @click="chooseColor(item.code)"
      />
      <div class="picker-gap" />
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: var(--co-gap-2xl);
  background: var(--co-bg-page);
}

.group {
  margin-top: var(--co-gap-md);
}

.note {
  margin: var(--co-gap-md) var(--co-gap-lg) 0;
  font-size: var(--co-font-xs);
  line-height: 1.7;
  color: var(--co-text-3);
}

.note b {
  font-weight: 600;
  color: var(--co-text-2);
}

/* 清不掉字段的提示用 warning 而不是 danger：它不会造成数据损坏，
   只是「你以为删掉了其实没有」，用红色会显得像出了事故。 */
.note.warn {
  color: var(--co-warning-strong);
}

.actions {
  padding: var(--co-gap-xl) var(--co-gap-lg) 0;
}

.picker-head {
  padding: var(--co-gap-lg) var(--co-gap-lg) var(--co-gap-sm);
  font-size: var(--co-font-lg);
  font-weight: 600;
  color: var(--co-text-1);
}

.picker-gap {
  height: calc(var(--co-gap-lg) + env(safe-area-inset-bottom));
}

.current {
  background: var(--co-primary-weak);
}

.current :deep(.van-cell__title) {
  color: var(--co-primary-strong);
  font-weight: 600;
}
</style>
