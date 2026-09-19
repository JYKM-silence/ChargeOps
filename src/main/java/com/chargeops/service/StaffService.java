package com.chargeops.service;

import com.chargeops.dto.StaffLoginDTO;
import com.chargeops.vo.StaffLoginVO;
import com.chargeops.vo.StaffVO;

/**
 * 运维人员服务
 *
 * <p><b>接口里不出现任何 MyBatis / Servlet / Spring Web 的类型</b> ——
 * 没有 {@code Staff} 实体（那是持久化层的形状）、没有 {@code HttpServletRequest}。
 * 入参出参都是自己定义的类型，这是「Controller 只做协议转换、
 * Service 只做业务」这条分层规则的落点。
 *
 * <p>好处在测试时最明显：测试 {@code login} 不需要启动 Spring、
 * 不需要造一个 HTTP 请求，直接 {@code new StaffServiceImpl(mockMapper, ...)}
 * 然后断言抛的是不是 {@code PasswordErrorException} 就行。
 * 如果 Service 方法签名里有 {@code HttpServletRequest}，这件事就做不到了。
 *
 * <p>充电业务后面会加大量方法（站点、桩、枪、套餐、订单、钱包），
 * 那些方法更要守住这条线 —— 一旦有方法开始接收 Servlet 类型，
 * 整个 Service 层的可测试性就一起消失了。
 */
public interface StaffService {

    /**
     * 管理端登录
     *
     * @param staffLoginDTO 账号密码
     * @return 登录信息 + token
     * @throws com.chargeops.exception.AccountNotFoundException 账号不存在
     * @throws com.chargeops.exception.AccountLockedException   账号被禁用
     * @throws com.chargeops.exception.PasswordErrorException   密码错误
     */
    StaffLoginVO login(StaffLoginDTO staffLoginDTO);

    /**
     * 查询当前登录者自己的信息
     *
     * <p><b>★ 注意这个方法<b>没有</b> id 参数。</b>
     * 这不是省事，是一条安全约定：当前登录者是谁只能从
     * {@code BaseContext}（拦截器从 token 解出来的）读，
     * 永远不能由调用方指定。
     *
     * <p>形状上写成 {@code getById(Long id)} 也能实现同样的功能，
     * 但那样接口就变成了「查任意一个人」—— 攻击者把路径里的 id 一改
     * 就能看别人的资料。而「只有自己能查自己」这件事，
     * 在方法签名里表达出来比在注释里写一句「注意只能传自己的 id」可靠得多。
     */
    StaffVO getCurrentStaff();
}
