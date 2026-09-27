<script setup lang="ts">
/**
 * 危险动作的二次确认弹窗。
 *
 * ★★ 为什么这个组件必须存在（而不是随手 `ElMessageBox.confirm`）★★
 *
 *   后端**在任何地方都没有幂等键**。已核实：
 *   `WalletTransactionType.PACKAGE_PURCHASE` / `PACKAGE_REFUND` 的
 *   `order_id` 写的是 NULL，而唯一索引 `uk_txn_order_type` 是
 *   `(order_id, type)` —— **唯一索引不约束 NULL**，所以对这两个类型
 *   完全不起作用。也就是说没有任何一层能阻止重复扣款。
 *
 *   结论：**资金动作的正确性只能靠前端这一次确认来保证**。
 *   所以这个弹窗做了三件事：
 *     1. `requireTypedValue` —— 让用户**手打一遍**金额/编号。
 *        这是唯一能有效降低「手滑连点」的手段：确认框点一下只要 0.2 秒，
 *        打一遍数字要 3 秒，这 3 秒就是思考时间。
 *     2. 提交期间**锁死**（`loading` + 禁用），关不掉、点不了第二次
 *     3. 失败后**不自动关闭** —— 让用户看到失败原因，
 *        并且保持在同一个上下文里可以重试
 *
 * ★ 非资金类危险动作（删除）用 `requireTypedValue` 传名称即可，
 *   打字成本换来「删错东西」的概率下降，值。
 */

import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    /** 影响说明。要写清楚「会发生什么」，不是「确定吗？」。 */
    description?: string
    /** 逐条列出的后果。比一段长文字易读。 */
    consequences?: string[]
    confirmText?: string
    cancelText?: string
    /** 按钮文案里的动词，如「调账」「作废」「退款」。 */
    actionName?: string
    /** 需要用户逐字输入的比对值。传了就显示输入框。 */
    requireTypedValue?: string | null
    /** 输入框上方的提示。 */
    typedLabel?: string
    loading?: boolean
  }>(),
  {
    confirmText: '确认',
    cancelText: '取消',
    actionName: '操作',
    typedLabel: '请输入下面的内容以确认',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
}>()

const typed = ref('')

/**
 * ★ 打开时清空输入，且**只有完全一致**才放行。
 *   刻意不做「去掉空格 / 忽略大小写」的宽容匹配 ——
 *   宽容匹配会让人开始复制粘贴，那就失去手打的意义了。
 */
const typedOk = computed(
  () => !props.requireTypedValue || typed.value === props.requireTypedValue,
)

watch(
  () => props.modelValue,
  (open) => {
    if (open) typed.value = ''
  },
)

/** ★ 提交中禁止关闭 —— 关掉之后请求还在飞，用户会以为没提交而再点一次。 */
function requestClose(): void {
  if (props.loading) return
  emit('update:modelValue', false)
}

function onConfirm(): void {
  if (props.loading || !typedOk.value) return
  emit('confirm')
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="props.title"
    width="440px"
    align-center
    :close-on-click-modal="false"
    :close-on-press-escape="!props.loading"
    :show-close="!props.loading"
    @update:model-value="requestClose"
  >
    <div class="warn-banner">
      <span class="mark">!</span>
      <div class="banner-text">
        <p v-if="props.description" class="desc">{{ props.description }}</p>
        <ul v-if="props.consequences?.length" class="list">
          <li v-for="(c, i) in props.consequences" :key="i">{{ c }}</li>
        </ul>
      </div>
    </div>

    <slot />

    <div v-if="props.requireTypedValue" class="typed">
      <p class="typed-label">
        {{ props.typedLabel }}：<code>{{ props.requireTypedValue }}</code>
      </p>
      <el-input
        v-model="typed"
        :placeholder="props.requireTypedValue"
        :disabled="props.loading"
        @keyup.enter="onConfirm"
      />
    </div>

    <template #footer>
      <el-button :disabled="props.loading" @click="requestClose">
        {{ props.cancelText }}
      </el-button>
      <el-button
        type="danger"
        :loading="props.loading"
        :disabled="!typedOk"
        @click="onConfirm"
      >
        {{ props.confirmText === '确认' ? `确认${props.actionName}` : props.confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.warn-banner {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, var(--co-danger) 22%, transparent);
  border-radius: var(--co-radius-md);
  background: var(--co-danger-weak);
}

.mark {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  margin-top: 1px;
  border-radius: 50%;
  background: var(--co-danger);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
}

.banner-text {
  min-width: 0;
}

.desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--co-danger-strong);
}

.list {
  margin: 8px 0 0;
  padding-left: 16px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--co-text-2);
}

.list li::marker {
  color: var(--co-danger);
}

.typed {
  margin-top: 16px;
}

.typed-label {
  margin: 0 0 7px;
  font-size: 12.5px;
  color: var(--co-text-2);
}

.typed-label code {
  padding: 2px 6px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-1);
  font-size: 12.5px;
  font-weight: 600;
  /* 让用户能准确看清要打什么，尤其是金额 */
  font-variant-numeric: tabular-nums;
}
</style>
