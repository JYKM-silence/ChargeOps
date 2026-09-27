/**
 * 管理端「车主」域：车主档案、车主权益（会员套餐）、以及**唯一的**那个钱包接口。
 *
 * ★★ 为什么这三块在同一个文件里，而订单不在这里 ★★
 *   它们的入口都是「先选中一个车主」：`/api/admin/member/{id}`、
 *   `/api/admin/member-package?memberId=`、`/api/admin/wallet/{memberId}/adjust`。
 *   权益和调账在**前端**也共用一个文件（`api/member.ts` / `api/wallet.ts`），
 *   而那两处的理由是一样的：运营打开这个页面的十次里有九次是在回答
 *   「这个人为什么不能用了」—— 档案、权益、余额是同一个问题的三个侧面。
 *   订单不在其中，因为它有独立的列表页和独立的状态机（见 `admin-order.ts`）。
 *
 * ★★ 这个文件里**没有**任何一个接口能读到车主的余额 ★★
 *   `MemberVO` 上没有 `balance`，全项目只有 `POST /wallet/{id}/adjust`
 *   会**在响应里**带回调账后的 `WalletVO`。
 *   所以 mock 也**不许**在车主详情里塞一个余额字段 ——
 *   前端页面上一旦有得读，就会有人去渲染它，而真后端会返回 undefined，
 *   表现为「余额永远是空白」。宁可这里少一个字段。
 *
 * ★ 三块的鉴权都是 `guardStaff`（`/api/admin/**` 全在拦截器后面）。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import type {
  MemberPackageVoidVO,
  MemberStatusDTO,
  MemberVO,
  WalletAdjustDTO,
  WalletVO,
} from '../../types/admin'
import type { PageResult } from '../../http/types'
import type { MockContext } from './core'
import {
  checkPageParams,
  envelope,
  fail,
  guardStaff,
  missingRequired,
  numParam,
  ok,
  okVoid,
  paginate,
  pageParams,
  strParam,
  toNumber,
} from './core'
import {
  memberPackageAlreadyUsed,
  memberPackageLockedByOrder,
  memberPackageStatusChanged,
  memberPackageStatusConflict,
  TEXT,
} from './texts'
import { sortMemberPackages, toMemberPackageVo, toMemberVo, toWalletVo } from './vo'
import type { DbMemberPackage } from '../db'
import { MEMBER_PACKAGE_STATUS } from '../../constants/dicts'

/**
 * 读路径参数里的数字。
 *
 * ★ 解析不出来时返回 `null`（而不是 0）：路径里的 id 一旦不是数字，
 *   后端抛的是 `MethodArgumentTypeMismatchException`，它**没有**对应的 handler，
 *   落到兜底分支变成「未知错误」—— 于是 mock 也返回同一句，而不是
 *   「车主不存在」（后者会让人以为是数据问题）。
 */
function pathId(raw: string | undefined): number | null {
  if (raw === undefined) return null
  const n = Number(raw)
  return Number.isFinite(n) && Number.isInteger(n) ? n : null
}

export function adminMemberHandlers(ctx: MockContext): RequestHandler[] {
  return [
    // ══════════════════════════════════════════════════════════════════════
    // 车主
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 车主分页。
     *
     * ★ 三个筛选条件的匹配方式**各不相同**，这不是随意的：
     *   · `phone` 是**前缀**（`LIKE 'x%'`）——  手机号是定长的，
     *     前缀匹配能用上索引；`%x%` 会全表扫，而车主表是全项目最大的表。
     *   · `nickname` 是**模糊**（`LIKE '%x%'`）—— 昵称是用户随手起的，
     *     要求从头匹配等于让搜索框变成摆设。
     *   · `status` 是**等值**。
     *   抄错任何一个（比如把 phone 写成模糊）在 mock 里看不出问题，
     *   但真后端上会从「毫秒」变成「几秒」，而演示时数据量小，永远发现不了。
     *
     * ★★ `phone` **没有**「命中过多」那道闸 ★★
     *   订单列表（`admin-order.ts`）有，这里**没有** —— 别顺手加。
     *   两组 SQL 放在一起对比就看得出来：`OrderMapper.pageByAdmin` 先按前缀
     *   count 一次，超 500 直接抛；`MemberMapper.page` 只是分页查。
     *   所以在管理端搜「13」得到的是**第一页车主**，而不是一句报错。
     */
    http.get('/api/admin/member/page', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const url = new URL(request.url)
        const badPage = checkPageParams(url.searchParams)
        if (badPage) return badPage

        const phone = strParam(url.searchParams, 'phone')
        const nickname = strParam(url.searchParams, 'nickname')
        const status = numParam(url.searchParams, 'status')

        const rows = ctx.db.members
          .filter((m) => (phone ? m.phone.startsWith(phone) : true))
          .filter((m) => (nickname ? (m.nickname ?? '').includes(nickname) : true))
          .filter((m) => (status !== undefined ? m.status === status : true))
          // ★ `id DESC`：按注册时间倒序。新注册的车主排在最前面 ——
          //   运营打开列表十次有九次是来处理「刚注册的那个人」。
          .sort((a, b) => b.id - a.id)

        const { pageNum, pageSize } = pageParams(url.searchParams)
        const page: PageResult<MemberVO> = paginate(rows.map(toMemberVo), pageNum, pageSize)
        return ok(page)
      }),
    ),

    /**
     * 车主详情。
     *
     * ★ 找不到时报「车主不存在」而不是返回 null：这个端点的主要失败场景是
     *   「列表里点进去、期间被删了」，用户需要知道是数据没了，
     *   而不是看到一个空白页。
     */
    http.get('/api/admin/member/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        const member = ctx.db.memberById(id)
        if (!member) return fail(TEXT.MEMBER_NOT_FOUND)
        return ok(toMemberVo(member))
      }),
    ),

    /**
     * 启用 / 禁用车主。
     *
     * ★★ 顺序照抄：**先校验取值，再查存在** ★★
     *   反过来的话，`PUT /api/admin/member/999999/status` 传一个非法 status
     *   会先报「车主不存在」—— 而用户真正改错的是那个取值。
     *   后端两处都有这句校验（DTO 的 `@Min/@Max` 与 Service 的显式判断），
     *   且**文案完全相同**，所以这里只写一次就够。
     *
     * ★★ 这个接口**不会**让车主的 token 失效 ★★
     *   token 是自包含的、服务端不查库，member token 还有 7 天有效期。
     *   所以禁用之后旧 token 最长还能用 7 天，只有 10 个资金端点会在
     *   Service 层重查状态（`assertActive`）。mock 必须原样保留这个缺口，
     *   否则「禁用 → 立刻掉线」这个**不成立**的假设会在演示里被验证通过。
     *   ★ 注意：`lock` 场景种子里把演示车主设成 `status = 0`，
     *     正是为了让这条链路（200 + code:0 + 那句「账号被禁用」）能真的走到。
     */
    http.put('/api/admin/member/:id/status', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const body = (await request.json().catch(() => ({}))) as MemberStatusDTO
        const missing = missingRequired([[body.status, '状态不能为空']])
        if (missing) return missing
        // ★ `@Min/@Max` 这一类 core 层的 `missingRequired` 刻意不覆盖（见它的注释），
        //   这里单独写：它是**值域**校验，而且后端在两处都写了它、文案一样。
        if (body.status !== 0 && body.status !== 1) return fail('状态只能是 0禁用、1正常')

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        const member = ctx.db.memberById(id)
        if (!member) return fail(TEXT.MEMBER_NOT_FOUND)

        member.status = body.status
        member.updateTime = ctx.db.now()
        return okVoid()
      }),
    ),

    // ══════════════════════════════════════════════════════════════════════
    // 车主权益
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 某车主名下的**全部**权益（**裸数组**）。
     *
     * ★★ 不加 `status` 过滤，也不加「只返回生效中」这类好心 ★★
     *   运营查这个车主，十次有九次就是为了回答「这份权益为什么不能用」——
     *   把已用尽 / 已过期 / 已作废的滤掉，等于把答案藏起来，
     *   页面上就只剩一个空列表和一句「该车主没有权益」。
     *
     * ★★ 缺 `memberId` 参数时后端返回的是「未知错误」★★
     *   `@RequestParam Long memberId` 是必填的，缺参数抛的
     *   `MissingServletRequestParameterException` **没有**对应的 handler，
     *   于是落到兜底分支。所以这里刻意**不**给出「车主 id 不能为空」这种
     *   贴心话 —— 那句话是 Service 里的，而这辈子都走不到（Spring 在更外层就拦了）。
     *   演示时如果非要看那句友好的话，那是**加了功能**，不是修了 bug。
     *
     * ★ 这里没有 `assertActive`：`listByMember` 是管理端的方法，
     *   查的是**车主**的状态而请求来自**运营**，两回事。别顺手补一个。
     */
    http.get('/api/admin/member-package', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const memberId = numParam(new URL(request.url).searchParams, 'memberId')
        if (memberId === undefined) return fail(TEXT.UNKNOWN_ERROR)

        // ★★ 排序**不是** id 倒序 ★★
        //   `db.packagesOf` 是按 id 倒序给的（它服务的是「最新买的那份」这类调用），
        //   而这条接口走的是 `MemberPackageMapper.listByMember` ——
        //   `ORDER BY status, expire_time, id`：生效中的在前、同级先到期的在前。
        //   两者是不同的顺序，所以这里必须显式换成 `sortMemberPackages`。
        //   ★ 车主端的「我的权益」用的是**同一条 SQL**，两边共用一个函数，
        //     否则同一个人在两个端上看到的权益顺序会不一样。
        return ok(sortMemberPackages(ctx.db.packagesOf(memberId)).map(toMemberPackageVo))
      }),
    ),

    /**
     * 作废一份权益并**全额退款**。
     *
     * ★★ 七步的**顺序**是这份 handler 的全部价值 ★★
     *   后端把它们写成一个方法就是有意的：①②③④⑤ 是「给出一句能解释的话」，
     *   ⑥ 才是「真正决定要不要退钱」的那一步，⑦ 在最后。
     *   任何一步提到 ⑥ 前面或后面，都会产生一个具体的、可复现的错误：
     *   · ② 提到 ① 前 → 查不到的行报「状态不允许」；
     *   · ④（金额）提到 ⑥ 后 → 钱退了一半才发现算不出退多少；
     *   · ⑤（用过）提到 ⑥ 后 → 两次并发作废各退一次，流水完全正常。
     *
     * ★★ 退款金额**不从请求里读** ★★
     *   它等于权益上的 `payAmount` 快照，`null` 时**拒绝**（而不是当 0）：
     *   null 是「不知道」（这行建于 V3 之前），0 是「这是赠送的」。
     *   把前者当 0 处理，就是**白送出去一份权益还不退钱**。
     *
     * ★ ⑥ 的 CAS 与 ③ 的检查**不能合并**：③ 负责给出带订单 id 的那句话，
     *   ⑥ 负责在并发下真的挡住。删了 ③，并发时用户会看到「状态不允许」——
     *   而他的权益明明还是生效中，那句话是错的。
     */
    http.post('/api/admin/member-package/:id/void', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const body = (await request.json().catch(() => ({}))) as { remark?: string }
        // ★ 空备注是**DTO 拦截**的（`@NotBlank`，文案是「作废原因不能为空」），
        //   不是 Service 那句。两句话不一样，以先跑的那句为准。
        const missing = missingRequired([[body.remark, '作废原因不能为空']])
        if (missing) return missing

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        const at = ctx.db.now()

        // ① 存在
        const mp = ctx.db.memberPackages.find((p) => p.id === id)
        if (!mp) return fail(TEXT.MEMBER_PACKAGE_NOT_FOUND)

        // ② 状态必须是「1 生效中」——用「当前是什么」组织这句话，
        //    因为已用尽 / 已过期 / 已作废的下一步动作完全不同。
        if (mp.status !== 1) return fail(memberPackageStatusConflict(MEMBER_PACKAGE_STATUS.label(mp.status)))

        // ③ 没被订单占着（带订单 id，运营可以据此直接去查那笔单）
        if (mp.lockedOrderId !== null) return fail(memberPackageLockedByOrder(mp.lockedOrderId))

        // ④ 金额快照必须存在，且必须在扣钱之前
        if (mp.payAmount === null) return fail(TEXT.MEMBER_PACKAGE_PAY_AMOUNT_MISSING)

        // ⑤ 完全没用过。★ `status <> 8`（取消不算用过）—— 一笔 1→8 的订单
        //    从头到尾没充过电，把它算成「用过」是不对的。
        const usedByOrders = ctx.db.orders.filter(
          (o) => o.memberPackageId === id && o.status !== 8,
        ).length
        if (usedByOrders > 0) return fail(memberPackageAlreadyUsed(usedByOrders))

        // ⑥ CAS：权威判据，且**必须排在退钱之前**。
        //    这条流水的 `order_id` 是 NULL，`uk_txn_order_type` 不约束 NULL ——
        //    数据库层没有第二道防线，这一行就是全部。
        //    ★ mock 是单线程的，这一行永远为真；写出它不是为了「并发」，
        //      而是为了让这段代码与后端**长得一样** —— 将来谁把 mock 改成
        //      多线程/异步（比如加 SharedArrayBuffer）时，缺了这一行就是真退两次钱。
        const cas = ctx.db.memberPackages.find(
          (p) => p.id === id && p.status === 1 && p.lockedOrderId === null,
        )
        if (!cas) return fail(memberPackageStatusChanged())

        cas.status = 4
        cas.remark = body.remark as string
        cas.updateTime = at

        // ⑦ 退钱。★ 金额为 0 **不写流水** —— 赠送套餐没有钱可退，
        //   一条 0 元流水只会给对账添噪音（`refundPackage` 自己也会拒绝 0，
        //   所以这个判断不是多余的防御，是必需的）。
        if (mp.payAmount > 0) refundPackage(ctx, mp, mp.payAmount, body.remark as string, at)

        const vo: MemberPackageVoidVO = {
          memberPackageId: mp.id,
          memberId: mp.memberId,
          packageName: mp.packageName,
          refundAmount: mp.payAmount / 100,
        }
        return ok(vo)
      }),
    ),

    // ══════════════════════════════════════════════════════════════════════
    // 钱包（管理端唯一的一个）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 管理员调账：加钱或扣钱（金额**自带符号**）。
     *
     * ★★ 三段校验里只有第一段是 DTO 的，但**最后能到达用户的是 DTO 那两段** ★★
     *   `WalletAdjustDTO` 上 `remark` 的 `@NotBlank` 文案是「调账原因不能为空」，
     *   而 Service 里那句是「调账必须填写原因」—— 两个都在源码里，
     *   但 HTTP 请求只可能撞到前面那个（`@Valid` 在 Controller 上，先跑）。
     *   抄 Service 那句等于发了一句**用户永远不会从真后端收到**的话。
     *
     * ★ `@DecimalMin/@DecimalMax`（±100000）本该按 core 层的政策跳过，
     *   这里**故意破例**：它是这条路径上**唯一**挡着百万级误操作的东西，
     *   Service 里没有对应的复查。跳过它，mock 就会让一笔真后端会拒绝的
     *   调账「成功」，而那是**钱**。
     *
     * ★★ `0` 到不了 DTO 就过不了 Service —— 三层各挡一件事 ★★
     *   0 落在 ±100000 区间内，DTO 放它过去，Service 再用一句
     *   「调账金额不能为 0」拦下。这句话之所以值得单列，是因为
     *   0 元流水什么都不改变、却会污染对账（前端也有一道 `isAdjustableAmount`）。
     *
     * ★ 这条路径**没有幂等键**：同一个 payload 提交两次就是两条流水，
     *   mock 同样如此（不去重）—— 那件事由前端 `withActionLock` 负责。
     */
    http.post('/api/admin/wallet/:memberId/adjust', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const body = (await request.json().catch(() => ({}))) as WalletAdjustDTO
        const amount = toNumber(body.amount)
        const remark = typeof body.remark === 'string' ? body.remark : ''

        // ★ 这一层**不**复用 `missingRequired`：它只管「空不空」，
        //   而这个 DTO 上混了三种问题（缺值 / 越界 / 空白），且两种字段的
        //   错误要按**字段声明顺序**拼成一句话（Spring 的
        //   `Collectors.joining("；")`）。分开返回会让「同时说错两件事」
        //   只报出第一件，管理员改一次被拒一次。
        const problems: string[] = []
        if (body.amount === null || body.amount === undefined || body.amount === '') {
          problems.push('调账金额不能为空')
        } else if (amount !== null && (amount < -100000 || amount > 100000)) {
          problems.push(amount < -100000 ? '调账金额超出下限，请确认是否填错' : '调账金额超出上限，请确认是否填错')
        }
        if (remark.trim() === '') problems.push('调账原因不能为空')
        if (problems.length > 0) return fail(problems.join('；'))

        const memberId = pathId(params.memberId as string | undefined)
        if (memberId === null) return fail(TEXT.UNKNOWN_ERROR)

        // ★ 两次「业务拒绝」由 `db.adjust` 负责（它逐条复刻了
        //   `WalletServiceImpl.adjust` 的三道校验与顺序），
        //   这里只把它翻成信封。返回的是**调账之后**的钱包 ——
        //   那是运营唯一能对着念一遍、确认这次操作生效了的数字。
        const result = ctx.db.adjust(memberId, body.amount, remark.trim())
        if (!result.ok) return fail(result.msg)

        const vo: WalletVO = toWalletVo(ctx.db.walletOf(memberId))
        return ok(vo)
      }),
    ),
  ]
}

/**
 * ⑦ 的退款动作 —— 加钱 + 写一条 `8 套餐退款` 流水。
 *
 * ★ 为什么不用 `db.adjust`（它也会加钱、也会写流水）：流水**类型不同**。
 *   调账是 `7`，套餐退款是 `8`，而对账的人正是靠这个 type 区分
 *   「运营手工改的账」和「系统按规则退的钱」。用 `adjust` 会安静地
 *   把两类混成一条 —— 半年后没人能回答「这笔钱是谁决定的」。
 *
 * ★ `remark` 由 handler 拼，不由调用方传：只有这一层同时知道
 *   「作废的是哪一份权益」和「运营写的原因为何」。这条流水没有 `order_id`，
 *   这一行文字是它唯一的解释。
 *   ★ 前缀 `作废套餐权益退款：` 是**逐字**抄的 `MemberPackageServiceImpl` ——
 *     `member_package.remark` 是 `varchar(255)`，DTO 把原因限到 200 字
 *     就是为了给这个前缀留位置。
 */
function refundPackage(
  ctx: MockContext,
  mp: DbMemberPackage,
  amount: number,
  remark: string,
  at: Date,
): void {
  const wallet = ctx.db.walletOf(mp.memberId)
  wallet.balance += amount
  wallet.updateTime = at
  ctx.db.recordTxn({
    memberId: mp.memberId,
    // ★ `order_id` 为 NULL —— 套餐是**直购**，没有购买单，
    //   所以 `uk_txn_order_type` 这条唯一索引管不到它（NULL 不参与唯一性），
    //   数据库层拦不住第二次作废。见 `api/member.ts` 里那段警告。
    orderId: null,
    type: 8,
    amount,
    remark: `作废套餐权益退款：${mp.packageName}（${remark}）`,
    at,
  })
}
