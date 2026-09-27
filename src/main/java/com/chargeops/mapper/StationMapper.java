package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.MemberStationPageQueryDTO;
import com.chargeops.dto.StationPageQueryDTO;
import com.chargeops.entity.Station;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 充电站数据访问
 *
 * <h2>★ 注解还是 XML：一条可照做的分工规则</h2>
 *
 * <p>沿用 {@link StaffMapper} 已经立下的规矩：
 * <ul>
 *   <li><b>固定的单条 SQL → 注解。</b>没有动态条件、一眼能读完的，
 *       写在方法上方，读者不用跳文件</li>
 *   <li><b>带 {@code <if>} / {@code <foreach>} → XML。</b>
 *       动态标签在注解里根本没有对应语法</li>
 *   <li><b>会被多处复用的列清单 → XML。</b>三处写同样的列名，迟早有一处漏改</li>
 * </ul>
 *
 * <h2>★ 不做「先查是否存在再插入」的预检</h2>
 *
 * <p>重复的 {@code code} 由唯一索引 {@code uk_station_code} 挡，
 * 抛出的 {@code DuplicateKeyException} 由 {@code GlobalExceptionHandler}
 * 翻译成「xxx 已存在」。
 *
 * <p>为什么不先 {@code SELECT} 一下再决定插不插：那是 <b>TOCTOU 竞态</b>
 * （两个并发请求都查到「不存在」然后都插入），而且是一次多余的往返 ——
 * 用应用层代码去重复<b>数据库本来就守住的约束</b>，
 * 得到的只有更慢和更不可靠。
 */
@Mapper
public interface StationMapper {

    /**
     * 按主键查
     *
     * @return {@code null} 表示不存在或已被删除。调用方必须处理这个分支
     */
    Station getById(@Param("id") Long id);

    /**
     * 批量按主键查
     *
     * <p>存在的意义是消除 N+1：{@code ChargerService.page} 拿到 10 个桩之后
     * 要补站名，逐个 {@code getById} 就是 10 次查询，而这里只要 1 次。
     *
     * <p><b>★ {@code ids} 不能是空集合。</b>{@code <foreach>} 展开成
     * {@code IN ()} 是 SQL 语法错误（不是返回空列表）。调用方必须先判空 ——
     * 这不是一个可以被「顺手处理掉」的边界，因为空集合在业务上很常见
     * （第一页恰好没有数据）。
     */
    List<Station> listByIds(@Param("ids") List<Long> ids);

    /**
     * 新增站点
     *
     * <p>{@link AutoFill} 填四个审计字段（本表四列齐全），
     * {@code useGeneratedKeys} 把自增主键回填到 {@code station.id}。
     *
     * <h2>★★ {@code openTime} / {@code closeTime} <b>必须非空</b>，DDL 默认值救不了它们</h2>
     *
     * <p>DDL 里它们写的是 {@code NOT NULL DEFAULT '00:00:00'} /
     * {@code '24:00:00'}，看起来「不传就有默认值」。<b>不是的。</b>
     * 默认值只在<b>该列没有出现在 INSERT 语句里</b>时生效，而这条 SQL 是
     * <b>全列插入</b> —— 传 null 就是往 {@code NOT NULL} 列里显式写
     * {@code NULL}，MySQL 报的是 errno 1048（{@code Column 'open_time'
     * cannot be null}），而不是「静默用默认值」。这个坑是
     * {@code StationMapperTest} 第一次跑的时候<b>真的踩到的</b>，9 个用例全红。
     *
     * <p><b>怎么处理的：</b>默认值放在 {@code StationDTO} 的字段初始值上
     * （{@code openTime = "00:00:00"} / {@code closeTime = "24:00:00"}），
     * 好处是默认值出现在 Swagger 文档里，客户端看得见，
     * 而不是藏在 DDL 里只有读建表语句的人知道。
     *
     * <p>★ 不改成「null 就省略这一列、让 DDL 兜底」：那要把这条全列插入
     * 改成 {@code <trim>} 动态 SQL，收益只是让一个被 DTO 默认值挡在前面、
     * 本来就到不了的分支不报错。
     */
    @AutoFill(OperationType.INSERT)
    void insert(Station station);

    /**
     * 按主键更新非空字段
     *
     * <p>★ <b>局部更新的一个已知限制：没法把字段改成空。</b>
     * {@code <set>} + {@code <if>} 的写法下，传 null 的字段直接不出现在 SQL 里，
     * 所以「清空运营商后保存，刷新还是旧值」。
     *
     * <p>这是刻意接受的：全项目统一这一套更新约定，比这个限制本身更重要。
     * 修法是 {@code BeanMergeUtils.copyNonNullProperties} + 去掉 {@code <if>} 的
     * 全行更新，等真有需求时统一改。
     */
    @AutoFill(OperationType.UPDATE)
    void update(Station station);

    /**
     * 分页查询
     *
     * <p><b>★ 调用方必须紧挨着写 {@code PageHelper.startPage(...)}，
     * 中间不能插入任何别的查询。</b>
     * <pre>
     *   PageHelper.startPage(q.getPageNum(), q.getPageSize());
     *   List&lt;Station&gt; rows = stationMapper.page(q);   // ★ 必须紧挨着
     *   long total = ((Page&lt;Station&gt;) rows).getTotal();
     * </pre>
     * {@code startPage} 把分页参数放进 ThreadLocal，会被<b>该线程上的下一条
     * MyBatis 查询</b>消费掉。中间插了别的查询，分页参数就跑到那条查询上去了 ——
     * 表现是主查询没有 LIMIT（返回全表）而辅助查询被 LIMIT 10，
     * 一个完全看不出和分页有关的现象。
     *
     * <p>★ 另外 {@code support-methods-arguments: false}，
     * 所以「方法参数里带 pageNum/pageSize 就自动分页」在这里不成立，
     * 必须显式调 {@code startPage}。
     */
    List<Station> page(StationPageQueryDTO query);

    /**
     * 车主端分页查询 —— <b>只返回「1 启用」的站点</b>
     *
     * <h2>★★ 为什么不复用 {@link #page} ★★</h2>
     *
     * <p>两者的差别只有一处，但它<b>不能</b>靠调用方传参补齐：
     * {@code page} 的 {@code status} 是<b>可空</b>的，不传就是「全部」——
     * 那是管理端要的（运维必须看得到停用和维护中的站）。
     * 而车主端<b>永远</b>只能看到启用的。
     *
     * <p>★ 用 {@code page} 加一个 {@code query.setStatus(1)} 能达到同样效果，
     * 但那把「车主端只看得见启用的站」这条规则变成了 Service 里的一行赋值 ——
     * <b>任何一个人漏掉它，停用中的站点就会出现在车主端，而且不会报错</b>。
     * 写死在这条 SQL 的 {@code WHERE} 里，它才是结构性的。
     *
     * <p>★★ <b>「维护中（2）」也不返回。</b>★★ 维护中的站点对车主就是「现在别来」，
     * 展示出来只会让人白跑一趟。这和 {@code Station} 类注释第四节那句
     * 「不要复用 StatusConstant」是同一件事的两面：三个状态各有各的语义，
     * 而车主端只需要其中一个。
     *
     * <p>★ <b>分页调用约定（{@code startPage} 必须紧挨着）见 {@link #page}。</b>
     */
    List<Station> pageForMember(MemberStationPageQueryDTO query);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ <b>这是裸删除，不做任何守卫。</b>调用方必须先
     * {@code assertDeletable} —— 站点下还有充电桩时删掉它，
     * 那些桩会变成挂在不存在的站点上的孤儿：列表页还在显示，
     * 但点进站点是 404，而桩自己的接口一切正常。这种数据只能靠人工修。
     *
     * <p>守卫放在 Service 里，是因为它需要读另一张表（{@code charger}），
     * 而 Mapper 层的职责是「一条 SQL」，不是「一个业务规则」。
     */
    @Delete("DELETE FROM station WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
