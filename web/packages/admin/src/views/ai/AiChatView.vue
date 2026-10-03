<script setup lang="ts">
/**
 * AI 运维助手（对话页）。
 *
 * ★★ 这个页面的三条约束，都来自后端是**同步 + 本地模型**这件事 ★★
 *
 *   1. **一次提问要等十几到几十秒**
 *      后端同步等模型多轮往返（带工具调用至少两轮），读超时 60 秒。
 *      所以「发出去之后到回答回来」之间必须一直有可见的进行态，
 *      否则用户会以为卡死，然后**再点一次** —— 而助手是会建工单的，
 *      重复提问会建出重复工单。这里的防重发闸门是硬性的，不是体验优化。
 *
 *   2. **会话上下文存在服务端内存里**
 *      前端只负责把上一轮返回的 `sessionId` 带回去。它重启即失效，
 *      所以不能存进 localStorage 当长期标识 —— 刷新页面等于开新会话是对的。
 *
 *   3. **回答里的 `references` 是「它为什么这么说」的唯一证据**
 *      知识库里没有对应内容时它是空的，那时助手应当直说。把这个列表显示出来，
 *      才能分辨「知识库缺料」和「模型答偏了」—— 后者重问也没用。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chat, reloadKnowledge, searchKnowledge } from '@/api/aiOps'
import type { KnowledgeChunkVO } from '@chargeops/shared'
import { parseAnswer } from '@/utils/chatText'
import PagePanel from '@/components/PagePanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import CoIcon from '@/components/CoIcon.vue'

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  /** 只有助手消息有。空数组 = 这一轮没召回任何片段，本身就是信息。 */
  references?: KnowledgeChunkVO[] | null
  /** 这一轮失败了（超时/网络/后端报错）。用来在时间线上留痕，而不是只弹一个会消失的 toast。 */
  failed?: boolean
}

const messages = ref<ChatMessage[]>([])
const draft = ref('')

/**
 * 服务端会话 id。第一轮不传，由后端生成后回传。
 *
 * ★ 请求失败时**不推进**它：那次提问没有在服务端留下任何东西，
 *   把失败响应当成会话起点会让下一轮的上下文指向一个空会话。
 */
const sessionId = ref<string | null>(null)

/** 硬性防重发闸门 —— 见文件头第 1 点。 */
const sending = ref(false)

const threadEl = ref<HTMLElement | null>(null)

const canSend = computed(() => draft.value.trim().length > 0 && !sending.value)

/** 后端 `@Size(max = 500)`。超了是 400，不如在本地就说清楚。 */
const MAX_QUESTION = 500
const tooLong = computed(() => draft.value.trim().length > MAX_QUESTION)

/**
 * 输入框自动长高。
 *
 * 写死 `rows` 会让长问题被截断成一个滚动小窗，而运维提问经常带一段告警原文。
 * 上限 6 行：再高输入框会把对话挤出屏幕。
 */
const rows = computed(() => Math.min(6, Math.max(1, draft.value.split('\n').length)))

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const el = threadEl.value
  if (el) el.scrollTop = el.scrollHeight
}

async function send(preset?: string): Promise<void> {
  const question = (preset ?? draft.value).trim()
  if (!question || sending.value) return

  if (question.length > MAX_QUESTION) {
    ElMessage({ type: 'warning', message: `问题最多 ${MAX_QUESTION} 个字，请精简后再发。` })
    return
  }

  draft.value = ''
  messages.value.push({ role: 'user', text: question })
  sending.value = true
  void scrollToBottom()

  try {
    const vo = await chat({ question, sessionId: sessionId.value })
    if (!vo) return // 理论上不会：失败会抛，这里只是把可空类型收窄

    sessionId.value = vo.sessionId
    messages.value.push({ role: 'assistant', text: vo.answer, references: vo.references })
  } catch {
    // 错误提示由 http 层统一弹过了，这里只在时间线上留一条痕。
    messages.value.push({
      role: 'assistant',
      failed: true,
      text:
        '这一轮没有拿到回答。如果刚才等的是超时，服务端可能仍在处理并且**已经建了工单** —— ' +
        '先去「运维工单」看一眼再决定要不要重问，直接重问有可能建出两张。',
    })
  } finally {
    sending.value = false
    void scrollToBottom()
  }
}

function onKeydown(event: KeyboardEvent): void {
  // Enter 发送、Shift+Enter 换行。中文输入法组字过程中的 Enter 不能当发送：
  // 用 isComposing 过滤，否则每次选词的确认键都会把半句话发出去。
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  void send()
}

async function newSession(): Promise<void> {
  if (messages.value.length === 0) return
  try {
    await ElMessageBox.confirm(
      '助手会忘掉之前聊过的内容。已经建出来的工单不受影响。',
      '开始新会话',
      { confirmButtonText: '开新会话', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  messages.value = []
  sessionId.value = null
}

/* ── 知识库工具 ───────────────────────────────────────────────────────── */

const showTools = ref(false)

/**
 * 检索预览。
 *
 * ★ 排查「助手答得不对」的第一个动作：**先只看检索结果**。
 *   召回为空 → 语料问题（没灌进去 / 这个问题没有对应文档），重问一百次也没用；
 *   召回正常但答得不对 → 提示词或模型的问题。
 *   这两条路的处理完全不同，所以这个开关是常驻的，不是「高级功能」。
 */
const probeQuery = ref('')
const probeRows = ref<KnowledgeChunkVO[] | null>(null)
const probing = ref(false)

async function runProbe(): Promise<void> {
  const q = probeQuery.value.trim()
  if (!q || probing.value) return
  probing.value = true
  try {
    probeRows.value = (await searchKnowledge(q)) ?? []
  } catch {
    probeRows.value = null
  } finally {
    probing.value = false
  }
}

const reloading = ref(false)

async function doReload(): Promise<void> {
  if (reloading.value) return
  try {
    await ElMessageBox.confirm(
      '会按 knowledge/ 目录下的 markdown 重建全部向量，已有片段先删后写。文档多时这一步要花上一会儿。',
      '重建知识库索引',
      { confirmButtonText: '开始重建', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  reloading.value = true
  try {
    const count = await reloadKnowledge()
    if (count === null) return

    // ★ 0 不能报成功：那说明文档一条都没读到（目录空、路径写错、打包后没带上）。
    //   报成「重建完成」会让用户带着一个空知识库去提问，然后怪模型。
    if (count === 0) {
      ElMessage({
        type: 'warning',
        message: '重建完成，但一条片段都没生成 —— 请检查 classpath:knowledge/ 下是否有文档。',
        duration: 6000,
        showClose: true,
      })
    } else {
      ElMessage({ type: 'success', message: `索引已重建，共 ${count} 个片段` })
    }
  } catch {
    // http 层已提示
  } finally {
    reloading.value = false
  }
}

/* ── 开场示例 ─────────────────────────────────────────────────────────── */

/**
 * 示例问题的写法：**都带具体的桩编号**。
 * 问「充电桩坏了怎么办」只会得到一段泛泛的手册摘录，看不出工具调用有没有发生；
 * 带编号才会走「查状态 → 查告警 → 检索案例」那条链。
 */
const EXAMPLES = [
  '1 号桩通信中断告警怎么处理？',
  'E-021 这个故障码是什么意思？',
  '最近这台桩报过哪些警，有类似的工单吗？',
] as const

/** 助手回答没有 markdown 渲染，这里把模型可能输出的格式折成结构化块。 */
function blocksOf(text: string) {
  return parseAnswer(text)
}

// 会话一旦开始就锁定在底部附近：新消息进来时自动滚。
watch(() => messages.value.length, () => void scrollToBottom())
</script>

<template>
  <div class="chat">
    <PagePanel
      class="chat-panel"
      title="AI 运维助手"
      subtitle="用自然语言问设备问题。助手会自己查设备状态、翻最近告警、检索知识库和历史工单，再给出处理建议。"
      flush
    >
      <template #actions>
        <el-button size="small" :disabled="messages.length === 0" @click="newSession">
          <CoIcon name="plus" :size="13" />
          新会话
        </el-button>
        <el-button
          size="small"
          :type="showTools ? 'primary' : 'default'"
          :plain="!showTools"
          @click="showTools = !showTools"
        >
          <CoIcon name="search" :size="13" />
          检索工具
        </el-button>
        <el-button size="small" :loading="reloading" @click="doReload">
          <CoIcon name="refresh" :size="13" />
          重建索引
        </el-button>
      </template>

      <div class="body" :class="{ 'with-tools': showTools }">
        <div ref="threadEl" class="thread">
          <!-- 开场：示例问题做成可点的按钮。空输入框 + 一句「随便问」，
               实际会让人盯着屏幕不知道从哪问起。 -->
          <div v-if="messages.length === 0" class="intro">
            <span class="intro-mark"><CoIcon name="ai" :size="22" /></span>
            <h3>问点什么</h3>
            <p>
              助手看得见实时数据，所以问题里带上<b>桩编号或故障码</b>才会有具体答案。
              问完之后如果它认为要跟进，会直接建一张工单。
            </p>
            <div class="examples">
              <button
                v-for="ex in EXAMPLES"
                :key="ex"
                type="button"
                class="example"
                @click="send(ex)"
              >
                {{ ex }}
              </button>
            </div>
          </div>

          <div v-for="(m, i) in messages" :key="i" class="msg" :class="`is-${m.role}`">
            <span class="avatar">
              <CoIcon :name="m.role === 'user' ? 'user' : 'ai'" :size="14" />
            </span>

            <div class="bubble" :class="{ 'is-failed': m.failed }">
              <!-- 用户消息一律纯文本：它就是用户自己敲的，不需要任何解释 -->
              <p v-if="m.role === 'user'" class="plain">{{ m.text }}</p>

              <template v-else>
                <template v-for="(b, bi) in blocksOf(m.text)" :key="bi">
                  <p v-if="b.kind === 'p'" class="blk">
                    <template v-for="(s, si) in b.spans" :key="si">
                      <strong v-if="s.strong">{{ s.text }}</strong>
                      <code v-else-if="s.code">{{ s.text }}</code>
                      <template v-else>{{ s.text }}</template>
                    </template>
                  </p>
                  <h4 v-else-if="b.kind === 'h'" class="blk-h">
                    <template v-for="(s, si) in b.spans" :key="si">
                      <strong v-if="s.strong">{{ s.text }}</strong>
                      <template v-else>{{ s.text }}</template>
                    </template>
                  </h4>
                  <p v-else class="blk li">
                    <span class="marker">{{ b.marker }}</span>
                    <span>
                      <template v-for="(s, si) in b.spans" :key="si">
                        <strong v-if="s.strong">{{ s.text }}</strong>
                        <code v-else-if="s.code">{{ s.text }}</code>
                        <template v-else>{{ s.text }}</template>
                      </template>
                    </span>
                  </p>
                </template>

                <!-- ★ 依据必须可展开。没有这一块，助手说的每句话都无法核对，
                     而运维照着一条编出来的建议去断电，代价是实打实的。 -->
                <details v-if="!m.failed" class="refs">
                  <summary>
                    <CoIcon name="search" :size="12" />
                    <template v-if="m.references && m.references.length">
                      参考了 {{ m.references.length }} 段知识库内容
                    </template>
                    <template v-else>这一轮没有召回知识库内容</template>
                  </summary>

                  <ul v-if="m.references && m.references.length" class="ref-list">
                    <li v-for="(r, ri) in m.references" :key="ri">
                      <div class="ref-head">
                        <span class="ref-source">{{ r.source }}</span>
                        <span v-if="r.section" class="ref-section">{{ r.section }}</span>
                        <span v-if="r.score !== null" class="ref-score">
                          相似度 {{ r.score.toFixed(3) }}
                        </span>
                      </div>
                      <p class="ref-snippet">{{ r.snippet }}</p>
                    </li>
                  </ul>
                  <p v-else class="ref-none">
                    说明知识库里没有这个问题的对应文档。助手此时只能靠通用知识回答 ——
                    这种回答要打个折扣看，正确的补救是把相关资料放进 knowledge/ 目录后重建索引。
                  </p>
                </details>
              </template>
            </div>
          </div>

          <!-- ★ 进行态。文案要**说出要等多久**，否则用户等 8 秒就以为坏了。 -->
          <div v-if="sending" class="msg is-assistant">
            <span class="avatar"><CoIcon name="ai" :size="14" /></span>
            <div class="bubble is-thinking">
              <span class="dots"><i /><i /><i /></span>
              <span class="thinking-text">
                正在处理。本地模型加上工具调用，通常要 10~60 秒 ——
                <b>这期间请不要重复提问</b>，助手可能会建工单。
              </span>
            </div>
          </div>
        </div>

        <aside v-if="showTools" class="tools">
          <h4>知识库检索</h4>
          <p class="tools-hint">
            只检索、不调模型。用来分辨「知识库里没有」和「模型答得不好」——
            前者要补文档，后者重问也没用。
          </p>
          <el-input
            v-model="probeQuery"
            placeholder="例如：E-021"
            clearable
            @keyup.enter="runProbe"
          />
          <el-button class="probe-btn" size="small" :loading="probing" @click="runProbe">
            检索
          </el-button>

          <EmptyState
            v-if="probeRows !== null && probeRows.length === 0"
            kind="empty"
            title="没有召回任何片段"
            description="换个关键词，或者确认这些文档已经灌进索引（点上面的「重建索引」）。"
          />
          <ul v-else-if="probeRows && probeRows.length" class="probe-list">
            <li v-for="(r, i) in probeRows" :key="i">
              <div class="ref-head">
                <span class="ref-source">{{ r.source }}</span>
                <span v-if="r.score !== null" class="ref-score">{{ r.score.toFixed(3) }}</span>
              </div>
              <p class="ref-snippet">{{ r.snippet }}</p>
            </li>
          </ul>
        </aside>
      </div>

      <template #footer>
        <div class="composer">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="rows"
            resize="none"
            :disabled="sending"
            placeholder="问一个设备问题，Enter 发送，Shift + Enter 换行"
            @keydown="onKeydown"
          />
          <div class="composer-side">
            <span class="counter" :class="{ 'is-over': tooLong }">
              {{ draft.trim().length }} / {{ MAX_QUESTION }}
            </span>
            <el-button type="primary" :loading="sending" :disabled="!canSend || tooLong" @click="send()">
              发送
            </el-button>
          </div>
        </div>
      </template>
    </PagePanel>
  </div>
</template>

<style scoped>
/* ★ 页面的高度要自己定：`.content` 是自然高度（没有滚动容器），
   聊天页需要「输入框固定、只有消息区滚动」，所以这里把整页钉在一屏内。
   108px = 顶栏 56 + 内容区上下内边距 20 + 32。 */
.chat {
  height: calc(100vh - 108px);
  min-height: 420px;
}

.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.chat-panel :deep(.panel-body) {
  flex: 1;
  min-height: 0;
  padding: 0;
}

.body {
  display: flex;
  height: 100%;
  min-height: 0;
}

.thread {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 18px 20px 4px;
}

/* ── 开场 ─────────────────────────────────────────────────────────── */
.intro {
  max-width: 52ch;
  margin: 28px auto 0;
  text-align: center;
}

.intro-mark {
  display: inline-grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 14px;
  background: var(--co-accent-weak);
  color: var(--co-accent-strong);
}

.intro h3 {
  margin: 12px 0 6px;
  font-size: 15px;
  font-weight: 600;
  color: var(--co-text-1);
}

.intro p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.intro b {
  color: var(--co-text-2);
  font-weight: 600;
}

.examples {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 18px;
}

.example {
  padding: 10px 14px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-card);
  color: var(--co-text-2);
  font-family: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--co-duration-fast) var(--co-ease),
    color var(--co-duration-fast) var(--co-ease);
}

.example:hover {
  border-color: var(--co-primary);
  color: var(--co-primary);
}

/* ── 消息 ─────────────────────────────────────────────────────────── */
.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.msg.is-user {
  flex-direction: row-reverse;
}

.avatar {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 9px;
  background: var(--co-bg-3);
  color: var(--co-text-2);
}

.msg.is-assistant .avatar {
  background: var(--co-accent-weak);
  color: var(--co-accent-strong);
}

.bubble {
  max-width: 76ch;
  padding: 11px 14px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-card);
  font-size: 13px;
  line-height: 1.75;
  color: var(--co-text-1);
  min-width: 0;
}

.msg.is-user .bubble {
  background: var(--co-primary-weak);
  border-color: color-mix(in srgb, var(--co-primary) 22%, transparent);
}

/* 失败消息用警示底色，和正常回答区分 —— 它是「你没拿到东西」，
   不是一段助手说的话。 */
.bubble.is-failed {
  background: var(--co-warning-weak);
  border-color: color-mix(in srgb, var(--co-warning) 28%, transparent);
  color: var(--co-warning-strong);
}

.plain {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.blk {
  margin: 0 0 8px;
  white-space: pre-wrap;
  word-break: break-word;
}

.blk:last-of-type {
  margin-bottom: 0;
}

.blk-h {
  margin: 14px 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--co-text-1);
}

.blk-h:first-child {
  margin-top: 0;
}

.blk.li {
  display: flex;
  gap: 7px;
  padding-left: 2px;
}

.blk.li .marker {
  flex-shrink: 0;
  color: var(--co-text-3);
  font-variant-numeric: tabular-nums;
}

.bubble strong {
  font-weight: 600;
}

.bubble code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--co-bg-3);
  font-family: 'Cascadia Mono', Consolas, monospace;
  font-size: 12px;
}

/* ── 引用 ─────────────────────────────────────────────────────────── */
.refs {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--co-border-light);
}

.refs summary {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--co-text-3);
  cursor: pointer;
  list-style: none;
}

.refs summary::-webkit-details-marker {
  display: none;
}

.refs summary::after {
  content: '▾';
  margin-left: 2px;
  font-size: 10px;
  transition: transform var(--co-duration-fast) var(--co-ease);
}

.refs[open] summary::after {
  transform: rotate(180deg);
}

.refs:hover summary {
  color: var(--co-primary);
}

.ref-list,
.probe-list {
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ref-list li,
.probe-list li {
  padding: 9px 11px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-2);
}

.ref-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 11.5px;
}

.ref-source {
  color: var(--co-text-2);
  font-weight: 500;
}

.ref-section {
  color: var(--co-text-3);
}

.ref-score {
  margin-left: auto;
  color: var(--co-text-4);
  font-variant-numeric: tabular-nums;
}

.ref-snippet {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.65;
  color: var(--co-text-2);
  /* 片段可能很长，最多展示 4 行，剩下的交给用户自己去知识库里翻 */
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ref-none {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.65;
  color: var(--co-text-3);
}

/* ── 进行态 ───────────────────────────────────────────────────────── */
.bubble.is-thinking {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--co-text-3);
}

.thinking-text {
  font-size: 12.5px;
  line-height: 1.6;
}

.thinking-text b {
  color: var(--co-text-2);
  font-weight: 600;
}

.dots {
  display: inline-flex;
  gap: 4px;
  flex-shrink: 0;
}

.dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--co-accent);
  animation: co-bounce 1.2s var(--co-ease) infinite;
}

.dots i:nth-child(2) {
  animation-delay: 0.15s;
}

.dots i:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes co-bounce {
  0%,
  60%,
  100% {
    opacity: 0.3;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .dots i {
    animation: none;
    opacity: 0.7;
  }
}

/* ── 右侧检索工具 ─────────────────────────────────────────────────── */
.tools {
  width: 300px;
  flex-shrink: 0;
  overflow-y: auto;
  padding: 16px 16px 20px;
  border-left: 1px solid var(--co-border-light);
  background: var(--co-bg-2);
}

.tools h4 {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--co-text-1);
}

.tools-hint {
  margin: 0 0 12px;
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--co-text-3);
}

.probe-btn {
  margin-top: 8px;
  width: 100%;
}

/* ── 输入区 ───────────────────────────────────────────────────────── */
.composer {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  width: 100%;
}

.composer :deep(.el-textarea) {
  flex: 1;
}

.composer-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  flex-shrink: 0;
}

.counter {
  font-size: 11px;
  color: var(--co-text-4);
  font-variant-numeric: tabular-nums;
}

.counter.is-over {
  color: var(--co-danger);
  font-weight: 600;
}
</style>
