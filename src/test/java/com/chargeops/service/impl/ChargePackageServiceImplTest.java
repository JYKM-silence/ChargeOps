package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.dto.CategoryPageQueryDTO;
import com.chargeops.dto.ChargePackageDTO;
import com.chargeops.dto.ChargePackagePageQueryDTO;
import com.chargeops.dto.ChargePackageUpdateDTO;
import com.chargeops.entity.Category;
import com.chargeops.entity.ChargePackage;
import com.chargeops.enumeration.CategoryType;
import com.chargeops.enumeration.PackageType;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.CategoryMapper;
import com.chargeops.mapper.ChargePackageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ChargePackageServiceImpl} 的测试 —— 只测 {@code assertTypeFields}
 *
 * <h2>★ 一、为什么整个 P1a 的 Service 层只有这一个类值得写单测</h2>
 *
 * <p>其余 Service 方法都是「查一下、判个空、调 mapper」，那些逻辑的真值
 * 在于 <b>SQL 对不对</b>，而 SQL 的真值只有连上 MySQL 才知道 ——
 * 它们由 {@code ChargePackageMapperTest} 那一组集成测试覆盖。
 * 在这里再 mock 一遍 mapper、断言「确实调用了 insert」，只是把
 * 实现照抄一遍当成断言，除了一百行样板之外什么也没证明。
 *
 * <p><b>而 {@code assertTypeFields} 不一样，它是纯逻辑</b>：
 * 输入是一个实体对象，输出是「过 / 抛」，中间不碰数据库。
 * 「哪四个字段、哪个类型要哪个、另外三个必须为空」这套判断的复杂度
 * 与 mapper 无关，所以它可以、也应该在没有数据库的情况下被证明。
 *
 * <h2>★ 二、用手写桩类，不用 Mockito</h2>
 *
 * <p>Mockito 在 {@code spring-boot-starter-test} 里现成可用，
 * 但这里的桩类只有三个方法要写，而手写换来一个 Mockito 给不了的东西：
 *
 * <p><b>接口以后新加方法时，编译器会逼着我实现它。</b>
 * Mockito 的 {@code mock()} 对没打桩的方法<b>静默返回 null</b> ——
 * 于是那个方法被新代码调用时，测试会以一个莫名其妙的 NPE 失败，
 * 或者更糟：返回 null 恰好被当成合法输入，测试全绿而生产是错的。
 * 手写的桩类则根本不编译。桩类里那两个
 * {@code UnsupportedOperationException} 也是同一个用意：
 * 本类没打算覆盖分页和删除，走到那里就应该响亮地炸掉，
 * 而不是安静地返回一个没人看过的值。
 *
 * <h2>★ 三、不启动 Spring</h2>
 *
 * <p>直接 {@code new ChargePackageServiceImpl(stub)}。
 * 代价是 {@code @Transactional} 不生效（没有代理，注解就是一行注释）——
 * 对本类无所谓，因为这里断言的是<b>抛异常之前有没有写库</b>，
 * 而那是方法体内部的顺序问题，和事务无关。
 * 收益是整个测试类是同步的、毫秒级的、不需要 MySQL 和 Redis。
 */
@DisplayName("ChargePackageServiceImpl 类型专属字段校验")
class ChargePackageServiceImplTest {

    private static final String MISMATCH_PREFIX = MessageConstant.PACKAGE_TYPE_FIELD_MISMATCH + "：";

    /** 一个合法的「充电套餐分类」的 id —— 见 {@link #chargePackageCategory()} */
    private static final Long CHARGE_PACKAGE_CATEGORY_ID = 1L;

    /**
     * 造一个桩分类 Mapper，里面存着一个 {@code type=1} 的合法分类
     *
     * <p>★ 这个默认值让<b>所有和分类无关的测试</b>（本类里其他全部用例）
     * 不用关心分类校验的存在 —— 它们测的是类型专属字段，
     * 而分类校验是另一条独立的规则。
     *
     * <p>要测分类校验本身的那几个用例，改用
     * {@link #categoryStubWith(Long, int)} 显式指定。
     */
    private static StubCategoryMapper chargePackageCategory() {
        return categoryStubWith(CHARGE_PACKAGE_CATEGORY_ID, CategoryType.CHARGE_PACKAGE.getCode());
    }

    /**
     * 造一个桩分类 Mapper，里面存着指定的分类
     *
     * @param id   分类 id
     * @param type {@code CategoryType} 的 code；传一个不存在的 id 就得到「查不到」
     */
    private static StubCategoryMapper categoryStubWith(Long id, int type) {
        StubCategoryMapper categoryMapper = new StubCategoryMapper();
        categoryMapper.stored = Category.builder()
                .id(id)
                .name("快充")
                .type(type)
                .sort(0)
                .status(1)
                .build();
        return categoryMapper;
    }

    // ==================== 创建 ====================

    @Test
    @DisplayName("创建电量包：填了 energyKwh 就通过，且四个 NOT NULL 列被兜底")
    void createEnergyPackageShouldPass() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();
        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, chargePackageCategory());

        ChargePackageDTO dto = new ChargePackageDTO();
        dto.setName("150度电包");
        dto.setPackageType(PackageType.ENERGY.getCode());
        dto.setCategoryId(CHARGE_PACKAGE_CATEGORY_ID);
        dto.setPrice(new BigDecimal("100.00"));
        dto.setEnergyKwh(new BigDecimal("150.00"));

        Long id = service.create(dto);

        // 桩类模拟了 useGeneratedKeys 的回填
        assertThat(id).isEqualTo(99L);
        assertThat(mapper.insertCallCount).isEqualTo(1);

        // ★ 这四个默认值不是装饰 —— 它们是 NOT NULL 列，而 insert 是全列插入，
        //   DDL 的 DEFAULT 在这种情况下不生效（errno 1048）。
        //   这个断言就是那条规则的可执行版本。
        ChargePackage written = mapper.inserted;
        assertThat(written.getStationId()).isEqualTo(ChargePackageDTO.DEFAULT_STATION_ID);
        assertThat(written.getValidDays()).isEqualTo(ChargePackageDTO.DEFAULT_VALID_DAYS);
        assertThat(written.getStatus()).isEqualTo(ChargePackageDTO.DEFAULT_STATUS);
        assertThat(written.getSort()).isEqualTo(ChargePackageDTO.DEFAULT_SORT);
    }

    @Test
    @DisplayName("创建时长包：缺 durationMinutes → 抛，且一行都没写进库")
    void createDurationPackageWithoutDurationShouldFail() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();
        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, chargePackageCategory());

        ChargePackageDTO dto = new ChargePackageDTO();
        dto.setName("2小时时长包");
        dto.setPackageType(PackageType.DURATION.getCode());
        dto.setCategoryId(CHARGE_PACKAGE_CATEGORY_ID);
        dto.setPrice(new BigDecimal("30.00"));
        // ★ 故意不设 durationMinutes

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(ParamErrorException.class)
                .hasMessage(MISMATCH_PREFIX + "时长包必须填写 durationMinutes");

        // ★ 这条断言比「抛了异常」更有价值：它证明校验发生在写库【之前】。
        //   反过来的实现（先 insert 再校验）也能通过上面那条断言 ——
        //   但会在库里留下一行只有名字没有时长的套餐，
        //   而且自增 id 被白白消耗掉。
        assertThat(mapper.insertCallCount).isZero();
    }

    @Test
    @DisplayName("创建电量包：energyKwh 有值但 times 也非空 → 抛（第二半：不该有的必须为空）")
    void createEnergyPackageWithLeftoverTimesShouldFail() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();
        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, chargePackageCategory());

        ChargePackageDTO dto = new ChargePackageDTO();
        dto.setName("150度电包");
        dto.setPackageType(PackageType.ENERGY.getCode());
        dto.setCategoryId(CHARGE_PACKAGE_CATEGORY_ID);
        dto.setPrice(new BigDecimal("100.00"));
        dto.setEnergyKwh(new BigDecimal("150.00"));
        dto.setTimes(5);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(ParamErrorException.class)
                .hasMessage(MISMATCH_PREFIX + "电量包不能设置 times"
                        + "（这些字段属于别的套餐类型，残留值会被计费逻辑读走）");

        assertThat(mapper.insertCallCount).isZero();
    }

    @Test
    @DisplayName("★ 一次报全：四个字段都有值时，消息里按固定顺序列出全部三个残留字段")
    void createWithAllTypeFieldsShouldReportEveryLeftoverAtOnce() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();
        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, chargePackageCategory());

        ChargePackageDTO dto = new ChargePackageDTO();
        dto.setName("什么都填了的电量包");
        dto.setPackageType(PackageType.ENERGY.getCode());
        dto.setCategoryId(CHARGE_PACKAGE_CATEGORY_ID);
        dto.setPrice(new BigDecimal("100.00"));
        dto.setEnergyKwh(new BigDecimal("150.00"));
        dto.setDurationMinutes(120);
        dto.setServiceDiscount(new BigDecimal("0.8000"));
        dto.setTimes(5);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(ParamErrorException.class)
                // ★ 断言【完整的】消息，包括字段顺序。
                //   顺序来自 assertTypeFields 里的 LinkedHashMap，
                //   它保证了同一份错误数据每次给出的消息一字不差 ——
                //   前端按 msg 做分支判断时这一点是承重的。
                //   管理员一次就能看到三个要清的字段，不用「补一个提交一次」。
                .hasMessage(MISMATCH_PREFIX + "电量包不能设置 durationMinutes、serviceDiscount、times"
                        + "（这些字段属于别的套餐类型，残留值会被计费逻辑读走）");

        assertThat(mapper.insertCallCount).isZero();
    }

    // ==================== 更新：校验的是【合并后的行】 ====================

    @Test
    @DisplayName("更新只传 price 的合法时长包 → 通过，时长和站点原样保留")
    void updateOnlyPriceOfValidDurationPackageShouldPass() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();
        mapper.stored = ChargePackage.builder()
                .id(7L)
                .name("2小时时长包")
                .packageType(PackageType.DURATION.getCode())
                .categoryId(CHARGE_PACKAGE_CATEGORY_ID)
                .stationId(0L)
                .price(new BigDecimal("30.00"))
                .durationMinutes(120)
                .status(1)
                .build();

        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, chargePackageCategory());

        ChargePackageUpdateDTO dto = new ChargePackageUpdateDTO();
        dto.setPrice(new BigDecimal("35.00"));
        // ★ 只改价格 —— 这正是不用 DTO 校验的原因：
        //   DTO 里 durationMinutes 是 null，而它并不是「用户想清空时长」。

        service.update(7L, dto);

        assertThat(mapper.updateCallCount).isEqualTo(1);
        ChargePackage written = mapper.updated;
        assertThat(written.getPrice()).isEqualByComparingTo("35.00");
        // ★ mergeNonNull 必须【不】把 DTO 里的 null 写进去。
        //   换成 BeanUtils.copyProperties 的话这两条会立刻挂 ——
        //   而挂掉的表现（时长包被清空了时长）在生产上就是「这个套餐算不出钱」。
        assertThat(written.getDurationMinutes()).isEqualTo(120);
        assertThat(written.getStationId()).isEqualTo(0L);
        assertThat(written.getName()).isEqualTo("2小时时长包");
    }

    @Test
    @DisplayName("★ 库里的行 type=1 缺 energyKwh 却残留 durationMinutes → 只改价格也照样抛")
    void updateShouldStillFailWhenStoredRowLacksRequiredField() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();
        // 一行【本来就坏了】的数据：类型说它是电量包，却没有电量，
        // 反倒留着一个只属于时长包的字段。
        mapper.stored = ChargePackage.builder()
                .id(7L)
                .name("历史遗留的坏行")
                .packageType(PackageType.ENERGY.getCode())
                .stationId(0L)
                .price(new BigDecimal("100.00"))
                .durationMinutes(30)
                .status(1)
                .build();

        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, chargePackageCategory());

        ChargePackageUpdateDTO dto = new ChargePackageUpdateDTO();
        dto.setPrice(new BigDecimal("120.00"));

        // 方向一在合并后的行上生效：energyKwh 仍然没有，所以要抛。
        // 注意这个请求【看起来完全无害】—— 它只是想改个价格，
        // 而且 DTO 里没有任何类型专属字段，单看请求体是合法的。
        assertThatThrownBy(() -> service.update(7L, dto))
                .isInstanceOf(ParamErrorException.class)
                .hasMessage(MISMATCH_PREFIX + "电量包必须填写 energyKwh");

        assertThat(mapper.updateCallCount).isZero();
    }

    @Test
    @DisplayName("★★ 库里那行 durationMinutes 有残留值，而 DTO 里根本没有这个字段 → 仍然抛")
    void updateShouldValidateMergedRowNotDto() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();
        // 一行「两个类型专属字段都有值」的坏数据。
        // 它是怎么变成这样的：见 ChargePackageMapper.update 的 javadoc ——
        // <if test="x != null"> 的写法清不掉一个字段，
        // 所以历史脏数据能一直留在库里。
        mapper.stored = ChargePackage.builder()
                .id(7L)
                .name("残留了电量的坏行")
                .packageType(PackageType.ENERGY.getCode())
                .stationId(0L)
                .price(new BigDecimal("100.00"))
                .energyKwh(new BigDecimal("150.00"))
                .durationMinutes(30)
                .status(1)
                .build();

        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, chargePackageCategory());

        ChargePackageUpdateDTO dto = new ChargePackageUpdateDTO();
        dto.setPrice(new BigDecimal("120.00"));

        // ★★ 这是本类里最重要的一条断言。
        //
        //   请求体里既没有 energyKwh 也没有 durationMinutes ——
        //   如果 assertTypeFields 的实现改成「对着 DTO 校验」，
        //   这个请求会被【放行】，那行脏数据继续留在库里，
        //   而 P1c 应用套餐时会读走 energyKwh=150，
        //   在一个本该只按时长计的套餐上白白送出 150 度电。
        //
        //   它能被拦住，唯一的原因是校验对象是
        //   「DTO 非空值覆盖到库里那行之后」的合并结果。
        assertThatThrownBy(() -> service.update(7L, dto))
                .isInstanceOf(ParamErrorException.class)
                .hasMessage(MISMATCH_PREFIX + "电量包不能设置 durationMinutes"
                        + "（这些字段属于别的套餐类型，残留值会被计费逻辑读走）");

        assertThat(mapper.updateCallCount).isZero();
    }

    @Test
    @DisplayName("更新的类型取自库里那行：DTO 想改类型也改不了（UpdateDTO 里没有这个字段）")
    void updateTypeComesFromStoredRow() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();
        mapper.stored = ChargePackage.builder()
                .id(7L)
                .name("次卡")
                .packageType(PackageType.TIMES.getCode())
                .categoryId(CHARGE_PACKAGE_CATEGORY_ID)
                .stationId(0L)
                .price(new BigDecimal("50.00"))
                .times(5)
                .status(1)
                .build();

        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, chargePackageCategory());

        ChargePackageUpdateDTO dto = new ChargePackageUpdateDTO();
        dto.setPrice(new BigDecimal("45.00"));
        dto.setTimes(8);

        service.update(7L, dto);

        // 类型仍然是库里的 4（次卡），改的是次数 —— 而不是「把次卡改成别的类型」。
        // 这个字段不在 UpdateDTO 里，所以「改类型」在编译期就是不可能的，
        // 这里断言的是那条设计决策在运行时的样子。
        assertThat(mapper.updated.getPackageType()).isEqualTo(PackageType.TIMES.getCode());
        assertThat(mapper.updated.getTimes()).isEqualTo(8);
        assertThat(mapper.updated.getPrice()).isEqualByComparingTo("45.00");
    }

    // ==================== 分类校验（D13） ====================

    @Test
    @DisplayName("创建：分类 id 指向一个不存在的分类 → NotFoundException，且一行都没写进库")
    void createWithMissingCategoryShouldFail() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();

        // ★ 一个【空】的桩：stored 保持 null，getById 返回 null 就是「查不到」。
        //   不用 categoryStubWith —— 那个方法一定会造出一行来。
        StubCategoryMapper categoryMapper = new StubCategoryMapper();

        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, categoryMapper);

        ChargePackageDTO dto = new ChargePackageDTO();
        dto.setName("150度电包");
        dto.setPackageType(PackageType.ENERGY.getCode());
        // ★ 一个不存在的分类 id —— 这正是 P1a 之前会【静默写进库】的输入：
        //   charge_package.category_id 是裸 bigint，没有外键，
        //   指到一个已经被删掉的分类上不会撞任何约束。
        dto.setCategoryId(999L);
        dto.setPrice(new BigDecimal("100.00"));
        dto.setEnergyKwh(new BigDecimal("150.00"));

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(MessageConstant.CATEGORY_NOT_FOUND);

        // ★ 校验必须在写库【之前】。反过来的实现（先 insert 再查分类）
        //   也能通过上面那条断言，但库里会留下一行 category_id=999 的套餐 ——
        //   而它正是 P1a 那个已知缺口的样子。
        assertThat(mapper.insertCallCount).isZero();
    }

    @Test
    @DisplayName("★ 创建：分类存在但 type=2（增值服务） → ParamErrorException，且一行都没写进库")
    void createWithWrongCategoryTypeShouldFail() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();

        // 一个【存在】的、id 也对得上的分类 —— 但它不是「充电套餐分类」。
        // 这一条是 D13 的核心：指错 type 不会撞任何约束，
        // 所以只能由消费方自己判。
        StubCategoryMapper categoryMapper =
                categoryStubWith(CHARGE_PACKAGE_CATEGORY_ID, CategoryType.VALUE_ADDED.getCode());

        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, categoryMapper);

        ChargePackageDTO dto = new ChargePackageDTO();
        dto.setName("150度电包");
        dto.setPackageType(PackageType.ENERGY.getCode());
        dto.setCategoryId(CHARGE_PACKAGE_CATEGORY_ID);
        dto.setPrice(new BigDecimal("100.00"));
        dto.setEnergyKwh(new BigDecimal("150.00"));

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(ParamErrorException.class)
                // ★ 断言完整消息，包括两个 label 和分类名。
                //   消息要说清「你要的是哪一档、这个分类实际是哪一档」——
                //   否则管理员的下一步动作是「换一个分类试试」，
                //   而不是「我选错类目了」。理由同 assertTypeFields 的「一次报全」。
                .hasMessage("分类类型不匹配：充电套餐只能挂在「充电套餐分类」下，"
                        + "而分类「快充」是「增值服务分类」");

        assertThat(mapper.insertCallCount).isZero();
    }

    @Test
    @DisplayName("★★ 更新：只改价格，但库里那行挂在 type=2 的分类上 → 照样抛（校验覆盖每一条写路径）")
    void updateShouldAlsoValidateCategoryType() {
        StubChargePackageMapper mapper = new StubChargePackageMapper();
        mapper.stored = ChargePackage.builder()
                .id(7L)
                .name("次卡")
                .packageType(PackageType.TIMES.getCode())
                // ★ 一个【合法存在但 type 不对】的分类 id。
                //   行本身怎么变成这样的：它是在 D13 落地【之前】建的，
                //   或者分类的 type 后来被手工 SQL 改过。
                .categoryId(5L)
                .stationId(0L)
                .price(new BigDecimal("50.00"))
                .times(5)
                .status(1)
                .build();

        StubCategoryMapper categoryMapper =
                categoryStubWith(5L, CategoryType.VALUE_ADDED.getCode());

        ChargePackageServiceImpl service = new ChargePackageServiceImpl(mapper, categoryMapper);

        ChargePackageUpdateDTO dto = new ChargePackageUpdateDTO();
        dto.setPrice(new BigDecimal("45.00"));
        // ★ 请求体里【没有 categoryId】—— 它只是想改个价格。
        //   如果 create 校验了而 update 没校验，「建一个合法套餐、
        //   再把它挪到别的类目下」就是一条完整的绕过路径。
        //   这是本项目里「校验必须覆盖每一条能到达那个字段的写路径」
        //   那条规则的运行时形态。

        assertThatThrownBy(() -> service.update(7L, dto))
                .isInstanceOf(ParamErrorException.class)
                .hasMessage("分类类型不匹配：充电套餐只能挂在「充电套餐分类」下，"
                        + "而分类「快充」是「增值服务分类」");

        assertThat(mapper.updateCallCount).isZero();
    }

    /**
     * 手写的 {@link ChargePackageMapper} 桩
     *
     * <p>只实现被测路径真正走到的方法；另外两个直接抛 ——
     * 这样「本类没覆盖分页和删除」是一条会自己执行的断言，
     * 而不是一句注释。理由见类注释第二节。
     *
     * <p>★ {@code insert} 里回填 id 是<b>必须的</b>：
     * 真实的 MyBatis 靠 {@code useGeneratedKeys="true"} 做这件事，
     * 而 {@code ChargePackageServiceImpl.create} 最后返回的正是
     * {@code pkg.getId()}。桩类不回填的话，那条断言永远只能看到 null。
     */
    private static final class StubChargePackageMapper implements ChargePackageMapper {

        /** {@code getById} 要返回的那一行；{@code null} 表示「查不到」 */
        private ChargePackage stored;

        /** 被 {@code insert} 写进来的那一行 —— 用来断言「写了什么」 */
        private ChargePackage inserted;

        /** 被 {@code update} 写进来的那一行 */
        private ChargePackage updated;

        private int insertCallCount;

        private int updateCallCount;

        @Override
        public ChargePackage getById(Long id) {
            return stored;
        }

        @Override
        public void insert(ChargePackage chargePackage) {
            insertCallCount++;
            chargePackage.setId(99L);
            this.inserted = chargePackage;
        }

        @Override
        public void update(ChargePackage chargePackage) {
            updateCallCount++;
            this.updated = chargePackage;
        }

        @Override
        public List<ChargePackage> page(ChargePackagePageQueryDTO query) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖分页，见 ChargePackageMapperTest 的集成测试");
        }

        @Override
        public int deleteById(Long id) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖删除，见 ChargePackageMapperTest 的集成测试");
        }

        @Override
        public int countByCategoryId(Long categoryId) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖「分类下有几个套餐」，见 CategoryServiceImplTest");
        }

        @Override
        public List<ChargePackage> listAvailableForMember(Long stationId) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖车主端货架，见 MemberPackageServiceImplTest 的集成测试");
        }
    }

    /**
     * 手写的 {@link CategoryMapper} 桩
     *
     * <p>★ <b>接进 {@code ChargePackageServiceImpl} 的是它，不是真的分类库。</b>
     * 被测类现在只用到 {@code getById} 一个方法 ——
     * 「这个分类存不存在、是什么 type」正是 D13 要判的两件事，
     * 而它们<b>不需要数据库就能构造</b>：一个 {@code stored} 字段就够了。
     *
     * <p>★ 其余五个方法一律抛，和 {@link StubChargePackageMapper} 同一个约定：
     * 本类不覆盖分类的增删改查，走到那里就应该响亮地炸掉，
     * 而不是安静地返回 null（Mockito 的默认行为）——
     * 返回 null 恰好是「分类不存在」，会被 {@code requireCategory}
     * 当成合法输入抛出一个看起来正确的异常，测试全绿而原因完全错了。
     */
    private static final class StubCategoryMapper implements CategoryMapper {

        /** {@code getById} 要返回的那一行；{@code null} 表示「查不到」 */
        private Category stored;

        @Override
        public Category getById(Long id) {
            // ★ 不比对 id —— 桩类里只存了一行，「查的就是它」。
            //   如果被测代码传了一个别的 id 过来（那是真 bug），
            //   一个「查得到」的返回值会让 bug 藏起来。
            //   这里用 stored 的 id 做一次相等判断，让它藏不住。
            if (stored != null && !stored.getId().equals(id)) {
                return null;
            }
            return stored;
        }

        @Override
        public List<Category> listEnabled(Integer type) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖分类列表，见 CategoryMapperTest 的集成测试");
        }

        @Override
        public void insert(Category category) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖分类新增，见 CategoryMapperTest 的集成测试");
        }

        @Override
        public void update(Category category) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖分类修改，见 CategoryMapperTest 的集成测试");
        }

        @Override
        public int deleteById(Long id) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖分类删除，见 CategoryMapperTest 的集成测试");
        }

        @Override
        public List<Category> page(CategoryPageQueryDTO query) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖分类分页，见 CategoryMapperTest 的集成测试");
        }
    }
}
