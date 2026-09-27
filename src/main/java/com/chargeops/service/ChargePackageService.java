package com.chargeops.service;

import com.chargeops.dto.ChargePackageDTO;
import com.chargeops.dto.ChargePackagePageQueryDTO;
import com.chargeops.dto.ChargePackageUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.ChargePackageVO;

/**
 * 充电套餐服务
 *
 * <p>类型专属字段：{@code package_type} = 1 电量包（{@code energyKwh}）/
 * 2 时长包（{@code durationMinutes}）/ 3 服务费折扣包（{@code serviceDiscount}）/
 * 4 次卡（{@code times}），各自必填，且其余三个必须为 null。MySQL 没有跨列的
 * 「条件必填」约束，这条规则只能靠本层守。静默的是后半句：一个从「150 度电包」
 * 改成「30 天时长包」的套餐如果 {@code energyKwh} 还留着 150，应用套餐时就会
 * 在一个时长包上白白送出 150 度电，而每一步都没报错。
 *
 * <p> 更新时校验的对象是「合并后的行」，不是 DTO。只改价格的管理员不会传
 * {@code energyKwh}，那个值只能从库里来，拿 DTO 去校验会误判成「电量包缺电量」。
 *
 * <p> {@code stationId=0} 是「全平台」，不是「没填」：唯一索引
 * {@code uk_pkg_name_station (name, station_id)} 里的 {@code station_id}
 * 永不为 null，因为 MySQL 的唯一索引不约束 NULL。
 */
public interface ChargePackageService {

    /**
     * 新增套餐
     *
     * @return 新套餐的 id
     * @throws com.chargeops.exception.NotFoundException     分类不存在
     * @throws com.chargeops.exception.ParamErrorException   类型专属字段不匹配，或分类不是「充电套餐分类」
     * @throws org.springframework.dao.DuplicateKeyException 同一作用域下套餐名已存在
     */
    Long create(ChargePackageDTO chargePackageDTO);

    /**
     * 修改套餐
     *
     * <p> 不能改 {@code packageType}（它决定哪些列必填，而「清空旧类型的字段」
     * 在局部更新语义下做不到），也不能改 {@code stationId}（它参与唯一键）。
     *
     * <p> 分类校验在 update 里也必须再跑一次：{@code categoryId} 是可改字段，
     * 「先建一个合法套餐、再把它改到 {@code type=2} 的分类下」是一条能完全
     * 绕过 create 校验的路径。
     *
     * @throws com.chargeops.exception.NotFoundException     套餐不存在，或分类不存在
     * @throws com.chargeops.exception.ParamErrorException   合并后的行类型专属字段不匹配，
     * 或目标分类不是「充电套餐分类」
     */
    void update(Long id, ChargePackageUpdateDTO chargePackageUpdateDTO);

    /**
     * 删除套餐（硬删）
     *
     * <p> 硬删只用于「名字建错了、一次都没卖出去」的场景，但必须支持，
     * 因为 {@code uk_pkg_name_station} 不看 status。
     */
    void delete(Long id);

    /** 按 id 查详情。查不到抛 {@code NotFoundException} */
    ChargePackageVO getById(Long id);

    /**
     * 分页查询
     *
     * <p> {@code stationId} 是精确匹配：查 {@code stationId=1} 时不会把全平台套餐
     * （{@code stationId=0}）带出来。这是管理端的语义，不是车主端的「我能买哪些」。
     */
    PageResult page(ChargePackagePageQueryDTO query);
}
