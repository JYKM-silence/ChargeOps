<script setup lang="ts">
/**
 * 启动充电（1 待启动 → 2 充电中）。
 *
 * ★★ 这个表单填的是**设备读数**，不是业务参数 ★★
 *   本项目没有设备接入层，运维在这里**代设备录入**它在报文里本该带的东西。
 *   所以字段旁必须写清「读数去哪看」—— 录错读数会直接算错电量，
 *   而电量是结算的唯一依据。
 *
 * ★★ 最要命的一条：`startMeterKwh` 可空，但**缺数据时不要填 0** ★★
 *   它是**累计读数**（电表从出厂到现在的总度数），不是本次充电量。
 *   填 0 会让 `totalEnergyKwh = 结束读数 − 0 = 整个电表读数`，
 *   算出一笔几百度的订单 —— 而且**这个错误永远不会报错**。
 *   留空的话结算会因为缺数据进异常挂账，那是一个有人会去看的失败。
 *   → 所以这个字段**允许留空**，且提示语要明确说"不知道就留空"。
 *
 * ★ 这个动作**不动钱**（钱在建单时已冻结），所以确认弹窗不用做资金级
 *   的"手打一遍"确认，一个普通弹窗就够。
 */
import { computed, reactive } from 'vue'
import { inRange, invalidateOptionsByTag } from '@chargeops/shared'
import type { AdminOrderVO, OrderStartDTO } from '@chargeops/shared'
import { startOrder } from '@/api/order'
import { useSubmitLock } from '@/composables/useSubmitLock'
import OrderStatusTag from '@/components/OrderStatusTag.vue'

const props = defineProps<{ order: AdminOrderVO }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const form = reactive({
  // ★ 空字符串表示"设备没上报"，提交时转成 undefined（不发这个字段）。
  //   注意不能转成 0 —— 见文件头。
  startMeterKwh: '' as string,
  startSoc: undefined as number | undefined,
})

const { pending, run } = useSubmitLock({ throttleMs: 500 })

const socInvalid = computed(
  () => form.startSoc !== undefined && !inRange(form.startSoc, 0, 100, true),
)

/** 留空是**合法**的 —— 所以这里只校验"填了的话得是个数"。 */
const meterInvalid = computed(() => {
  if (form.startMeterKwh === '') return false
  const n = Number(form.startMeterKwh)
  return !Number.isFinite(n) || n < 0
})

const canSubmit = computed(() => !meterInvalid.value && !socInvalid.value)

async function onSubmit(): Promise<void> {
  if (!canSubmit.value) return

  const payload: OrderStartDTO = {
    // ★ 空字符串 → undefined → `cleanParams` 会把这个字段整个丢掉，
    //   后端拿到的是 null，走"缺数据"分支而不是拿到 0。
    startMeterKwh: form.startMeterKwh === '' ? undefined : form.startMeterKwh,
    startSoc: form.startSoc ?? null,
  }

  const outcome = await run(() => startOrder(props.order.id, payload))
  if (outcome.status !== 'done') return

  invalidateOptionsByTag('connector')
  emit('done', '已启动充电，充电枪状态已变为「充电中」')
}

/** 只允许数字与小数点，挡住 `1.2.3` 这类输入。 */
function onMeterInput(v: string): void {
  if (v === '' || /^\d*\.?\d*$/.test(v)) form.startMeterKwh = v
}
</script>

<template>
  <el-dialog
    :model-value="true"
    title="启动充电"
    width="480px"
    align-center
    :close-on-click-modal="false"
    :show-close="!pending"
    @update:model-value="emit('close')"
  >
    <div class="order-line">
      <span class="no">{{ props.order.orderNo }}</span>
      <OrderStatusTag :status="props.order.status" size="sm" />
    </div>

    <p class="lead">
      这一步是<b>代设备录入</b>充电桩在启动报文里会上报的数据。录错的读数会直接算错电量，
      而电量是结算的唯一依据。
    </p>

    <el-form label-position="top">
      <el-form-item>
        <template #label>
          <span class="label-row">
            起始电表读数
            <em>可留空</em>
          </span>
        </template>
        <!-- ★ 用一层 div 包住输入框与提示：`el-form-item` 的 content 是
             flex 行容器，直接放两个子节点会让提示文字跑到输入框右边去。 -->
        <div class="field">
          <el-input
            :model-value="form.startMeterKwh"
            placeholder="设备没上报就留空"
            @update:model-value="onMeterInput"
          >
            <template #append>kWh</template>
          </el-input>

          <!-- ★ 这条提示是这个弹窗里最重要的一句话，所以放在字段下面而不是 tooltip 里 -->
          <p class="field-hint is-warn">
            这是电表的<b>累计读数</b>（从出厂到现在的总度数），不是本次充电量。
            <b>不知道就留空，千万不要填 0</b> —— 填 0 会让总电量等于整个电表读数，
            算出一笔几百度的订单，而且这个错误不会报任何错。
          </p>
        </div>
      </el-form-item>

      <el-form-item label="起始电量百分比">
        <div class="field">
          <el-input-number
            v-model="form.startSoc"
            :min="0"
            :max="100"
            :controls="false"
            placeholder="可留空"
            style="width: 100%"
          />
          <p class="field-hint">设备上报的 SOC，0 ~ 100。没上报就留空。</p>
          <p v-if="socInvalid" class="field-hint is-error">SOC 必须在 0 到 100 之间。</p>
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="pending" @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="pending" :disabled="!canSubmit" @click="onSubmit">
        启动充电
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.order-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.no {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--co-text-1);
  font-variant-numeric: tabular-nums;
}

.lead {
  margin: 0 0 18px;
  padding: 10px 12px;
  border-radius: var(--co-radius-md);
  background: var(--co-bg-3);
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--co-text-2);
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

/* ★ 让 form-item 里的「控件 + 提示」竖着排 */
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
</style>
