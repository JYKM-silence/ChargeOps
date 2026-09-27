<script setup lang="ts">
/**
 * 列表页的筛选条。
 *
 * ★ 筛选条件放**卡片内的一行**，不放进弹窗：
 *   运维的高频动作是「换个站点再看看」，弹窗每次要多两次点击。
 *
 * ★ 「查询」按钮**不需要** —— 输入框回车、下拉变更都直接触发。
 *   但保留一个「重置」，因为把 4 个筛选项逐个清空很烦。
 *
 * ★ 提交节流由 `useSubmitLock` 在页面层做，这里只负责布局与事件。
 */

withDefaults(
  defineProps<{
    /** 有筛选条件时，重置按钮才可用。 */
    dirty?: boolean
    loading?: boolean
  }>(),
  { dirty: false, loading: false },
)

defineEmits<{ search: []; reset: [] }>()
</script>

<template>
  <div class="filter-bar">
    <div class="fields">
      <slot />
    </div>

    <div class="actions">
      <el-button type="primary" :loading="loading" @click="$emit('search')">
        <slot name="search-text">查询</slot>
      </el-button>
      <el-button :disabled="!dirty" @click="$emit('reset')">重置</el-button>
    </div>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 1px solid var(--co-border-light);
}

.fields {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  flex: 1;
  min-width: 0;
}

/* ★ 让插槽里的 el-input / el-select 有统一的宽度与高度，
   否则每个页面都要手写 style="width: 180px"，而且必然不一致。 */
.fields :deep(.el-input),
.fields :deep(.el-select) {
  width: 180px;
}

/* 明确标了 wide 的（如订单号、手机号）给宽一点 */
.fields :deep(.is-wide) {
  width: 240px;
}

.fields :deep(.el-date-editor) {
  width: 260px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
