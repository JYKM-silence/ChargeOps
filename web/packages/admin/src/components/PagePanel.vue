<script setup lang="ts">
/**
 * 内容面板（白卡片）。
 *
 * ★ 层次靠「1px 浅描边 + 极弱阴影」建立，不是纯投影也不是纯描边：
 *   纯投影在浅色底上会显脏，纯描边又太平。
 *   `--co-shadow-sm` 是两段式阴影（0 1px 2px + 0 4px 12px），
 *   近距离有一点点实感、远距离散开，这是「卡片浮起来但没飘走」的写法。
 *
 * ★ `title` 与 `actions` 同一行，标题左、动作右：
 *   把「新建」按钮放在卡片右上角而不是表格上方居中，
 *   是为了让视线从标题扫到按钮时不用跳。
 */

withDefaults(
  defineProps<{
    title?: string
    /** 副标题 / 口径说明。工作台那类「样本有偏」的提示放这里。 */
    subtitle?: string
    /** 去掉内边距 —— 表格自带 12px 边距时用，否则会出现双层留白。 */
    flush?: boolean
  }>(),
  { flush: false },
)
</script>

<template>
  <section class="panel">
    <header v-if="title || $slots.actions || subtitle" class="panel-head">
      <div class="head-text">
        <h2 v-if="title" class="panel-title">{{ title }}</h2>
        <p v-if="subtitle" class="panel-subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.actions" class="head-actions">
        <slot name="actions" />
      </div>
    </header>

    <div class="panel-body" :class="{ 'is-flush': flush }">
      <slot />
    </div>

    <footer v-if="$slots.footer" class="panel-foot">
      <slot name="footer" />
    </footer>
  </section>
</template>

<style scoped>
.panel {
  background: var(--co-bg-card);
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-lg);
  box-shadow: var(--co-shadow-sm);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px 12px;
}

.panel-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--co-text-1);
}

.panel-subtitle {
  margin: 5px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--co-text-3);
  max-width: 60ch;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.panel-body {
  padding: 0 18px 18px;
}

/* 表格用：表格自己会撑满，这里只留底部一点呼吸 */
.panel-body.is-flush {
  padding: 0 0 4px;
}

.panel-foot {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--co-border-light);
}
</style>
