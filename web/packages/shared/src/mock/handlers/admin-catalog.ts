/**
 * 商品目录：**套餐分类** + **充电套餐**（管理端）。
 *
 * ★★ 为什么这两个域合在一起 ★★
 *   它们之间有一条**单向的类型约束**，而那条约束是全项目最容易漏掉的模式：
 *   「充电套餐只能挂在 `type = 1 充电套餐分类` 下」。
 *   这条校验在 `create` 里跑一遍是不够的 —— `categoryId` 在
 *   `ChargePackageUpdateDTO` 里是**可改字段**，所以「先建一个合法套餐、
 *   再把它改到一个 `type = 2` 的分类下」是一条完全绕过校验的路径。
 *   两条写路径放在同一个文件里，「校验必须覆盖每一条能到达那个字段的路」
 *   这件事才是看得见的。
 *
 * ★ 另一个理由：分类的删除守卫就是「它下面还有没有套餐」——
 *   两个域共用一次 `count`，拆开会让那句「该分类下还有 N 个充电套餐」
 *   和它数的东西分居两个文件。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import type {
  CategoryDTO,
  CategoryUpdateDTO,
  ChargePackageDTO,
  ChargePackageUpdateDTO,
} from '../../types/admin'
import {
  checkPageParams,
  envelope,
  fail,
  guardStaff,
  missingRequired,
  numParam,
  ok,
  okVoid,
  pageParams,
  paginate,
  strParam,
  toNumber,
} from './core'
import type { MockContext } from './core'
import {
  alreadyExists,
  categoryHasPackage,
  categoryTypeMismatch,
  packageFieldLeftover,
  packageFieldRequired,
  TEXT,
} from './texts'
import { toCategoryVo, toPackageVo } from './vo'
import type { DbCategory, DbPackage } from '../db'
import { toCents } from '../db'
import { CATEGORY_TYPE, PACKAGE_TYPE } from '../../constants/dicts'

/**
 * 套餐类型的码值。
 *
 * ★ 与 `admin-station.ts` 里那组字面量同样的理由：`createDict` 出来的字典
 *   **没有**按名字取码值的成员，所以 `PACKAGE_TYPE.ENERGY` 在类型上不存在。
 *   中文标签注在旁边，读的时候一眼能对上字典。
 */
const PKG_ENERGY = 1 // PACKAGE_TYPE「电量包」
const PKG_DURATION = 2 // 「时长包」
const PKG_SERVICE_DISCOUNT = 3 // 「服务费折扣包」
const PKG_TIMES = 4 // 「次卡」
const CATEGORY_CHARGE_PACKAGE = 1 // CATEGORY_TYPE「充电套餐分类」

/** 建套餐时四个默认值：`ChargePackageDTO` 的**字段初始值**，不是 DDL 默认。 */
const DEFAULT_STATION_ID = 0 // 0 = 全平台通用（SQL 里的 `station_id IN (0, ?)`）
const DEFAULT_VALID_DAYS = 0 // 0 = 不过期
const DEFAULT_STATUS = 1 // 上架
const DEFAULT_SORT = 0

/**
 * 每种套餐类型**要求**填哪个字段 —— 后端那个 `switch (type)` 的等价物。
 *
 * ★ 写成一张表而不是一串三元表达式：后端注释明写了这张表的用途是
 *   「**新增一个套餐类型只需要改一处**」。四组 `if` 的话，加类型时要记得改四处，
 *   漏改的表现是「新类型能建但没有任何字段被要求」—— 一个没有任何报错的漏洞。
 *
 * ★ 值的顺序要与 `assertTypeFields` 里的 `fields` **一致**，因为报错位置
 *   会出现在「方向二」的残留字段列表里。
 */
const REQUIRED_FIELD_OF: Record<number, string> = {
  [PKG_ENERGY]: 'energyKwh',
  [PKG_DURATION]: 'durationMinutes',
  [PKG_SERVICE_DISCOUNT]: 'serviceDiscount',
  [PKG_TIMES]: 'times',
}

// ══════════════════════════════════════════════════════════════════════════
// 分类
// ══════════════════════════════════════════════════════════════════════════

/**
 * 分类类型的合法性 —— 复刻 `CategoryType.of`。
 *
 * ★★ 为什么在 Service 里再判一遍（DTO 上已经有 `@Min(1) @Max(2)`）★★
 *   `@Max(2)` 是一个**复制的约束**：枚举里将来加第三个值时它不会跟着变，
 *   表现是「新类型在 DTO 层就被 400 打回」。`CategoryType.of()` 读的是枚举本身，
 *   所以「合法值有哪些」只有一个定义处。
 *
 * ★ 两条消息**分开**（空 / 未知）而不是合成一句：一个是「表单没提交这个字段」，
 *   一个是「提交了一个不存在的值」，排查方向完全不同。
 */
function categoryTypeError(type: number | null | undefined): string | null {
  if (type === null || type === undefined) return '分类类型为空'
  return CATEGORY_TYPE.byCode(type) ? null : `未知的分类类型：${type}`
}

/** 套餐类型的合法性 —— 复刻 `PackageType.of`，与上面同形。 */
function packageTypeError(type: number | null | undefined): string | null {
  if (type === null || type === undefined) return '套餐类型为空'
  return PACKAGE_TYPE.byCode(type) ? null : `未知的套餐类型：${type}`
}

export function categoryHandlers(ctx: MockContext): RequestHandler[] {
  return [
    http.get('/api/admin/category/page', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const query = new URL(request.url).searchParams
        const bad = checkPageParams(query)
        if (bad) return bad

        const name = strParam(query, 'name')
        const type = numParam(query, 'type')
        const status = numParam(query, 'status')

        const rows = ctx.db.categories.filter((c) => {
          if (name && !c.name.includes(name)) return false
          if (type !== undefined && c.type !== type) return false
          if (status !== undefined && c.status !== status) return false
          return true
        })
        // ★ 分页列表按 `id DESC`（新建的在最上面），而 `listEnabled` 按 `sort` ——
        //   两个接口的排序**不同**，因为它们的用途不同：
        //   一个是「我最近建了什么」，一个是「下拉框里的顺序」。
        rows.sort((a, b) => b.id - a.id)

        const { pageNum, pageSize } = pageParams(query)
        const page = paginate(rows, pageNum, pageSize)
        return ok({ total: page.total, records: page.records.map(toCategoryVo) })
      }),
    ),

    /**
     * 分类下拉用的**裸数组**接口。
     *
     * ★★ 路由注册顺序：这条在 `/:id` **之前** ★★
     *   MSW 按注册顺序匹配，`/api/admin/category/list` 也会命中
     *   `/api/admin/category/:id` 那条（`:id` = `list`），
     *   于是下拉框会拿到一个 `Number('list')` = `NaN` 的查询结果 ——
     *   报出来的是「分类不存在」，看起来和路径毫无关系。
     */
    http.get('/api/admin/category/list', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const type = numParam(new URL(request.url).searchParams, 'type')
        const rows = ctx.db.categories.filter((c) => {
          // ★ 只返回**启用**的：这是下拉框的数据源，把停用的分类也列出来
          //   会让运营把一个新套餐挂到一个已经下线的分类下。
          if (c.status !== 1) return false
          if (type !== undefined && c.type !== type) return false
          return true
        })
        rows.sort((a, b) => (a.sort !== b.sort ? a.sort - b.sort : a.id - b.id))
        return ok(rows.map(toCategoryVo))
      }),
    ),

    http.get('/api/admin/category/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const category = ctx.db.categories.find((c) => c.id === Number(params.id))
        if (!category) return fail(TEXT.CATEGORY_NOT_FOUND)
        return ok(toCategoryVo(category))
      }),
    ),

    http.post('/api/admin/category', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const dto = (await request.json().catch(() => ({}))) as CategoryDTO
        const missing = missingRequired([
          [dto.name, '分类名称不能为空'],
          [dto.type, '分类类型不能为空'],
        ])
        if (missing) return missing

        // ★ 顺序：**先校验 type 的合法性，再落到实体上** —— 抄自后端。
        const typeError = categoryTypeError(dto.type)
        if (typeError) return fail(typeError)

        // ★ `uk_category_type_name (type, name)` 是**复合**唯一索引：
        //   同一个 type 下不能重名，但「充电套餐分类」和「增值服务分类」
        //   可以各有一个「月卡」。判重时只比 name 会把后一种情况误判成冲突。
        if (ctx.db.categories.some((c) => c.type === dto.type && c.name === dto.name)) {
          return fail(alreadyExists(dto.name))
        }

        const now = ctx.db.now()
        // ★ `sort` / `status` 的三个 `??` 对应后端那三个 `if (x == null)`：
        //   字段初始值只挡「JSON 里没有这个键」，`{"status": null}` 是**显式 null**，
        //   Jackson 会照常调 setter 覆盖掉初始值 —— 而 insert 是全列插入，
        //   NOT NULL 列拿到 null 就是 errno 1048。
        const row: DbCategory = {
          id: ctx.db.nextId('category'),
          name: dto.name,
          type: dto.type,
          sort: dto.sort ?? 0,
          status: dto.status ?? 1,
          // ★ `DbCategory` 上**没有** `updateTime` —— 后端的 `category` 表
          //   只有 `create_time`。这不是漏了，见 `db.ts` 的接口定义。
          createTime: now,
        }
        ctx.db.categories.push(row)
        return ok(row.id)
      }),
    ),

    http.put('/api/admin/category/:id', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        const category = ctx.db.categories.find((c) => c.id === id)
        if (!category) return fail(TEXT.CATEGORY_NOT_FOUND)

        // ★ `CategoryUpdateDTO` 里**没有** `type` —— 分类的归属类型建立后不可改
        //   （改它会让挂在这个分类下的一堆套餐瞬间变成非法数据）。
        //   这是编译期事实，不是「记得排除」，所以这里根本不读 dto.type。
        const dto = (await request.json().catch(() => ({}))) as CategoryUpdateDTO
        if (dto.name !== undefined) {
          const clash = ctx.db.categories.some(
            (c) => c.type === category.type && c.name === dto.name && c.id !== id,
          )
          if (clash) return fail(alreadyExists(dto.name))
          category.name = dto.name
        }
        if (dto.sort !== undefined) category.sort = dto.sort
        if (dto.status !== undefined) category.status = dto.status
        return okVoid()
      }),
    ),

    http.delete('/api/admin/category/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        const category = ctx.db.categories.find((c) => c.id === id)
        if (!category) return fail(TEXT.CATEGORY_NOT_FOUND)

        // ★★ 守卫式**硬删**，而且前端**无法预判** ★★
        //   后端没有「该分类下的套餐数」这样的接口，`CategoryVO` 上也刻意没有
        //   这个字段 —— 所以删除按钮必然要允许失败，由调用方把
        //   「该分类下还有 N 个充电套餐」翻成一条**指向套餐列表的出路**。
        //   mock 里也不提供「预检」，否则前端会开始依赖一个真后端没有的能力。
        const count = ctx.db.packages.filter((p) => p.categoryId === id).length
        if (count > 0) return fail(categoryHasPackage(count))

        ctx.db.categories.splice(ctx.db.categories.findIndex((c) => c.id === id), 1)
        return okVoid()
      }),
    ),
  ]
}

// ══════════════════════════════════════════════════════════════════════════
// 充电套餐
// ══════════════════════════════════════════════════════════════════════════

/**
 * 类型专属字段校验 —— 复刻 `ChargePackageServiceImpl.assertTypeFields`。
 *
 * ★★ 两个方向都要查，而**第二个方向才是要紧的那个** ★★
 *   方向一（该有的要有）报出来时用户是知道的：他少填了一个必填项。
 *   方向二（不该有的必须为空）是**静默**的：一个时长包里残留的
 *   `energyKwh = 150` 会被计费逻辑读走，凭空送出 150 度电 ——
 *   没有任何报错，直到财务对账。
 *
 * ★ `leftovers` 的顺序是 `LinkedHashMap` 的插入顺序（energyKwh →
 *   durationMinutes → serviceDiscount → times），**不是** DTO 里的顺序。
 *   保持一致是有意义的：同一份错误数据每次给出的消息应该一字不差，
 *   否则前端按 msg 做分支的逻辑会失配。
 */
function assertTypeFields(pkg: {
  packageType: number | null
  energyKwh: number | null
  durationMinutes: number | null
  serviceDiscount: number | null
  times: number | null
}): string | null {
  const typeError = packageTypeError(pkg.packageType)
  if (typeError) return typeError
  const type = pkg.packageType as number
  const label = PACKAGE_TYPE.label(type)

  const fields: readonly (readonly [string, number | null])[] = [
    ['energyKwh', pkg.energyKwh],
    ['durationMinutes', pkg.durationMinutes],
    ['serviceDiscount', pkg.serviceDiscount],
    ['times', pkg.times],
  ]
  const required = REQUIRED_FIELD_OF[type]

  const requiredValue = fields.find(([name]) => name === required)?.[1]
  if (requiredValue === null || requiredValue === undefined) {
    return packageFieldRequired(label, required)
  }

  const leftovers = fields
    .filter(([name, value]) => name !== required && value !== null && value !== undefined)
    .map(([name]) => name)
  if (leftovers.length > 0) return packageFieldLeftover(label, leftovers)

  return null
}

/**
 * 分类必须存在、且必须是「充电套餐分类」—— 复刻 `requireCategory`。
 *
 * ★★ 这个方法**每一条能写到 `categoryId` 的路径都要调** ★★
 *   create 和 update 各一次。只在 create 里校验的话，
 *   「先建一个合法套餐、再把它改到一个 type=2 的分类下」就是一条绕过路径 ——
 *   而它不需要任何特殊操作，普通的编辑功能就能走到。
 */
function requireChargePackageCategory(ctx: MockContext, categoryId: number | null): string | null {
  if (categoryId === null || categoryId === undefined) return TEXT.CATEGORY_NOT_FOUND
  const category = ctx.db.categories.find((c) => c.id === categoryId)
  if (!category) return TEXT.CATEGORY_NOT_FOUND
  if (category.type !== CATEGORY_CHARGE_PACKAGE) {
    return categoryTypeMismatch(category.name, CATEGORY_TYPE.label(category.type))
  }
  return null
}

export function packageHandlers(ctx: MockContext): RequestHandler[] {
  return [
    http.get('/api/admin/package/page', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const query = new URL(request.url).searchParams
        const bad = checkPageParams(query)
        if (bad) return bad

        const name = strParam(query, 'name')
        const categoryId = numParam(query, 'categoryId')
        const packageType = numParam(query, 'packageType')
        const stationId = numParam(query, 'stationId')
        const status = numParam(query, 'status')

        const rows = ctx.db.packages.filter((p) => {
          if (name && !p.name.includes(name)) return false
          if (categoryId !== undefined && p.categoryId !== categoryId) return false
          if (packageType !== undefined && p.packageType !== packageType) return false
          // ★ `stationId` 是**等值**匹配，不是 SQL 里 `listAvailableForMember` 那个
          //   `IN (0, ?)`。管理端要看的是「这个站点专属的套餐」，
          //   把全平台通用的也混进来会让筛选结果无法解释。
          if (stationId !== undefined && p.stationId !== stationId) return false
          if (status !== undefined && p.status !== status) return false
          return true
        })
        // ★ 排序是 `sort ASC, id DESC` 而不是 `id DESC` —— 唯一一个**两级**排序的
        //   列表。因为套餐列表是按「运营心里的推荐顺序」排的（sort 是手填的），
        //   id 只在 sort 相同时兜底保证确定性（没有它，两条 sort 相同的套餐
        //   在两次请求里可能换位置）。
        rows.sort((a, b) => (a.sort !== b.sort ? a.sort - b.sort : b.id - a.id))

        const { pageNum, pageSize } = pageParams(query)
        const page = paginate(rows, pageNum, pageSize)
        return ok({ total: page.total, records: page.records.map(toPackageVo) })
      }),
    ),

    http.get('/api/admin/package/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const pkg = ctx.db.packages.find((p) => p.id === Number(params.id))
        if (!pkg) return fail(TEXT.PACKAGE_NOT_FOUND)
        return ok(toPackageVo(pkg))
      }),
    ),

    http.post('/api/admin/package', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const dto = (await request.json().catch(() => ({}))) as ChargePackageDTO
        const missing = missingRequired([
          [dto.name, '套餐名称不能为空'],
          [dto.categoryId, '套餐分类不能为空'],
          [dto.packageType, '套餐类型不能为空'],
          [dto.price, '售价不能为空'],
        ])
        if (missing) return missing

        const now = ctx.db.now()
        const row: DbPackage = {
          id: ctx.db.nextId('package'),
          name: dto.name,
          categoryId: dto.categoryId,
          packageType: dto.packageType,
          // ★ `stationId` 的默认值是 **`0`**（全平台通用），不是 `null`。
          //   后端的 SQL 判据是 `station_id IN (0, ?)`，所以 `0` 和 `NULL`
          //   在业务上是两回事 —— 用 `null` 的话这个套餐**一个站点都匹配不到**，
          //   而它看起来完全正常。
          stationId: dto.stationId ?? DEFAULT_STATION_ID,
          price: toCents(dto.price),
          originPrice: dto.originPrice === undefined || dto.originPrice === null
            ? null
            : toCents(dto.originPrice),
          energyKwh: toNumber(dto.energyKwh),
          durationMinutes: toNumber(dto.durationMinutes),
          times: toNumber(dto.times),
          serviceDiscount: toNumber(dto.serviceDiscount),
          // ★ `validDays = 0` 表示**不过期**，不是「当天过期」——
          //   这个坑的代价是一批卖出去的权益在当天全部失效。
          validDays: dto.validDays ?? DEFAULT_VALID_DAYS,
          description: dto.description ?? null,
          image: dto.image ?? null,
          status: dto.status ?? DEFAULT_STATUS,
          sort: dto.sort ?? DEFAULT_SORT,
          createTime: now,
          updateTime: now,
        }

        // ★★ 顺序：先跑**纯内存**的字段校验，再跑**要查库**的分类校验 ★★
        //   两者都错时先报字段那个：它更便宜，而且说的是「你这张表单本身填错了」，
        //   比「你选的那个分类不对」更靠前一步 —— 用户得先填对表单，
        //   选哪个分类才有意义。
        const fieldError = assertTypeFields(row)
        if (fieldError) return fail(fieldError)
        const categoryError = requireChargePackageCategory(ctx, row.categoryId)
        if (categoryError) return fail(categoryError)

        // ★ `uk_pkg_name_station (name, station_id)` 同样是复合唯一索引。
        //   `stationId` 为 `0`（通用）的两条同名也算冲突 —— 因为是 `=` 比较，
        //   不是 SQL 里那种「NULL 不参与唯一约束」的情形。
        if (ctx.db.packages.some((p) => p.name === row.name && p.stationId === row.stationId)) {
          return fail(alreadyExists(row.name))
        }

        ctx.db.packages.push(row)
        return ok(row.id)
      }),
    ),

    http.put('/api/admin/package/:id', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        const row = ctx.db.packages.find((p) => p.id === id)
        if (!row) return fail(TEXT.PACKAGE_NOT_FOUND)

        const dto = (await request.json().catch(() => ({}))) as ChargePackageUpdateDTO
        const now = ctx.db.now()

        // ★★ 校验的对象是**合并后的行**，不是 DTO ★★
        //   只改价格的管理员不会传 `energyKwh`，拿 DTO 去校验会误判成
        //   「电量包缺电量」，把一个完全合法的请求打回。代价是多一次查询。
        //
        // ★ 合并方向：DTO 的**非 null** 值覆盖到从库里读出的行上。
        //   不能用「DTO 里有什么就写什么」的全量覆盖 —— 那会把 `undefined` 和
        //   `null` 混为一谈，而后者在 `ChargePackageUpdateDTO` 里是
        //   **「清空这个字段」**（比如把 `originPrice` 的划线价去掉）。
        if (dto.name !== undefined) row.name = dto.name
        if (dto.categoryId !== undefined) row.categoryId = dto.categoryId
        if (dto.price !== undefined) row.price = toCents(dto.price)
        if (dto.originPrice !== undefined) {
          row.originPrice = dto.originPrice === null ? null : toCents(dto.originPrice)
        }
        if (dto.energyKwh !== undefined) row.energyKwh = toNumber(dto.energyKwh)
        if (dto.durationMinutes !== undefined) row.durationMinutes = toNumber(dto.durationMinutes)
        if (dto.times !== undefined) row.times = toNumber(dto.times)
        if (dto.serviceDiscount !== undefined) row.serviceDiscount = toNumber(dto.serviceDiscount)
        if (dto.validDays !== undefined) row.validDays = dto.validDays
        if (dto.description !== undefined) row.description = dto.description
        if (dto.image !== undefined) row.image = dto.image
        if (dto.status !== undefined) row.status = dto.status
        if (dto.sort !== undefined) row.sort = dto.sort
        row.updateTime = now

        // ★★ 两条校验都必须**再跑一次** ★★
        //   `packageType` 和 `categoryId` 在 UpdateDTO 里分别是不可改和可改：
        //   前者不可改所以类型不会变，但**字段值可能变**成一个非法的组合
        //   （给时长包顺手填上电量）；后者可改，所以「改到一个 type=2 的分类下」
        //   是一条完全绕过 create 校验的路。这是一类很容易漏的模式：
        //   **校验必须覆盖每一条能到达那个字段的写路径**。
        const fieldError = assertTypeFields(row)
        if (fieldError) return fail(fieldError)
        const categoryError = requireChargePackageCategory(ctx, row.categoryId)
        if (categoryError) return fail(categoryError)

        const clash = ctx.db.packages.some(
          (p) => p.name === row.name && p.stationId === row.stationId && p.id !== id,
        )
        if (clash) return fail(alreadyExists(row.name))

        // ★ 用**合并后的整体**去更新：「校验的那一行」和「写进去的那一行」
        //   是同一个对象，两件事不可能不一致。上面直接改 `row` 就是为了这一点。
        return okVoid()
      }),
    ),

    http.delete('/api/admin/package/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        const pkg = ctx.db.packages.find((p) => p.id === id)
        if (!pkg) return fail(TEXT.PACKAGE_NOT_FOUND)

        // ★★ 这里**没有**「已经卖出去过就不能删」的守卫，是**刻意照抄后端**的 ★★
        //   `ChargePackageServiceImpl.delete` 只做 `requirePackage` + `deleteById`。
        //   所以删掉一个已经卖出 100 份的套餐是**允许的** —— 那些已售出的权益行
        //   带着 `packageName` 快照，不会变成空白。
        //   给 mock 加上这个守卫会让前端多处理一条真后端永远不会出现的错误，
        //   而演示时「删掉一个卖过的套餐」会莫名其妙地失败。
        ctx.db.packages.splice(ctx.db.packages.findIndex((p) => p.id === id), 1)
        return okVoid()
      }),
    ),
  ]
}
