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
     * <h2>★★ 交给第 5 步（DTO）的一份清单：这几个字段必须有值</h2>
     *
     * <p>这条 SQL 是全列插入，而 DDL 的 {@code DEFAULT} 只在列<b>没有出现在
     * INSERT 语句里</b>时生效 —— 所以下面这些 {@code NOT NULL} 列
     * <b>必须由调用方给出</b>，漏一个就是 errno 1048（实测过，见
     * {@link StationMapper#insert} 里那段）。
     *
     * <table border="1">
     *   <caption>DTO 必须提供默认值的字段</caption>
     *   <tr><th>字段</th><th>DDL 默认值</th><th>建议的 DTO 字段初始值</th></tr>
     *   <tr><td>{@code stationId}</td><td>0</td><td>{@code 0L}（0 = 全平台）</td></tr>
     *   <tr><td>{@code validDays}</td><td>0</td><td>{@code 0}（0 = 永久）</td></tr>
     *   <tr><td>{@code status}</td><td>1</td><td>{@code 1}（1 = 上架）</td></tr>
     *   <tr><td>{@code sort}</td><td>0</td><td>{@code 0}</td></tr>
     * </table>
     *
     * <p>写在 DTO 的<b>字段初始值</b>上（不是 Service 里的 {@code if (x == null)}），
     * 和 {@code pageNum = 1} 是同一个手法：默认值出现在 Swagger 文档里，
     * 客户端看得见，而不是藏在 DDL 里只有读建表语句的人知道。
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
     * <p>★ <b>四个类型专属列都在 {@code <set>} 里</b>，包括把值写成 null 的能力。
     * 但因为用的是 {@code <if test="x != null">} 的写法，
     * 传 null 的字段只会被<b>跳过</b>而不是被清空 —— 所以「把一个字段清掉」
     * 在这个写法下做不到。见下面的重要说明。
     *
     * <p>★ <b>本轮不影响正确性，因为 {@code packageType} 不能改。</b>
     * 创建时类型就定死了（{@code ChargePackageUpdateDTO} 里没有这个字段），
     * 所以不存在「电量包改成时长包，需要顺手把 energy_kwh 清掉」这个场景。
     * 如果哪天允许改类型，这条限制会立刻变成一个<b>静默的错误</b>：
     * 改完类型、专属列却留着旧值，而 {@code assertTypeFields}
     * 恰好会拦住它（这正是那条校验的意义）—— 但拦住之后管理员没有任何办法清掉它，
     * 只能删掉重建。到那时要改成「合并整行后全量 update」的写法。
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
     * 统计某个分类下的套餐数量
     *
     * <p>★ 存在的理由是<b>一个删除守卫</b>：{@code CategoryService.delete}
     * 用它判断「这个分类还能不能删」。
     *
     * <h2>★★ 为什么不给它加外键，让数据库自己拦</h2>
     *
     * <p>{@code charge_package.category_id} 在 DDL 里是一个<b>裸 bigint，
     * 没有 FOREIGN KEY</b>。这不是疏漏，是这套 schema 的既定风格 ——
     * 全库十八张表一张外键都没有。理由（假设将来有人想补）：
     * <ol>
     *   <li>外键的报错是 {@code errno 1451}，落到
     *       {@code GlobalExceptionHandler} 里就是「未知错误」，
     *       而这里能给出「该分类下还有充电套餐，请先删除或调整分类」</li>
     *   <li>外键会拦住<b>数据的搬运</b>（导入导出、分库、修数据脚本），
     *       而拦住的理由往往和当下要做的事无关</li>
     *   <li>它只在<b>写入</b>时生效 —— 已经存在的悬空引用它管不了，
     *       所以「数据是否一致」最终仍然要靠应用层查</li>
     * </ol>
     *
     * <p>★ <b>应用层校验和外键的取舍不是「谁更强」，而是「报错给谁看」。</b>
     * 这条守卫的产出是一句人话，外键的产出是一个数字。
     *
     * <h2>★★ 但它有一个外键没有的缺口，必须说清楚</h2>
     *
     * <p><b>「先 count 再 delete」在并发下不是原子的。</b>
     * 两个请求同时进来：A 正在把套餐挂到分类 X 上，B 正在删分类 X ——
     * 两者各自的检查都能通过，结果是套餐挂到了一个已经不存在的分类上。
     *
     * <p>★ <b>本轮接受这个缺口</b>，理由和 {@code VehicleMapper} 的
     * {@code is_default} 那段一样：分类是一张<b>十行量级的配置表</b>，
     * 由管理员在管理端手工操作，两个管理员同时操作同一个分类的
     * 概率低到可以忽略；而且真发生了，危害是「一个套餐的分类指向不存在的 id」，
     * 表现是套餐列表里那个分类名显示为空 —— <b>看得见、改得动、不丢钱</b>。
     *
     * <p>★ 要修的话正确做法是<b>把 delete 也带上条件</b>：
     * {@code DELETE FROM category WHERE id = ? AND NOT EXISTS (SELECT 1 FROM charge_package WHERE category_id = ?)}
     * —— 让「检查」和「删除」变成同一条语句，靠 InnoDB 的行锁保证原子性。
     * 而不是引入分布式锁（和 {@code RedisConstant.LOCK_CONNECTOR_START}
     * 那段「锁只用来削峰，正确性靠数据库」的哲学保持一致）。
     * 现在不做的原因是它会让这条 SQL 从「一眼看懂」变成
     * 「需要想一下」—— 收益（一个管理员手工操作才可能触发的竞态）
     * 配不上这个代价。
     *
     * <p>★ 将来如果 {@code category} 变成车主端也能触发写入的东西，
     * 这个判断要重新做一遍。
     */
    @Select("SELECT COUNT(*) FROM charge_package WHERE category_id = #{categoryId}")
    int countByCategoryId(@Param("categoryId") Long categoryId);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ 常规的「下架」手段是 {@code status = 0}，不是删除 ——
     * 历史订单可能引用着这个套餐，删掉会让那些引用变成悬空 id。
     *
     * <p>但硬删<b>必须存在</b>：{@code uk_pkg_name_station (name, station_id)}
     * 不看 {@code status}，所以一个建错名字的套餐会<b>永久占用</b>那个名字。
     * 「150度电包」打成「150度点包」之后再想用正确的名字，
     * 只能先把错的删掉 —— 没有硬删的话，这个操作要去生产库手敲 DELETE。
     *
     * <p>★ <b>P1c 要在这里加上「有没有已售出的套餐」的检查</b>：
     * 本轮的守卫只做「存在性」判断，P1c 之后还应该查
     * {@code member_package}（或等价的持有表）——
     * 删掉一个已被车主购买的套餐，那些持有的记录会失去它的定义。
     */
    @Delete("DELETE FROM charge_package WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
