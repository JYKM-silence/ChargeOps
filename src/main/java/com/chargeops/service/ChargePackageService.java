package com.chargeops.service;

import com.chargeops.dto.ChargePackageDTO;
import com.chargeops.dto.ChargePackagePageQueryDTO;
import com.chargeops.dto.ChargePackageUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.ChargePackageVO;

/**
 * 充电套餐服务
 *
 * <p>形状沿用 {@link StationService}，但多一条别的域都没有的规则。
 *
 * <h2>★★ 「类型专属字段」的校验是本域唯一的复杂点</h2>
 *
 * <pre>
 *   package_type = 1 电量包        → energyKwh       必填，其余三个必须为 null
 *   package_type = 2 时长包        → durationMinutes 必填，其余三个必须为 null
 *   package_type = 3 服务费折扣包  → serviceDiscount 必填，其余三个必须为 null
 *   package_type = 4 次卡          → times           必填，其余三个必须为 null
 * </pre>
 *
 * <p>MySQL 没有「条件必填」这种约束能力（{@code CHECK} 在 8.0 里能写，
 * 但它跨列、错误消息不可控，而且这个项目已经在应用层处理所有跨列规则）。
 * 所以这条规则只能靠本层守。
 *
 * <p><b>★ 两个方向都要校验。</b>「必填的要有」只是其中一半 ——
 * 更要紧的是<b>「其余三个必须为 null」</b>，因为它是<b>静默</b>的：
 * 一个从「150 度电包」改成「30 天时长包」的套餐，如果 {@code energyKwh}
 * 还留着 150，P1c 应用套餐时读到它，就会在一个时长包上白白送出 150 度电。
 * 金额对不上，但每一步都没报错。
 *
 * <p><b>★ 而且更新时校验的对象是「合并后的行」，不是 DTO。</b>
 * 只改价格的管理员不会传 {@code energyKwh} —— 那个字段的值只能从库里来。
 * 拿 DTO 去校验会把它误判成「电量包缺电量」。
 * 反过来，如果 DTO 传了不是本类型的字段，合并后的行会同时有两个非空值，
 * 而「其余必须为 null」那一半会拒绝它。<b>一个条件同时守住了两个方向。</b>
 *
 * <h2>★ {@code stationId=0} 是「全平台」，不是「没填」</h2>
 *
 * <p>唯一索引 {@code uk_pkg_name_station (name, station_id)} 里的
 * {@code station_id} <b>永不为 null</b> —— 因为 MySQL 的唯一索引不约束 NULL，
 * 用 NULL 会让「全平台」这个作用域完全不受保护，同名套餐能被无限重复创建。
 * 见 {@code ChargePackage} 类注释第二节。
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
     * <p>★ 不能改 {@code packageType}（它决定哪些列必填，而「清空旧类型的字段」
     * 在局部更新语义下做不到），也不能改 {@code stationId}（它参与唯一键，
     * 而且是已售出售后归属的一部分）。见 {@code ChargePackageUpdateDTO}。
     *
     * <p>★ <b>分类校验在 update 里也必须再跑一次</b>：
     * {@code categoryId} 是可改字段，「先建一个合法套餐、再把它改到
     * {@code type=2} 的分类下」是一条能完全绕过 create 校验的路径。
     * 这是一类很容易漏的模式 —— <b>校验要覆盖每一条能到达那个字段的写路径。</b>
     *
     * @throws com.chargeops.exception.NotFoundException     套餐不存在，或分类不存在
     * @throws com.chargeops.exception.ParamErrorException   合并后的行类型专属字段不匹配，
     *                                                       或目标分类不是「充电套餐分类」
     */
    void update(Long id, ChargePackageUpdateDTO chargePackageUpdateDTO);

    /**
     * 删除套餐（硬删）
     *
     * <p>★ 常规的「退役」手段是 {@code status = 0 下架}，硬删只用于
     * 「名字建错了、一次都没卖出去」的场景 —— 但那个场景必须支持，
     * 因为 {@code uk_pkg_name_station} 不看 status。
     *
     * @throws com.chargeops.exception.NotFoundException 套餐不存在
     */
    void delete(Long id);

    /**
     * 按 id 查详情
     *
     * @throws com.chargeops.exception.NotFoundException 套餐不存在
     */
    ChargePackageVO getById(Long id);

    /**
     * 分页查询
     *
     * <p>★ {@code stationId} 是<b>精确匹配</b>：查 {@code stationId=1} 时
     * <b>不会</b>把全平台套餐（{@code stationId=0}）带出来。
     * 这是管理端的语义（一份可编辑的清单），而不是车主端的
     * （「我能买哪些」）。见 {@code ChargePackagePageQueryDTO} 类注释。
     */
    PageResult page(ChargePackagePageQueryDTO query);
}
