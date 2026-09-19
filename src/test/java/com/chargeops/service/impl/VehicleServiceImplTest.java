package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.dto.VehicleDTO;
import com.chargeops.dto.VehicleUpdateDTO;
import com.chargeops.entity.Vehicle;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.UserNotLoginException;
import com.chargeops.mapper.VehicleMapper;
import com.chargeops.vo.VehicleVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code VehicleServiceImpl} 的测试
 *
 * <h2>★★ 一、这个类用手写的<b>内存假库</b>，不是记录调用的桩</h2>
 *
 * <p>{@code MemberServiceImplTest} / {@code ChargePackageServiceImplTest}
 * 用的是「桩 + 断言调用参数」那一套。这里不用，因为本类要验的东西
 * <b>不是某一次调用的参数，而是一串操作之后的状态</b>：
 *
 * <pre>
 *   有车的车主，一定有且只有一辆默认车
 * </pre>
 *
 * <p>这条不变量在 {@code create} / {@code delete} / {@code setDefault}
 * 三条路径上各自维护一半，而且<b>只有连起来看才成立</b> ——
 * 单独看任何一次调用都是对的。所以假库必须<b>记住状态</b>。
 * 见下面的 {@link InMemoryVehicleMapper}。
 *
 * <h2>★★ 二、假库的保真度是这份测试的<b>全部风险</b>，所以逐条对齐真 SQL</h2>
 *
 * <p>一个写得比真库宽松的假库，会让测试通过而生产失败 ——
 * 那比没有测试更糟。所以假库的每一条语句都对着
 * {@code VehicleMapper.xml} 抄了它真正承重的部分：
 *
 * <table border="1">
 *   <caption>假库 vs 真 SQL</caption>
 *   <tr><th>真 SQL 里承重的东西</th><th>假库里的对应实现</th></tr>
 *   <tr><td>{@code WHERE id = ? AND member_id = ?}
 *       （每一条写语句都带归属）</td>
 *       <td>{@link InMemoryVehicleMapper#markDefault} /
 *           {@link InMemoryVehicleMapper#update} /
 *           {@link InMemoryVehicleMapper#deleteByIdAndMember}
 *           都先比 {@code memberId}，不匹配就当「0 行」</td></tr>
 *   <tr><td>{@code id <> #{id}}（{@code clearDefaultExcept} 的排除条件）</td>
 *       <td>{@link InMemoryVehicleMapper#clearDefaultExcept} 跳过自己</td></tr>
 *   <tr><td>{@code is_default} 是 {@code NOT NULL} 且全列插入 → 传 null 是 errno 1048</td>
 *       <td>{@link InMemoryVehicleMapper#insert} 抛
 *           {@code DataIntegrityViolationException}</td></tr>
 *   <tr><td>{@code <set> + <if>} 只写非空字段（{@code plateNo} 还判了 {@code != ''}）</td>
 *       <td>{@link InMemoryVehicleMapper#update} 逐个字段判</td></tr>
 *   <tr><td>{@code ORDER BY is_default DESC, id DESC}</td>
 *       <td>{@link InMemoryVehicleMapper#listByMemberId} 同一个比较器</td></tr>
 * </table>
 *
 * <p>★ <b>反过来，这个假库【不】做的事也要说清楚</b>：
 * <ul>
 *   <li><b>不填审计列</b>。{@code @AutoFill} 是 MyBatis 插件，
 *       在不启动 Spring 的单测里根本不存在。
 *       审计四列由 {@code MemberMapperTest}/{@code VehicleMapperTest}
 *       那一组集成测试对着真库断言</li>
 *   <li><b>不模拟唯一索引 {@code uk_vehicle_member_plate}</b>。
 *       重复车牌要撞键，那是真库的事（{@code VehicleMapperTest} 已覆盖），
 *       在假库里再造一个唯一约束只会变成「测我自己的假库」</li>
 * </ul>
 *
 * <h2>★ 三、为什么「越权」在这个类里测不出新东西，但还是要测</h2>
 *
 * <p>它已经在 {@code VehicleMapperTest} 对着真库测过了。
 * 这里再测一遍的理由只有一个，而且足够：<b>断言的是【另一层】</b>。
 * Mapper 那层证明「写不进别人的车」，Service 这层证明
 * 「读不到就翻译成一句合适的错误，而且<b>根本没往下走</b>」——
 * 后者是 {@code requireMine} 的职责，它要是哪天被删掉，
 * mapper 层的测试全绿，而接口会返回「成功」+ 改 0 行。
 */
@DisplayName("VehicleServiceImpl 默认车不变量与归属隔离")
class VehicleServiceImplTest {

    private static final long MEMBER_ID = 7L;
    private static final long OTHER_MEMBER_ID = 8L;

    private final InMemoryVehicleMapper vehicleMapper = new InMemoryVehicleMapper();

    private final VehicleServiceImpl service = new VehicleServiceImpl(vehicleMapper);

    @BeforeEach
    void login() {
        BaseContext.setCurrentId(MEMBER_ID);
    }

    @AfterEach
    void cleanUp() {
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    /** 测试夹具：直接往假库里放一行，绕过 Service（等价于集成测试里的 JdbcTemplate） */
    private Vehicle seed(long memberId, String plateNo, int isDefault) {
        return vehicleMapper.seed(memberId, plateNo, isDefault);
    }

    private VehicleDTO createDto(String plateNo, Integer isDefault) {
        VehicleDTO dto = new VehicleDTO();
        dto.setPlateNo(plateNo);
        dto.setPlateColor(1);
        dto.setBrand("比亚迪");
        dto.setModel("汉 EV");
        dto.setBatteryCapacityKwh(new BigDecimal("85.00"));
        dto.setIsDefault(isDefault);
        return dto;
    }

    /** ★ 这条不变量是本类存在的理由 —— 单独抽出来，让每条测试都能一行断言它 */
    private void assertExactlyOneDefaultFor(long memberId) {
        List<Vehicle> mine = vehicleMapper.rawRowsOf(memberId);
        if (mine.isEmpty()) {
            return;   // 没有车时前提不成立，见 VehicleServiceImpl.promoteFirstRemaining
        }
        assertThat(mine.stream().filter(v -> Integer.valueOf(1).equals(v.getIsDefault())).toList())
                .as("车主 %d 有 %d 辆车，其中默认车", memberId, mine.size())
                .hasSize(1);
    }

    // ==================== ★★ 未登录 ====================

    @Test
    @DisplayName("★★ 六个方法的第一件事都是 requireCurrentMemberId —— 未登录时【一行数据都没碰】")
    void everyMethodShouldRefuseBeforeTouchingTheDatabase() {
        BaseContext.removeCurrentId();

        // ★ 逐个方法验，不能只验一个。这个类的设计原则是
        //   「每个方法的第一件事都是 requireCurrentMemberId()」，
        //   而漏掉某一个的症状是：那个方法会带着 memberId = null 往下走，
        //   变成 WHERE member_id = NULL —— 一个【永远匹配不到行】的查询
        //   （SQL 里 = NULL 恒为假）。表现是「加车永远失败、列表永远为空」，
        //   而且不报错。所以这里逐个钉死。
        assertThatThrownBy(() -> service.listMine())
                .isInstanceOf(UserNotLoginException.class);
        assertThatThrownBy(() -> service.getMine(1L))
                .isInstanceOf(UserNotLoginException.class);
        assertThatThrownBy(() -> service.create(createDto("粤BD12345", null)))
                .isInstanceOf(UserNotLoginException.class);
        assertThatThrownBy(() -> service.update(1L, new VehicleUpdateDTO()))
                .isInstanceOf(UserNotLoginException.class);
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(UserNotLoginException.class);
        assertThatThrownBy(() -> service.setDefault(1L))
                .isInstanceOf(UserNotLoginException.class);

        // ★★ 「第一件事」的证据：mapper 一次都没被调用过。
        //   只断言抛了 UserNotLoginException 是不够的 ——
        //   一个「先查库、再判登录」的实现也能抛对异常，
        //   但它已经把 null 传进 SQL 了。
        assertThat(vehicleMapper.touchCount).isZero();
    }

    // ==================== ★★ 归属隔离 ====================

    @Test
    @DisplayName("★★ 「不存在」和「不是你的」返回【完全相同】的一句话")
    void getMineShouldNotDistinguishMissingFromSomeoneElses() {
        seed(MEMBER_ID, "粤BD00001", 1);
        Vehicle theirs = seed(OTHER_MEMBER_ID, "粤BD00002", 1);
        long ghostId = 999_999L;

        String forGhost = catchMessageOf(() -> service.getMine(ghostId));
        String forTheirs = catchMessageOf(() -> service.getMine(theirs.getId()));

        // ★★ 断言的不是「两句都是车辆不存在」，而是「两句一模一样」。
        //   分开提示会变成「这个 id 存在，只是不属于你」——
        //   那等于免费告诉攻击者哪些 id 是有效的，
        //   他可以拿一个 id 从 1 数到 10000，把系统里所有有效车辆 id 标出来。
        //   写成 isEqualTo(MessageConstant.VEHICLE_NOT_FOUND) 只钉住了一半：
        //   它挡不住「别人的车给一句更详细的提示」这种改动。
        assertThat(forGhost)
                .isEqualTo(forTheirs)
                .isEqualTo(MessageConstant.VEHICLE_NOT_FOUND);
    }

    @Test
    @DisplayName("★★ 改 / 删 / 设为默认 别人的车：抛「不存在」，而且【一行都没写】")
    void writingAnotherMembersVehicleShouldFailWithoutWritingAnything() {
        Vehicle theirs = seed(OTHER_MEMBER_ID, "粤BD00002", 1);
        Vehicle mine = seed(MEMBER_ID, "粤BD00001", 1);

        VehicleUpdateDTO dto = new VehicleUpdateDTO();
        dto.setBrand("改成别人的牌子");   // ★ 故意传一个非空字段，确认它没被写下去

        assertThatThrownBy(() -> service.update(theirs.getId(), dto))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(MessageConstant.VEHICLE_NOT_FOUND);
        assertThatThrownBy(() -> service.delete(theirs.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(MessageConstant.VEHICLE_NOT_FOUND);
        assertThatThrownBy(() -> service.setDefault(theirs.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(MessageConstant.VEHICLE_NOT_FOUND);

        // ★★ 三条断言，缺一不可：
        //   1. 别人的车原封不动 —— 归属校验不只是「返回 404」，是真的没写
        assertThat(vehicleMapper.rawRow(theirs.getId()).getBrand()).isNull();
        //   2. 别人的车还在 —— delete 那次没有真的删掉
        assertThat(vehicleMapper.rawRow(theirs.getId())).isNotNull();
        //   3. 我自己的默认车也没被顺手清掉 ——
        //      setDefault 的第一步是 clearDefaultExcept，
        //      如果归属校验被删掉，它会先把【我】的默认车清了，再去改别人的
        assertThat(vehicleMapper.rawRow(mine.getId()).getIsDefault()).isEqualTo(1);
        assertExactlyOneDefaultFor(MEMBER_ID);
    }

    // ==================== ★★ create ====================

    @Test
    @DisplayName("★★ 第一辆车强制为默认车 —— 即使请求明确要求 isDefault = 0")
    void firstVehicleShouldAlwaysBecomeDefault() {
        // ★ 传的是 0，也就是「我不要它当默认车」。
        //   服务端必须忽略这个要求，因为「有车的车主一定有且只有一辆默认车」
        //   这条不变量在 0 辆车 → 1 辆车这一步只能由这里维护。
        service.create(createDto("粤BD12345", 0));

        assertThat(vehicleMapper.rawRowsOf(MEMBER_ID)).singleElement()
                .satisfies(v -> assertThat(v.getIsDefault()).isEqualTo(1));
    }

    @Test
    @DisplayName("★ 第二辆车要求当默认车 → 旧默认车被清掉，仍然恰好一辆默认车")
    void secondVehicleRequestingDefaultShouldTakeOver() {
        Vehicle first = seed(MEMBER_ID, "粤BD00001", 1);

        service.create(createDto("粤BD00002", 1));

        // ★ 这条断言同时覆盖两件事：
        //   · clearDefaultExcept 真的跑了
        //   · 它的 id <> #{id} 排除条件生效了 ——
        //     没有那个条件，它会连【刚插入的这辆】一起清掉
        //     （新行在同一个事务里已经可见，且 is_default 已经是 1），
        //     结果是接口返回成功、用户的车没成为默认车，而且不报错。
        assertThat(vehicleMapper.rawRow(first.getId()).getIsDefault()).isZero();
        assertExactlyOneDefaultFor(MEMBER_ID);
    }

    @Test
    @DisplayName("★★ 第二辆车【显式传 null】的 isDefault 落 0，不是 errno 1048")
    void secondVehicleWithNullIsDefaultShouldFallBackToZero() {
        seed(MEMBER_ID, "粤BD00001", 1);

        // ★ 必须是【第二辆车】：第一辆会走 isFirstVehicle 分支强制置 1，
        //   那条分支会把这里要验的兜底逻辑整个盖住。
        //
        //   json 里写 {"isDefault": null} 时字段真的是 null（不是缺省），
        //   而 VehicleDTO 上那个字段初始值挡不住显式 null。
        //   一路传到 SQL 就是往 NOT NULL 列里写 NULL → errno 1048。
        service.create(createDto("粤BD00002", null));

        assertThat(vehicleMapper.rawRowsOf(MEMBER_ID))
                .filteredOn(v -> "粤BD00002".equals(v.getPlateNo()))
                .singleElement()
                .satisfies(v -> assertThat(v.getIsDefault()).isZero());
    }

    @Test
    @DisplayName("★ 对照：不做兜底的话，插入 null 的 isDefault 会真的炸（假库模拟 errno 1048）")
    void insertingWithoutIsDefaultWouldBlowUpAtTheMapperLevel() {
        // ★ 这是上面那条测试的「响」的那一半 —— 证明兜底不是洁癖。
        //   假库在这一点上对齐真库：vehicle.is_default 是 NOT NULL
        //   且 insert 是全列插入，DDL 的 DEFAULT 0 救不了它。
        assertThatThrownBy(() -> vehicleMapper.insert(
                Vehicle.builder().memberId(MEMBER_ID).plateNo("粤BD00003").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("★★ 归属来自 BaseContext，请求体里【根本没有】memberId 这个字段")
    void ownershipShouldComeFromTheContextNotTheRequest() {
        service.create(createDto("粤BD12345", null));

        assertThat(vehicleMapper.rawRowsOf(MEMBER_ID)).hasSize(1);
        assertThat(vehicleMapper.rawRowsOf(OTHER_MEMBER_ID)).isEmpty();

        // ★★ 这条反射断言不是在炫技，它钉的是 D9 那条设计：
        //   「车主不能改自己的 phone / status —— 用 DTO 缺字段变成编译期事实」。
        //   同一个手法用在这里：归属不是「服务端记得忽略它」，而是
        //   【客户端没有表达方式】。字段一旦被加回 DTO，
        //   BeanUtils.copyProperties(vehicleDTO, vehicle) 这行
        //   会立刻开始把请求里的 memberId 拷进实体 —— 而 create 里
        //   紧接着的 setMemberId(memberId) 才是覆盖它的那一行。
        //   两行代码的先后顺序从此变成安全问题，那正是这条测试要阻止的。
        assertThatThrownBy(() -> VehicleDTO.class.getDeclaredField("memberId"))
                .isInstanceOf(NoSuchFieldException.class);
    }

    // ==================== ★★ setDefault ====================

    @Test
    @DisplayName("★★ 设为默认：先清旧再置新，结果恰好一辆默认车")
    void setDefaultShouldLeaveExactlyOneDefault() {
        seed(MEMBER_ID, "粤BD00001", 1);
        Vehicle target = seed(MEMBER_ID, "粤BD00002", 0);

        service.setDefault(target.getId());

        // ★ 顺序（clearDefaultExcept → markDefault）本身没有单独断言 ——
        //   真 SQL 里 clearDefaultExcept 带 id <> #{id}，两个顺序结果相同。
        //   这里断言的是【结果】，那才是不变量。顺序被写死是为了让人
        //   不必去重新验证那个排除条件是否还在，见 Service 类注释第二节。
        assertThat(vehicleMapper.rawRow(target.getId()).getIsDefault()).isEqualTo(1);
        assertExactlyOneDefaultFor(MEMBER_ID);
    }

    @Test
    @DisplayName("★ 设为默认是幂等的 —— 对已经是默认车的再调一次也成功")
    void setDefaultShouldBeIdempotent() {
        Vehicle only = seed(MEMBER_ID, "粤BD00001", 1);

        service.setDefault(only.getId());

        assertThat(vehicleMapper.rawRow(only.getId()).getIsDefault()).isEqualTo(1);
        assertExactlyOneDefaultFor(MEMBER_ID);
    }

    // ==================== ★★ delete ====================

    @Test
    @DisplayName("★★ 删掉默认车 → 剩下的第一辆被自动提升")
    void deletingTheDefaultShouldPromoteTheNextOne() {
        Vehicle first = seed(MEMBER_ID, "粤BD00001", 1);
        Vehicle second = seed(MEMBER_ID, "粤BD00002", 0);

        service.delete(first.getId());

        // ★ 不提升的话，「有车的车主一定有且只有一辆默认车」就断了，
        //   而 VehicleVO 让前端「取列表第一条」当默认车正是依赖它 ——
        //   断了之后下单页那个默认选中的车辆会变成空的。
        assertThat(vehicleMapper.rawRow(second.getId()).getIsDefault()).isEqualTo(1);
        assertExactlyOneDefaultFor(MEMBER_ID);
    }

    @Test
    @DisplayName("★★ 提升的是【最新】的那辆非默认车，不是最老的 —— 因为它按 id DESC 取第一条")
    void promotionShouldFollowTheSqlOrdering() {
        Vehicle defaultOne = seed(MEMBER_ID, "粤BD00001", 1);
        Vehicle older = seed(MEMBER_ID, "粤BD00002", 0);
        Vehicle newer = seed(MEMBER_ID, "粤BD00003", 0);   // id 最大

        service.delete(defaultOne.getId());

        // ★★ 这条测试钉的是【两条规则的交互】，而那是最容易静默改变的东西：
        //   promoteFirstRemaining 取的是 listByMemberId 的第一条，
        //   而那条 SQL 排的是 `is_default DESC, id DESC` ——
        //   默认车删掉之后，「剩下的第一辆」按 id 倒序，也就是【最新】那辆。
        //   哪天有人把 ORDER BY 改成 id ASC（看起来同样合理，
        //   「按添加顺序排」），这里会失败：被提升的会变成 older。
        //   车主端看到的现象是「删了默认车，新的默认车不是他最近加的那辆」。
        assertThat(vehicleMapper.rawRow(newer.getId()).getIsDefault()).isEqualTo(1);
        assertThat(vehicleMapper.rawRow(older.getId()).getIsDefault()).isZero();
        assertExactlyOneDefaultFor(MEMBER_ID);
    }

    @Test
    @DisplayName("★ 删非默认车 → 默认车纹丝不动，而且【没有去查列表】")
    void deletingANonDefaultShouldNotTouchTheDefault() {
        Vehicle defaultOne = seed(MEMBER_ID, "粤BD00001", 1);
        Vehicle other = seed(MEMBER_ID, "粤BD00002", 0);

        service.delete(other.getId());

        assertThat(vehicleMapper.rawRow(defaultOne.getId()).getIsDefault()).isEqualTo(1);
        // ★ 提升逻辑【压根没进去】—— 条件判断挡在了前面，
        //   连那次列表查询都省了。
        //   没有这条断言的话，一个「删完总是重新选一次默认车」的实现也能通过
        //   上面的断言（它只是重新选出了同一辆），但那会白跑一次列表查询，
        //   而且改变了 promoteFirstRemaining 的语义。
        //   ★ 和「删掉最后一辆车」那条对照着看：那条里列表查询是 1 次
        //     （promoteFirstRemaining 靠它发现没车了），这条里是 0 次。
        assertThat(vehicleMapper.listByMemberIdCallCount).isZero();
        assertThat(vehicleMapper.markDefaultCallCount).isZero();
    }

    @Test
    @DisplayName("★ 删掉最后一辆车：不抛异常，也不去提升（空列表是正常情况）")
    void deletingTheLastVehicleShouldBeFine() {
        Vehicle only = seed(MEMBER_ID, "粤BD00001", 1);

        service.delete(only.getId());

        assertThat(vehicleMapper.rawRowsOf(MEMBER_ID)).isEmpty();
        // ★★ 「有车的车主一定有默认车」在 0 辆车时前提不成立，
        //   所以这里不需要任何补救 —— 但也不能抛异常。
        //
        //   ★ 列表查询【确实发生了】：promoteFirstRemaining 就是靠它
        //     发现「已经没车了」的。所以这里断言的是「查了、但没提升」，
        //     不是「什么也没查」—— 后者是个更常见但不成立的直觉。
        assertThat(vehicleMapper.listByMemberIdCallCount).isEqualTo(1);
        assertThat(vehicleMapper.markDefaultCallCount).isZero();
    }

    // ==================== update ====================

    @Test
    @DisplayName("★★ 改车牌：小写 + 首尾空格 → 存进去是【大写、无空格】")
    void updateShouldNormalizePlateNo() {
        Vehicle mine = seed(MEMBER_ID, "粤BD00001", 1);

        VehicleUpdateDTO dto = new VehicleUpdateDTO();
        dto.setPlateNo("  粤bd00099  ");

        service.update(mine.getId(), dto);

        // ★ trim() 是【正确性要求】不是美化：库的排序规则是
        //   utf8mb4_0900_ai_ci（NO PAD），尾随空格是【有意义】的，
        //   所以 uk_vehicle_member_plate 拦不住「同一块牌带个尾空格再登记一次」。
        //   这条 trim 是目前唯一挡住它的东西（VehicleMapperTest 已对着真库
        //   验证过：带尾空格的行真的能插进去，产生两行看起来一样的记录）。
        //
        // ★ toUpperCase(Locale.ROOT) 里的 Locale.ROOT 同理不是装饰 ——
        //   土耳其语区域下 'i' 的大写是 'İ'，而那个 bug
        //   只在区域设置为 tr_TR 的服务器上出现，本机永远复现不了。
        assertThat(vehicleMapper.rawRow(mine.getId()).getPlateNo()).isEqualTo("粤BD00099");
    }

    @Test
    @DisplayName("★ 改车牌：纯空白被规范化成空串，而 <if> 判了 != '' —— 结果是【不改】")
    void blankPlateNoShouldBeTreatedAsNoChange() {
        Vehicle mine = seed(MEMBER_ID, "粤BD00001", 1);

        VehicleUpdateDTO dto = new VehicleUpdateDTO();
        dto.setPlateNo("   ");

        service.update(mine.getId(), dto);

        // ★ 这不是漏洞，是和「传 null 表示不改」一致的语义：
        //   规范化后是空串 → XML 的 <if test="plateNo != null and plateNo != ''">
        //   跳过它 → 车牌保持原值。
        //   和字段判空方式不同这件事有关（plateNo 判 != ''，plateColor 只判 != null），
        //   完整说明见 VehicleMapper.xml 与 Service 的 normalizePlateNo。
        assertThat(vehicleMapper.rawRow(mine.getId()).getPlateNo()).isEqualTo("粤BD00001");
    }

    @Test
    @DisplayName("★ 改资料：只覆盖传了的字段，没传的原样保留（不是清空）")
    void updateShouldMergeNonNullFieldsOnly() {
        Vehicle mine = seed(MEMBER_ID, "粤BD00001", 1);
        vehicleMapper.setBrand(mine.getId(), "原来的牌子");

        VehicleUpdateDTO dto = new VehicleUpdateDTO();
        dto.setModel("新的型号");   // ★ 只传 model

        service.update(mine.getId(), dto);

        Vehicle after = vehicleMapper.rawRow(mine.getId());
        assertThat(after.getModel()).isEqualTo("新的型号");
        // ★ 这两条是「合并不是覆盖」的证据。
        //   用 BeanUtils.copyProperties(dto, existing) 的话，
        //   DTO 里的 null 会把它们覆盖掉 ——「不传」变成「清空」，
        //   和 <set> + <if> 的 SQL 语义直接矛盾。
        assertThat(after.getBrand()).isEqualTo("原来的牌子");
        assertThat(after.getPlateNo()).isEqualTo("粤BD00001");
    }

    @Test
    @DisplayName("★★ 改资料【碰不到】 is_default —— 它只有 setDefault 一条写入路径")
    void updateShouldNotBeAbleToTouchIsDefault() {
        Vehicle mine = seed(MEMBER_ID, "粤BD00001", 1);

        VehicleUpdateDTO dto = new VehicleUpdateDTO();
        dto.setBrand("换个牌子");

        service.update(mine.getId(), dto);

        // ★ 这一列不在 VehicleUpdateDTO 里，也不在 XML 的 <set> 里。
        //    「两条写入路径通向同一个不变量，迟早有一条忘了清旧的」——
        //    所以这个类里只有 setDefault 一条路径。
        //    （VehicleUpdateDTO 上没有 isDefault 字段这件事是编译期事实，
        //     这条断言验的是「就算有人绕过 DTO 直接给实体塞了值，
        //     SQL 也不会写它」—— 假库忠实模拟了 <set> 里没有这一列。）
        assertThat(vehicleMapper.rawRow(mine.getId()).getIsDefault()).isEqualTo(1);
    }

    // ==================== 读 ====================

    @Test
    @DisplayName("★ listMine 不重排 —— SQL 的顺序就是最终顺序")
    void listMineShouldTrustTheMappersOrdering() {
        seed(MEMBER_ID, "粤BD00001", 1);
        seed(MEMBER_ID, "粤BD00002", 0);

        // ★ 让假库故意返回一个【乱序】的结果。
        //   真要「修好」它的话，在 Service 里加一个 .sorted() 就行了 ——
        //   而那恰恰是 Service 的注释明确不要做的事：
        //   在内存里再排一次会【掩盖】「SQL 忘了写 ORDER BY」这个错误，
        //   而那个错误的表现（翻页重复、顺序随机）正是那个排序要防的。
        //   这条测试让「顺手加个 sorted」这个改动静默不下来。
        vehicleMapper.scrambledOrder = true;

        List<VehicleVO> mine = service.listMine();

        // 从新到旧（播种顺序的反向），也就是假库给的顺序
        assertThat(mine).extracting(VehicleVO::getPlateNo)
                .containsExactly("粤BD00002", "粤BD00001");
    }

    @Test
    @DisplayName("★ 派生标签降级：车牌颜色非法或没填 → 标签为 null，但接口不 500")
    void plateColorLabelShouldDegradeInsteadOfThrowing() {
        Vehicle illegal = seed(MEMBER_ID, "粤BD00001", 1);
        vehicleMapper.setPlateColor(illegal.getId(), 99);   // 枚举里没有 99
        seed(MEMBER_ID, "粤BD00002", 0);   // 第二辆：plateColor 保持 null

        List<VehicleVO> mine = service.listMine();

        // ★ 只读路径上一行脏数据不该让整个列表 500 ——
        //   而且恰恰是出了脏数据的时候，管理员才最需要打开这个页面去修它。
        //   这是项目统一的「写路径严格，读路径降级」。
        assertThat(mine).allSatisfy(vo -> assertThat(vo.getPlateColorLabel()).isNull());
        // ★ 但原始值照常返回 —— 降级的是标签，不是数据本身。
        //   前端拿不到标签还能显示原始数字，拿不到数据就只能显示空白。
        assertThat(mine).extracting(VehicleVO::getPlateColor).containsExactly(99, null);
    }

    // ==================== 假库 ====================

    private static String catchMessageOf(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        throw new AssertionError("预期抛异常，但没有");
    }

    /**
     * 内存假库
     *
     * <p>为什么是假库而不是桩，以及它的保真度边界，见类注释第一、二节。
     * <b>每一条语句都对着 {@code VehicleMapper.xml} 抄了它真正承重的部分</b>，
     * 尤其注意：三条不带 {@code member_id} 条件的语句在这里是<b>不存在</b>的 ——
     * 和真 Mapper 一样。
     */
    private static final class InMemoryVehicleMapper implements VehicleMapper {

        /** id → 行。用 LinkedHashMap 只为让失败信息可读，顺序不承载语义 */
        private final Map<Long, Vehicle> rows = new LinkedHashMap<>();

        private long nextId = 1;

        /** 测试夹具用：绕过 Service 直接放一行进去 */
        private Vehicle seed(long memberId, String plateNo, int isDefault) {
            Vehicle v = Vehicle.builder()
                    .memberId(memberId)
                    .plateNo(plateNo)
                    .isDefault(isDefault)
                    .build();
            v.setId(nextId++);
            rows.put(v.getId(), v);
            return v;
        }

        /** 测试夹具用：直接改一行的某几个字段（模拟「库里有别的数据」） */
        private void setBrand(long id, String brand) {
            rows.get(id).setBrand(brand);
        }

        private void setPlateColor(long id, Integer plateColor) {
            rows.get(id).setPlateColor(plateColor);
        }

        /** 不限归属地取一行 —— 只给测试断言用，Service 拿不到这种东西 */
        private Vehicle rawRow(long id) {
            return rows.get(id);
        }

        private List<Vehicle> rawRowsOf(long memberId) {
            return rows.values().stream().filter(v -> memberId == v.getMemberId()).toList();
        }

        // ==================== VehicleMapper ====================

        private int touchCount;
        private int markDefaultCallCount;
        private int listByMemberIdCallCount;
        private boolean scrambledOrder;

        /**
         * ★ 对应 {@code WHERE id = ? AND member_id = ?} ——
         * <b>返回拷贝</b>，这样 Service 忘了调 {@code update} 时
         * 假库里的行不会被它白改一遍（否则那半个 bug 会被藏起来）。
         */
        @Override
        public Vehicle getByIdAndMember(Long id, Long memberId) {
            touchCount++;
            Vehicle row = rows.get(id);
            if (row == null || !row.getMemberId().equals(memberId)) {
                return null;
            }
            return copyOf(row);
        }

        /** ★ 对应 {@code WHERE member_id = ? ORDER BY is_default DESC, id DESC} */
        @Override
        public List<Vehicle> listByMemberId(Long memberId) {
            touchCount++;
            listByMemberIdCallCount++;

            List<Vehicle> mine = new ArrayList<>(rows.values().stream()
                    .filter(v -> memberId.equals(v.getMemberId()))
                    .toList());

            if (scrambledOrder) {
                // 测试专用：反过来，用来验证 Service 不会自作主张地重排
                return mine.reversed();
            }

            mine.sort(Comparator.comparing(Vehicle::getIsDefault).reversed()
                    .thenComparing(Comparator.comparing(Vehicle::getId).reversed()));
            return mine;
        }

        /** ★ 对应 {@code SELECT COUNT(*) FROM vehicle WHERE member_id = ?} */
        @Override
        public int countByMemberId(Long memberId) {
            touchCount++;
            return (int) rows.values().stream()
                    .filter(v -> memberId.equals(v.getMemberId()))
                    .count();
        }

        /**
         * ★ 对应真表的「{@code is_default} 是 NOT NULL + 全列插入」
         *
         * <p>抛出的类型和真库一致：MySQL 的 errno 1048
         * （Column cannot be null）由 Spring 翻成
         * {@code DataIntegrityViolationException}。
         * ★ 假库在这一点上必须<b>一样严格</b>：宽松的假库会让
         * 「Service 忘了兜底」这个 bug 在单测里静默通过。
         */
        @Override
        public void insert(Vehicle vehicle) {
            touchCount++;
            if (vehicle.getIsDefault() == null) {
                throw new DataIntegrityViolationException(
                        "Column 'is_default' cannot be null（模拟 errno 1048）");
            }
            vehicle.setId(nextId++);
            rows.put(vehicle.getId(), copyOf(vehicle));
        }

        /**
         * ★ 对应 {@code <set> + <if>} 与 {@code WHERE id = ? AND member_id = ?}
         *
         * <p>★ 只写 XML 里真的有的那几列 —— 尤其
         * <b>{@code is_default} 和 {@code member_id} 不在其中</b>。
         * ★ {@code <if>} 的判空条件也照抄：{@code plateNo} / {@code brand} /
         * {@code model} / {@code remark} 判 {@code != null and != ''}，
         * {@code plateColor} / {@code batteryCapacityKwh} 只判 {@code != null}。
         */
        @Override
        public void update(Vehicle vehicle) {
            touchCount++;
            Vehicle row = rows.get(vehicle.getId());
            if (row == null || !row.getMemberId().equals(vehicle.getMemberId())) {
                return;   // 0 行
            }
            if (notBlank(vehicle.getPlateNo())) {
                row.setPlateNo(vehicle.getPlateNo());
            }
            if (vehicle.getPlateColor() != null) {
                row.setPlateColor(vehicle.getPlateColor());
            }
            if (notBlank(vehicle.getBrand())) {
                row.setBrand(vehicle.getBrand());
            }
            if (notBlank(vehicle.getModel())) {
                row.setModel(vehicle.getModel());
            }
            if (vehicle.getBatteryCapacityKwh() != null) {
                row.setBatteryCapacityKwh(vehicle.getBatteryCapacityKwh());
            }
            if (notBlank(vehicle.getRemark())) {
                row.setRemark(vehicle.getRemark());
            }
        }

        /** ★ 对应 {@code DELETE FROM vehicle WHERE id = ? AND member_id = ?} */
        @Override
        public int deleteByIdAndMember(Long id, Long memberId) {
            touchCount++;
            Vehicle row = rows.get(id);
            if (row == null || !row.getMemberId().equals(memberId)) {
                return 0;
            }
            rows.remove(id);
            return 1;
        }

        /**
         * ★ 对应
         * {@code UPDATE vehicle SET is_default = 0 WHERE member_id = ? AND is_default = 1 AND id <> ?}
         *
         * <p>★ {@code id <> ?} 和 {@code is_default = 1} 两个条件都照抄 ——
         * 它们各自承重：前者保证新建默认车时不会把自己刚设的标记清掉，
         * 后者让「本来就都不是默认车」时返回 0 行（那是正常情况，不是错误）。
         */
        @Override
        public int clearDefaultExcept(Vehicle defaultVehicle) {
            touchCount++;
            int affected = 0;
            for (Vehicle row : rows.values()) {
                boolean sameOwner = row.getMemberId().equals(defaultVehicle.getMemberId());
                boolean isDefault = Integer.valueOf(1).equals(row.getIsDefault());
                boolean isSelf = row.getId().equals(defaultVehicle.getId());
                if (sameOwner && isDefault && !isSelf) {
                    row.setIsDefault(0);
                    affected++;
                }
            }
            return affected;
        }

        /**
         * ★ 对应 {@code UPDATE vehicle SET is_default = 1 WHERE id = ? AND member_id = ?}
         *
         * <p>★ {@code member_id} 在 WHERE 里 ⇒ <b>归属校验就是写入条件本身</b>。
         * 不匹配返回 0 —— 这条是「设为默认别人的车」那个测试
         * 能在 Service 层之外再兜一层的依据。
         */
        @Override
        public int markDefault(Vehicle vehicle) {
            touchCount++;
            markDefaultCallCount++;
            Vehicle row = rows.get(vehicle.getId());
            if (row == null || !row.getMemberId().equals(vehicle.getMemberId())) {
                return 0;
            }
            row.setIsDefault(1);
            return 1;
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isEmpty();
        }

        private static Vehicle copyOf(Vehicle source) {
            return Vehicle.builder()
                    .id(source.getId())
                    .memberId(source.getMemberId())
                    .plateNo(source.getPlateNo())
                    .plateColor(source.getPlateColor())
                    .brand(source.getBrand())
                    .model(source.getModel())
                    .batteryCapacityKwh(source.getBatteryCapacityKwh())
                    .isDefault(source.getIsDefault())
                    .remark(source.getRemark())
                    .build();
        }
    }
}
