/**
 * 车主端「浏览」：选站 → 选枪。
 *
 * ★★ 这两个端点补的是一个具体的缺口 ★★
 *   `POST /api/member/orders` 要 `stationId` / `chargerId` / `connectorId` 三个 id，
 *   而在它们出现之前，车主端**没有任何接口能让人拿到其中任何一个** ——
 *   只能扫二维码。没有二维码的场景（运维在电话里说「你去 A 站用 3 号枪」、
 *   先在手机上看看哪把枪空着）完全走不通。
 *
 * ★★ 两个都是「全平台共享数据」，所以都不查账号状态 ★★
 *   路径在 `/api/member/**` 下、必须带 token，这是**路径约定**，不是业务语义：
 *   换一个车主登录看到的是同一批站、同一批枪。所以和钱包/权益不同，
 *   这里没有 `assertActive` —— 判据是「这个动作有没有资金后果」，
 *   而看站点不产生任何后果（被禁用的车主看得见站、但下不了单）。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import type { PageResult } from '../../http/types'
import type { MemberConnectorVO, MemberStationVO } from '../../types/member'
import { checkPageParams, envelope, fail, guardMember, ok, paginate, pageParams, strParam } from './core'
import type { MockContext } from './core'
import { TEXT } from './texts'
import { toMemberConnectorVo, toMemberStationVo } from './vo'

export function memberBrowseHandlers(ctx: MockContext): RequestHandler[] {
  return [
    /**
     * 分页查询充电站 —— 车主端**唯一**能看到站点的入口。
     *
     * ★★ `status = 1` 写死在 SQL 的 `WHERE` 里，不在 handler 里 ★★
     *   维护中（2）和停用（0）的站点**不返回** —— 对车主来说「维护中」就是
     *   「现在别来」，展示出来只会让人白跑一趟。所以 `MemberStationVO` 里
     *   **没有** `status` 字段，前端也不必再过滤一遍。
     *   把它写成「返回全部、由前端灰掉」看起来更「灵活」，但那是在要求每个调用点
     *   都记得过滤 —— 漏一个就是一个「点了导航过去发现关门」的演示。
     *
     * ★ `pageSize` 的 100 上限是这条路径上唯一挡住 `?pageSize=1000000` 的东西
     *   （DTO 上的 `@Max(100)`），所以 `checkPageParams` 不能省。
     * ★ 名称**模糊**、城市**精确**（`city` 走 `idx_station_city_status` 索引，
     *   用 `LIKE` 会让那个索引失效）。两者都由 `cleanParams` 决定「不传就不筛」。
     */
    http.get('/api/member/station/page', ({ request }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const url = new URL(request.url)
        const badPage = checkPageParams(url.searchParams)
        if (badPage) return badPage

        const name = strParam(url.searchParams, 'name')
        const city = strParam(url.searchParams, 'city')

        const rows = ctx.db.stations
          .filter((s) => s.status === 1)
          .filter((s) => (name === undefined ? true : s.name.includes(name)))
          .filter((s) => (city === undefined ? true : s.city === city))
          // ★ `ORDER BY id DESC` —— 新建的站在最前。
          .sort((a, b) => b.id - a.id)

        const { pageNum, pageSize } = pageParams(url.searchParams)
        const page: PageResult<MemberStationVO> = paginate(
          rows.map((s) => toMemberStationVo(ctx.db, s)),
          pageNum,
          pageSize,
        )
        return ok(page)
      }),
    ),

    /**
     * 某个站点下的**全部**充电枪 —— 「选枪」这一步。
     *
     * ★★ 不过滤枪的状态 ★★ 离线 / 故障 / 维护（0/4/5）的枪也会返回。
     *   筛掉它们会让一个正在维护的站显示成**空列表** —— 车主看到的是
     *   「这个站一把枪都没有」，而事实是「这个站有 8 把枪，现在都不能用」。
     *   两句完全不同的话，而后者才是能让人做出决定的那句。
     *   状态原样返回（`status` + `statusLabel` + `available`），由前端灰掉。
     *   ★ 注意 `available` 是**快照不是预订**：它为 true 之后建单仍可能失败
     *     （那把枪刚好被别人抢了），那是正常的并发竞争，不是这个字段算错了。
     *
     * ★★ 站点不存在时返回**空列表**，不是 404 ★★
     *   判据是「这个接口回答的是什么问题」：它回答「这个站有哪些枪」，
     *   而「站点不存在」「站点还没有枪」「枪都被删光了」对车主是同一件事 ——
     *   现在没有一把能用的枪。要做「这个 id 存不存在」的判断，那是站点详情的事。
     *
     * ★ `ORDER BY id`（按枪 id，**不是**按枪号）：`gun_no` 是字符串，
     *   按它排出来是 `1, 10, 2`。后端的注释里写明了「重排的职责在前端」，
     *   所以这里**不**替前端做那件事 —— 这是刻意和真实顺序保持一致，
     *   而不是「忘了排」。
     */
    http.get('/api/member/connector/by-station/:stationId', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const raw = params.stationId as string | undefined
        const stationId = raw === undefined ? NaN : Number(raw)
        // ★ 非数字 → 「未知错误」（参数绑定失败落到 catch-all），和路径 id 的规则一致。
        if (!Number.isFinite(stationId) || !Number.isInteger(stationId)) {
          return fail(TEXT.UNKNOWN_ERROR)
        }

        const rows = ctx.db.connectors
          .filter((c) => c.stationId === stationId)
          .sort((a, b) => a.id - b.id)
        const list: MemberConnectorVO[] = rows.map((c) => toMemberConnectorVo(ctx.db, c))
        return ok(list)
      }),
    ),
  ]
}
