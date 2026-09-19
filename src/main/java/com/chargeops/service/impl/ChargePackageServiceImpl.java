package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.constant.StatusConstant;
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
import com.chargeops.result.PageResult;
import com.chargeops.service.ChargePackageService;
import com.chargeops.vo.ChargePackageVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 充电套餐服务实现
 *
 * <p>这个类里唯一有真分支的方法是 {@link #assertTypeFields} ——
 * 而它也是 P1a 里唯一值得写 mock 单测的地方（见
 * {@code ChargePackageServiceImplTest}）。其余逻辑薄到
 * mapper 集成测试已经覆盖，再写一遍断言只是重复。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChargePackageServiceImpl implements ChargePackageService {

    private final ChargePackageMapper chargePackageMapper;

    /**
     * ★ 这个依赖只服务一件事：{@link #requireCategory} 的 type 校验（D13）。
     *
     * <p>它关掉的是 P1a 留下的一个已知缺口 —— 在那之前
     * {@code charge_package.category_id} 是一个<b>没人验证过的裸 bigint</b>，
     * 可以指向一个不存在的分类，也可以指向一个语义完全不对的分类。
     * 缺口本身的完整描述见 {@code CategoryType} 的类注释。
     *
     * <p>★ 依赖方向：这里是 {@code Service → 别的域的 Mapper}，
     * 和 {@code CategoryServiceImpl} 依赖 {@code ChargePackageMapper} 是
     * <b>同一类做法，而且两者不构成环</b> —— 依赖图里没有
     * Service → Service 的边。这也是为什么这里注入的是 Mapper
     * 而不是 {@code CategoryService}：两个 Service 互相注入是 Spring
     * 允许但很难读的结构（谁先初始化？循环里哪一环是真正的规则所在？）。
     */
    private final CategoryMapper categoryMapper;

    @Override
    @Transactional
    public Long create(ChargePackageDTO chargePackageDTO) {
        ChargePackage pkg = new ChargePackage();
        BeanUtils.copyProperties(chargePackageDTO, pkg);

        // ★★ 四个默认值必须在这里兜底，理由见 StationServiceImpl.create 那段：
        //   字段初始值只挡「没传这个键」，{"stationId": null} 是显式 null，
        //   Jackson 会照常调 setter 覆盖掉初始值 ——
        //   而 ChargePackageMapper.xml 的 insert 是全列插入，
        //   四个 NOT NULL 列拿到 null 就是 errno 1048。
        //
        //   这个坑在本项目里连踩三次，所以这里的四个兜底不是防御性代码，
        //   是「全列插入」和「DDL DEFAULT」之间那条系统性摩擦的第四次收口。
        if (pkg.getStationId() == null) {
            pkg.setStationId(ChargePackageDTO.DEFAULT_STATION_ID);
        }
        if (pkg.getValidDays() == null) {
            pkg.setValidDays(ChargePackageDTO.DEFAULT_VALID_DAYS);
        }
        if (pkg.getStatus() == null) {
            pkg.setStatus(ChargePackageDTO.DEFAULT_STATUS);
        }
        if (pkg.getSort() == null) {
            pkg.setSort(ChargePackageDTO.DEFAULT_SORT);
        }

        // ★ 顺序：先跑【纯内存】的字段校验，再跑【要查库】的分类校验。
        //
        //   两者都错的时候，先报字段那个。理由是它更便宜（不查库），
        //   而且它说的是「你这张表单本身填错了」，比
        //   「你选的那个分类不对」更靠前一步 —— 管理员修完字段
        //   重新提交时，如果分类还是错的，那时候再报分类。
        //   一次报一个问题，但顺序是「离输入最近的先说」。
        assertTypeFields(pkg);

        // ★ D13：分类必须存在，而且必须是「充电套餐分类」
        requireCategory(pkg.getCategoryId());

        chargePackageMapper.insert(pkg);

        log.info("新增充电套餐成功：id={}, name={}, packageType={}, stationId={}",
                pkg.getId(), pkg.getName(), pkg.getPackageType(), pkg.getStationId());
        return pkg.getId();
    }

    @Override
    @Transactional
    public void update(Long id, ChargePackageUpdateDTO chargePackageUpdateDTO) {
        // ★★ 先把库里那一行读出来 —— 校验的对象是【合并后的行】，不是 DTO。
        //
        //   只改价格的管理员不会传 energyKwh（他没打算动它，
        //   甚至可能没看到它），拿 DTO 去校验会误判成「电量包缺电量」，
        //   把一个完全合法的请求打回。
        //
        //   所以这里必须先读，不能像别的域那样只拷 DTO 就更新。
        //   代价是多一次查询，换来的是校验的正确性 —— 这笔账很划算。
        ChargePackage existing = requirePackage(id);

        // ★ 合并方向：DTO 的非空值覆盖到从库里读出的行上。
        //
        //   BeanUtils.copyProperties(source, target) 是**全量**覆盖，
        //   会把 DTO 里的 null 也写进去 —— 那样「不传」就变成了「清空」，
        //   和 <set> + <if> 的更新语义直接矛盾（DTO 说清空、
        //   SQL 说不动，两边打架而 SQL 赢）。
        //
        //   所以这里不用 copyProperties，显式逐字段判空覆盖。
        //   啰嗦，但它是唯一能让「内存里的行」和「SQL 实际写出来的行」
        //   保持一致的做法 —— 而校验用的正是内存里的那一行。
        mergeNonNull(chargePackageUpdateDTO, existing);

        assertTypeFields(existing);

        // ★★ D13 在这里也必须再跑一次，而且理由【不是】「防御性编程」。
        //
        //   categoryId 在 ChargePackageUpdateDTO 里是【可改字段】——
        //   管理员确实可以把一个套餐从「快充」挪到「慢充」。
        //   如果只在 create 里校验，那么「先建一个合法套餐、
        //   再把它改到一个 type=2 的分类下」就是一条完全绕过校验的路径。
        //
        //   ★ 这是一类很容易漏的漏洞模式：**校验必须覆盖每一条能到达
        //     那个字段的写路径**，而不只是最显眼的那一条。
        //     本项目的 create/update 成对校验是统一的形状
        //     （对照 CategoryServiceImpl：那边没有这个问题，
        //      因为 CategoryUpdateDTO 里根本没有 type）。
        requireCategory(existing.getCategoryId());

        // ★ 用【合并后的整体】去更新，而不是只更新 DTO 里那几个字段。
        //   看起来多写了几个字段，但换来的是：
        //   「校验的那一行」和「写进去的那一行」是同一个对象，
        //   两件事不可能不一致。
        chargePackageMapper.update(existing);

        log.info("修改充电套餐：id={}", id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requirePackage(id);
        chargePackageMapper.deleteById(id);
        log.info("删除充电套餐：id={}", id);
    }

    @Override
    public ChargePackageVO getById(Long id) {
        return toVo(requirePackage(id));
    }

    @Override
    public PageResult page(ChargePackagePageQueryDTO query) {
        // ★ PageHelper 的调用顺序见 ChargerServiceImpl.page 里那段长注释。
        //   这里不需要富化，所以顺序天然是对的 —— 危险之处也正在于此。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<ChargePackage> rows = chargePackageMapper.page(query);
        long total = ((Page<ChargePackage>) rows).getTotal();

        return new PageResult(total, rows.stream().map(this::toVo).toList());
    }

    // ==================== 私有 ====================

    private ChargePackage requirePackage(Long id) {
        ChargePackage pkg = chargePackageMapper.getById(id);
        if (pkg == null) {
            throw new NotFoundException(MessageConstant.PACKAGE_NOT_FOUND);
        }
        return pkg;
    }

    /**
     * 分类校验：必须存在，而且必须是「充电套餐分类」
     *
     * <p>★ 这个方法<b>只做两件事</b>，第二件是 P1a 留下的已知缺口（D13）。
     *
     * <h2>★★ 一、为什么必须校验 {@code type == 1}</h2>
     *
     * <p>因为<b>没有任何别的层会拦它</b>。DDL 把老的 {@code uk(name)}
     * 全局唯一改成了 {@code uk(type, name)} 复合唯一，注释写的原因是
     * 「『快充』既可能是套餐分类也可能是服务分类，全局唯一会误伤」——
     * 这个拆分是对的，但它<b>把「我该用哪个 type」这个责任交给了消费方</b>。
     *
     * <p>而 {@code charge_package.category_id} 是裸的 {@code bigint}，
     * 全项目没有外键。所以指错 type <b>不撞任何约束</b>，
     * 后果是静默的：一个充电套餐挂到「停车服务」分类下，
     * 套餐自己一切正常、管理端列表显示正常，
     * 只有在按 {@code type=1} 过滤的那个页面里怎么都找不到它。
     *
     * <p>★ 一句话：<b>拆 type 本来是为了消除歧义；
     * 不在消费方校验，就等于只增加了歧义而没有秩序。</b>
     *
     * <h2>★★ 二、为什么<b>不</b>校验 {@code status}</h2>
     *
     * <p>这一条同样值得写清楚，因为「存在性 + type 都查了，
     * 顺手把 status 也查了吧」是接下来最容易发生的事。
     *
     * <p>停用的分类<b>不该阻止挂在上面的套餐被编辑</b>。否则会出现：
     * 一个分类因为业务调整被停用了，某个套餐要改个价格，
     * 管理员必须先<b>把分类重新启用</b>、改完价、再停用回去 ——
     * 一个纯粹由校验制造出来的、没有任何业务含义的操作。
     *
     * <p>★ 「停用分类」的语义是<b>「不再出现在新建套餐的下拉框里」</b>，
     * 不是「存量数据失效」。这和 {@code TariffTemplateService} 里
     * 「停用模板不影响已有绑定」是同一个道理 ——
     * 运营开关管的是<b>未来的选择</b>，不是<b>过去的数据</b>。
     * （而 {@code CategoryMapper.listEnabled} 只返回 {@code status=1}，
     *   正是「未来的选择」那一侧的落地。）
     *
     * <h2>★ 三、消息里带上「当前是什么类型」</h2>
     *
     * <p>「分类类型不匹配」这六个字不足以让管理员行动 ——
     * 他需要知道的是自己<b>选中了什么</b>。所以拼上当前分类的名字和类型标签，
     * 和 {@code CategoryServiceImpl.assertDeletable} 带上数量是同一个理由：
     * <b>错误消息要指向下一步动作</b>。
     *
     * @throws NotFoundException      分类不存在（{@code category_id} 指向一个已经被删掉的分类）
     * @throws ParamErrorException    分类存在但 type 不是充电套餐分类
     */
    private void requireCategory(Long categoryId) {
        Category category = categoryMapper.getById(categoryId);
        if (category == null) {
            throw new NotFoundException(MessageConstant.CATEGORY_NOT_FOUND);
        }

        CategoryType type = CategoryType.of(category.getType());
        if (type != CategoryType.CHARGE_PACKAGE) {
            throw new ParamErrorException(MessageConstant.CATEGORY_TYPE_MISMATCH
                    + "：充电套餐只能挂在「" + CategoryType.CHARGE_PACKAGE.getLabel()
                    + "」下，而分类「" + category.getName() + "」是「" + type.getLabel() + "」");
        }
    }

    /**
     * 类型专属字段校验：该有的要有，<b>不该有的必须为空</b>
     *
     * <p>这是本类唯一有真分支的方法，也是 P1a 里唯一值得 mock 单测的地方。
     *
     * <p><b>★ 为什么把四个字段放进一个 {@code Map} 而不是写四组 if。</b>
     * 因为需要的判断是「四个字段里，恰好一个属于当前类型」——
     * 表达成「一个 {@code Map} + 一个『当前类型要求哪个键』」之后，
     * 两个方向各自变成一行 stream 操作，<b>而且新增一个套餐类型时
     * 只需要改一处</b>（{@code PackageType} 枚举加一个值 + 这里的 switch 加一个分支）。
     * 写成四组 if 的话，加类型时要记得改四个地方 ——
     * 而漏改的表现是「新类型能建但没有任何字段被要求」，
     * 一个没有任何报错的、数据层面的漏洞。
     *
     * <p><b>★★ 两个方向必须都查，而且第二个方向更要紧。</b>
     * 见接口类注释：残留字段是静默的，一个时长包里残留的
     * {@code energyKwh=150} 会被 P1c 读走，凭空送出 150 度电。
     *
     * <p>★ 报错消息里列出<b>全部</b>问题字段，而不是遇到第一个就抛。
     * 理由同 {@code TariffValidator}：管理员在填一张表单，
     * 一次只报一个问题意味着「补一个、提交、再被打回」的反复往返。
     */
    private void assertTypeFields(ChargePackage pkg) {
        PackageType type = PackageType.of(pkg.getPackageType());

        // ★ LinkedHashMap 保证消息里字段的顺序稳定 ——
        //   同一份错误数据每次给出的消息应该一字不差，
        //   否则前端按 msg 做分支的逻辑会在某些请求上失配。
        Map<String, Object> typeFields = new LinkedHashMap<>();
        typeFields.put("energyKwh", pkg.getEnergyKwh());
        typeFields.put("durationMinutes", pkg.getDurationMinutes());
        typeFields.put("serviceDiscount", pkg.getServiceDiscount());
        typeFields.put("times", pkg.getTimes());

        String requiredField = switch (type) {
            case ENERGY -> "energyKwh";
            case DURATION -> "durationMinutes";
            case SERVICE_DISCOUNT -> "serviceDiscount";
            case TIMES -> "times";
        };

        // 方向一：类型要求的字段必须有
        if (typeFields.get(requiredField) == null) {
            throw new ParamErrorException(MessageConstant.PACKAGE_TYPE_FIELD_MISMATCH
                    + "：" + type.getLabel() + "必须填写 " + requiredField);
        }

        // 方向二：其余三个必须为空（见方法注释 —— 这一半才是静默的那个）
        List<String> leftovers = typeFields.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(requiredField))
                .filter(entry -> entry.getValue() != null)
                .map(Map.Entry::getKey)
                .toList();

        if (!leftovers.isEmpty()) {
            throw new ParamErrorException(MessageConstant.PACKAGE_TYPE_FIELD_MISMATCH
                    + "：" + type.getLabel() + "不能设置 " + String.join("、", leftovers)
                    + "（这些字段属于别的套餐类型，残留值会被计费逻辑读走）");
        }
    }

    /**
     * 把 DTO 的非空字段覆盖到实体上
     *
     * <p>★ <b>刻意不用 {@code BeanUtils.copyProperties}。</b>
     * 那个方法是全量覆盖，会把 DTO 里的 null 也写进去 ——
     * 于是「不传」变成「清空」，和 {@code <set> + <if>} 的 SQL 语义矛盾。
     * 两边打架的结果是 SQL 赢（{@code <if>} 跳过了 null），
     * 而内存里的对象已经被清空了 —— <b>校验看到的那一行和真正写进去的那一行
     * 不是同一个东西</b>，于是校验形同虚设。
     *
     * <p>★ 逐字段判空很啰嗦，但它是「显式」的：
     * 新增一个可更新字段时，忘了在这里加一行，表现是「那个字段改了不生效」——
     * 一个立刻会被人发现的问题。而用 copyProperties 的话，
     * 忘了处理会表现为「那个字段被静默清空」—— 难发现得多。
     */
    private void mergeNonNull(ChargePackageUpdateDTO dto, ChargePackage target) {
        if (dto.getName() != null) {
            target.setName(dto.getName());
        }
        if (dto.getCategoryId() != null) {
            target.setCategoryId(dto.getCategoryId());
        }
        if (dto.getPrice() != null) {
            target.setPrice(dto.getPrice());
        }
        if (dto.getOriginPrice() != null) {
            target.setOriginPrice(dto.getOriginPrice());
        }
        if (dto.getEnergyKwh() != null) {
            target.setEnergyKwh(dto.getEnergyKwh());
        }
        if (dto.getDurationMinutes() != null) {
            target.setDurationMinutes(dto.getDurationMinutes());
        }
        if (dto.getTimes() != null) {
            target.setTimes(dto.getTimes());
        }
        if (dto.getServiceDiscount() != null) {
            target.setServiceDiscount(dto.getServiceDiscount());
        }
        if (dto.getValidDays() != null) {
            target.setValidDays(dto.getValidDays());
        }
        if (dto.getDescription() != null) {
            target.setDescription(dto.getDescription());
        }
        if (dto.getImage() != null) {
            target.setImage(dto.getImage());
        }
        if (dto.getStatus() != null) {
            target.setStatus(dto.getStatus());
        }
        if (dto.getSort() != null) {
            target.setSort(dto.getSort());
        }
    }

    private ChargePackageVO toVo(ChargePackage pkg) {
        ChargePackageVO vo = new ChargePackageVO();
        BeanUtils.copyProperties(pkg, vo);
        vo.setPackageTypeLabel(labelOfType(pkg.getPackageType()));
        vo.setStatusLabel(labelOfStatus(pkg.getStatus()));
        return vo;
    }

    /**
     * ★ 派生字段的降级策略见 {@code StationServiceImpl.labelOf} ——
     * 只读路径上的派生字段失败返回 null，不抛。
     */
    private String labelOfType(Integer packageType) {
        if (packageType == null) {
            return null;
        }
        try {
            return PackageType.of(packageType).getLabel();
        } catch (ParamErrorException e) {
            log.warn("套餐类型无法识别，packageType={}", packageType);
            return null;
        }
    }

    /**
     * 套餐状态是真的二值（{@code 1上架 0下架}），
     * 所以这里能直接映射，不需要枚举 —— {@code StatusConstant} 就是为它准备的。
     */
    private String labelOfStatus(Integer status) {
        if (status == null) {
            return null;
        }
        // ★ 常量写在左边：status 为 null 时 Objects.equals 也不会 NPE，
        //   而 `status == StatusConstant.ENABLE` 这种写法比较的是 Integer 引用，
        //   只因 Integer 缓存了 -128~127 才恰好正确 —— 一个「碰巧能跑」的隐患。
        //   同一个理由见 StaffServiceImpl.login 里那段更长的说明。
        if (StatusConstant.ENABLE.equals(status)) {
            return "上架";
        }
        if (StatusConstant.DISABLE.equals(status)) {
            return "下架";
        }
        log.warn("套餐状态无法识别，status={}", status);
        return null;
    }
}
