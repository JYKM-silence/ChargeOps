package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.ChargePackagePageQueryDTO;
import com.chargeops.entity.ChargePackage;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 充电套餐数据访问
 *
 * <p>注解 / XML 的分工规则见 {@link StationMapper} 的类注释。
 *
 * <h2>★ 这个 Mapper 不负责「类型专属列」的校验</h2>
 *
 * <p>「{@code package_type=1} 时 {@code energy_kwh} 必填、其余三个为 NULL」
 * 这条规则<b>不在这一层</b>，在 {@code ChargePackageServiceImpl.assertTypeFields}。
 *
 * <p>理由是它需要看<b>合并后的整行</b>：更新时类型取自库里读出的行、
 * 专属字段取自请求体，DTO 层和 Mapper 层都只拿得到其中一半。
 * 详见 {@link com.chargeops.enumeration.PackageType} 的类注释。
 */
@Mapper
public interface ChargePackageMapper {

    ChargePackage getById(@Param("id") Long id);

    /**
     * 新增套餐
     *
     * <p>★★ 这条 SQL 是全列插入，DDL 的 {@code DEFAULT} 只在列<b>没有出现在
     * INSERT 语句里</b>时生效 —— 所以这些 {@code NOT NULL} 列<b>必须由调用方
     * 给出</b>，漏一个就是 errno 1048：{@code stationId}（0 = 全平台）/
     * {@code validDays}（0 = 永久）/ {@code status}（1 = 上架）/ {@code sort}（0）。
     * 它们的默认值写在 {@code ChargePackageDTO} 的<b>字段初始值</b>上，
     * 而不是藏在 DDL 里只有读建表语句的人知道。
     *
     * <p>其余 {@code NOT NULL} 列（{@code name / categoryId / packageType / price}）
     * 没有默认值，应该在 DTO 上用 {@code @NotNull} / {@code @NotBlank} 拦，
     * 让它们变成 400 而不是 500。
     *
     * <p>★ <b>{@code stationId} 尤其不能漏</b>：{@code uk_pkg_name_station (name, station_id)}
     * 是唯一键，而 MySQL 的唯一索引<b>不约束 NULL</b> ——
     * 如果 {@code station_id} 是 NULL，同一个名字可以被无限次创建，
     * 「套餐名不重复」这条约束会静默失效。
     */
    @AutoFill(OperationType.INSERT)
    void insert(ChargePackage chargePackage);

    /**
     * 按主键更新非空字段
     *
     * <p>★ 四个类型专属列走 {@code <if test="x != null">}，传 null 只会被
     * <b>跳过</b>而不是被清空 —— 「把一个字段清掉」在这个写法下做不到。
     * 这一点不影响正确性，因为 {@code packageType} 不能改
     * （{@code ChargePackageUpdateDTO} 里没有这个字段），
     * 「电量包改成时长包、顺手把 energy_kwh 清掉」这个场景不存在。
     *
     * <p>★ 哪天允许改类型，它会立刻变成一个<b>静默的错误</b>：专属列留着旧值，
     * 而 {@code assertTypeFields} 恰好会拦住它（这正是那条校验的意义）——
     * 但拦住之后管理员没有任何办法清掉它，只能删掉重建。
     * 到那时要改成「合并整行后全量 update」的写法。
     */
    @AutoFill(OperationType.UPDATE)
    void update(ChargePackage chargePackage);

    /**
     * 分页查询
     *
     * <p>★ {@code PageHelper.startPage} 的调用约定见 {@link StationMapper#page}。
     */
    List<ChargePackage> page(ChargePackagePageQueryDTO query);

    /**
     * ★ <b>车主端</b>「我在这个站点能买哪些套餐」
     *
     * <p>★★ <b>它和 {@link #page} 是两个查询，不是一个查询加一个开关。</b>★★
     * 区别只有一处，但那一处是语义性的：管理员按站点查是<b>精确匹配</b>
     * （「科技园站配了哪些套餐」是一份可编辑的清单，全平台套餐不在本站的管理范围内，
     * 混进来会让人以为它属于本站）；而车主问的是<b>「我能买哪些」</b> ——
     * 全平台套餐当然能买，所以是 {@code station_id IN (0, ?)}。
     *
     * <p>★ <b>做成一个布尔参数会让这个区别依赖调用方传对那个参数</b>，
     * 而传错的后果是管理员看到一个错误的清单（或车主看到一个空的货架）。
     * 完整论证见 {@code ChargePackagePageQueryDTO} 的类注释。
     *
     * <p>★ <b>{@code stationId} 必传，没有「不限站点」这个变体。</b>
     * 这不是偷懒 —— 让它可以为 {@code null} 就必须在 SQL 里写一个
     * {@code <if>} 来跳过范围条件，而那个分支的后果是
     * <b>「忘了传站点」变成「返回全平台所有套餐」</b>，一个静默的范围放大。
     * 车主端的真实 UX 也总是「在某个站扫码 → 看这个站的套餐」。
     * 真需要无站点上下文的商城页时，另加一条语句。
     *
     * <p>★ <b>{@code status = 1} 直接写死在 XML 里，不做参数。</b>
     * 这个方法只有一个用途，给它加一个 status 参数等于允许调用方问
     * 「这个站下架了哪些套餐」—— 那个问题属于管理端，而管理端用 {@link #page}。
     *
     * <p>★ 走 {@code idx_pkg_station_status (station_id, status)}：
     * {@code IN (0, ?)} 是两个等值区间，各自再按 status 过滤，非常高效。
     * 末尾的 {@code ORDER BY sort, id} 会产生一次 filesort ——
     * <b>可以接受</b>：套餐是几十行量级的配置数据，不是一个会增长的事实表。
     */
    List<ChargePackage> listAvailableForMember(@Param("stationId") Long stationId);

    /**
     * 统计某个分类下的套餐数量
     *
     * <p>★ 存在的理由是<b>一个删除守卫</b>：{@code CategoryService.delete}
     * 用它判断「这个分类还能不能删」。{@code charge_package.category_id}
     * 是裸 bigint、没有 FOREIGN KEY —— 应用层校验和外键的取舍不是
     * 「谁更强」，而是<b>报错给谁看</b>：这条守卫的产出是一句
     * 「该分类下还有充电套餐」，外键的产出是一个 errno。
     *
     * <p>★★ 但它有一个外键没有的缺口：「先 count 再 delete」在并发下不是原子的 ——
     * A 正在把套餐挂到分类 X 上、B 正在删分类 X，两者各自的检查都能通过。
     * <b>这个缺口是接受的</b>（理由同 {@code VehicleMapper} 的 {@code is_default}
     * 那段）：分类是十行量级的配置表、由管理员手工操作，真发生了也只是
     * 「套餐列表里那个分类名显示为空」—— <b>看得见、改得动、不丢钱</b>。
     *
     * <p>★ 要修的正确做法是<b>把 delete 也带上条件</b>：
     * {@code DELETE FROM category WHERE id = ? AND NOT EXISTS (SELECT 1 FROM charge_package WHERE category_id = ?)}
     * —— 让「检查」和「删除」变成同一条语句，靠 InnoDB 的行锁保证原子性。
     * 现在不做，是因为收益配不上让这条 SQL 从「一眼看懂」变成「需要想一下」的代价。
     */
    @Select("SELECT COUNT(*) FROM charge_package WHERE category_id = #{categoryId}")
    int countByCategoryId(@Param("categoryId") Long categoryId);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ 常规的「下架」手段是 {@code status = 0}，不是删除 ——
     * 历史订单可能引用着这个套餐，删掉会让那些引用变成悬空 id。
     *
     * <p>但硬删<b>必须存在</b>：{@code uk_pkg_name_station} 不看 {@code status}，
     * 一个建错名字的套餐会<b>永久占用</b>那个名字 ——
     * 没有硬删的话，改名这个操作要去生产库手敲 DELETE。
     *
     * <p>★ 守卫只做「存在性」判断，还没有查 {@code member_package} ——
     * 删掉一个已被车主购买的套餐，那些持有记录会失去它的定义。
     */
    @Delete("DELETE FROM charge_package WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
