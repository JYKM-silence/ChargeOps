/**
 * 列表查询的默认值与限制。
 *
 * 这里每一条都对应一个**后端行为**，不是拍脑袋定的体验优化。
 */

import { lastNDaysRange } from '../utils/datetime'

/**
 * 订单列表**默认只查近 7 天**。
 *
 * ★★ 为什么必须给默认区间：后端订单列表在**不带任何过滤条件**时，
 *   是 `ORDER BY create_time DESC` + PageHelper 分页 ——
 *   没有可用的索引能把排序和条件一起吃掉，于是对**全表**做 filesort。
 *   订单表是这个项目里增长最快的表，演示时第一批数据就能感觉到首屏变慢。
 *
 *   这不是前端能「优化」的问题（索引在后端），但前端可以不触发它：
 *   默认带上 7 天区间，命中的是 `idx_order_status_create` 一类的索引。
 *
 * ★ UI 上必须**显示**这个默认条件，并且可以清掉 ——
 *   偷偷加筛选条件会让用户以为「这个时间段没有数据」，
 *   而实际上他只是没看到筛选器里有值。
 */
export const ORDER_DEFAULT_RANGE_DAYS = 7

/** 生成订单列表的默认时间区间。 */
export function defaultOrderRange(now = new Date()): { begin: string; end: string } {
  return lastNDaysRange(ORDER_DEFAULT_RANGE_DAYS, now)
}

/**
 * 按手机号搜索车主的**最小长度**：6 位数字。
 *
 * ★★ 后端的订单列表 / 车主列表按 `memberPhone` 做的是**前缀搜索**
 *   （`LIKE '138%'`）。前缀太短（比如 `1`）会匹配到几乎全部车主，
 *   后端**直接拒绝**并返回一句 `code:0`：
 *   「该手机号前缀匹配到的车主过多，请输入更长的前缀」。
 *
 *   所以搜索框必须**等用户输入到 6 位才发请求** ——
 *   否则用户每敲一个数字都会看到一次红色错误提示，
 *   而那个提示并不是他做错了什么，只是还没输完。
 *
 * ★ 长度按**数字个数**算（去掉空格和连字符），不是字符串长度。
 */
export const PHONE_SEARCH_MIN_LENGTH = 6

/** 手机号是否够长、可以发搜索请求了。 */
export function canSearchByPhone(phone: string | null | undefined): boolean {
  if (!phone) return false
  const digits = phone.replace(/\D/g, '')
  return digits.length >= PHONE_SEARCH_MIN_LENGTH
}

/**
 * 分页每页条数。
 *
 * ★ 用 10 而不是 20/50：管理端的表格行高较大（要放状态下拉、操作按钮），
 *   一屏放不下 20 行，多出来的行会让「加载中」的时间变长而没有收益。
 *   H5 的列表用 10 也正好是一屏半，滚动加载的手感最自然。
 */
export const DEFAULT_PAGE_SIZE_UI = 10

/**
 * 工作台客户端聚合的**上限**。
 *
 * ★★ 后端**没有任何统计接口** —— 营收、电量这些数字只能由前端
 *   把订单翻出来自己加。这里定的上限是「最多翻 5 页 × 100 条 = 500 单」。
 *
 *   为什么必须有上限：不加就是「有多少单拉多少单」，
 *   订单表一大，工作台就成了整个系统最慢的页面。
 *
 * ★ 因此工作台上的营收数字是**有偏样本**（近 7 天的前 500 单），
 *   UI 上必须常驻这个口径说明。**不要**把它做成看起来精确的假数字 ——
 *   那比不做更糟，因为它会被人当真去对账。
 */
export const DASHBOARD_SAMPLE_LIMIT = 500

/** 工作台并发请求上限。20 个请求全并发会让后端连接池瞬间打满。 */
export const DASHBOARD_CONCURRENCY = 4
