<script setup lang="ts">
/**
 * 运维工单（列表）。
 *
 * 工单是「已经发生过的事」的记录，所以这个页面只做两件事：筛出来、推进状态。
 * 建单入口在 AI 助手的对话里（`source = 2`），**改标题和正文的入口故意没有** ——
 * 正文会被当同类故障的历史案例召回（只召回 `status = 2` 的那些），
 * 能改就等于能篡改助手下一轮的依据。
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  DEFAULT_PAGE_SIZE_UI,
  TICKET_SOURCE,
  TICKET_STATUS,
  formatRelative,
  formatServerTime,
} from '@chargeops/shared'
import type { MaintenanceTicketVO, Option, Tone } from '@chargeops/shared'
import { pageTickets, updateTicketStatus } from '@/api/maintenanceTicket'
import { stationOptions } from '@/api/options'
import { usePagedList } from '@/composables/usePagedList'
import { useOptions } from '@/composables/useOptions'
import PagePanel from '@/components/PagePanel.vue'
import FilterBar from '@/components/FilterBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import CoIcon from '@/components/CoIcon.vue'

const list = usePagedList<MaintenanceTicketVO, Record<string, unknown>>({
  fetch: (query) => pageTickets(query as never),
  // 默认「全部工单按时间倒序」，不预设状态：运维进来最常见的动作是
  // 从全部里挑出待处理的那几条，预设 0 反而会让他看不到刚才跟过的单。
  initial: { stationId: undefined, status: undefined, source: undefined },
})

const { options: stations } = useOptions<number>(stationOptions)

const TICKET_STATUS_OPTIONS = TICKET_STATUS.options()
const TICKET_SOURCE_OPTIONS = TICKET_SOURCE.options()

/** 已经用了哪个筛选项 —— 空态要说清「是被筛掉的」还是「真的没有」。 */
const hasFilter = computed(
  () =>
    list.filters.stationId !== undefined ||
    list.filters.status !== undefined ||
    list.filters.source !== undefined,
)

/* ── 状态推进 ─────────────────────────────────────────────────────────── */

/**
 * 正在改的那一条。
 *
 * 用一个 per-row 的 id 而不是 `useSubmitLock` 的全局布尔：
 * 后者会让**所有行**的按钮同时转圈，看起来像整页在提交。
 * 并发保护交给 `changing !== null` 这一句。
 */
const changing = ref<number | null>(null)

function canMoveTo(row: MaintenanceTicketVO, next: number): boolean {
  return row.status !== next && changing.value === null
}

async function moveTo(row: MaintenanceTicketVO, next: number): Promise<void> {
  if (!canMoveTo(row, next)) return

  /**
   * 走到「已完成」要二次确认，不是因为它危险，而是因为它**有下游后果**：
   * 只有 status = 2 的工单会被当历史案例喂给 AI。
   * 一条没真正解决的工单被标成已完成，之后会被助手当成结论引用 ——
   * 这个错误发生在前端，代价却出在下一次问答上，所以要在源头拦一下。
   */
  if (next === 2) {
    try {
      await ElMessageBox.confirm(
        '标记完成后，这条工单的正文会被 AI 助手当作同类故障的历史案例引用。请确认处理结论已经写清了。',
        '标记完成',
        { confirmButtonText: '标记完成', cancelButtonText: '再看看', type: 'warning' },
      )
    } catch {
      return // 用户取消，不是错误
    }
  }

  changing.value = row.id
  try {
    // ★ 重复提交同一个状态是安全的：后端比对后直接返回，不报「工单不存在」。
    //   失败提示由 http 层统一弹，这里不重复弹。
    await updateTicketStatus(row.id, { status: next })
    ElMessage({ type: 'success', message: next === 2 ? '已标记完成，会成为助手的历史案例' : '状态已更新' })
    await list.reload()
  } catch {
    // 已经弹过错误提示，列表保持原样即可
  } finally {
    changing.value = null
  }
}

/** 每行按当前状态给出「下一步」和「退回」，而不是三个状态码让用户自己挑。 */
function nextStepOf(status: number | null): { label: string; to: number } | null {
  if (status === 0) return { label: '开始处理', to: 1 }
  if (status === 1) return { label: '标记完成', to: 2 }
  return null
}

function reopenOf(status: number | null): { label: string; to: number } | null {
  if (status === 1) return { label: '退回待处理', to: 0 }
  if (status === 2) return { label: '重新打开', to: 0 }
  return null
}
</script>

<template>
  <div class="page">
    <PagePanel flush>
      <FilterBar
        :dirty="list.dirty.value"
        :loading="list.loading.value"
        @search="list.search()"
        @reset="list.reset()"
      >
        <el-select v-model="list.filters.stationId" placeholder="全部站点" clearable filterable>
          <el-option
            v-for="opt in (stations as Option<number>[])"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>

        <el-select v-model="list.filters.status" placeholder="全部状态" clearable>
          <el-option
            v-for="opt in TICKET_STATUS_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>

        <el-select v-model="list.filters.source" placeholder="全部来源" clearable>
          <el-option
            v-for="opt in TICKET_SOURCE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </FilterBar>

      <p class="note">
        <CoIcon name="ai" :size="13" />
        来源为「AI助手」的工单是助手在对话里自动建的，正文是它当时给出的处理建议。标成
        <b>已完成</b> 之后，这条正文才会被当成同类故障的历史案例召回。
      </p>

      <el-table v-loading="list.loading.value" :data="list.rows.value" row-key="id" stripe>
        <!-- ★ 正文用展开行而不是弹窗：看工单时几乎总要先读正文再决定改什么状态，
             弹窗会多两次点击，而且没法对照着看列表里的上下文。 -->
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail">
              <p v-if="row.content" class="detail-body">{{ row.content }}</p>
              <p v-else class="detail-body is-muted">这条工单没有填写处理内容。</p>

              <dl class="detail-meta">
                <div><dt>告警</dt><dd>{{ row.alarmId ?? '未关联' }}</dd></div>
                <div><dt>充电桩 ID</dt><dd>{{ row.chargerId ?? '未关联' }}</dd></div>
                <div><dt>更新时间</dt><dd>{{ formatServerTime(row.updateTime) }}</dd></div>
              </dl>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="工单号" width="170">
          <template #default="{ row }">
            <span class="ticket-no">{{ row.ticketNo }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <!-- ★ 优先用 VO 上的 statusLabel（后端枚举翻译），拿不到才回落本地字典：
                 两处的措辞必须一致，回落只是为了让 status 为 null 时不渲染成空白。 -->
            <StatusTag
              :label="row.statusLabel ?? TICKET_STATUS.label(row.status)"
              :tone="TICKET_STATUS.tone(row.status) as Tone"
              size="sm"
            />
          </template>
        </el-table-column>

        <el-table-column label="来源" width="100">
          <template #default="{ row }">
            <StatusTag
              :label="row.sourceLabel ?? TICKET_SOURCE.label(row.source)"
              :tone="TICKET_SOURCE.tone(row.source) as Tone"
              size="sm"
            />
          </template>
        </el-table-column>

        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />

        <el-table-column label="充电桩" min-width="140">
          <template #default="{ row }">
            <span v-if="row.chargerName">{{ row.chargerName }}</span>
            <span v-else class="muted">未关联</span>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">
            <el-tooltip :content="formatServerTime(row.createTime)" placement="top">
              <span class="time">{{ formatRelative(row.createTime) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <div class="ops">
              <el-button
                v-if="nextStepOf(row.status)"
                size="small"
                type="primary"
                :plain="nextStepOf(row.status)!.to !== 2"
                :loading="changing === row.id"
                :disabled="!canMoveTo(row, nextStepOf(row.status)!.to)"
                @click="moveTo(row, nextStepOf(row.status)!.to)"
              >
                {{ nextStepOf(row.status)!.label }}
              </el-button>

              <el-button
                v-if="reopenOf(row.status)"
                size="small"
                :loading="changing === row.id"
                :disabled="!canMoveTo(row, reopenOf(row.status)!.to)"
                @click="moveTo(row, reopenOf(row.status)!.to)"
              >
                {{ reopenOf(row.status)!.label }}
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyState
            :kind="list.error.value ? 'error' : hasFilter ? 'filtered' : 'empty'"
            :title="list.error.value ? '加载失败' : hasFilter ? undefined : '还没有工单'"
            :description="
              list.error.value || hasFilter
                ? undefined
                : '工单由 AI 助手在对话中自动建立。去「AI 运维助手」问一次设备故障，它会查状态、翻告警、检索历史案例并给出处理建议。'
            "
            @retry="list.error.value ? list.reload() : list.reset()"
          />
        </template>
      </el-table>

      <template #footer>
        <el-pagination
          :current-page="list.pageNum.value"
          :page-size="list.pageSize.value"
          :total="list.total.value"
          :page-sizes="[10, 20, 50, DEFAULT_PAGE_SIZE_UI]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="list.goPage"
          @size-change="(n: number) => { list.pageSize.value = n; list.goPage(1) }"
        />
      </template>
    </PagePanel>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 9px 18px;
  border-bottom: 1px solid var(--co-border-light);
  background: var(--co-accent-weak);
  color: var(--co-accent-strong);
  font-size: 12.5px;
  line-height: 1.5;
}

.note b {
  font-weight: 600;
}

.ticket-no {
  font-family: inherit;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--co-text-1);
}

.time {
  font-size: 12.5px;
  color: var(--co-text-2);
}

.muted {
  color: var(--co-text-4);
}

.ops {
  display: flex;
  gap: 6px;
}

.detail {
  padding: 4px 48px 14px;
}

.detail-body {
  margin: 0 0 12px;
  max-width: 78ch;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.75;
  color: var(--co-text-1);
}

.detail-body.is-muted {
  color: var(--co-text-3);
}

.detail-meta {
  display: flex;
  gap: 28px;
  margin: 0;
  font-size: 12.5px;
}

.detail-meta div {
  display: flex;
  gap: 6px;
}

.detail-meta dt {
  color: var(--co-text-3);
}

.detail-meta dd {
  margin: 0;
  color: var(--co-text-2);
  font-variant-numeric: tabular-nums;
}
</style>
