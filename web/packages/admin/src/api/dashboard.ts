/**
 * 工作台数据。
 *
 * ★★ 这个文件存在的唯一理由：**后端 52 个管理端端点里没有任何统计接口** ★★
 *   没有 `/statistics`、没有 `/overview`、没有 `/summary`。所以工作台上的数字
 *   只有两条来路，而它们的**可信度不一样**：
 *
 *     1. **各域总数**（站点 / 充电桩 / 充电枪 / 车主）
 *        → 用列表接口 `pageSize=1` 拿响应里的 `total`。这是后端 `COUNT(*)`
 *          数出来的**精确值**，不是估算。传 `pageSize=1` 是因为工作台一条记录
 *          都不需要，传 100 会把 100 行白白搬到浏览器再丢掉。
 *
 *     2. **营收 / 电量 / 状态分布**
 *        → 后端没有聚合接口，只能把订单列表翻回来在客户端自己加。
 *          翻的页数有上限（`DASHBOARD_SAMPLE_LIMIT = 500` 单），
 *          所以它们是**有偏样本**（偏「最新的 500 单」，且只覆盖近 7 天）。
 *          → 调用方**必须**把口径显示给用户（见 `OrderSample` 的四个字段），
 *            **不要**渲染成「总营收 ¥12,345.67」这种没有口径的表述：
 *            一个看起来精确的假数字比不做更糟，因为它会被人拿去对账。
 */

import {
  AMOUNT_SCALE,
  DASHBOARD_CONCURRENCY,
  DASHBOARD_SAMPLE_LIMIT,
  MAX_PAGE_SIZE,
  ORDER_STATUS,
  ORDER_STATUS_LIST,
  defaultOrderRange,
  orderStatusLabel,
  parseServerTime,
  sumDecimal,
  type AdminOrderVO,
  type Tone,
} from '@chargeops/shared'
import { http } from './http'

// ══════════════════════════════════════════════════════════════════════════
// 一、精确值：各域总数
// ══════════════════════════════════════════════════════════════════════════

/**
 * 有「总数」可取的域。
 *
 * ★ URL **逐字**照抄后端：类级 `@RequestMapping("/api/admin/xxx")`
 *   + 方法级 `@GetMapping("/page")`。拼错不会报错（后端关了
 *   `FAIL_ON_UNKNOWN_PROPERTIES`），只会 404 —— 所以宁可在这里写死一张表。
 */
export type CountKey = 'station' | 'charger' | 'connector' | 'member'

const COUNT_URL: Record<CountKey, string> = {
  station: '/api/admin/station/page',
  charger: '/api/admin/charger/page',
  connector: '/api/admin/connector/page',
  member: '/api/admin/member/page',
}

/**
 * 取某个域的**精确**总数。
 *
 * ★ `pageSize=1`：只要 `total`，不要记录。后端 `pageSize` 上限是 100
 *   （`@Max(100)`，超了直接 `code:0`），而 `http.page()` 内部已经把它夹在
 *   `[1, 100]` 之间，所以这里传 1 是安全的。
 */
export async function fetchTotal(key: CountKey): Promise<number> {
  const paged = await http.page<unknown>(COUNT_URL[key], { pageNum: 1, pageSize: 1 })
  return paged.total
}

// ══════════════════════════════════════════════════════════════════════════
// 二、有偏样本：近 7 天的订单
// ══════════════════════════════════════════════════════════════════════════

/**
 * ★★ 订单列表的路径是 `/api/admin/orders`，**不是** `/api/admin/order/page` ★★
 *   订单是唯一一个「列表接口不带 `/page` 后缀」的域 —— 见 `OrderAdminController`
 *   类级的 `@RequestMapping("/api/admin/orders")` 加方法级的裸 `@GetMapping`。
 *   按其他域的习惯补一个 `/page` 上去会 404。
 */
const ORDER_URL = '/api/admin/orders'

/** 采样每页取 100 条 —— 后端 `@Max(100)` 的上限，页数越少请求越少。 */
const SAMPLE_PAGE_SIZE = MAX_PAGE_SIZE

/** 采样最多翻几页：500 / 100 = 5 页。 */
const SAMPLE_PAGE_COUNT = Math.ceil(DASHBOARD_SAMPLE_LIMIT / SAMPLE_PAGE_SIZE)

/** 采样结果 + 口径。**四个字段都是给 UI 写说明用的，不要只取 rows**。 */
export interface OrderSample {
  /** 采样到的订单（**不是全量**），按 `createTime` 倒序。 */
  rows: AdminOrderVO[]
  /**
   * 近 7 天的订单**总数**（第一页响应里的 `total`，后端精确值）。
   *
   * ★ 这一个数字**不是样本** —— 它和 `rows.length` 的区别正是口径说明的落点：
   *   「近 7 天订单数」可以精确到个位，而「营收」只能按样本估算。
   */
  totalInRange: number
  /**
   * 是否**已经拿到近 7 天的全部订单**（没触上限、也没有分页失败）。
   * 为 true 时样本其实就是全量 —— UI 值得把这句话说出来，
   * 因为「样本 137 单」和「样本 137 单，已是全量」在读的人眼里不是一回事。
   */
  complete: boolean
  /**
   * 是否触到了 `DASHBOARD_SAMPLE_LIMIT` 单的采样上限。
   *
   * ★★ 它和 `complete` 是**两件事**，文案也不同 ★★
   *   触上限意味着「实际值**确定**高于此」；而分页失败只是「不知道差多少」。
   *   混成一句会丢掉最有用的那条信息。
   */
  truncated: boolean
  /** 取回失败的页数。>0 时样本不只是**有偏**，而是**残缺**的。 */
  failedPages: number
}

/**
 * 受限并发地跑一批任务，**其中一个失败不影响其余的**。
 *
 * ★ 为什么不能直接 `Promise.all` 全并发：工作台一次要发十来个请求，
 *   全并发会把后端连接池瞬间打满 —— `DASHBOARD_CONCURRENCY = 4` 就是为这件事定的。
 *
 * ★ 为什么返回 settled 结果而不是「fulfilled 数组」：
 *   翻页时挂了一页，不应该让已经拿到的几页作废。少算几单，
 *   比整页报错更接近真相 —— 而口径说明里已经写了「仅供参考」。
 */
async function mapLimit<T, R>(
  limit: number,
  items: readonly T[],
  task: (item: T) => Promise<R>,
): Promise<Array<PromiseSettledResult<R>>> {
  const results = new Array<PromiseSettledResult<R>>(items.length)
  let cursor = 0

  async function worker(): Promise<void> {
    while (cursor < items.length) {
      const index = cursor
      cursor += 1
      try {
        results[index] = { status: 'fulfilled', value: await task(items[index]) }
      } catch (reason) {
        results[index] = { status: 'rejected', reason }
      }
    }
  }

  const workers = Math.min(Math.max(limit, 1), items.length)
  // 空数组时 workers = 0，`Promise.all([])` 立即完成 —— 不要写成 `workers || 1`，
  // 那会去读一个不存在的下标。
  await Promise.all(Array.from({ length: workers }, () => worker()))
  return results
}

/** 时间字符串 → 可比较的数字。解析不了当 0（排到最后），不让一个坏值把排序炸掉。 */
function timeValue(input: string | null | undefined): number {
  // ★ 必须走 `parseServerTime`，**禁止** `new Date(后端字符串)`：
  //   后端给的是 `yyyy-MM-dd HH:mm:ss`（空格分隔、非 ISO），Safari 会得到 Invalid Date。
  return parseServerTime(input)?.getTime() ?? 0
}

/**
 * 拉取近 7 天的订单样本（最多 500 单）。
 *
 * ★★ `createTimeFrom/To` **不是优化，是前提** ★★
 *   不带任何条件的订单列表在后端是 `ORDER BY create_time DESC` 全表 filesort ——
 *   没有索引能把排序和这个条件一起吃掉。而工作台是首屏，它慢等于整个后台慢。
 *   区间由 shared 的 `defaultOrderRange()` 生成（近 7 天，含端点）。
 */
export async function fetchOrderSample(): Promise<OrderSample> {
  const { begin, end } = defaultOrderRange()
  const range = { createTimeFrom: begin, createTimeTo: end }

  // 第一页单独发：它的 `total` 决定了要不要继续翻，而且这个 total 本身就是
  // 「近 7 天订单数」这个精确值，无论后面翻不翻得到都不受影响。
  const first = await http.page<AdminOrderVO>(ORDER_URL, {
    pageNum: 1,
    pageSize: SAMPLE_PAGE_SIZE,
    ...range,
  })

  const totalInRange = first.total
  const pageCount = Math.min(
    Math.max(1, Math.ceil(totalInRange / SAMPLE_PAGE_SIZE)),
    SAMPLE_PAGE_COUNT,
  )

  const remainingPages: number[] = []
  for (let pageNum = 2; pageNum <= pageCount; pageNum += 1) remainingPages.push(pageNum)

  const rest = await mapLimit(DASHBOARD_CONCURRENCY, remainingPages, (pageNum) =>
    // 原样带 `http.get` 的 GET 去重：每页参数不同，本来也不会互相合并
    http.page<AdminOrderVO>(ORDER_URL, { pageNum, pageSize: SAMPLE_PAGE_SIZE, ...range }),
  )

  const records: AdminOrderVO[] = [...first.records]
  let failedPages = 0
  for (const item of rest) {
    if (item.status === 'fulfilled') records.push(...item.value.records)
    else failedPages += 1
  }

  // ★ 按 id 去重：翻页期间若有新单落库，后续页会整体后移，同一条可能在两页里
  //   都出现（同时另一些被跳过）。去重**补不回**被跳过的那几条 ——
  //   所以样本还带一个「翻页窗口内的新单」偏差，口径说明里已写「仅供参考」。
  const seen = new Set<number>()
  const rows: AdminOrderVO[] = []
  for (const row of records) {
    if (seen.has(row.id)) continue
    seen.add(row.id)
    rows.push(row)
  }

  // 后端已按 create_time DESC 排好，这里再排一次是为了让「最近 N 条」只依赖
  // 时间本身，而不依赖分页结果拼装的顺序。
  rows.sort((a, b) => timeValue(b.createTime) - timeValue(a.createTime))

  // ★ 判据用 `totalInRange > 上限` 而**不是** `rows.length < totalInRange`：
  //   后者在「翻页期间有新单落库导致去重掉了两条」时也会成立，
  //   于是口径会变成「已达 500 单上限，实际值高于此」—— 而实际上并没有触上限。
  //   口径写错比不写更糟，因为它给了用户一个错误的确定性。
  const truncated = totalInRange > DASHBOARD_SAMPLE_LIMIT

  return {
    rows,
    totalInRange,
    complete: failedPages === 0 && !truncated,
    truncated,
    failedPages,
  }
}

// ══════════════════════════════════════════════════════════════════════════
// 三、样本聚合
// ══════════════════════════════════════════════════════════════════════════

/** 条形图的一行。`label` / `tone` 都来自 `ORDER_STATUS_LIST`，**不要自己写色值**。 */
export interface OrderStatusCount {
  code: number
  /** 已知状态用字典里的中文名；未知码用 `状态N`（露出原始码，见 `orderStatusLabel`）。 */
  label: string
  tone: Tone
  count: number
}

/** 由样本推算出来的那几个数字。 */
export interface OrderStats {
  /** 样本内「已支付」订单的实付金额合计。**字符串**（2 位小数），不是 number。 */
  paidAmount: string
  /** 样本内的充电量合计（4 位小数，展示时再取 2 位）。 */
  energyKwh: string
  /** 各状态单数。顺序 = `ORDER_STATUS_LIST`，未知码追加在末尾。 */
  statusCounts: OrderStatusCount[]
  /** 采样到的订单数（= 口径说明里的 N）。 */
  sampleSize: number
}

/**
 * 电量的小数位。**必须和 `formatKwh` 内部用的一致（4 位）** ——
 * 先用 2 位求和，等于把每一单的充电量各自取整后再相加，500 单的偏差是肉眼可见的。
 */
const ENERGY_SCALE = 4

/**
 * 把样本聚合成工作台上的数字。
 *
 * ★★ 金额**不能**用 `Number(a) + Number(b)` ★★
 *   后端 BigDecimal 的承诺是「只在最后取整一次、Σ分段 == 总额」。
 *   前端浮点求和在分位上会出现偏差，于是「分段加起来 ≠ 总额」看起来像后端 bug，
 *   实际是前端算错 —— 排查方向会完全跑偏。所以一律走 `sumDecimal`（内部 BigInt）。
 *
 * ★ 营收口径只取 `5 已支付`，理由都在取舍上：
 *   · `4 待支付` / `6 支付失败` 的钱**还没收到**，算进去是虚增；
 *   · `7 已退款` 的钱已经回到车主余额，但**列表接口没有 `refundAmount`**
 *     （只有订单详情里才有），所以无法从列表里扣减，只能整单排除。
 *     → 「宁可少算，不要算错」，卡片上把这一点写明了。
 *
 * ★ 电量口径是**全部样本**：电已经真的充进车里了，和订单后来是退款还是取消无关。
 *   没充过电的订单 `totalEnergyKwh` 是 null，`toScaled` 会把它当 0（不是漏算）。
 */
export function aggregateSample(rows: readonly AdminOrderVO[]): OrderStats {
  const paidAmounts = rows
    .filter((order) => order.status === ORDER_STATUS.PAID)
    .map((order) => order.payAmount)

  const counter = new Map<number, number>()
  for (const order of rows) counter.set(order.status, (counter.get(order.status) ?? 0) + 1)

  const known = ORDER_STATUS_LIST.map((meta) => ({
    code: meta.code as number,
    label: meta.label,
    tone: meta.tone as Tone,
    count: counter.get(meta.code) ?? 0,
  }))

  // 后端加了新状态而前端字典没跟上时，**不能把它悄悄丢掉** ——
  // 那会让「各状态之和 ≠ 样本单数」，而用户找不到少掉的那几条。
  // 追加在末尾并露出原始码，与 OrderStatusTag 的兜底是同一个原则。
  const extras = [...counter.keys()]
    .filter((code) => !known.some((item) => item.code === code))
    .sort((a, b) => a - b)
    .map((code) => ({
      code,
      label: orderStatusLabel(code),
      tone: 'info' as Tone,
      count: counter.get(code) ?? 0,
    }))

  return {
    paidAmount: sumDecimal(paidAmounts, AMOUNT_SCALE),
    energyKwh: sumDecimal(
      rows.map((order) => order.totalEnergyKwh),
      ENERGY_SCALE,
    ),
    statusCounts: [...known, ...extras],
    sampleSize: rows.length,
  }
}
