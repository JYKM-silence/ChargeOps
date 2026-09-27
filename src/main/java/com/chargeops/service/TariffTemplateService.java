package com.chargeops.service;

import com.chargeops.dto.TariffTemplateDTO;
import com.chargeops.dto.TariffTemplatePageQueryDTO;
import com.chargeops.dto.TariffTemplateUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.TariffTemplateVO;

import java.util.List;

/**
 * 计费模板服务
 *
 * <p>模板的规则不可变，一旦被任何站点绑定：模板 id 在下单那一刻冻结进
 * {@code charging_order}，如果规则还能被改，已冻结的 id 就会指向被改过的规则，
 * 而且是静默失效 —— 订单金额照样算得出来，只是和用户启动时看到的不一样。
 * 所以 {@code saveRules} 有一道守卫：{@code StationTariffMapper.countByTemplateId}
 * 只要返回非 0 就拒绝修改规则。要调价就新建一个模板版本，用
 * {@code station_tariff.effective_from} 绑定生效期。
 *
 * <p> 改名称、地区、说明、{@code status} 都不经过守卫：它们不改变任何一条规则，
 * 冻结的是规则，不是模板头。
 */
public interface TariffTemplateService {

    /**
     * 新增模板（头 + 全部规则，一次写完）
     *
     * <p> 校验规则在前、写库在后 —— 一个规则有缺口的模板不该先落库拿到一个 id 再回滚，
     * 那样自增 id 会被白白消耗掉。
     *
     * @return 新模板的 id
     * @throws com.chargeops.exception.ParamErrorException   规则未完整覆盖 24 小时，或规则之间有重叠
     * @throws org.springframework.dao.DuplicateKeyException 模板编码已存在
     */
    Long create(TariffTemplateDTO tariffTemplateDTO);

    /**
     * 修改模板
     *
     * <p> {@code rules} 不传表示「只改头信息」；传了就受不可变守卫约束。
     *
     * @throws com.chargeops.exception.DataConflictException 模板已被站点绑定，不允许改规则
     */
    void update(Long id, TariffTemplateUpdateDTO tariffTemplateUpdateDTO);

    /**
     * 删除模板（硬删）
     *
     * <p> 被任何 {@code station_tariff} 行引用时拒绝 —— 删掉一个正在被站点使用的模板，
     * 那些站点的电价解析会返回空结果，表现为「这个站点的订单算不出钱」。
     *
     * <p> 规则不会被级联删除，会留下孤儿行，这是有意的：孤儿规则至少是可查、
     * 可人工清理的，而级联删除会让「删掉模板」这个动作顺手抹掉 8 行计价数据。
     *
     * @throws com.chargeops.exception.DataConflictException 模板已被站点绑定
     */
    void delete(Long id);

    /** 按 id 查详情（含规则） */
    TariffTemplateVO getById(Long id);

    /** 分页查询（不含规则）。一页 10 个模板就是 80 条规则，而列表页只显示模板名和地区 */
    PageResult page(TariffTemplatePageQueryDTO query);

    /** 全部启用中的模板，供「新建绑定」的下拉框使用。不分页：模板是配置数据，一个地区通常只有个位数个 */
    List<TariffTemplateVO> listAllEnabled();
}
