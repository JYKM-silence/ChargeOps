<script setup lang="ts">
/**
 * 页面的空 / 错 / 载三态（H5）。
 *
 * ★★ 三种状态必须**分开**，不能合成一个「没有数据」★★
 *   它们的下一步动作完全不同：
 *     · `empty`     —— 真的没数据 → 引导「去充电」
 *     · `filtered`  —— 有数据但这个筛选条件没命中 → 引导「取消筛选」
 *     · `error`     —— 加载失败 → 给「重试」按钮
 *   合成一个的话，用户看到「暂无数据」会以为自己的订单丢了。
 */

withDefaults(
  defineProps<{
    kind?: 'empty' | 'filtered' | 'error' | 'loading'
    title?: string
    description?: string
    /** 重试按钮文案。空则不显示按钮。 */
    retryText?: string
  }>(),
  { kind: 'empty', retryText: '重试' },
)

const emit = defineEmits<{ retry: [] }>()

const DEFAULT_TITLE: Record<string, string> = {
  empty: '暂无数据',
  filtered: '没有符合条件的结果',
  error: '加载失败',
  loading: '加载中…',
}

const DEFAULT_DESC: Record<string, string> = {
  empty: '',
  filtered: '换个条件试试，或者清空筛选。',
  error: '可能是网络不通，也可能是服务暂时不可用。',
  loading: '',
}
</script>

<template>
  <div class="state">
    <template v-if="kind === 'loading'">
      <van-loading size="22" />
      <p class="title">{{ title || DEFAULT_TITLE.loading }}</p>
    </template>

    <template v-else>
      <div class="art" :class="`art-${kind}`" aria-hidden="true">
        <span v-if="kind === 'error'">!</span>
        <span v-else-if="kind === 'filtered'">🔍</span>
        <span v-else>⚡</span>
      </div>

      <p class="title">{{ title || DEFAULT_TITLE[kind] }}</p>
      <p v-if="description || DEFAULT_DESC[kind]" class="desc">
        {{ description || DEFAULT_DESC[kind] }}
      </p>

      <van-button
        v-if="kind === 'error'"
        size="small"
        round
        plain
        type="primary"
        class="retry"
        @click="emit('retry')"
      >
        {{ retryText }}
      </van-button>
      <slot />
    </template>
  </div>
</template>

<style scoped>
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 56px 32px;
  text-align: center;
}

.art {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--co-bg-3);
  font-size: 24px;
  line-height: 1;
}

/* ★ 错误的图形用 danger 底色，其余用中性 ——
     「加载失败」和「暂无数据」在视觉上必须有区别，
     否则用户会以为系统坏了或者自己的数据丢了。 */
.art-error {
  background: var(--co-danger-weak);
  color: var(--co-danger-strong);
  font-weight: 700;
}

.art-filtered {
  background: var(--co-warning-weak);
}

.title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--co-text-2);
}

.desc {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--co-text-3);
  max-width: 260px;
}

.retry {
  margin-top: 4px;
}
</style>
