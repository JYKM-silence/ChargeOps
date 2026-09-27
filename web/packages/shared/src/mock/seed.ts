/**
 * 内存库的种子数据。
 *
 * ★★ 三条贯穿全文件的规则，改任何一条之前先读这里 ★★
 *
 *   一、**所有时间都相对 `now` 算，不写死日期。**
 *     订单列表的默认筛选是「近 7 天」。写死 `2026-01-01` 的话，那个筛选器
 *     一打开就是空的 —— 而空列表看起来像「后端没数据」，不像「fixtures 过期」。
 *     代价是：**每次刷新页面数据都会整体平移**，所以不要拿截图去比对时间。
 *
 *   二、**订单一律走 `db.createOrder` + `mark*` 这套状态机，不手写行。**
 *     这正是 `db.ts` 给所有 `mark*` 留 `at` 参数的原因（见那个文件头）：
 *     走同一套 CAS 造出来的历史订单，与用户点出来的订单**在结构上不可能不同** ——
 *     冻结额、分段、事件流、枪的 `currentOrderId` 全都是真的。
 *     手写一个 `{status: 5}` 的行，看起来一样，但那笔订单没有分段、没有事件，
 *     点进详情就是一片空白，而且没人知道是数据缺了还是接口坏了。
 *
 *   三、**场景只改「把哪个落点摆出来」，不改业务规则。**
 *     `conflict` 只是把那把枪标成「已被别人改过」，`insufficient` 只是把余额按低 ——
 *     之后发生的一切仍然由 `db.ts` 的真实逻辑产生。
 */

import type { Cents, MockDb } from './db'
import { STALE_PENDING_PAY_REASON, yuan } from './db'
import type { MockScenario } from './scenario'
import { estimateFreezeCents, resolveTemplateIdAt } from './handlers/order-rules'

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** 「base 往前 ms」的绝对时刻。种子里每一个时间都应该这样算出来。 */
function ago(base: Date, ms: number): Date {
  return new Date(base.getTime() - ms)
}

/** 相对某个时刻往后推。用来造同一笔订单的 startTime / endTime。 */
function plus(from: Date, ms: number): Date {
  return new Date(from.getTime() + ms)
}

/**
 * 演示车主的手机号。
 *
 * ★ 选 `138` 开头是有意的：填充车主占的是 `150` 号段，
 *   所以「按手机号搜订单」时输入 `138` 只命中演示车主这几个人，
 *   而输入 `150` 会撞上限（见 `FILLER_MEMBER_COUNT`）。
 */
export const DEMO_PHONE = '13800138000'
/** 演示车主的密码。`admin/123456` 之外唯一要记住的一组。 */
export const DEMO_PASSWORD = '123456'
/** 管理端超管账号。 */
export const ADMIN_USERNAME = 'admin'
export const ADMIN_PASSWORD = '123456'
/** 站点级账号（role 2），用来演示「数据被 stationId 限住」的形态。 */
export const STATION_STAFF_USERNAME = 'station01'

/**
 * 填充车主的数量。
 *
 * ★★ 这 500 条**不是**为了「列表看起来有数据」，是为了让一条**拒绝分支可达** ★★
 *   `ChargingOrderServiceImpl.resolveMemberIds` 在前缀命中数 `>= 500` 时直接抛
 *   「该手机号前缀匹配到的车主过多」。没有这 500 行，那个分支在 mock 里
 *   永远走不到 —— 而它恰恰是一条**前端有专门处理**的分支
 *   （订单列表页用 `msgStartsWith` 匹配它，并提示「多输入几位数字」）。
 *
 * ★ 号段选 `15000000000`：它和演示车主的 `138…` 完全不重叠，
 *   所以「输 138 有结果、输 150 撞上限」这两件事可以分别演示，
 *   而不是只能演示其中一个。
 */
const FILLER_MEMBER_COUNT = 500

/** 演示车主的 id。★ 填充车主先入库，所以它是「最后一个以外」的那个。 */
const DEMO_MEMBER_ID = FILLER_MEMBER_COUNT + 1

/**
 * 把场景落点摆进库里。
 *
 * ★ 在**订单流水之后**才调 —— 它要改的是「最终状态」：
 *   比如 `insufficient` 把余额按到 3 元，必须发生在所有结算完成之后，
 *   否则那些结算根本跑不起来（它们自己也要钱）。
 */
export function seedDb(db: MockDb, scenario: MockScenario): void {
  const now = db.now()

  seedStaff(db, now)
  seedMembers(db, now)
  seedVehicles(db, now)
  // ★ 折扣权益的 id 要传给订单流水 —— 有一笔订单必须**真的**用它结算过，
  //   否则 `discountAmount > 0` 那半张详情页永远看不到。写死一个数字的话，
  //   将来往上面加一份权益就会静默错位到另一份上。
  const discountPackageId = seedCatalog(db, now)
  seedOrders(db, now, discountPackageId)

  applyScenario(db, scenario, now)
}

// ══════════════════════════════════════════════════════════════════════════
// 账号
// ══════════════════════════════════════════════════════════════════════════

function seedStaff(db: MockDb, now: Date): void {
  db.staff.push(
    {
      id: db.nextId('staff'),
      username: ADMIN_USERNAME,
      password: ADMIN_PASSWORD,
      name: '系统管理员',
      phone: '13900000001',
      sex: '男',
      // 1 = 超管。★ `stationId` 为 null 表示「不属于任何站点」= 看得到全部数据。
      role: 1,
      stationId: null,
      status: 1,
      createTime: ago(now, 180 * DAY),
    },
    {
      id: db.nextId('staff'),
      username: STATION_STAFF_USERNAME,
      password: ADMIN_PASSWORD,
      name: '中关村站站长',
      phone: '13900000002',
      // ★ `sex` 是**字符串**（`StaffVO.sex` 就是 string），和车主端的数字 `gender`
      //   不是一回事。这里写数字会被类型检查拦下来；真后端上是 Jackson 把 `"男"`
      //   直接绑到 String 字段，所以「两种都收」的宽松处理反而是错的。
      sex: '女',
      role: 2,
      stationId: 1,
      status: 1,
      createTime: ago(now, 150 * DAY),
    },
  )
}

// ══════════════════════════════════════════════════════════════════════════
// 车主
// ══════════════════════════════════════════════════════════════════════════

function seedMembers(db: MockDb, now: Date): void {
  // ★★ 顺序即 id 顺序，而列表按 `id DESC` 排 —— 所以填充车主**必须先塞** ★★
  //   反过来的话，会员列表的第一页全是「填充车主 1/2/3…」，
  //   演示时要翻 50 页才看得到演示车主。填充数据的用途是「撑大基数」，
  //   不是「占住首屏」。
  for (let i = 1; i <= FILLER_MEMBER_COUNT; i += 1) {
    db.members.push({
      id: db.nextId('member'),
      phone: `1500000${String(i).padStart(4, '0')}`,
      password: DEMO_PASSWORD,
      nickname: `填充车主${i}`,
      avatar: null,
      gender: i % 3,
      status: 1,
      lastLoginTime: ago(now, i * 37 * MINUTE),
      createTime: ago(now, (400 - (i % 300)) * DAY),
      updateTime: ago(now, i * HOUR),
    })
  }

  db.members.push(
    {
      id: db.nextId('member'),
      phone: DEMO_PHONE,
      password: DEMO_PASSWORD,
      nickname: '演示车主',
      avatar: null,
      gender: 1,
      // ★ `locked` 场景把这里改成 0（见 `applyScenario`）。判据是
      //   `/me` 返回 **HTTP 200 且 status === 0** 时 H5 强制登出 ——
      //   所以这条记录是那条链路的**唯一**触发源。
      status: 1,
      lastLoginTime: ago(now, 2 * HOUR),
      createTime: ago(now, 120 * DAY),
      updateTime: ago(now, 2 * HOUR),
    },
    {
      id: db.nextId('member'),
      phone: '13800138001',
      password: DEMO_PASSWORD,
      nickname: '张伟',
      avatar: null,
      gender: 1,
      status: 1,
      lastLoginTime: ago(now, 5 * HOUR),
      createTime: ago(now, 90 * DAY),
      updateTime: ago(now, 5 * HOUR),
    },
    {
      id: db.nextId('member'),
      phone: '13800138002',
      password: DEMO_PASSWORD,
      nickname: '李娜',
      avatar: null,
      gender: 2,
      status: 1,
      lastLoginTime: ago(now, 3 * DAY),
      createTime: ago(now, 60 * DAY),
      updateTime: ago(now, 3 * DAY),
    },
    {
      id: db.nextId('member'),
      phone: '13912345678',
      // ★★ `password: null` 是**合法且有意的状态**（DDL 注释：验证码登录的用户为 NULL）★★
      //   用它登录会命中「该账号未设置密码，无法使用密码登录」——
      //   那句文案**刻意不并进**「手机号或密码错误」，因为那类用户重试密码永远不会成功。
      //   这一行就是那条分支的落点。
      password: null,
      nickname: '未设密码的车主',
      avatar: null,
      gender: 0,
      status: 1,
      lastLoginTime: null,
      createTime: ago(now, 15 * DAY),
      updateTime: ago(now, 15 * DAY),
    },
  )
}

// ══════════════════════════════════════════════════════════════════════════
// 车辆与钱包
// ══════════════════════════════════════════════════════════════════════════

function seedVehicles(db: MockDb, now: Date): void {
  db.vehicles.push(
    {
      id: db.nextId('vehicle'),
      memberId: DEMO_MEMBER_ID,
      plateNo: '京A·D12345',
      plateColor: 3,
      brand: '比亚迪',
      model: '汉 EV',
      // ★ 85.4 而不是整数：预估冻结 = 容量 × 最贵单价，容量带小数
      //   才能看出「冻结额是算出来的」而不是拍的一个整数。
      batteryCapacityKwh: 85.4,
      isDefault: 1,
      remark: '通勤车',
      createTime: ago(now, 118 * DAY),
      updateTime: ago(now, 118 * DAY),
    },
    {
      id: db.nextId('vehicle'),
      memberId: DEMO_MEMBER_ID,
      plateNo: '京C·66666',
      plateColor: 4,
      brand: '蔚来',
      model: 'ES6',
      batteryCapacityKwh: 75,
      // ★ `isDefault` 是**数字**：判默认车要用 `=== 1`，别用真值判断。
      isDefault: 0,
      remark: null,
      createTime: ago(now, 80 * DAY),
      updateTime: ago(now, 80 * DAY),
    },
    {
      id: db.nextId('vehicle'),
      memberId: DEMO_MEMBER_ID + 1,
      plateNo: '沪A·12345',
      plateColor: 3,
      brand: '特斯拉',
      model: 'Model 3',
      batteryCapacityKwh: 60,
      isDefault: 1,
      remark: null,
      createTime: ago(now, 88 * DAY),
      updateTime: ago(now, 88 * DAY),
    },
  )

  // ★ 充值走 `db.recharge`（而不是直接写余额）：这样余额、`totalRecharge`
  //   和那条 `1 充值` 流水三者天然一致。手写余额的话 `totalRecharge`
  //   会是一个对不上的数，而「累计充值」正是钱包页头上的一个展示项。
  // ★ 第三位车主（李娜）**没有任何车辆** —— `ChargingOrderVO.plateNo` 为 null
  //   是合法形态，前端不能假设它一定非空。
  db.recharge(DEMO_MEMBER_ID, 5000, ago(now, 30 * DAY))
  db.recharge(DEMO_MEMBER_ID + 1, 500, ago(now, 20 * DAY))
  db.recharge(DEMO_MEMBER_ID + 2, 300, ago(now, 10 * DAY))
}

// ══════════════════════════════════════════════════════════════════════════
// 站点 / 充电桩 / 充电枪 / 计费 / 分类 / 套餐
// ══════════════════════════════════════════════════════════════════════════

/**
 * 充电枪 id 的语义名字。
 *
 * ★ 这些数字在下面被引用十几次，写死裸数字的话「4 号枪为什么不能有订单」
 *   这种问题没法从代码里看出来。id 按创建顺序分配，顺序由 `seedCatalog`
 *   里的 `chargers` 数组和每把枪的 `guns` 数组共同决定。
 */
const GUN = {
  /** 1 号站 · 1 号直流桩 · 1 号枪 —— 空闲，用来「现场新开一单」 */
  FREE_DC: 1,
  /** 2 号枪 —— 充电中（订单 ⑦） */
  CHARGING: 2,
  /** 3 号枪 —— 已结束待结算（订单 ⑥） */
  SETTLING: 3,
  /** ★★ 4 号枪 —— 1 号站 · 交流桩 · 1 号枪。留给「乐观锁冲突」演示，必须保持空闲 ★★ */
  CONFLICT_TARGET: 4,
  /** 5 号枪 —— 被「待启动」的订单 ⑧ 占着 */
  HELD: 5,
  /** 9 号枪 —— 手工置成「故障」 */
  FAULTY: 9,
  /** 11 号枪 —— 手工置成「离线」 */
  OFFLINE: 11,
} as const

/** 站点 / 桩 / 枪 / 计费 / 套餐。返回折扣权益的 id（订单流水要用）。 */
function seedCatalog(db: MockDb, now: Date): number {
  const stations = [
    {
      code: 'ST001',
      name: '中关村充电站',
      operator: '国网北京',
      province: '北京市',
      city: '北京',
      district: '海淀区',
      address: '中关村大街 1 号院地下车库 B1',
      longitude: 116.316,
      latitude: 39.983,
      servicePhone: '010-88886666',
      openTime: '00:00',
      closeTime: '24:00',
      parkingFee: 0,
      status: 1,
    },
    {
      code: 'ST002',
      name: '陆家嘴充电站',
      operator: '星星充电',
      province: '上海市',
      city: '上海',
      district: '浦东新区',
      address: '世纪大道 100 号 P2 层',
      longitude: 121.506,
      latitude: 31.245,
      servicePhone: '021-66668888',
      openTime: '06:00',
      closeTime: '23:00',
      // 5 元停车费 —— 顺带覆盖 `Decimal | null` 的金额字段
      parkingFee: 500,
      status: 1,
    },
    {
      code: 'ST003',
      name: '珠江新城充电站',
      operator: '特来电',
      province: '广东省',
      city: '广州',
      district: '天河区',
      address: '华夏路 8 号',
      longitude: 113.32,
      latitude: 23.12,
      servicePhone: '020-33335555',
      openTime: '00:00',
      closeTime: '24:00',
      parkingFee: null,
      // ★ 2 维护中。★ 车主端站点列表的 SQL 里写死了 `WHERE status = 1`，
      //   所以这个站**不会出现在 H5 上** —— 而它下面的枪仍然是「空闲」的，
      //   这正是「过滤发生在后端而不是前端」的证据。
      status: 2,
    },
    {
      code: 'ST004',
      name: '亦庄停用测试站',
      operator: '国网北京',
      province: '北京市',
      city: '北京',
      district: '大兴区',
      address: '荣华中路 15 号',
      longitude: 116.5,
      latitude: 39.79,
      servicePhone: null,
      openTime: null,
      closeTime: null,
      parkingFee: 0,
      status: 0,
    },
  ]

  stations.forEach((s, i) => {
    db.stations.push({
      id: db.nextId('station'),
      ...s,
      createTime: ago(now, (200 - i * 10) * DAY),
      updateTime: ago(now, (30 - i) * DAY),
    })
  })

  // ── 充电桩 ──────────────────────────────────────────────────────────────
  // ★ `guns` 里的 `'10'` 是**有意**的：枪号是字符串，所以接口不能替前端排序，
  //   页面上「1, 10, 2」这个顺序是真实的，前端的重排逻辑才有东西可修。
  const chargers = [
    { stationId: 1, deviceNo: 'DC-001', name: '1 号直流桩', type: 1, model: 'XC-D60', ratedPowerKw: 60, guns: ['1', '2', '10'] },
    { stationId: 1, deviceNo: 'AC-001', name: '1 号交流桩', type: 2, model: 'XC-A7', ratedPowerKw: 7, guns: ['1', '2', '3'] },
    { stationId: 2, deviceNo: 'DC-002', name: '2 号直流桩', type: 1, model: 'XC-D120', ratedPowerKw: 120, guns: ['1', '2'] },
    { stationId: 2, deviceNo: 'AC-002', name: '2 号交流桩', type: 2, model: 'XC-A7', ratedPowerKw: 7, guns: ['1'] },
    { stationId: 3, deviceNo: 'DC-003', name: '3 号直流桩', type: 1, model: 'XC-D60', ratedPowerKw: 60, guns: ['1', '2'] },
  ]

  chargers.forEach((c, i) => {
    db.chargers.push({
      id: db.nextId('charger'),
      stationId: c.stationId,
      deviceNo: c.deviceNo,
      name: c.name,
      type: c.type,
      model: c.model,
      ratedPowerKw: c.ratedPowerKw,
      // ★ `gunCount` 是**冗余列**，后端在枪增删时用 `COUNT(*)` 重算。
      //   这里先写 0，等枪插完再统一重算（见下面）—— 分两步是为了让
      //   「重算」这件事在这份数据上也真的发生过一次。
      gunCount: 0,
      status: 1,
      // ★ 心跳时间必须靠近 now：前端会把它渲染成「在线/离线」的相对时间，
      //   写死一个旧日期会让所有桩都显示成「离线 300 天」。
      lastHeartbeatTime: ago(now, 2 * MINUTE + i * 30_000),
      firmwareVersion: `v1.${i}.3`,
      createTime: ago(now, (180 - i * 5) * DAY),
      updateTime: ago(now, 2 * MINUTE),
    })
  })

  // ── 充电枪 ──────────────────────────────────────────────────────────────
  chargers.forEach((c, ci) => {
    c.guns.forEach((gunNo) => {
      const id = db.nextId('connector')
      db.connectors.push({
        id,
        chargerId: ci + 1,
        stationId: c.stationId,
        gunNo,
        connectorType: c.type === 1 ? '国标直流快充' : '国标交流慢充',
        ratedPowerKw: c.ratedPowerKw,
        status: 1,
        currentOrderId: null,
        // ★ 累计充电次数给一个非零值：列表里那一列全是 0 会让人怀疑字段没接上。
        totalChargeCount: (id * 17) % 40,
        // ★ 从 0 起，和 `ConnectorServiceImpl.create` 一致（它写死 `version = 0`）。
        //   写成 1 会让「乐观锁冲突」的演示**提前一次**：第一次 PUT 传回
        //   version=1 撞上库里的 1 反而成功了，得点两次才冲突。
        version: 0,
        createTime: ago(now, (170 - ci * 5) * DAY),
        updateTime: ago(now, 2 * MINUTE),
      })
    })
  })

  // ★★ 冗余列重算 ★★ 与后端 `refreshGunCount` 同一件事。放在枪全部插完之后，
  //   而不是在循环里自增 —— 自增在「有枪被删过」或「上一次事务回滚过」时
  //   会错得无法恢复，重算则永远是精确值。
  for (const charger of db.chargers) {
    charger.gunCount = db.connectors.filter((c) => c.chargerId === charger.id).length
  }

  seedTariffs(db, now)
  seedPackages(db, now)
  return seedMemberPackages(db, now)
}

/** 一个完整覆盖 24 小时的尖峰平谷模板。缺一段的话结算会报「时段存在缺口」。 */
function seedTariffs(db: MockDb, now: Date): void {
  const t1 = db.nextId('tariffTemplate')
  db.tariffTemplates.push({
    id: t1,
    code: 'TPL-BJ-001',
    name: '北京城区尖峰平谷',
    region: '北京',
    description: '每日 5 段，完整覆盖 24 小时',
    status: 1,
    createTime: ago(now, 120 * DAY),
    updateTime: ago(now, 60 * DAY),
  })

  /** [periodType, start, end, energyPrice, servicePrice] */
  const rules: [number, string, string, number, number][] = [
    [4, '00:00:00', '08:00:00', 0.3, 0.2],
    [3, '08:00:00', '11:00:00', 0.6, 0.3],
    [2, '11:00:00', '18:00:00', 0.9, 0.4],
    [1, '18:00:00', '22:00:00', 1.2, 0.5],
    [3, '22:00:00', '24:00:00', 0.6, 0.3],
  ]
  rules.forEach(([periodType, startTime, endTime, energyPrice, servicePrice], i) => {
    db.tariffRules.push({
      id: db.nextId('tariffRule'),
      templateId: t1,
      periodType,
      startTime,
      endTime,
      energyPrice,
      servicePrice,
      seq: i + 1,
      createTime: ago(now, 120 * DAY),
      updateTime: ago(now, 120 * DAY),
    })
  })

  // 第二个模板：**已停用**。用它演示「停用的模板不能用于新建绑定」，
  // 也顺带说明模板可以被多个站点共用（下面几个站绑的都是 t1）。
  const t2 = db.nextId('tariffTemplate')
  db.tariffTemplates.push({
    id: t2,
    code: 'TPL-GZ-OLD',
    name: '广州老版两段价（已停用）',
    region: '广州',
    description: '历史版本，保留仅供对照',
    status: 0,
    createTime: ago(now, 400 * DAY),
    updateTime: ago(now, 100 * DAY),
  })
  const oldRules: [number, string, string, number, number][] = [
    [4, '00:00:00', '08:00:00', 0.25, 0.15],
    [3, '08:00:00', '24:00:00', 0.5, 0.25],
  ]
  oldRules.forEach(([periodType, startTime, endTime, energyPrice, servicePrice], i) => {
    db.tariffRules.push({
      id: db.nextId('tariffRule'),
      templateId: t2,
      periodType,
      startTime,
      endTime,
      energyPrice,
      servicePrice,
      seq: i + 1,
      createTime: ago(now, 400 * DAY),
      updateTime: ago(now, 400 * DAY),
    })
  })

  // ── 站点计费绑定 ────────────────────────────────────────────────────────
  // ★★ 1 号站有**三条**绑定，这是刻意的 ★★ 解析逻辑的排序和区间在这三条上
  //   全都能被看出来：一条已失效（历史）、一条正在生效（胜出）、一条未来生效
  //   （不参与）。只有一条绑定的话，那个排序函数写错了也不会有人发现。
  const bindings = [
    { stationId: 1, templateId: t2, fromDay: 120, toDay: 60, priority: 5 },
    { stationId: 1, templateId: t1, fromDay: 60, toDay: null, priority: 10 },
    { stationId: 2, templateId: t1, fromDay: 45, toDay: null, priority: 10 },
    { stationId: 3, templateId: t1, fromDay: 30, toDay: null, priority: 10 },
    // ★ 未来生效 + **更高**优先级：它在时间窗口外，所以不会抢走上面那条。
    //   把「优先级」误解成「谁大就用谁、不看时间」的人，会在这里得到错误的价。
    { stationId: 1, templateId: t2, fromDay: -7, toDay: null, priority: 20 },
  ]
  bindings.forEach((b) => {
    db.stationTariffs.push({
      id: db.nextId('stationTariff'),
      stationId: b.stationId,
      templateId: b.templateId,
      effectiveFrom: ago(now, b.fromDay * DAY),
      effectiveTo: b.toDay === null ? null : ago(now, b.toDay * DAY),
      priority: b.priority,
      status: 1,
      createTime: ago(now, 120 * DAY),
      updateTime: ago(now, 60 * DAY),
    })
  })

  // ★ 种子自检：1 号站此刻必须解析到 `t1`（上面那堆绑定的唯一正确结果）。
  //   写错一条 `priority`、或把 `effectiveTo` 当成闭区间，这里立刻炸 ——
  //   否则症状是「所有历史订单的价都变成了老模板的价」，
  //   而那要逐段翻账单才看得出来。
  const resolved = resolveTemplateIdAt(db, 1, now)
  if (resolved !== t1) {
    throw new Error(`种子数据错误：1 号站此刻应解析到模板 ${t1}，实际是 ${resolved}`)
  }
}

/** 分类 + 套餐。★ 显式一个类型，因为下面每一行的额度字段都是可选的。 */
interface PackageSeed {
  name: string
  categoryId: number
  packageType: number
  stationId: number
  price: Cents
  originPrice?: Cents
  energyKwh?: number
  durationMinutes?: number
  times?: number
  serviceDiscount?: number
  validDays: number
  sort: number
  status: number
}

function seedPackages(db: MockDb, now: Date): void {
  const categories = [
    { name: '充电套餐', type: 1, sort: 1, status: 1 },
    { name: '增值服务', type: 2, sort: 2, status: 1 },
    { name: '已停用分类', type: 1, sort: 3, status: 0 },
  ]
  categories.forEach((c, i) => {
    db.categories.push({
      id: db.nextId('category'),
      name: c.name,
      type: c.type,
      sort: c.sort,
      status: c.status,
      createTime: ago(now, (100 - i * 10) * DAY),
    })
  })

  const packages: PackageSeed[] = [
    // ★ 四种 `packageType` 各一个，且**只填自己那一列额度**：
    //   后端 `assertTypeFields` 会检查「不属于这个类型的额度字段必须为空」，
    //   多填一列会在保存时被拒绝（提示里明说「残留值会被计费逻辑读走」）。
    { name: '畅充电量包 100 度', categoryId: 1, packageType: 1, stationId: 0, price: 9900, originPrice: 12000, energyKwh: 100, validDays: 90, sort: 1, status: 1 },
    { name: '月卡时长包 600 分钟', categoryId: 1, packageType: 2, stationId: 0, price: 5900, originPrice: 7900, durationMinutes: 600, validDays: 30, sort: 2, status: 1 },
    { name: '服务费 5 折包', categoryId: 1, packageType: 3, stationId: 0, price: 2900, serviceDiscount: 0.5, validDays: 30, sort: 3, status: 1 },
    { name: '10 次卡', categoryId: 1, packageType: 4, stationId: 0, price: 19900, originPrice: 26000, times: 10, validDays: 365, sort: 4, status: 1 },
    // ★ 站点专属包：`stationId = 1`。车主端可购列表的条件是
    //   `station_id IN (0, #{stationId})`，所以它在 1 号站可见、在 2 号站不可见。
    //   ★ `0` 才是「全平台通用」（**不是** null）。
    { name: '中关村专属 50 度包', categoryId: 1, packageType: 1, stationId: 1, price: 3900, energyKwh: 50, validDays: 30, sort: 5, status: 1 },
    // ★ 已下架：车主端买不到（「该套餐已下架」），管理端列表里看得到。
    { name: '已下架体验包', categoryId: 1, packageType: 1, stationId: 0, price: 990, energyKwh: 10, validDays: 7, sort: 6, status: 0 },
  ]

  packages.forEach((p, i) => {
    db.packages.push({
      id: db.nextId('package'),
      name: p.name,
      categoryId: p.categoryId,
      packageType: p.packageType,
      stationId: p.stationId,
      price: p.price,
      originPrice: p.originPrice ?? null,
      energyKwh: p.energyKwh ?? null,
      durationMinutes: p.durationMinutes ?? null,
      times: p.times ?? null,
      serviceDiscount: p.serviceDiscount ?? null,
      validDays: p.validDays,
      description: `${p.name} —— mock 演示数据`,
      image: null,
      status: p.status,
      sort: p.sort,
      createTime: ago(now, (80 - i * 5) * DAY),
      updateTime: ago(now, (20 - i) * DAY),
    })
  })
}

/**
 * 演示车主名下的权益。返回**折扣包**那一份的 id（订单流水要用）。
 *
 * ★★ 六份权益刻意覆盖**四个状态 + 一条不可退的坏数据** ★★
 *   管理端的权益弹窗要能演示：正常作废退钱、已用尽、已过期、已作废，
 *   以及「没有购买金额快照 → 人工处理」这条**拒绝**分支。
 *   只有一份「生效中」的话，那个弹窗上四种状态标签一个都看不到。
 *
 * ★ 余量是填出来的、不是算出来的：这份数据的用途是「摆状态」，
 *   而不是重现某一次购买 —— 购买流程由 handler 真的去扣。
 */
function seedMemberPackages(db: MockDb, now: Date): number {
  const rows: {
    packageId: number
    packageType: number
    name: string
    payAmount: Cents | null
    totalKwh: number | null
    remainingKwh: number | null
    totalMinutes: number | null
    remainingMinutes: number | null
    totalTimes: number | null
    remainingTimes: number | null
    serviceDiscount: number | null
    fromDay: number
    /** 正 = 还有几天到期；负 = 已经过期几天；null = 永不过期。 */
    expireInDays: number | null
    status: number
    remark: string | null
  }[] = [
    // ① 可作废（没有被任何订单引用过 → 全额退 99 元）。★ 作废演示的落点。
    { packageId: 1, packageType: 1, name: '畅充电量包 100 度', payAmount: 9900, totalKwh: 100, remainingKwh: 62.5, totalMinutes: null, remainingMinutes: null, totalTimes: null, remainingTimes: null, serviceDiscount: null, fromDay: 10, expireInDays: 80, status: 1, remark: null },
    // ② 已用尽（余量 0）
    { packageId: 4, packageType: 4, name: '10 次卡', payAmount: 19900, totalKwh: null, remainingKwh: null, totalMinutes: null, remainingMinutes: null, totalTimes: 10, remainingTimes: 0, serviceDiscount: null, fromDay: 300, expireInDays: 65, status: 2, remark: null },
    // ③ 已过期（`expire_time` 已经过去 —— 定时任务会把它落成 3，这里直接给终态）
    { packageId: 2, packageType: 2, name: '月卡时长包 600 分钟', payAmount: 5900, totalKwh: null, remainingKwh: null, totalMinutes: 600, remainingMinutes: 300, totalTimes: null, remainingTimes: null, serviceDiscount: null, fromDay: 40, expireInDays: -3, status: 3, remark: null },
    // ④ 已作废（作废原因写在 `remark` 上；那笔钱当时已经退回去了）
    { packageId: 3, packageType: 3, name: '服务费 5 折包', payAmount: 2900, totalKwh: null, remainingKwh: null, totalMinutes: null, remainingMinutes: null, totalTimes: null, remainingTimes: null, serviceDiscount: 0.5, fromDay: 20, expireInDays: 10, status: 4, remark: '客服误发，已作废并退款' },
    // ⑤ ★★ `payAmount: null` —— 「不知道当时付了多少钱」★★
    //   它建于本功能上线之前，`pay_amount` 那一列没有回溯。
    //   作废它会被拒绝（「没有购买金额快照…请人工处理」），
    //   因为拿现在的售价现算一个金额，等于「按今天的价退去年的钱」。
    //   ★ 这也是为什么库里存的必须是**快照**，而不是一个 JOIN 出来的价。
    //   ★ `expireInDays: null` 顺带覆盖「永不过期」这一支。
    { packageId: 1, packageType: 1, name: '畅充电量包 100 度', payAmount: null, totalKwh: 100, remainingKwh: 100, totalMinutes: null, remainingMinutes: null, totalTimes: null, remainingTimes: null, serviceDiscount: null, fromDay: 200, expireInDays: null, status: 1, remark: '迁移数据，无金额快照' },
  ]

  rows.forEach((m, i) => {
    db.memberPackages.push({
      id: db.nextId('memberPackage'),
      memberId: DEMO_MEMBER_ID,
      packageId: m.packageId,
      packageType: m.packageType,
      packageName: m.name,
      payAmount: m.payAmount,
      totalKwh: m.totalKwh,
      remainingKwh: m.remainingKwh,
      totalMinutes: m.totalMinutes,
      remainingMinutes: m.remainingMinutes,
      totalTimes: m.totalTimes,
      remainingTimes: m.remainingTimes,
      serviceDiscount: m.serviceDiscount,
      effectiveFrom: ago(now, m.fromDay * DAY),
      expireTime:
        m.expireInDays === null
          ? null
          : m.expireInDays < 0
            ? ago(now, -m.expireInDays * DAY)
            : plus(now, m.expireInDays * DAY),
      status: m.status,
      lockedOrderId: null,
      remark: m.remark,
      createTime: ago(now, m.fromDay * DAY),
      updateTime: ago(now, (m.fromDay - i) * DAY),
    })
  })

  // ⑥ 服务费折扣包，**生效中且会被一笔已结算订单真的用掉** —— 用来演示
  //   `discountAmount > 0` 与分段里的优惠。上面那五份的用途是「摆状态」，
  //   这一份的用途是「参与计费」，所以它单独一段、并且**返回它的 id**。
  const discountId = db.nextId('memberPackage')
  db.memberPackages.push({
    id: discountId,
    memberId: DEMO_MEMBER_ID,
    packageId: 3,
    packageType: 3,
    packageName: '服务费 5 折包',
    payAmount: 2900,
    totalKwh: null,
    remainingKwh: null,
    totalMinutes: null,
    remainingMinutes: null,
    totalTimes: null,
    remainingTimes: null,
    serviceDiscount: 0.5,
    effectiveFrom: ago(now, 25 * DAY),
    expireTime: plus(now, 20 * DAY),
    status: 1,
    lockedOrderId: null,
    remark: null,
    createTime: ago(now, 25 * DAY),
    updateTime: ago(now, 25 * DAY),
  })
  return discountId
}

// ══════════════════════════════════════════════════════════════════════════
// 订单
// ══════════════════════════════════════════════════════════════════════════

/**
 * 造 11 笔订单，覆盖**全部 9 个状态**。
 *
 * ★★ 为什么每个状态都要有一笔 ★★
 *   管理端订单列表带状态筛选、详情页按状态显示不同的操作按钮。
 *   少了某个状态，那个按钮就永远点不到、「这个状态下的界面」也永远看不到 ——
 *   而这类缺失**不会报错**，只会让人以为「那个功能没做」。
 *
 * ★ 冻结额一律走 `estimateFreezeCents`（和建单 handler 同一个函数）：
 *   种子里的数字和用户自己下单时的数字必须是同一套算法算出来的，
 *   否则会出现「手工造的那几单冻了 100、用户点出来的冻了 145」
 *   这种只在演示时才被发现的偏差。
 */
function seedOrders(db: MockDb, now: Date, discountPackageId: number): void {
  const M = { demo: DEMO_MEMBER_ID, second: DEMO_MEMBER_ID + 1, third: DEMO_MEMBER_ID + 2 }
  /** 车辆 id 按 `seedVehicles` 的插入顺序。 */
  const V = { demo1: 1, demo2: 2, second: 3 }

  // ★ 1 号站此刻生效的模板，用它填进每一笔订单 —— 订单的模板是**建单当时**
  //   解析出来的快照，之后运营改价不影响它（见 `DbOrder.tariffTemplateId`）。
  const templateId = resolveTemplateIdAt(db, 1, now)
  if (templateId === null) throw new Error('种子数据错误：1 号站此刻没有生效的计费模板')

  /** 预估冻结：容量 × 最贵单价。容量为 null 时按 60 度算（见 order-rules）。 */
  const freezeFor = (vehicleId: number | null): Cents => {
    const capacity =
      vehicleId === null ? null : (db.vehicles.find((v) => v.id === vehicleId)?.batteryCapacityKwh ?? null)
    return estimateFreezeCents(db, capacity, templateId)
  }

  /**
   * 开一笔单。★ 建单失败在种子里是**代码写错了**，不是「用户操作不当」—— 直接抛。
   * 静默跳过的话会得到「少了一笔订单」的怪现象，而没有任何提示。
   */
  const open = (
    memberId: number,
    vehicleId: number | null,
    connectorId: number,
    createAt: Date,
    frozenAmount: Cents,
    memberPackageId?: number,
  ) => {
    const connector = db.connectorById(connectorId)
    if (!connector) throw new Error(`种子数据错误：充电枪 ${connectorId} 不存在`)
    const res = db.createOrder({
      memberId,
      vehicleId,
      stationId: connector.stationId,
      chargerId: connector.chargerId,
      connectorId,
      tariffTemplateId: templateId,
      payMethod: 1,
      frozenAmount,
      memberPackageId: memberPackageId ?? null,
      at: createAt,
    })
    // ★ 两个失败分支分开：`CasResult` 是**可辨识联合**（`{ok:true}` 没有 `msg`），
    //   写成 `!res.ok || !res.order` 的话 `res` 已经被收窄成联合类型，
    //   编译期就取不到 `msg` 了 —— 而那句 msg 正是排查种子错误的唯一线索。
    if (!res.ok) throw new Error(`种子数据错误：建单失败 —— ${res.msg}`)
    if (!res.order) throw new Error('种子数据错误：建单返回了 ok 但没有 order')
    return res.order
  }

  // ── ① 异常挂账（6 天前）────────────────────────────────────────────────
  // ★ 走的是**真实路径**：结束电表读数小于起始读数 → `markEnded` 自己转挂账。
  //   直接调 `markAbnormal` 会少一条 `end` 事件，而时间轴上「先结束、再异常」
  //   这个先后顺序恰恰是详情页要解释的东西。
  {
    const createAt = ago(now, 6 * DAY)
    const order = open(M.second, V.second, 6, createAt, freezeFor(V.second))
    db.markStarted(order.id, 1200.5, 42, plus(createAt, 2 * MINUTE))
    db.markEnded(
      order.id,
      { endMeterKwh: 1190, endSoc: 48, stopReason: 4, endTime: plus(createAt, 38 * MINUTE) },
      plus(createAt, 38 * MINUTE),
    )
  }

  // ── ② 已取消（5 天前）──────────────────────────────────────────────────
  // ★ 不带车辆：车牌快照为 null —— 演示「没下挂车辆也能充」这条合法形态，
  //   顺带让列表里出现一个 `plateNo` 为空的行（而不是清一色的车牌）。
  {
    const createAt = ago(now, 5 * DAY)
    const order = open(M.third, null, 7, createAt, freezeFor(null))
    db.markCancelled(order.id, plus(createAt, 3 * MINUTE))
  }

  // ── ③ 已退款（4 天前，**绑了服务费 5 折包**）──────────────────────────
  {
    const createAt = ago(now, 4 * DAY)
    const order = open(M.demo, V.demo1, 8, createAt, freezeFor(V.demo1), discountPackageId)
    db.markStarted(order.id, 3000, 30, plus(createAt, 2 * MINUTE))
    db.markEnded(
      order.id,
      { endMeterKwh: 3050, endSoc: 82, stopReason: 2, endTime: plus(createAt, 40 * MINUTE) },
      plus(createAt, 40 * MINUTE),
    )
    db.markSettled(order.id, plus(createAt, 41 * MINUTE))
    db.markPaid(order.id, plus(createAt, 45 * MINUTE))
    // ★ 退款金额从订单**自己**的 `payAmount` 上取，不写死 ——
    //   写死的话，一旦账单口径变了，种子里的退款额就和一个不存在的实付对不上了。
    const payAmount = db.orderById(order.id)?.payAmount ?? 0
    if (payAmount > 0) db.markRefunded(order.id, yuan(payAmount), ago(now, 4 * DAY - HOUR))
  }

  // ── ④ 支付失败（3 天前）────────────────────────────────────────────────
  // ★ 用 `STALE_PENDING_PAY_REASON`（定时任务那条原因），而不是随手编一句：
  //   这句文案在 `db.ts` 里是导出常量、并且会被写进事件 detail，
  //   两处必须是同一句，否则「超时标失败」在时间轴上会和别的异常长得不一样。
  {
    const createAt = ago(now, 3 * DAY)
    const order = open(M.second, V.second, 6, createAt, freezeFor(V.second))
    db.markStarted(order.id, 5000, 25, plus(createAt, 2 * MINUTE))
    db.markEnded(
      order.id,
      { endMeterKwh: 5030, endSoc: 70, stopReason: 1, endTime: plus(createAt, 40 * MINUTE) },
      plus(createAt, 40 * MINUTE),
    )
    db.markSettled(order.id, plus(createAt, 41 * MINUTE))
    db.markPayFailed(order.id, STALE_PENDING_PAY_REASON, plus(createAt, 60 * MINUTE))
  }

  // ── ⑤ 已支付（2 天前）──────────────────────────────────────────────────
  // ★ 它是管理端「退款」演示的落点：**只有 `5 已支付` 能退款**。
  {
    const createAt = ago(now, 2 * DAY)
    const order = open(M.demo, V.demo1, 7, createAt, freezeFor(V.demo1))
    db.markStarted(order.id, 8000, 20, plus(createAt, 2 * MINUTE))
    db.markEnded(
      order.id,
      { endMeterKwh: 8028, endSoc: 88, stopReason: 2, endTime: plus(createAt, 40 * MINUTE) },
      plus(createAt, 40 * MINUTE),
    )
    db.markSettled(order.id, plus(createAt, 41 * MINUTE))
    db.markPaid(order.id, plus(createAt, 43 * MINUTE))
  }

  // ── ⑥ 充电结束待结算（100 分钟前）─────────────────────────────────────
  // ★ 停留在这里不结算 —— 它是管理端「结算」按钮唯一的落点。
  {
    const createAt = ago(now, 130 * MINUTE)
    const order = open(M.demo, V.demo2, GUN.SETTLING, createAt, freezeFor(V.demo2))
    db.markStarted(order.id, 12000, 35, ago(now, 128 * MINUTE))
    db.markEnded(
      order.id,
      { endMeterKwh: 12042, endSoc: 74, stopReason: 1, endTime: ago(now, 100 * MINUTE) },
      ago(now, 100 * MINUTE),
    )
  }

  // ── ⑦ 充电中（38 分钟前）──────────────────────────────────────────────
  // ★ H5 订单详情页在「充电中」时会轮询电量 —— 没有这一笔，那条轮询
  //   永远停在「已结束」，而演示的人会以为轮询没写。
  {
    const createAt = ago(now, 40 * MINUTE)
    const order = open(M.demo, V.demo1, GUN.CHARGING, createAt, freezeFor(V.demo1))
    db.markStarted(order.id, 15000, 28, ago(now, 38 * MINUTE))
  }

  // ── ⑧ 待启动（8 分钟前）───────────────────────────────────────────────
  // ★ 只有 `1 待启动` 能取消 —— 它是 H5「取消订单」按钮唯一的落点。
  //   它会一直占着那把枪，所以列表里也要能看到「已占用」的枪。
  {
    const createAt = ago(now, 8 * MINUTE)
    open(M.second, null, GUN.HELD, createAt, freezeFor(null))
  }

  // ── ⑨ 待支付（3 分钟前结算）· `insufficient` 场景的落点 ───────────────
  // ★★ 这一笔的预估冻结是**刻意压小的**（2 元），理由见 `applyScenario` ★★
  {
    const createAt = ago(now, 60 * MINUTE)
    const order = open(M.demo, V.demo1, 8, createAt, 200)
    db.markStarted(order.id, 20000, 22, ago(now, 58 * MINUTE))
    db.markEnded(
      order.id,
      { endMeterKwh: 20045, endSoc: 76, stopReason: 1, endTime: ago(now, 10 * MINUTE) },
      ago(now, 10 * MINUTE),
    )
    db.markSettled(order.id, ago(now, 3 * MINUTE))
  }

  // ── ⑩ 待支付（**16 分钟前结算**）· 定时任务扫单的落点 ─────────────────
  // ★★ 「16 分钟」是落在 `settleTime` 上的，不是 `createTime` ★★
  //   扫单判据是 `settle_time < now - 15min`（见 `db.ts`），而 `createTime`
  //   是 40 分钟前 —— 车总得先充一会儿电，才谈得上「待支付」。
  //   把 16 分钟写到 `createTime` 上的话，这一笔仍然会被扫到，但扫它的理由是错的。
  //   → 演示效果：**第一次请求打进来时**，它变成 `6 支付失败` 并多一条
  //     `abnormal` 事件（`toStatus: 6`，前端会渲染成「余额不足」）。
  {
    const createAt = ago(now, 40 * MINUTE)
    const order = open(M.demo, V.demo1, 6, createAt, freezeFor(V.demo1))
    db.markStarted(order.id, 22000, 31, ago(now, 38 * MINUTE))
    db.markEnded(
      order.id,
      { endMeterKwh: 22020, endSoc: 55, stopReason: 1, endTime: ago(now, 18 * MINUTE) },
      ago(now, 18 * MINUTE),
    )
    db.markSettled(order.id, ago(now, 16 * MINUTE))
  }

  // ── ⑪ 待支付（2 分钟前结算）· 正常支付与倒计时的落点 ───────────────────
  // ★ 与 ⑩ 成对存在，缺一不可：⑩ 会被扫成 6，如果只有它，
  //   那「待支付」这个状态在第一次刷新之后就再也看不到了 ——
  //   而「支付」按钮、倒计时、15 分钟窗口全都挂在它上面。
  {
    const createAt = ago(now, 5 * MINUTE)
    const order = open(M.second, V.second, 7, createAt, freezeFor(V.second))
    db.markStarted(order.id, 24000, 18, ago(now, 4 * MINUTE))
    db.markEnded(
      order.id,
      { endMeterKwh: 24012, endSoc: 40, stopReason: 1, endTime: ago(now, 3 * MINUTE) },
      ago(now, 3 * MINUTE),
    )
    db.markSettled(order.id, ago(now, 2 * MINUTE))
  }

  // ── 收尾：几处**不经过状态机**的列 ─────────────────────────────────────
  // ★ 桩和枪的状态是「设备上报」的，没有哪个订单操作会写它们，
  //   所以只能在这里显式摆好 —— 这正是它们和订单状态**不一致**的合法来源。
  setConnectorStatus(db, GUN.FAULTY, 4, now)
  setConnectorStatus(db, GUN.OFFLINE, 0, now)
  // 桩：跟着它下面的枪走。2 号交流桩只有一把枪且已故障 → 桩也故障；
  // 3 号站整体维护中、设备离线；1 号直流桩有一把枪在充电中。
  setChargerStatus(db, 4, 3, now)
  setChargerStatus(db, 5, 0, now)
  setChargerStatus(db, 1, 2, now)
}

function setConnectorStatus(db: MockDb, id: number, status: number, at: Date): void {
  const connector = db.connectorById(id)
  if (!connector) return
  connector.status = status
  // ★ 改状态也要动 version：枪是本项目**唯一**带乐观锁的实体，
  //   而「编辑弹窗里拿到的 version 落后了」正是冲突场景要演示的事。
  connector.version += 1
  connector.updateTime = at
}

function setChargerStatus(db: MockDb, id: number, status: number, at: Date): void {
  const charger = db.chargerById(id)
  if (!charger) return
  charger.status = status
  charger.updateTime = at
}

// ══════════════════════════════════════════════════════════════════════════
// 场景落点
// ══════════════════════════════════════════════════════════════════════════

/**
 * 把当前场景的「落点」摆出来。
 *
 * ★★ 这里做的每一件事，都是**摆一个真实存在的状态**，不是伪造一个响应 ★★
 *   余额按低之后，冻钱真的会失败；枪被打上冲突标记之后，PUT 真的会走那条
 *   「影响 0 行 → 再查一次 → 已存在就报冲突」的分支。
 *   所以打开 devtools 看请求，信封格式和其它场景一模一样 ——
 *   切场景不会让 mock 变得「更假」。
 */
function applyScenario(db: MockDb, scenario: MockScenario, now: Date): void {
  switch (scenario) {
    /**
     * 乐观锁冲突：把那把枪标成「在库里已经被别人改过」。
     *
     * ★ 只标记**一次**（标记在下一次冲突的 PUT 时清掉）—— 因为要演示的第二件事
     *   恰恰是「重新打开编辑框、拿到新的 version，再提交就成功了」。
     *   永久冲突的话，那条成功路径永远走不到，用户会以为这个功能坏了。
     */
    case 'conflict': {
      const gun = db.connectorById(GUN.CONFLICT_TARGET)
      if (gun) gun.pendingConflict = true
      break
    }

    /**
     * 余额不足。两个落点，都要摆：
     *
     *   ① **建单被拒**：冻钱时余额不够 → 「余额不足」，整笔回滚。
     *      预估冻结 = max(电池容量, 60) × 最贵单价 —— 演示车主那辆是 85.4 度，
     *      所以 85.4 × 1.70 ≈ 145 元。余额 3 元必然失败。
     *
     *   ② **支付失败**：待支付那一单（种子 ⑨）的**冻结额只有 2 元**，
     *      所以「解冻出来的钱 + 余额」也付不起账单 → 后端把订单 `4 → 6`、
     *      写一条 `abnormal` 事件，同时抛「结算失败：可用余额不足以支付 X」。
     *
     * ★★ 为什么要动「冻结额」这么一个不自然的数 ★★
     *   冻结额是 `容量 × 最贵单价`，而账单最高也就是 `容量 × 最贵单价` ——
     *   也就是说**正常路径下冻结额几乎总是 ≥ 账单**，那条「付不起」的分支
     *   在真实数据上根本走不到。真后端要触发它，得靠「钱在别处花掉了」
     *   （比如另一笔订单刚结算走）或者表读数异常。这里把两个条件都摆出来，
     *   让那条分支**可复现**；代价是这一笔的冻结额看起来偏小，
     *   而这是**刻意的**，不是算错了。
     */
    case 'insufficient': {
      const wallet = db.walletOf(DEMO_MEMBER_ID)
      // ★ 直接赋值而不是走 `db.adjust`：adjust 会写下一条巨额的「管理员调账」流水
      //   挂在车主账上（-4100 元），而演示要看的是「余额不足」，
      //   不是一条没人能解释的扣款记录。
      // ★ 余额（`balance`）和冻结（`frozenAmount`）是两个字段，改前者不影响后者 ——
      //   所以已经冻着的那几笔订单不会因为这个赋值而变得奇怪。
      wallet.balance = 300
      wallet.updateTime = now
      break
    }

    /**
     * 账号被禁用。
     *
     * ★★ 这条**不能**用 401 表达 ★★ 前端的判据是
     *   「`/me` 返回 **HTTP 200** 且 `status === 0`」——因为**禁用不会撤销 token**，
     *   7 天窗口内拦截器照样放行、请求照样返回 200。
     *   所以落点就是这一行 `status = 0`，其它什么都不用做。
     *
     * ★ 登录**不拦**禁用（handler 里刻意跳过状态检查）：否则
     *   「已登录 → 被禁用 → 探针发现」这条链路根本走不到 ——
     *   一登录就被拒的话，用户拿不到 token，也就没有「拿着有效 token 但被禁用」
     *   这个状态，而这正是演示要的东西。
     */
    case 'locked': {
      const member = db.memberById(DEMO_MEMBER_ID)
      if (member) {
        member.status = 0
        member.updateTime = now
      }
      break
    }

    // ★ `token-expired` / `slow` / `error` 都**不需要**改数据：
    //   前两个是「响应层」的事（见 `handlers/core.ts` 的 guard 与 envelope），
    //   `error` 更是要在**每个**端点上都能看到 —— 改数据反而会误导，
    //   让人以为某些接口还能用是「本来就没坏」。
    default:
      break
  }
}
