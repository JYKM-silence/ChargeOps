/**
 * 助手回答的极简排版。
 *
 * ★★ 为什么需要它，以及为什么不用 markdown 库 ★★
 *
 *   模型（qwen3）的输出带一点点格式：`### 小标题`、`- 列表`、`**重点**`。
 *   直接按纯文本渲染，用户会看到满屏的星号和井号；而 `white-space: pre-wrap`
 *   只能保住换行，保不住强调。
 *
 *   引一个完整 markdown 库不划算，而且**危险**：渲染 markdown 通常要 `v-html`，
 *   而这段文本的源头是一个会跟着知识库文档和用户提问跑偏的模型 ——
 *   等于把「模型可以往页面里注入 HTML」变成一条固定存在的路径。
 *
 *   → 所以这里**只解析上面那三种**，并且解析结果是一棵由模板渲染的数据树，
 *     全程不碰 `v-html`。模型输出了别的语法（表格、引用块）就原样按文本显示，
 *     那只是不好看，不会变成别的东西。
 *
 * ★ 只处理单行内的强调，不做跨行匹配：跨行的 `**` 几乎总是模型没写完，
 *   强行配对反而会把一大段正文变成粗体。
 */

/** 行内片段。`strong` / `code` 互斥（`` `**x**` `` 按 code 处理）。 */
export interface Span {
  text: string
  strong?: boolean
  code?: boolean
}

export type Block =
  | { kind: 'p'; spans: Span[] }
  | { kind: 'h'; spans: Span[] }
  | { kind: 'li'; spans: Span[]; marker: string }

/** `**粗体**` 或 `` `代码` ``，非贪婪；匹配不到就整段当普通文字。 */
const INLINE_RE = /\*\*([^*\n]+)\*\*|`([^`\n]+)`/g

function parseSpans(line: string): Span[] {
  const spans: Span[] = []
  let cursor = 0

  // 每次 `exec` 前重置 lastIndex：正则带 g 标志时它会跨调用保留，
  // 忘了重置会让第二次解析从上次的位置开始 —— 表现为「有些行少了开头一段」。
  INLINE_RE.lastIndex = 0

  let match: RegExpExecArray | null
  while ((match = INLINE_RE.exec(line)) !== null) {
    if (match.index > cursor) spans.push({ text: line.slice(cursor, match.index) })
    if (match[1] !== undefined) spans.push({ text: match[1], strong: true })
    else spans.push({ text: match[2] as string, code: true })
    cursor = match.index + match[0].length
  }

  if (cursor < line.length) spans.push({ text: line.slice(cursor) })
  // 空行也要产出一个空 span，否则模板里那句 `v-for` 什么都不渲染，
  // 段落会塌成 0 高，行间距消失。
  return spans.length > 0 ? spans : [{ text: '' }]
}

const HEADING_RE = /^\s{0,3}#{1,6}\s+(.*)$/
const BULLET_RE = /^\s{0,3}[-*+]\s+(.*)$/
const ORDERED_RE = /^\s{0,3}(\d{1,2})[.)]\s+(.*)$/

export function parseAnswer(text: string): Block[] {
  const blocks: Block[] = []

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trimEnd()
    if (line.trim() === '') continue

    const heading = HEADING_RE.exec(line)
    if (heading) {
      blocks.push({ kind: 'h', spans: parseSpans(heading[1]) })
      continue
    }

    const bullet = BULLET_RE.exec(line)
    if (bullet) {
      blocks.push({ kind: 'li', marker: '·', spans: parseSpans(bullet[1]) })
      continue
    }

    const ordered = ORDERED_RE.exec(line)
    if (ordered) {
      blocks.push({ kind: 'li', marker: `${ordered[1]}.`, spans: parseSpans(ordered[2]) })
      continue
    }

    blocks.push({ kind: 'p', spans: parseSpans(line) })
  }

  return blocks
}
