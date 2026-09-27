package com.chargeops.service.impl;

import com.chargeops.billing.TariffRuleSnapshot;
import com.chargeops.billing.TariffValidator;
import com.chargeops.constant.MessageConstant;
import com.chargeops.constant.StatusConstant;
import com.chargeops.dto.TariffRuleDTO;
import com.chargeops.dto.TariffTemplateDTO;
import com.chargeops.dto.TariffTemplatePageQueryDTO;
import com.chargeops.dto.TariffTemplateUpdateDTO;
import com.chargeops.entity.TariffRule;
import com.chargeops.entity.TariffTemplate;
import com.chargeops.enumeration.PeriodType;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.StationTariffMapper;
import com.chargeops.mapper.TariffRuleMapper;
import com.chargeops.mapper.TariffTemplateMapper;
import com.chargeops.result.PageResult;
import com.chargeops.service.TariffTemplateService;
import com.chargeops.utils.TimeCodec;
import com.chargeops.vo.TariffRuleVO;
import com.chargeops.vo.TariffTemplateVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费模板服务实现
 *
 * <p>本服务跨两张表写（模板头 + 规则），两者必须在同一个事务里完成，否则会出现
 * 「头改了、规则没改」这种半成品。规则另外受一条业务级不可变守卫约束，见 {@link #saveRules}。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TariffTemplateServiceImpl implements TariffTemplateService {

    private final TariffTemplateMapper tariffTemplateMapper;
    private final TariffRuleMapper tariffRuleMapper;
    private final StationTariffMapper stationTariffMapper;

    // ---------- 新增 ----------

    @Override
    @Transactional
    public Long create(TariffTemplateDTO tariffTemplateDTO) {
        // 先校验规则，再写任何东西：顺序反过来会让自增 id 被白白消耗掉，
        // 这张表每被拒绝一次就跳一个号，运维看到 id 不连续会以为有数据被删过。
        // 而且校验必须一次看到全集—— 「24 小时覆盖有没有缺口」和「相邻两条有没有
        // 重叠」都是跨行的性质，这正是 rules 内嵌在模板 DTO 里、而不是另开一个
        // 「加一条规则」接口的原因。
        List<TariffRuleSnapshot> snapshots = toSnapshots(tariffTemplateDTO.getRules());
        TariffValidator.validate(snapshots);

        TariffTemplate template = new TariffTemplate();
        // 实体上没有 rules 属性，copyProperties 自然不会带过去 —— 模板头与规则是两张表，
        // 组装只在 Service 里发生（见 getById）。
        BeanUtils.copyProperties(tariffTemplateDTO, template);

        // status 的兜底：{"status": null} 是显式 null，会覆盖字段初始值，
        // 而本列的 DDL 默认值在全列插入下不生效。
        if (template.getStatus() == null) {
            template.setStatus(StatusConstant.ENABLE);
        }

        tariffTemplateMapper.insert(template);

        // 同一事务里紧接着写规则：两步都成功才算「模板建好了」——
        // 一个只有头没有规则的模板看起来完全正常，直到有人用它绑定站点，
        // 才发现计费时全部时段无价。
        saveRules(template.getId(), tariffTemplateDTO.getRules());

        log.info("新增计费模板成功：id={}, code={}, 规则 {} 条",
                template.getId(), template.getCode(), tariffTemplateDTO.getRules().size());
        return template.getId();
    }

    // ---------- 修改 ----------

    @Override
    @Transactional
    public void update(Long id, TariffTemplateUpdateDTO tariffTemplateUpdateDTO) {
        requireTemplate(id);

        TariffTemplate template = new TariffTemplate();
        BeanUtils.copyProperties(tariffTemplateUpdateDTO, template);
        template.setId(id);

        // 先改头。改名称/地区/说明/status 都不受守卫约束 —— 它们不改变任何一条规则，
        // 所以不影响已冻结的订单。
        tariffTemplateMapper.update(template);

        // rules 为 null（或空列表）表示「不修改规则」。空列表也当成「不修改」是有意的：
        // 让一个空数组去清空一个正在用的模板的规则，会让所有用这个模板的站点在下单时
        // 算出空金额 —— 那个后果和「用户手滑提交了一个空表单」完全不成比例。
        List<TariffRuleDTO> rules = tariffTemplateUpdateDTO.getRules();
        if (rules != null && !rules.isEmpty()) {
            saveRules(id, rules);
        }

        log.info("修改计费模板：id={}, 规则{}", id,
                rules == null || rules.isEmpty() ? "未改动" : "已整体替换为 " + rules.size() + " 条");
    }

    // ---------- 删除 ----------

    @Override
    @Transactional
    public void delete(Long id) {
        requireTemplate(id);

        if (stationTariffMapper.countByTemplateId(id) > 0) {
            throw new DataConflictException(MessageConstant.TEMPLATE_IN_USE);
        }

        // 删头不级联删规则，见接口类注释：孤儿规则会在库里留下 template_id 指向
        // 不存在模板的行，但那是可查、可人工清理的（TariffRuleMapper.deleteByTemplateId），
        // 而级联删除会让「删掉模板」这个动作顺手抹掉 8 行计价数据，不可恢复。
        tariffTemplateMapper.deleteById(id);

        log.info("删除计费模板：id={}", id);
    }

    // ---------- 查询 ----------

    @Override
    public TariffTemplateVO getById(Long id) {
        TariffTemplate template = requireTemplate(id);

        TariffTemplateVO vo = toVo(template);
        // 详情才带规则，列表和下拉框都不带。规则按 start_time 排序 ——
        // 零填充的 HH:mm:ss 字典序等于时间序，而 "24:00:00" 自然排在最后。
        vo.setRules(tariffRuleMapper.listByTemplateId(id).stream()
                .map(this::toRuleVo)
                .toList());
        return vo;
    }

    @Override
    public PageResult page(TariffTemplatePageQueryDTO query) {
        // PageHelper 的调用顺序见 ChargerServiceImpl.page 里那段长注释。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<TariffTemplate> rows = tariffTemplateMapper.page(query);
        long total = ((Page<TariffTemplate>) rows).getTotal();

        return new PageResult(total, rows.stream().map(this::toVo).toList());
    }

    @Override
    public List<TariffTemplateVO> listAllEnabled() {
        return tariffTemplateMapper.listAllEnabled().stream().map(this::toVo).toList();
    }

    // ---------- 私有 ----------

    private TariffTemplate requireTemplate(Long id) {
        TariffTemplate template = tariffTemplateMapper.getById(id);
        if (template == null) {
            throw new NotFoundException(MessageConstant.TEMPLATE_NOT_FOUND);
        }
        return template;
    }

    /**
     * 整体替换一个模板的规则，带不可变守卫
     *
     * <p> 守卫查的是 {@code station_tariff} 而不是订单表：「有没有站点在用这个模板」比
     * 「有没有订单冻结过它」更早、更全 —— 被冻结的前提一定是先被绑定，而一个刚被绑定的
     * 模板哪怕还没有订单用它，也不该被改，因为下一笔订单随时会来。已知缺口是
     * 「有订单冻结过它、但绑定已经被删掉」这个组合，见 {@code TariffTemplateMapper.deleteById}。
     *
     * <p> 规则是整体替换（{@code deleteByTemplateId} + {@code batchInsert}），不是逐条更新：
     * 逐条更新会产生「中间状态」（删了一条尖峰还没插新的，此刻这个模板就不满足 24 小时覆盖），
     * 而中间状态一旦能被观察到，就会有人去处理它，然后处理逻辑扩散到所有读路径上。
     */
    private void saveRules(Long templateId, List<TariffRuleDTO> rules) {
        int boundCount = stationTariffMapper.countByTemplateId(templateId);
        if (boundCount > 0) {
            log.warn("拒绝修改模板规则：模板已被 {} 条站点绑定引用，templateId={}", boundCount, templateId);
            throw new DataConflictException(MessageConstant.TEMPLATE_IN_USE);
        }

        // 校验放在删除之前：反过来，一个校验失败的请求会先把旧规则删掉再报错 ——
        // 事务回滚能撤销，但那个瞬间库里是空的，而且回滚依赖 @Transactional 生效。
        TariffValidator.validate(toSnapshots(rules));

        // 时间必须在这里显式写 —— tariff_rule 表没有 user 两列，所以 batchInsert 上
        // 没有 @AutoFill（标了的话反射找不到 setCreateUser(Long)，会抛 IllegalStateException）。
        LocalDateTime now = LocalDateTime.now();

        tariffRuleMapper.deleteByTemplateId(templateId);
        // batchInsert 只收一个 List —— templateId 在每一行实体上已经有了，
        // 于是「传错 templateId」变成不可能发生的事：它不在参数里。
        tariffRuleMapper.batchInsert(toRuleEntities(templateId, rules, now));

        log.info("模板规则已整体替换：templateId={}, 新规则 {} 条", templateId, rules.size());
    }

    /**
     * DTO 列表 → 快照列表，交给 {@link TariffValidator} 的那一份
     *
     * <p> 顺带完成两件事：时间归一化（{@code "08:00"} → {@code "08:00:00"}，零填充的
     * {@code HH:mm:ss} 是「字典序等于时间序」这条性质的前提，而 {@code ORDER BY start_time}
     * 依赖它），以及 {@code servicePrice} 为空补 {@code 0}（本列 NOT NULL）。
     *
     * <p> 单行约束（时间格式非法、起点晚于终点、单价为负）由 {@code TariffRuleSnapshot}
     * 的构造器负责，这里不重复检查 —— 单行约束只在一个地方守。
     */
    private List<TariffRuleSnapshot> toSnapshots(List<TariffRuleDTO> rules) {
        if (rules == null) {
            throw new ParamErrorException("计费规则不能为空，必须完整覆盖 00:00:00 ~ 24:00:00");
        }
        return rules.stream()
                .map(dto -> TariffRuleSnapshot.of(
                        TimeCodec.normalize(dto.getStartTime()),
                        TimeCodec.normalize(dto.getEndTime()),
                        dto.getPeriodType(),
                        dto.getEnergyPrice(),
                        dto.getServicePrice() == null ? BigDecimal.ZERO : dto.getServicePrice()))
                .toList();
    }

    private List<TariffRule> toRuleEntities(Long templateId, List<TariffRuleDTO> rules, LocalDateTime now) {
        // seq 不传就按数组下标填 —— 前端的表单项通常没有「序号」这个概念。
        // 它不是约束（uk_tariff_rule_tpl_start 才是），这个字段只影响展示，
        // 查询本来就按 start_time 排。
        return java.util.stream.IntStream.range(0, rules.size())
                .mapToObj(i -> {
                    TariffRuleDTO dto = rules.get(i);
                    return TariffRule.builder()
                            .templateId(templateId)
                            .periodType(dto.getPeriodType())
                            .startTime(TimeCodec.normalize(dto.getStartTime()))
                            .endTime(TimeCodec.normalize(dto.getEndTime()))
                            .energyPrice(dto.getEnergyPrice())
                            .servicePrice(dto.getServicePrice() == null
                                    ? BigDecimal.ZERO : dto.getServicePrice())
                            .seq(dto.getSeq() == null ? i : dto.getSeq())
                            .createTime(now)
                            .updateTime(now)
                            .build();
                })
                .toList();
    }

    private TariffTemplateVO toVo(TariffTemplate template) {
        TariffTemplateVO vo = new TariffTemplateVO();
        BeanUtils.copyProperties(template, vo);
        // rules 刻意不在这里设置：分页和下拉框都不该带上它，详情接口自己会 set。
        // 留 null 而不是空 List —— 字段存在但为 null 至少是可预期的信号。
        vo.setStatusLabel(labelOfStatus(template.getStatus()));
        return vo;
    }

    private TariffRuleVO toRuleVo(TariffRule rule) {
        TariffRuleVO vo = new TariffRuleVO();
        BeanUtils.copyProperties(rule, vo);
        vo.setPeriodTypeLabel(labelOfPeriod(rule.getPeriodType()));
        return vo;
    }

    /**
     * 模板状态是真正的二值（{@code 1启用 0停用}），所以能直接映射 ——
     * 和 {@code charge_package.status} 一样，是全项目仅有的两个能用 {@link StatusConstant} 的地方。
     * 站点/桩/枪的状态值域完全不同（它们是 3 / 5 / 6 个值），别因为「都是状态」就共用一套常量。
     */
    private String labelOfStatus(Integer status) {
        if (status == null) {
            return null;
        }
        if (StatusConstant.ENABLE.equals(status)) {
            return "启用";
        }
        if (StatusConstant.DISABLE.equals(status)) {
            return "停用";
        }
        log.warn("模板状态无法识别，status={}", status);
        return null;
    }

    private String labelOfPeriod(Integer periodType) {
        if (periodType == null) {
            return null;
        }
        try {
            return PeriodType.of(periodType).getLabel();
        } catch (ParamErrorException e) {
            log.warn("时段类型无法识别，periodType={}", periodType);
            return null;
        }
    }
}
