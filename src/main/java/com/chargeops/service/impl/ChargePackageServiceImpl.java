package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
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
 * <p>唯一有真分支的方法是 {@link #assertTypeFields}，其余逻辑薄到
 * mapper 集成测试已经覆盖。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChargePackageServiceImpl implements ChargePackageService {

    private final ChargePackageMapper chargePackageMapper;

    /**
     * 这个依赖只服务 {@link #requireCategory} 的 type 校验。
     *
     * <p> 注入的是别的域的 Mapper 而不是 {@code CategoryService}：两个 Service
     * 互相注入是 Spring 允许但很难读的结构（{@code CategoryServiceImpl} 也依赖
     * {@code ChargePackageMapper}），依赖图里没有 Service → Service 的边。
     */
    private final CategoryMapper categoryMapper;

    @Override
    @Transactional
    public Long create(ChargePackageDTO chargePackageDTO) {
        ChargePackage pkg = new ChargePackage();
        BeanUtils.copyProperties(chargePackageDTO, pkg);

        // 四个默认值必须在这里兜底：{"stationId": null} 是显式 null，会覆盖掉
        // 字段初始值，而 insert 是全列插入，NOT NULL 列拿到 null 就是 errno 1048。
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

        // 顺序：先跑纯内存的字段校验，再跑要查库的分类校验 ——
        // 两者都错时先报字段那个，它更便宜，也更靠前一步。
        assertTypeFields(pkg);

        // 分类必须存在，而且必须是「充电套餐分类」
        requireCategory(pkg.getCategoryId());

        chargePackageMapper.insert(pkg);

        log.info("新增充电套餐成功：id={}, name={}, packageType={}, stationId={}",
                pkg.getId(), pkg.getName(), pkg.getPackageType(), pkg.getStationId());
        return pkg.getId();
    }

    @Override
    @Transactional
    public void update(Long id, ChargePackageUpdateDTO chargePackageUpdateDTO) {
        // 校验的对象是合并后的行，不是 DTO：只改价格的管理员不会传 energyKwh，
        // 拿 DTO 去校验会误判成「电量包缺电量」，把完全合法的请求打回。
        ChargePackage existing = requirePackage(id);

        // BeanUtils.copyProperties 是全量覆盖，会把 DTO 里的 null 也写进去，
        // 「不传」变成「清空」—— 和 <set> + <if> 的语义直接矛盾（DTO 说清空、
        // SQL 说不动，SQL 赢），而校验用的正是内存里的那一行。
        mergeNonNull(chargePackageUpdateDTO, existing);

        assertTypeFields(existing);

        // categoryId 在 ChargePackageUpdateDTO 里是可改字段，只在 create 里
        // 校验的话，「先建一个合法套餐、再把它改到一个 type=2 的分类下」
        // 就是一条完全绕过校验的路径。
        requireCategory(existing.getCategoryId());

        // 用合并后的整体去更新：「校验的那一行」和「写进去的那一行」是同一个对象
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
        // 这里不需要富化，顺序天然是对的 —— 危险之处也正在于此。
        // PageHelper 的调用顺序见 ChargerServiceImpl.page 里那段长注释。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<ChargePackage> rows = chargePackageMapper.page(query);
        long total = ((Page<ChargePackage>) rows).getTotal();

        return new PageResult(total, rows.stream().map(this::toVo).toList());
    }

    // ---------- 私有 ----------

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
     * <p> 必须校验 {@code type == 1}，因为没有任何别的层会拦它：
     * {@code charge_package.category_id} 是裸的 {@code bigint}，全项目没有外键，
     * 指错 type 不撞任何约束，后果静默 —— 一个充电套餐挂到「停车服务」分类下，
     * 套餐自己一切正常，只有在按 {@code type=1} 过滤的那个页面里怎么都找不到它。
     *
     * <p> 不校验 {@code status}：「停用分类」的语义是「不再出现在新建套餐的下拉框里」，
     * 不是「存量数据失效」。查了 status 的话，一个分类被停用后，管理员要改某个套餐的
     * 价格必须先把分类启用、改完再停用回去。
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
     * 类型专属字段校验：该有的要有，不该有的必须为空
     *
     * <p> 把四个字段放进一个 {@code Map} 而不是写四组 if：需要的判断是「四个字段里
     * 恰好一个属于当前类型」，写成四组 if 的话加类型时要记得改四个地方 ——
     * 漏改的表现是「新类型能建但没有任何字段被要求」，一个没有任何报错的数据漏洞。
     *
     * <p> 两个方向必须都查，而且第二个方向更要紧：残留字段是静默的，一个时长包里
     * 残留的 {@code energyKwh=150} 会被计费逻辑读走，凭空送出 150 度电。
     *
     * <p> 报错消息里列出全部问题字段，而不是遇到第一个就抛 —— 管理员在填一张表单，
     * 一次只报一个问题意味着反复往返。
     */
    private void assertTypeFields(ChargePackage pkg) {
        PackageType type = PackageType.of(pkg.getPackageType());

        // LinkedHashMap 保证消息里字段的顺序稳定 —— 同一份错误数据每次给出的
        // 消息应该一字不差，否则前端按 msg 做分支的逻辑会失配。
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

        // 方向二：其余三个必须为空（这一半才是静默的那个）
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
     * <p> 刻意不用 {@code BeanUtils.copyProperties}：它是全量覆盖，会把 DTO 里的
     * null 也写进去，而内存里的对象被清空之后，校验看到的那一行和真正写进去的
     * 那一行就不是同一个东西了。
     *
     * <p> 逐字段判空很啰嗦，但它是显式的：新增一个可更新字段时忘了在这里加一行，
     * 表现是「那个字段改了不生效」，立刻会被发现；用 copyProperties 的话，
     * 忘了处理会表现为「那个字段被静默清空」。
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

    /**
     * 映射本身在 {@link ChargePackageVO#from}，车主端的货架
     * （{@code MemberPackageServiceImpl.listAvailable}）也要用同一份 ——
     * 留两份的话，「{@code package_type = 1} 显示成什么」和「未知类型的降级策略」
     * 就有两个答案。
     */
    private ChargePackageVO toVo(ChargePackage pkg) {
        return ChargePackageVO.from(pkg);
    }
}
