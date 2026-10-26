package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.entity.Vehicle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VehicleMapper} 的集成测试
 *
 * <p>写法、理由和「为什么必须用真数据库」见 {@link StaffMapperTest} 的类注释。
 *
 * <h2>★★ 一、本类存在的头号理由：水平越权的三道防线只能在真库上验</h2>
 *
 * <p>{@code VehicleMapper} 的类注释说「作用域是文件名级的性质」，
 * 具体落在三处 {@code WHERE} 上：
 *
 * <pre>
 *   getByIdAndMember        WHERE id = ? AND member_id = ?
 *   update                  WHERE id = ? AND member_id = ?
 *   deleteByIdAndMember     WHERE id = ? AND member_id = ?
 * </pre>
 *
 * <p>★ 这三条断言<b>在单元测试里写不出来</b>：桩类里没有 {@code WHERE}，
 * 它只会「被调用了就返回准备好的那个对象」。真正的守卫是 InnoDB
 * 拿着两个条件去匹配行，只有真库知道匹配结果。
 *
 * <p>★ <b>而且这三条必须分开测，不能只测读。</b>
 * 一个常见的实现是「读的时候带 member_id，写的时候只按 id」——
 * 那样 {@code GET} 是安全的、{@code PUT} 和 {@code DELETE} 是漏的，
 * 而只测 {@code getByIdAndMember} 会全绿。
 *
 * <h2>★★ 二、{@code clearDefaultExcept} 的 {@code id <> #{id}} 单独测</h2>
 *
 * <p>那个排除条件是<b>正确性要求</b>而不是优化：新建第二辆车时，
 * Service 先 insert（此时新行已经 {@code is_default = 1}）再清默认，
 * 没有排除条件的话创建操作会把自己刚设的标记立刻清掉，<b>而且不报错</b>。
 *
 * <p>★ 要真测到它，被排除的那辆<b>本身必须是默认车</b> ——
 * 否则 {@code is_default = 1} 这个条件先把它滤掉了，
 * 测试看起来通过，实际完全没碰到 {@code id <>} 那一半。
 * 所以下面造的是「两辆默认车」这个（不该存在但可以存在的）状态。
 *
 * <h2>★★ 三、排序规则的两条断言（D8）</h2>
 *
 * <p>{@code utf8mb4_0900_ai_ci} 是<b>大小写不敏感</b>的，
 * 这决定了 D8「转大写不是为了唯一性」那整段论证。
 * 这里把它的两个后果都钉住：
 * <ul>
 *   <li>同一个车牌换个大小写 → <b>仍然撞唯一键</b>（大小写不敏感）</li>
 *   <li>同一个车牌加个尾随空格 → <b>不撞</b>（{@code _0900_} 系列是 NO PAD）</li>
 * </ul>
 * 后者正是 {@code VehicleServiceImpl.normalizePlateNo} 里 {@code trim()}
 * 存在的理由，也是「没有任何排序规则改动能救」的那一条。
 */
@SpringBootTest
class VehicleMapperTest {

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试创建的车辆 id */
    private final List<Long> createdVehicleIds = new ArrayList<>();

    /** 本次测试创建的车主 id */
    private final List<Long> createdMemberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdVehicleIds) {
            jdbcTemplate.update("DELETE FROM vehicle WHERE id = ?", id);
        }
        for (Long id : createdMemberIds) {
            jdbcTemplate.update("DELETE FROM member WHERE id = ?", id);
        }
        createdVehicleIds.clear();
        createdMemberIds.clear();
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    /**
     * 造一个专门的车主，返回它的 id
     *
     * <p>★ <b>不用种子车主 1</b>，理由三个：
     * <ol>
     *   <li>它已经有一辆车（{@code 粤BD12345}，默认车），
     *       「第一辆车强制默认」「两辆非默认车」这类用例要在干净的基础上造</li>
     *   <li>「同一个车牌挂到两个车主名下」是本表唯一键的<b>关键边界</b>
     *       （{@code uk_vehicle_member_plate} 是复合键），
     *       需要一个和种子车主不同的 member_id</li>
     *   <li>清理时按 id 删，不会碰到种子数据</li>
     * </ol>
     *
     * <p>★ <b>全项目没有外键</b>，所以这里其实可以直接用任意数字当 member_id。
     * 用真行是为了让测试数据和真数据同形 —— 一个「{@code member_id} 指向不存在的行」
     * 的测试库，会让人对「到底有没有引用完整性」产生错误印象。
     */
    private Long newMember() {
        String phone = "15" + String.format("%09d", System.nanoTime() % 1_000_000_000L);
        jdbcTemplate.update(
                "INSERT INTO member (phone, nickname, status, create_time, update_time) "
                        + "VALUES (?, ?, 1, NOW(3), NOW(3))",
                phone, "车辆测试车主");
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM member WHERE phone = ?", Long.class, phone);
        assertNotNull(id);
        createdMemberIds.add(id);
        return id;
    }

    /** 车牌尾部序号：只靠 nanoTime 取模会在同一个时钟刻度内撞号 */
    private static final AtomicInteger PLATE_SEQ = new AtomicInteger();

    /**
     * 造一个车牌号
     *
     * <p>★ 不能只用 {@code nanoTime % 100_000}：Windows 上连续两次调用的
     * {@code nanoTime} 可能落在同一个时钟刻度里，于是同一个测试方法里连造两辆车会拿到
     * 同一个车牌，直接撞 {@code uk_vehicle_member_plate} —— 表现是「偶发红一次、
     * 重跑又好了」，而根因和被测 SQL 毫无关系。加一个进程内自增序号把这条缝堵死。
     */
    private String uniquePlate() {
        return String.format("粤BD%05d%03d",
                System.nanoTime() % 100_000L, PLATE_SEQ.incrementAndGet() % 1000);
    }

    private Vehicle insertVehicle(Long memberId, String plateNo, Integer isDefault) {
        BaseContext.setCurrentId(memberId);
        Vehicle vehicle = Vehicle.builder()
                .memberId(memberId)
                .plateNo(plateNo)
                .plateColor(3)
                .brand("比亚迪 汉 EV")
                .model("2026 款")
                .batteryCapacityKwh(new BigDecimal("85.00"))
                .isDefault(isDefault)
                .build();
        vehicleMapper.insert(vehicle);
        assertNotNull(vehicle.getId(), "insert 后 id 应被回填，否则 useGeneratedKeys 没生效");
        createdVehicleIds.add(vehicle.getId());
        return vehicle;
    }

    // ==================== ★★ 一、水平越权 ====================

    @Test
    @DisplayName("★★ 读：别人的车辆 id 查不到（不是「查到了但没权限」，是根本查不到）")
    void getByIdAndMemberShouldNotLeakOtherMembersVehicle() {
        Long owner = newMember();
        Long stranger = newMember();
        Vehicle mine = insertVehicle(owner, uniquePlate(), 1);

        // ---- 1) 自己的车，两个条件都对得上 → 查得到 ----
        Vehicle found = vehicleMapper.getByIdAndMember(mine.getId(), owner);
        assertNotNull(found);
        assertEquals(mine.getPlateNo(), found.getPlateNo());

        // ---- 2) ★★ id 对、member_id 换成别人 → null ----
        //   注意返回的是 null，而不是「一条 member_id 是别人的行」。
        //   后者才是漏洞：Service 拿到它就会把别人的车牌返回给攻击者。
        assertNull(vehicleMapper.getByIdAndMember(mine.getId(), stranger),
                "★★ 用另一个车主的身份查到了这辆车。这条 SQL 的 WHERE 必须有两个条件，"
                        + "只有 id 的话就是一个水平越权漏洞（A 能读 B 的车）");

        // ---- 3) id 和 member_id 组合不存在 → null（正常的「查不到」） ----
        assertNull(vehicleMapper.getByIdAndMember(-1L, owner));
    }

    @Test
    @DisplayName("★★ 改：拿别人的 member_id 改不动这辆车（第二层防线是静默的）")
    void updateShouldNotBeAbleToTouchOtherMembersVehicle() {
        Long owner = newMember();
        Long stranger = newMember();
        Vehicle mine = insertVehicle(owner, uniquePlate(), 1);

        // ★ 故意把车牌改成「攻击者想要的值」，同时把 memberId 传成别人。
        //   这正是「只按 id 更新」的实现会放行的请求。
        Vehicle attack = Vehicle.builder()
                .id(mine.getId())
                .memberId(stranger)
                .plateNo("粤BD00000")
                .brand("被改掉的品牌")
                .build();

        // ★★ 注意这里【没有】影响行数可以断言：VehicleMapper.update 返回 void。
        //
        //   这不是疏忽，但它的后果值得写清楚 —— 和 deleteByIdAndMember 对比：
        //
        //     deleteByIdAndMember  → int，0 表示「不存在或不是你的」
        //     markDefault          → int，0 表示「不存在或不是你的」
        //     update               → void，★ 没有信号
        //
        //   ★ 所以 update 这一层的越权防护是【静默的】：SQL 的 WHERE 带
        //     member_id 保证「改不到」，但即使影响行数是 0，调用方也看不见。
        //     真正让接口报出「车辆不存在」的是 VehicleServiceImpl 在调用前
        //     执行的 requireMine(id, memberId) —— 那是一次带两个条件的 SELECT。
        //
        //   两层都在（先查归属 + WHERE 带作用域），但只有第一层会说话。
        //   ★ 下面这些断言测的是第二层：就算第一层被绕过、就算有人直接调
        //     Mapper，这辆车也改不动。
        vehicleMapper.update(attack);

        Vehicle reloaded = vehicleMapper.getByIdAndMember(mine.getId(), owner);
        assertNotNull(reloaded);
        assertEquals(mine.getPlateNo(), reloaded.getPlateNo(),
                "★★ 车牌被别人改掉了 —— 这条 SQL 的 WHERE 必须同时带 id 和 member_id");
        assertEquals("比亚迪 汉 EV", reloaded.getBrand(), "品牌被别人改掉了");

        // ---- 对照：用自己的 member_id 改，能改成功 ----
        //   ★ 这个对照组是必要的：没有它的话，上面两条断言在一个
        //     「update 完全不工作」的实现下也会通过。
        vehicleMapper.update(Vehicle.builder()
                .id(mine.getId())
                .memberId(owner)
                .brand("改成功了的品牌")
                .build());
        assertEquals("改成功了的品牌",
                vehicleMapper.getByIdAndMember(mine.getId(), owner).getBrand(),
                "用自己的身份也改不动的话，说明这条 UPDATE 根本没生效，"
                        + "那上面「改不动」的结论就没有意义了");
    }

    @Test
    @DisplayName("★★ 删：拿别人的 member_id 删不掉这辆车，而且影响行数是 0")
    void deleteShouldNotBeAbleToRemoveOtherMembersVehicle() {
        Long owner = newMember();
        Long stranger = newMember();
        Vehicle mine = insertVehicle(owner, uniquePlate(), 1);

        // ★★ 这是三条里后果最严重的一条：读是泄露、写是篡改、删是【不可逆的数据丢失】。
        assertEquals(0, vehicleMapper.deleteByIdAndMember(mine.getId(), stranger),
                "★★ 影响行数应该是 0，否则 A 可以删掉 B 的车");

        // ★ 行还在，而且是原样的
        assertNotNull(vehicleMapper.getByIdAndMember(mine.getId(), owner),
                "别人的车被删掉了 —— 这条 SQL 的 WHERE 必须同时带 id 和 member_id");

        // ---- 对照：用自己的 member_id 删，能删成功 ----
        assertEquals(1, vehicleMapper.deleteByIdAndMember(mine.getId(), owner));
        assertNull(vehicleMapper.getByIdAndMember(mine.getId(), owner));
        // ★ 再删一次返回 0 —— 和「不是你的」返回的是同一个值。
        //   这是刻意的：两种情况对外必须是同一个回答，
        //   分开会免费告诉攻击者「这个 id 存在，只是不属于你」。
        assertEquals(0, vehicleMapper.deleteByIdAndMember(mine.getId(), owner));
    }

    // ==================== ★★ 二、is_default 的两条条件更新 ====================

    @Test
    @DisplayName("★★ clearDefaultExcept：被排除的那辆【自己】必须留着默认标记")
    void clearDefaultExceptShouldNotClearTheExcludedVehicle() {
        Long member = newMember();
        Vehicle first = insertVehicle(member, uniquePlate(), 1);
        Vehicle second = insertVehicle(member, uniquePlate(), 1);

        // ★ 上面造出了「两辆默认车」—— 一个本不该存在、但确实可以被造出来的状态
        //   （手工 SQL 就能做到，而且没有唯一索引能拦它，见 Vehicle 类注释第一节）。
        //   用它来测是有意的：只有当被排除的那辆本身就是 is_default = 1 时，
        //   才真的碰到了 `id <> #{id}` 那一半。两辆都非默认的话，
        //   `is_default = 1` 会先把它们滤掉，测试看着通过但什么也没测到。
        assertEquals(1, first.getIsDefault());
        assertEquals(1, second.getIsDefault());

        BaseContext.setCurrentId(member);

        // ---- 1) 排除 second：second 应保持 1，first 应被清 0 ----
        int affected = vehicleMapper.clearDefaultExcept(second);
        assertEquals(1, affected,
                "应该正好清掉一辆（first）—— ★ 返回 0 也不是错误，"
                        + "只有这个值【携带含义】的判断在 markDefault 那边");

        assertEquals(1, vehicleMapper.getByIdAndMember(second.getId(), member).getIsDefault(),
                "★★ 被排除的那辆自己的默认标记被清掉了。这正是 `id <> #{id}` 要防的事 ——"
                        + "在真实的 create 路径上，那一刻新行【已经】是 is_default = 1，"
                        + "没有这个排除条件，创建操作会把自己刚设的标记立刻清掉，"
                        + "接口还是返回成功（换一辆车来试，第二辆车永远成不了默认车）");
        assertEquals(0, vehicleMapper.getByIdAndMember(first.getId(), member).getIsDefault(),
                "另一辆应该被清成非默认");

        // ---- 2) 幂等：再清一次没有东西可清，返回 0 ----
        assertEquals(0, vehicleMapper.clearDefaultExcept(second),
                "该车主已经没有别的默认车了，返回 0 是正常情况，不是错误");

        // ---- 3) ★★ 排除一辆【非默认】车 → 它会清掉那辆真正的默认车 ----
        //
        //   ★ 这一条断言记录的是一个【尖角】，不是正确行为。
        //   第一版这里写的是 assertEquals(0, ...)，跑起来是 1 —— 是我的断言错了。
        //
        //   原因：排除条件的名字叫 clearDefaultExcept，但它的排除是【按 id】的，
        //   不是按「哪辆是默认车」：
        //       WHERE member_id = ? AND is_default = 1 AND id <> #{id}
        //   传一辆非默认车进来时，这个 WHERE 对「真正的那辆默认车」
        //   完全没有保护作用 —— 它匹配上、被清成 0。
        //
        //   ★ 后果：该车主变成「有车但一辆默认车都没有」。
        //   而读侧约定是「取列表第一条当默认车」（见 VehicleVO 类注释第一节），
        //   所以这个状态【不报错、不抛异常】，只是前端下单页
        //   默认选中的车辆空了。
        //
        //   ★ 在 Service 层这个尖角够不着 —— clearDefaultExcept 只在两处被调，
        //     两处传的都是「即将成为默认车的那辆」：
        //       setDefault → target（要置为默认的那辆）
        //       create     → 刚 insert 的那辆（is_default 已经是 1）
        //     而 delete 走的是 promoteFirstRemaining → markDefault
        //     （车已经删了，没有旧标记要清），压根不需要这个方法。
        //
        //   把这条断言写下来，是为了让未来那个「想复用 clearDefaultExcept
        //   做点别的」的人先看到它会清掉什么。
        assertEquals(1, vehicleMapper.clearDefaultExcept(first),
                "★ 用一辆非默认车去调，它会清掉那辆真正的默认车 —— 排除是按 id 的，"
                        + "不是「非默认车碰不到默认车」");
        assertEquals(0, vehicleMapper.getByIdAndMember(second.getId(), member).getIsDefault(),
                "★★ 此时该车主一辆默认车都没有了。这个状态是【静默】的："
                        + "读侧「取第一条」不会报错，只是拿不到默认车");

        // ★ 而这个状态是可以自愈的 —— 再对任意一辆调一次「设为默认」即可。
        //   这也是 VehicleMapper 类注释第二节最后说的「修复路径」。
        vehicleMapper.markDefault(second);
        assertEquals(1, vehicleMapper.getByIdAndMember(second.getId(), member).getIsDefault());
    }

    @Test
    @DisplayName("★★ markDefault：归属校验就是写入条件本身（传别人的 member_id 改不动）")
    void markDefaultShouldRequireOwnership() {
        Long owner = newMember();
        Long stranger = newMember();
        Vehicle mine = insertVehicle(owner, uniquePlate(), 0);

        // ---- 1) ★ 传别人的 member_id → 0 行，车还是非默认 ----
        assertEquals(0, vehicleMapper.markDefault(Vehicle.builder()
                        .id(mine.getId())
                        .memberId(stranger)
                        .build()),
                "★★ 影响行数应该是 0 —— 「是不是你的车」就是写入条件本身，"
                        + "做成「先查归属再 WHERE id=?」会重新打开 TOCTOU 窗口，"
                        + "还会把安全检查挪到一个未来的调用方可能忘记加的位置");
        assertEquals(0, vehicleMapper.getByIdAndMember(mine.getId(), owner).getIsDefault());

        // ---- 2) 用自己的 member_id → 1 行，置为默认 ----
        BaseContext.setCurrentId(owner);
        assertEquals(1, vehicleMapper.markDefault(Vehicle.builder()
                .id(mine.getId())
                .memberId(owner)
                .build()));
        assertEquals(1, vehicleMapper.getByIdAndMember(mine.getId(), owner).getIsDefault());

        // ---- 3) 幂等：已经是默认车时再调一次也成功 ----
        //   ★ 这一条让「设为默认」可以被安全地重复触发，前端不用先判断状态。
        assertEquals(1, vehicleMapper.markDefault(Vehicle.builder()
                .id(mine.getId())
                .memberId(owner)
                .build()));
        assertEquals(1, vehicleMapper.getByIdAndMember(mine.getId(), owner).getIsDefault());
    }

    @Test
    @DisplayName("★ 「设为默认」的组合：先清旧再设新，最后恰好一辆默认车")
    void clearThenMarkShouldLeaveExactlyOneDefault() {
        Long member = newMember();
        Vehicle first = insertVehicle(member, uniquePlate(), 1);
        Vehicle second = insertVehicle(member, uniquePlate(), 0);

        BaseContext.setCurrentId(member);

        // ★ 这正是 VehicleServiceImpl.setDefault 的两步，顺序不能反。
        //   反过来（先设新再清旧）会把刚设的那辆清掉 ——
        //   而两辆默认车的时候 clearDefaultExcept 会连它一起清。
        vehicleMapper.clearDefaultExcept(second);
        vehicleMapper.markDefault(second);

        assertEquals(0, vehicleMapper.getByIdAndMember(first.getId(), member).getIsDefault(),
                "旧的默认车应该被清掉");
        assertEquals(1, vehicleMapper.getByIdAndMember(second.getId(), member).getIsDefault(),
                "新的默认车应该是 second");

        // ★ 用 listByMemberId 数一遍 —— 这是「恰好一辆默认车」这个不变量的
        //   最终观测方式，也是前端看到的那个列表。
        List<Vehicle> all = vehicleMapper.listByMemberId(member);
        assertEquals(2, all.size());
        assertEquals(1, all.stream().filter(v -> Integer.valueOf(1).equals(v.getIsDefault())).count(),
                "★★ 必须恰好一辆默认车。两辆的话前端列表里会出现两个「默认」标签，"
                        + "零辆的话「取第一条当默认车」会拿错");
    }

    // ==================== 读侧的排序约定 ====================

    @Test
    @DisplayName("★ listByMemberId：默认车在第一条，其余按 id 倒序（新车在前）")
    void listByMemberIdShouldPutDefaultFirstThenNewestFirst() {
        Long member = newMember();
        Vehicle oldest = insertVehicle(member, uniquePlate(), 0);
        Vehicle middle = insertVehicle(member, uniquePlate(), 0);
        Vehicle newest = insertVehicle(member, uniquePlate(), 0);

        // 最老的那辆设为默认 —— 这样「默认车排最前」和「id 倒序」
        // 两个规则会给出不同的答案，才能测出到底是哪个在起作用。
        BaseContext.setCurrentId(member);
        vehicleMapper.clearDefaultExcept(oldest);
        vehicleMapper.markDefault(oldest);

        List<Vehicle> all = vehicleMapper.listByMemberId(member);

        assertEquals(3, all.size());
        assertEquals(List.of(oldest.getId(), newest.getId(), middle.getId()),
                all.stream().map(Vehicle::getId).toList(),
                "★★ 期望顺序是 [默认车, 最新的, 中间的]。"
                        + "如果这里是纯 id 倒序，说明 is_default DESC 那一半没生效；"
                        + "★ 而如果默认车不是最老的那辆，两个规则恰好给出同一个答案，"
                        + "这条测试就测不出任何东西了");

        // ★ 读侧约定：需要默认车时取第一条，不要写 WHERE is_default = 1 再期望一行。
        assertEquals(1, all.get(0).getIsDefault());
    }

    @Test
    @DisplayName("★ countByMemberId：用来判断「这是不是他的第一辆车」")
    void countByMemberIdShouldCountOnlyThatMembersVehicles() {
        Long member = newMember();
        Long other = newMember();

        assertEquals(0, vehicleMapper.countByMemberId(member),
                "新车主一辆车都没有 —— 这正是 VehicleServiceImpl.create 里"
                        + "「第一辆车强制默认」那个判断的输入");

        insertVehicle(member, uniquePlate(), 1);
        insertVehicle(other, uniquePlate(), 1);
        insertVehicle(member, uniquePlate(), 0);

        assertEquals(2, vehicleMapper.countByMemberId(member),
                "★ 数的是【这个车主的】车辆数，别的车主的不能算进来 —— "
                        + "算错的话，第二辆车会被当成第一辆、强行变成默认车");
        assertEquals(1, vehicleMapper.countByMemberId(other));
    }

    // ==================== 唯一约束与排序规则 ====================

    @Test
    @DisplayName("★ 同一车主重复车牌 → DuplicateKeyException")
    void duplicatePlateShouldThrowDuplicateKeyException() {
        Long member = newMember();
        String plate = uniquePlate();
        insertVehicle(member, plate, 1);

        // ★ 断言具体的异常类型：GlobalExceptionHandler 的正则
        //   `Duplicate entry '(.*?)' for key` 靠它翻译成「xxx已存在」。
        DuplicateKeyException ex = assertThrows(DuplicateKeyException.class,
                () -> insertVehicle(member, plate, 0),
                "同一车主加两次同一车牌应该抛 DuplicateKeyException");
        assertTrue(ex.getMessage().contains("uk_vehicle_member_plate"),
                "message 里应该含唯一键名，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("★★ D8 回归：唯一键是【复合】的 —— 同一车牌挂到两个车主名下是合法的")
    void samePlateUnderDifferentMembersShouldBeAllowed() {
        Long memberA = newMember();
        Long memberB = newMember();
        String plate = uniquePlate();

        Vehicle a = insertVehicle(memberA, plate, 1);
        // ★ 这一条必须成功。唯一键是 uk_vehicle_member_plate (member_id, plate_no)，
        //   不是 uk_vehicle_plate —— 车牌本身【不是】全局唯一的。
        Vehicle b = insertVehicle(memberB, plate, 1);

        assertNotNull(a.getId());
        assertNotNull(b.getId());
        assertEquals(plate, vehicleMapper.getByIdAndMember(b.getId(), memberB).getPlateNo());

        // ★ 顺带说明为什么 DDL 上那条单独的 idx_vehicle_plate 是必要的：
        //   「客服按车牌反查车主」这个场景没有 member_id 可用，
        //   走不了上面那个复合唯一键的最左前缀。
        List<Long> owners = jdbcTemplate.queryForList(
                "SELECT member_id FROM vehicle WHERE plate_no = ? ORDER BY member_id",
                Long.class, plate);
        assertEquals(2, owners.size(), "按车牌反查应该能查到两个车主 —— 这正是 idx_vehicle_plate 的用途");
    }

    @Test
    @DisplayName("★★ D8：同一车牌换大小写仍然撞唯一键（排序规则大小写不敏感）")
    void samePlateInDifferentCaseShouldStillCollide() {
        Long member = newMember();
        // ★ 一个含字母、且字母是小写的车牌 —— 大小写差异必须真的存在，
        //   否则这条测试什么也没测到。
        String lower = "粤BDa" + (System.nanoTime() % 10_000L);
        insertVehicle(member, lower, 1);

        // ★★ 这条断言钉住了 D8 的整个论证前提：
        //   utf8mb4_0900_ai_ci 是【大小写不敏感】的，所以唯一索引本来就把
        //   '粤BDa1234' 和 '粤bda1234' 当成同一个值。
        //   ★ 所以 normalizePlateNo 里那个 toUpperCase() 【不是】为了唯一性 ——
        //     它做的是「展示统一」，以及「让这条不变量在列排序规则被改成
        //     utf8mb4_bin 之后依然成立」。
        //   ★ 哪天有人把列排序规则改成 _bin/_as_cs「为了更精确」，
        //     这条测试会失败，而失败信息会把他引到 D8 那段论证上。
        String upper = lower.toUpperCase(java.util.Locale.ROOT);
        assertNotEquals(upper, lower, "前置条件：这个车牌的大小写形式必须不同，否则测不到东西");

        DuplicateKeyException ex = assertThrows(DuplicateKeyException.class,
                () -> insertVehicle(member, upper, 0),
                "★★ 大小写不同的同一个车牌应该撞唯一键 —— 排序规则 utf8mb4_0900_ai_ci "
                        + "是大小写不敏感的。这条挂了说明列的排序规则被改成了 _bin/_as_cs，"
                        + "那会【静默地】改变唯一约束和「按车牌反查」两个行为，见 D8");
        assertTrue(ex.getMessage().contains("uk_vehicle_member_plate"));
    }

    @Test
    @DisplayName("★★ D8：尾随空格【不】撞唯一键 —— 这就是 normalizePlateNo 里 trim() 的理由")
    void plateWithTrailingSpaceShouldNOTCollide() {
        Long member = newMember();
        String plate = uniquePlate();
        insertVehicle(member, plate, 1);

        // ★★ 这条测试断言的是一个【坏消息】，而且必须把它固定下来：
        //   utf8mb4_0900_ai_ci 是 NO PAD 的 —— '粤BD1234 ' 和 '粤BD1234'
        //   是【两个不同的值】，唯一索引拦不住它。
        //
        //   ★ 所以「手机键盘带出一个尾随空格」会让同一个车牌在同一个车主名下
        //     存成两行。这一条【没有任何排序规则改动能救】——
        //     要修只能在写入侧 trim，那就是 VehicleServiceImpl.normalizePlateNo。
        //
        //   断言「它会成功插入」而不是「它应该失败」，是因为后者是做不到的事；
        //   把做不到的事写成一条会失败的测试，只会让人把测试删掉。
        //   而把它固定成「尾随空格确实能绕过唯一索引」，就给了 trim() 一个
        //   可执行的存在的理由 —— 哪天有人觉得 trim 是多余的顺手删了，
        //   这条测试仍然绿，但它的注释和 normalizePlateNo 的测试会指出问题。
        Vehicle sneaky = insertVehicle(member, plate + " ", 0);
        assertNotNull(sneaky.getId());

        // ★ 库里现在有两行「看起来一样」的车牌 —— 这正是那个坑的样子
        List<String> plates = jdbcTemplate.queryForList(
                "SELECT plate_no FROM vehicle WHERE member_id = ? ORDER BY id",
                String.class, member);
        assertEquals(2, plates.size(),
                "★★ 尾随空格的车牌被唯一索引放行了（NO PAD 排序规则）。"
                        + "如果你把列排序规则改成了 PAD SPACE 的，这条会失败 —— "
                        + "那说明 trim() 的必要性变了，请一并更新 D8 和 normalizePlateNo 的注释");
        assertEquals(plate, plates.get(0));
        assertEquals(plate + " ", plates.get(1));

        // ★ 而规范化之后它们就是同一个值了
        assertEquals(plate, (plate + " ").trim(),
                "★ normalizePlateNo 的 trim() 做的正是这件事 —— "
                        + "在唯一的写入路径上把尾随空格去掉，"
                        + "让上面那个「两行看起来一样」的状态不可能出现");
    }

    @Test
    @DisplayName("★ insert：漏传 is_default 时【报错】而不是用 DDL 的 DEFAULT 0")
    void insertWithoutIsDefaultShouldFailLoudly() {
        Long member = newMember();
        BaseContext.setCurrentId(member);

        // ★ is_default tinyint NOT NULL DEFAULT 0，看上去「不传就是非默认」。
        //   但 insert 是全列插入，列名在语句里 → 传 null 就是往 NOT NULL 列
        //   显式写 NULL → errno 1048。
        //   生产路径上这个值由 VehicleDTO.DEFAULT_IS_DEFAULT 提供，
        //   Service 再兜一次（字段初始值挡不住显式的 JSON null），
        //   所以这个分支到不了 —— 固定成「一旦漏传当场报错」。
        assertThrows(DataIntegrityViolationException.class,
                () -> vehicleMapper.insert(Vehicle.builder()
                        .memberId(member)
                        .plateNo(uniquePlate())
                        .build()),
                "全列插入时传 null 应该撞 NOT NULL 约束，而不是静默使用 DDL 默认值");
    }

    @Test
    @DisplayName("★ 可空字段往返：没填的保持 null，不是空字符串")
    void nullableColumnsShouldRoundTripAsNull() {
        Long member = newMember();
        BaseContext.setCurrentId(member);

        Vehicle minimal = Vehicle.builder()
                .memberId(member)
                .plateNo(uniquePlate())
                .isDefault(0)
                .build();
        vehicleMapper.insert(minimal);
        createdVehicleIds.add(minimal.getId());

        Vehicle reloaded = vehicleMapper.getByIdAndMember(minimal.getId(), member);
        assertNotNull(reloaded);
        // ★ null 和空串在 MySQL 里是不同的东西，混起来之后
        //   「这个字段是没填还是被清空了」就没法回答了。
        assertNull(reloaded.getPlateColor(), "plate_color 是 1蓝2黄3绿4渐变绿，可空 —— 没填就是 null");
        assertNull(reloaded.getBrand());
        assertNull(reloaded.getModel());
        assertNull(reloaded.getBatteryCapacityKwh(),
                "★ 电池容量是可空的：它的用途只是「估算充满需要多少电」，"
                        + "缺了只是估算不出来，不影响任何金额");
        assertNull(reloaded.getRemark());

        // ★ 而 is_default 是 NOT NULL 的，显式传了 0
        assertEquals(0, reloaded.getIsDefault());

        // ★ BigDecimal 用 compareTo 不用 equals —— 理由见 StationMapperTest。
        Vehicle full = insertVehicle(member, uniquePlate(), 0);
        assertEquals(0, new BigDecimal("85.00")
                .compareTo(vehicleMapper.getByIdAndMember(full.getId(), member).getBatteryCapacityKwh()));
    }

    // ==================== 删除：刻意没有守卫 ====================

    @Test
    @DisplayName("★★ 删除【没有】守卫，而且 P1c 也不该加 —— 这是 DDL 明说的")
    void deleteShouldHaveNoGuard() {
        Long member = newMember();
        Vehicle vehicle = insertVehicle(member, uniquePlate(), 1);

        // ★★ 这条测试断言的是「一个【不存在】的检查」。
        //   看起来反直觉，但它是 VehicleMapper 类注释第四节的可执行版本：
        //   charging_order.plate_no 的列注释写着
        //   「车牌快照：车辆可被删除/改牌，订单必须保留当时值」——
        //   ★ 那张快照列存在的【理由】就是允许车辆被删。
        //
        //   所以「有订单就不能删」这个守卫加在这里会直接和 DDL 的意图冲突，
        //   还会让「卖车之后把车从列表里去掉」变得不可能
        //   （vehicle 没有 status 列，硬删是唯一手段）。
        //   「车被删了」的检查属于【下单路径】，不属于删除路径。
        //
        //   ★ 这里没有办法真的造一张订单（P1c-3 才有），所以这条测试
        //     实际断言的是「删除不查任何别的表」—— 它照样能挡住
        //     「顺手加一个 assertDeletable」的改动，因为那需要注入
        //     一个新的依赖，而这条测试会因为它多查了一张表而失败。
        BaseContext.setCurrentId(member);
        assertEquals(1, vehicleMapper.deleteByIdAndMember(vehicle.getId(), member),
                "★ 车辆删除必须能成功。删不掉的话，车主卖掉旧车之后"
                        + "没有任何办法把它从「我的车辆」里去掉");

        assertNull(vehicleMapper.getByIdAndMember(vehicle.getId(), member));
    }
}
