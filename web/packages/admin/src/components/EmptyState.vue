<script setup lang="ts">
/**
 * 空状态。
 *
 * ★★ 空状态必须**区分三种「没有数据」**，这是它存在的全部意义 ★★
 *
 *   · `empty`  —— 真的没数据。文案要**指导下一步动作**（"还没有站点，先新建一个"），
 *                 而不是干巴巴一句「暂无数据」。后者让用户怀疑是不是加载失败。
 *   · `filtered` —— 有数据，但**被筛掉了**。这个必须和 `empty` 分开：
 *                 用户把筛选条件填错时，看到「暂无数据」会以为数据丢了，
 *                 然后跑去问运维。这里的正确动作是**提示清空筛选**。
 *   · `error`  —— 加载失败。要**给重试按钮**，并且把后端的原因显示出来。
 *
 *   把三者混成一个「暂无数据」是最常见也最气人的偷懒。
 */

import CoIcon from './CoIcon.vue'

withDefaults(
  defineProps<{
    kind?: 'empty' | 'filtered' | 'error'
    title?: string
    description?: string
  }>(),
  { kind: 'empty' },
)

const DEFAULT_TITLE: Record<string, string> = {
  empty: '暂无数据',
  filtered: '没有符合条件的记录',
  error: '加载失败',
}

const DEFAULT_DESC: Record<string, string> = {
  empty: '',
  filtered: '当前筛选条件下没有结果。可以试试放宽条件或清空筛选。',
  error: '请检查网络后重试。如果持续失败，可能是后端服务未启动。',
}

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="empty" :class="`is-${kind}`">
    <div class="icon">
      <CoIcon :name="kind === 'error' ? 'warning' : 'empty'" :size="30" :stroke-width="1.4" />
    </div>

    <p class="title">{{ title || DEFAULT_TITLE[kind] }}</p>
    <p v-if="description || DEFAULT_DESC[kind]" class="desc">
      {{ description || DEFAULT_DESC[kind] }}
    </p>

    <div class="actions">
      <slot name="actions">
        <el-button
          v-if="kind === 'error' || kind === 'filtered'"
          size="small"
          @click="$emit('retry')"
        >
          {{ kind === 'error' ? '重试' : '清空筛选' }}
        </el-button>
      </slot>
    </div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px 52px;
  text-align: center;
}

.icon {
  display: grid;
  place-items: center;
  width: 60px;
  height: 60px;
  margin-bottom: 14px;
  border-radius: 50%;
  background: var(--co-bg-3);
  color: var(--co-text-4);
}

.is-error .icon {
  background: var(--co-danger-weak);
  color: var(--co-danger);
}

.title {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--co-text-2);
}

.desc {
  margin: 6px 0 0;
  max-width: 42ch;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.actions:not(:empty) {
  margin-top: 16px;
}
</style>
