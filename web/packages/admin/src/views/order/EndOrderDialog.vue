<script setup lang="ts">
/**
 * 结束充电（2 充电中 → 3 待结算）。
 *
 * ★★ 结果**不一定是「待结算」** ★★
 *   电量 = 结束读数 − 起始读数。读数缺失或倒退时，「充电结束了」这个事实
 *   照常落库，但订单会**紧接着转 9 异常挂账**并带上原因。
 *   所以提交成功后**必须重新拉一次详情看实际结果**，不能默认它进了 3。
 *   这里把两种结果都当作成功提示出来，并明确告诉运维「去哪看原因」。
 *
 * ★★ `endTime` 为什么让调用方填，而不是服务端取 now() ★★
 *   计费窗口用哪个时间会**直接改变金额** —— 跨尖峰/平谷边界时差一个电价档。
 *   后端选择用**设备上报的时间**，因为计费的依据是「电是什么时候被用掉的」，
 *   那是物理事实；服务器时钟只能回答「消息什么时候到的」。
 *   留空时服务端取 now()，但那是**降级不是等价**，所以提示语要写清这一点。
 *
 * ★ `stopReason` 可空，而且 NULL 有信息量：只有「充电真的开始过」的订单才有停止原因。
 */
import { computed, reactive } from 'vue'
import { invalidateOptionsByTag, inRange, STOP_REASON, formatServerTime } from '@chargeops/shared'
import type { AdminOrderVO, OrderEndDTO } from '@chargeops/shared'
import { endOrder, getOrder } from '@/api/order'
import { useSubmitLock } from '@/composables/useSubmitLock'
import OrderStatusTag from '@/components/OrderStatusTag.vue'

const props = defineProps<{ order: AdminOrderVO }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const form = reactive({
  endMeterKwh: '',
  endSoc: undefined as number | undefined,
  stopReason: undefined as number | undefined,
  /** 设备上报的结束时刻。空 = 服务端取当前时间（降级）。 */
  endTime: '' as string,
})

const { pending, run } = useSubmitLock({ throttleMs: 500 })

/** ★ 结束读数是**必填**的（与 start 不同）：没有它就算不出电量，也就没法结算。 */
const meterInvalid = computed(() => {
  if (form.endMeterKwh === '') return true
  const n = Number(form.endMeterKwh)
  return !Number.isFinite(n) || n < 0
})

const socInvalid = computed(
  () => form.endSoc !== undefined && !inRange(form.endSoc, 0, 100, true),
)

const canSubmit = computed(() => !meterInvalid.value && !socInvalid.value)

/**
 * 结束时间必须**严格晚于开始充电的时间**。
 *
 * ★★ 比的是 `startTime`，**不是 `createTime`** ★★ ← 这里原先写的就是 `createTime`，是错的
 *
 *   后端 `settle` 的判据是 `endTime > startTime`，而 `end` **完全不校验**这个关系。
 *   两者之间的缝就是：`createTime < endTime <= startTime` 这一段。
 *   订单在 1 待启动 停留多久，这段缝就有多宽（`createTime` 是建单时刻，
 *   `startTime` 是运维点「启动充电」的时刻，中间可以隔几分钟）。
 *
 *   ★ 落进这段缝里的 `endTime` 会**一路通过**：前端放行 → `end` 收下并把订单
 *     推到 3 待结算 → 然后 `settle` **永久拒绝**（「计费结束时间必须晚于开始时间」）。
 *     此时 `end` 也已经不能再调（它的 CAS 要求 `status = 2`，而订单已经是 3），
 *     定时任务又只管 `4 → 6`（见 `ChargingOrderSweepJob`）——
 *     **这笔订单就死在 3 上了**，车主的冻结款也**没有任何接口能释放**。
 *     实测复现过：`endTime` 传 `2020-01-01`，`end` 返回 `code:1`，`settle` 从此永远 `code:0`。
 *
 * ★ 所以用 `<=` 而不是 `<`：后端把两个时间**截断到整秒**再比，
 *   于是「结束时间正好等于开始时间」也会被判成不合法（窗口 0 秒）。
 *   用 `<` 会放行这一种，同样把订单送进死路。
 *
 * ★ `startTime` 为 null 时退回 `createTime`：正常流程走不到（这个弹窗只对
 *   2 充电中 打开，那时 `startTime` 必然有值），但类型上是可空的，
 *   退回一个更宽松的下界，总好过 `null` 比较后静默放行一切。
 *
 * ★ 这里**只提示、不自动清空**。自动清掉用户刚输入的时间看起来"贴心"，
 *   实际上是让输入凭空消失，用户会反复输同一个值并以为输入框坏了。
 *   挡住提交 + 说清原因就够了。
 */
const timeInvalid = computed(() => {
  if (form.endTime === '') return false
  const lowerBound = props.order.startTime ?? props.order.createTime
  return form.endTime <= lowerBound
})

async function onSubmit(): Promise<void> {
  if (!canSubmit.value || timeInvalid.value) return

  const payload: OrderEndDTO = {
    endMeterKwh: form.endMeterKwh,
    endSoc: form.endSoc ?? null,
    stopReason: form.stopReason ?? null,
    // ★ 空字符串 → undefined：让后端走"用服务端时间"的降级分支，
    //   而不是收到一个空字符串然后反序列化失败
    //   （`LocalDateTime` 收到 `""` 会 400，而后端兜底文案是「未知错误」）。
    endTime: form.endTime === '' ? undefined : form.endTime,
  }

  const outcome = await run(async () => {
    await endOrder(props.order.id, payload)
    // ★ 成功之后立刻查一次实际落到哪个状态 —— 读数异常时会转 9 异常挂账。
    return getOrder(props.order.id)
  })

  if (outcome.status !== 'done') return

  invalidateOptionsByTag('connector')
  const after = outcome.value
  if (after && after.status === 9) {
    emit(
      'done',
      '充电已结束，但读数异常，订单已转「异常挂账」。具体原因请在订单详情的事件流里查看。',
    )
  } else {
    emit('done', '充电已结束，订单进入「待结算」，可在详情页执行结算')
  }
}

function onMeterInput(v: string): void {
  if (v === '' || /^\d*\.?\d*$/.test(v)) form.endMeterKwh = v
}
</script>

<template>
  <el-dialog
    :model-value="true"
    title="结束充电"
    width="500px"
    align-center
    :close-on-click-modal="false"
    :show-close="!pending"
    @update:model-value="emit('close')"
  >
    <div class="order-line">
      <span class="no">{{ props.order.orderNo }}</span>
      <OrderStatusTag :status="props.order.status" size="sm" />
    </div>

    <el-form label-position="top">
      <el-form-item>
        <template #label>
          <span class="label-row">结束电表读数 <em class="is-required">必填</em></span>
        </template>
        <div class="field">
          <el-input
            :model-value="form.endMeterKwh"
            placeholder="请输入设备上报的结束读数"
            @update:model-value="onMeterInput"
          >
            <template #append>kWh</template>
          </el-input>
          <p class="field-hint">
            同样是<b>累计读数</b>。电量 = 结束读数 − 起始读数，所以这一项不能留空
            —— 没有它就算不出电量，订单只能进异常挂账。
          </p>
          <p v-if="meterInvalid && form.endMeterKwh !== ''" class="field-hint is-error">
            请输入一个不小于 0 的数字。
          </p>
        </div>
      </el-form-item>

      <el-form-item label="结束电量百分比">
        <div class="field">
          <el-input-number
            v-model="form.endSoc"
            :min="0"
            :max="100"
            :controls="false"
            placeholder="可留空"
            style="width: 100%"
          />
          <p v-if="socInvalid" class="field-hint is-error">SOC 必须在 0 到 100 之间。</p>
        </div>
      </el-form-item>

      <el-form-item label="停止原因">
        <div class="field">
          <el-select v-model="form.stopReason" placeholder="可留空" clearable style="width: 100%">
            <el-option
              v-for="opt in STOP_REASON.options()"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <p class="field-hint">
            只有<b>充电真的开始过</b>的订单才有停止原因，所以可以留空。
          </p>
        </div>
      </el-form-item>

      <el-form-item>
        <template #label>
          <span class="label-row">设备上报的结束时刻 <em>可留空</em></span>
        </template>
        <div class="field">
          <el-date-picker
            v-model="form.endTime"
            type="datetime"
            placeholder="留空则使用服务器当前时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
          <!-- ★ 这条不是「顺手加的说明」，是这个字段存在的全部理由 -->
          <p class="field-hint is-warn">
            有设备上报的时间就填上。<b>跨尖峰/平谷时段边界时，用哪个时间会差一个电价档</b>，
            直接影响金额。留空会用服务器当前时间 —— 那是<b>降级，不是等价</b>。
          </p>
          <p v-if="timeInvalid" class="field-hint is-error">
            必须<b>晚于开始充电的时间</b>（{{ formatServerTime(props.order.startTime ?? props.order.createTime) }}）。
            这里放行的话订单会卡在「待结算」且无法结算，车主被冻结的钱也退不回来 —— 请填一个更晚的时间。
          </p>
        </div>
      </el-form-item>
    </el-form>

    <!-- ★ 提前告知"可能会挂账"，比事后解释好。
         注：起始读数只在**详情**里才有，列表行拿不到，所以这里无法做本地倒退检查，
         只能把这个规则讲清楚。 -->
    <p class="notice">
      读数为空或比起始读数小时，「充电结束了」这件事照常记录，但订单会转
      <b>9 异常挂账</b>并写明原因 —— 这是预期行为，不是失败。
    </p>

    <template #footer>
      <el-button :disabled="pending" @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="pending" :disabled="!canSubmit" @click="onSubmit">
        结束充电
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.order-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}

.no {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--co-text-1);
  font-variant-numeric: tabular-nums;
}

.label-row {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.label-row em {
  padding: 0 5px;
  border-radius: 3px;
  background: var(--co-bg-3);
  color: var(--co-text-3);
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
}

.label-row em.is-required {
  background: var(--co-danger-weak);
  color: var(--co-danger-strong);
}

.field {
  display: flex;
  flex-direction: column;
  width: 100%;
}

.field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--co-text-3);
}

.field-hint.is-warn {
  color: var(--co-warning-strong);
}

.field-hint.is-error {
  color: var(--co-danger);
}

.field-hint b {
  font-weight: 600;
}

.notice {
  margin: 4px 0 0;
  padding: 10px 12px;
  border-radius: var(--co-radius-md);
  background: var(--co-warning-weak);
  color: var(--co-warning-strong);
  font-size: 12px;
  line-height: 1.65;
}
</style>
