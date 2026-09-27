package com.chargeops.service;

import com.chargeops.dto.StaffLoginDTO;
import com.chargeops.vo.StaffLoginVO;
import com.chargeops.vo.StaffVO;

/**
 * 运维人员服务
 *
 * <p>接口里不出现任何 MyBatis / Servlet / Spring Web 的类型：没有 {@code Staff} 实体、
 * 没有 {@code HttpServletRequest}，入参出参都是自己定义的类型。方法签名里一旦出现
 * Servlet 类型，测试 {@code login} 就必须启动 Spring、造一个 HTTP 请求。
 */
public interface StaffService {

    /**
     * 管理端登录
     *
     * @throws com.chargeops.exception.AccountNotFoundException 账号不存在
     * @throws com.chargeops.exception.AccountLockedException   账号被禁用
     * @throws com.chargeops.exception.PasswordErrorException   密码错误
     */
    StaffLoginVO login(StaffLoginDTO staffLoginDTO);

    /**
     * 查询当前登录者自己的信息
     *
     * <p> 这个方法没有 id 参数：当前登录者是谁只能从 {@code BaseContext} 读，
     * 永远不能由调用方指定。写成 {@code getById(Long id)} 也能实现同样的功能，
     * 但那样接口就变成了「查任意一个人」—— 攻击者把路径里的 id 一改就能看别人的资料。
     */
    StaffVO getCurrentStaff();
}
