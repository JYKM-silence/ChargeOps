/**
 * 编辑表单的增量提交。
 *
 * ★★ 为什么不能把整个表单原样 PUT ★★
 *
 *   后端**所有** `*UpdateDTO` 都是「**传了就改、不传就不动**」的局部更新
 *   （`StationUpdateDTO` 的类注释：「这里没有 `@NotNull`」）。
 *   这带来一个反直觉的后果：
 *
 *   · 把整个表单原样提交 = **把没碰过的字段也「改」了一遍** ——
 *     值虽然相同，但 `update_user` / `update_time` 会被刷新，
 *     审计日志里会看到「张三改了 20 个字段」，而他只改了电话。
 *   · 更要紧的是**并发覆盖**：桩/站点/套餐**都没有 version 字段**
 *     （乐观锁只有充电枪有），所以「读出来 → 提交整份」这个模式
 *     会让 A 的改动静默覆盖 B 的改动，两边都不报错。
 *
 *   只提交真正改动过的键，能把上面两个问题都缩小到「确实改了的那些字段」。
 *
 * ★ 即使这样也**挡不住真正的并发**（B 改了电话、A 改了名称，A 提交时
 *   只带名称 → B 的电话改动保住了；但如果两人都改名称，后写仍然覆盖先写）。
 *   要真正解决得给表加 version 列 —— 这是 DDL 变更，不在本次范围。
 */

/**
 * 「设了就没法清空」的字段。
 *
 * ★★ 这是一个**必须写进 UI 的事实**：这些字段在 UpdateDTO 里是
 *   `if (x != null) set...`，所以**传 null 等于「不修改」而不是「清空」**。
 *   用户在编辑框里把「划线价」删掉再保存，后端收到的是 null，
 *   结果是**没有任何变化** —— 而他以为删掉了。
 *
 *   所以表单里这些字段**不要放「清空」按钮**，并在旁边写上说明。
 *   想真正清空只能改库。
 */
export const UNSETTABLE_FIELDS = [
  'originPrice', // ChargePackageUpdateDTO：划线价
  'effectiveTo', // StationTariffUpdateDTO：生效结束时刻
  'remark', // 多个 UpdateDTO：备注
  'image', // ChargePackageUpdateDTO：图片地址
  'description', // ChargePackageUpdateDTO：套餐说明
] as const

export interface DiffResult<T> {
  /** 只含改动过的键。空对象表示「什么都没改」，调用方应当直接关弹窗。 */
  payload: Partial<T>
  /** 被跳过的「试图清空但清不掉」的字段，用于给用户一个提示。 */
  unsettable: string[]
}

/**
 * 计算增量。
 *
 * @param original 打开编辑弹窗时的原始对象（必须是**服务端原值**，不是上一次提交的值）
 * @param edited   表单当前值
 */
export function diffUpdate<T extends Record<string, unknown>>(original: T, edited: T): DiffResult<T> {
  const payload: Partial<T> = {}
  const unsettable: string[] = []

  for (const key of Object.keys(edited) as (keyof T & string)[]) {
    const next = edited[key]
    const prev = original[key]

    // 表单里没这个字段（被 v-if 隐藏了）→ 不动它
    if (next === undefined) continue

    // 相等就跳过。★ 用宽松比较处理 `1` 与 `'1'`：
    // el-select 的 value 常常是字符串，而接口要数字，严格比较会把
    // 「没改」误判成「改了」，于是一次编辑带上全表字段。
    if (looseEquals(next, prev)) continue

    // 试图清空一个清不掉的字段
    if (
      (UNSETTABLE_FIELDS as readonly string[]).includes(key) &&
      (next === null || next === '') &&
      prev !== null &&
      prev !== '' &&
      prev !== undefined
    ) {
      unsettable.push(key)
      continue
    }

    payload[key] = next
  }

  return { payload, unsettable }
}

/** 宽松相等：数字/字符串互认，空值与空值相等。 */
function looseEquals(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a === null || a === undefined || a === '') {
    return b === null || b === undefined || b === ''
  }
  if (b === null || b === undefined || b === '') return false
  // 只对数字/字符串互转做宽松比较，其余走严格
  if (typeof a === 'number' || typeof b === 'number') {
    const na = Number(a)
    const nb = Number(b)
    if (Number.isFinite(na) && Number.isFinite(nb)) return na === nb
  }
  return false
}

/** 增量是否为空（没有任何改动）。用于「没改就关窗，别打一次空 PUT」。 */
export function isEmptyDiff<T extends Record<string, unknown>>(payload: Partial<T>): boolean {
  return Object.keys(payload).length === 0
}
