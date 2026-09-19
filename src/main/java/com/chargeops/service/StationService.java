package com.chargeops.service;

import com.chargeops.dto.StationDTO;
import com.chargeops.dto.StationPageQueryDTO;
import com.chargeops.dto.StationUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.StationVO;

/**
 * 充电站服务
 *
 * <p>分层约定见 {@link StaffService} 的类注释：接口里不出现任何
 * MyBatis / Servlet / Spring Web 的类型，入参出参都是自己定义的类型。
 *
 * <h2>★ 五个方法的形状是后面四个域（桩/枪/套餐/模板）的模板</h2>
 *
 * <pre>
 *   Long create(XxxDTO)              —— 返回新 id，不是 void
 *   void update(Long id, XxxUpdateDTO)
 *   void delete(Long id)
 *   XxxVO getById(Long id)
 *   PageResult page(XxxPageQueryDTO)
 * </pre>
 *
 * <p>两个形状上的决定值得单独说：
 *
 * <ul>
 *   <li><b>{@code create} 返回 {@code Long} 而不是 {@code void}。</b>
 *       MyBatis 的 {@code useGeneratedKeys} 本来就把自增 id 回填到了实体上，
 *       返回它是<b>顺手</b>的；而调用方（前端）拿到 id 就能直接跳转到详情页，
 *       不需要再发一次「按名字查刚建的那个」—— 那个查询还要处理同名的情况。</li>
 *
 *   <li><b>id 走参数，不进 DTO。</b>{@code update} 的 id 是独立的方法参数，
 *       而不是 {@code XxxUpdateDTO} 里的一个字段。这样客户端就<b>无法</b>
 *       通过改请求体来改「改的是哪一行」—— 路径是路径，数据是数据。</li>
 * </ul>
 *
 * <h2>★ 每个写方法都要说清楚它抛什么</h2>
 *
 * <p>{@code GlobalExceptionHandler} 只有一个
 * {@code @ExceptionHandler(BaseException.class)} 分支，
 * 所以业务异常的分类完全由<b>抛出的类型</b>决定，而不是由消息文本。
 * 接口的 javadoc 是唯一能让调用方（和半年后的自己）知道
 * 「哪些情况要处理」的地方。
 */
public interface StationService {

    /**
     * 新增充电站
     *
     * @return 新站点的 id
     * @throws com.chargeops.exception.ParamErrorException 营业时间不合法
     *         （格式问题由 DTO 的 {@code @Pattern} 挡在更前面，这里只管
     *         「结束必须晚于开始」这条跨字段规则）
     * @throws org.springframework.dao.DuplicateKeyException 站点编码已存在
     */
    Long create(StationDTO stationDTO);

    /**
     * 修改充电站
     *
     * <p>★ 只有非空字段会被更新，所以<b>无法通过这个方法清空任何字段</b>。
     * 见 {@code StationUpdateDTO} 类注释第一节。
     *
     * @throws com.chargeops.exception.NotFoundException 站点不存在
     */
    void update(Long id, StationUpdateDTO stationUpdateDTO);

    /**
     * 删除充电站（硬删）
     *
     * <p>★ 站点下还有充电桩时拒绝。守卫的理由和替代做法见实现类的
     * {@code assertDeletable}。
     *
     * @throws com.chargeops.exception.NotFoundException    站点不存在
     * @throws com.chargeops.exception.DataConflictException 站点下还有充电桩
     */
    void delete(Long id);

    /**
     * 按 id 查详情
     *
     * @throws com.chargeops.exception.NotFoundException 站点不存在
     */
    StationVO getById(Long id);

    /**
     * 分页查询
     *
     * <p>★ 用的是 PageHelper。实现类里那段「{@code startPage} 必须紧挨着
     * 查询」的约定值得读一遍 —— 它是本项目里最容易犯且最难发现的错误。
     */
    PageResult page(StationPageQueryDTO query);
}
