package com.chargeops.service;

import com.chargeops.dto.MemberLoginDTO;
import com.chargeops.dto.MemberPageQueryDTO;
import com.chargeops.dto.MemberPasswordDTO;
import com.chargeops.dto.MemberRegisterDTO;
import com.chargeops.dto.MemberUpdateDTO;
import com.chargeops.entity.Member;
import com.chargeops.result.PageResult;
import com.chargeops.vo.MemberLoginVO;
import com.chargeops.vo.MemberVO;

/**
 * 车主服务：车主端（{@link #getCurrentMember} / {@link #updateProfile} /
 * {@link #changePassword}）、管理端（{@link #page} / {@link #getById} /
 * {@link #updateStatus}）、两个免登录入口（{@link #register} / {@link #login}）共用。
 *
 * <p>车主端的方法一个 id 参数都没有，「改谁」只能来自 token（{@code BaseContext}）；
 * 管理端的三个都有 id。把「改谁」做成路径参数是越权漏洞最常见的来源：
 * {@code PUT /api/member/{id}} 同时就是「改自己」和「改任意人」两个接口，
 * 区别只在调用方传了什么，服务端必须靠一句 {@code if (id != currentId) throw} 来区分。
 * 这和 {@code MemberUpdateDTO} 里没有 {@code phone} / {@code status} 是同一类做法。
 *
 * <p>管理端不做新建、也不做硬删：账号来源已定为自助注册；车主有订单、钱包、车辆，
 * 硬删会让它们全变成孤儿，禁用就是停用手段。这和 {@code StationService} /
 * {@code CategoryService} 的「硬删必须存在」是相反的两个决定 —— 那两个域的唯一键
 * （{@code code} / {@code name}）是人造的，建错了不硬删就得去生产库手敲 DELETE，
 * 而 {@code phone} 是真实世界的标识。
 */
public interface MemberService {

    /**
     * 车主自助注册（注册即登录，返回的 token 可直接用）
     *
     * <p> 不预检手机号是否重复，直接插，让 {@code uk_member_phone} 去挡 ——
     * 预检既有多线程下的 TOCTOU，又是在应用层重复数据库已经提供的保证。
     *
     * @return 车主 id + 手机号 + 昵称 + token
     * @throws org.springframework.dao.DuplicateKeyException 手机号已注册
     */
    MemberLoginVO register(MemberRegisterDTO memberRegisterDTO);

    /**
     * 车主登录
     *
     * <p> 「手机号没注册」和「密码错误」返回同一句模糊文案
     * （{@code MEMBER_LOGIN_FAILED}）。「该账号未设置密码」是单独一句
     * （{@code MEMBER_PASSWORD_NOT_SET}）：那类用户拿密码登录永远失败，
     * 混进模糊文案里他会一直重试密码。
     *
     * @throws com.chargeops.exception.AccountLockedException 账号已被禁用
     * @throws com.chargeops.exception.ParamErrorException    账号未设置密码
     */
    MemberLoginVO login(MemberLoginDTO memberLoginDTO);

    /**
     * 查询当前登录车主（车主端的启动探针）
     *
     * <p> 返回的 {@code MemberVO} 里带 {@code status}，客户端能在启动时看到
     * 「我被禁用了」并主动登出。这不能替代服务端强制：禁用不撤销已签发的 token，
     * 窗口最长 7 天。
     */
    MemberVO getCurrentMember();

    /** 修改当前登录车主的资料（昵称 / 头像 / 性别）。改不了 {@code phone} 和 {@code status} */
    void updateProfile(MemberUpdateDTO memberUpdateDTO);

    /**
     * 修改当前登录车主的密码（必须验旧密码）
     *
     * <p> 未设密码的账号（{@code password IS NULL}）改不了密码：能走到这个接口的
     * 唯一凭证是一个 token，而那个账号从来没设过密码，没有任何东西能证明来的人是机主——
     * 跳过旧密码校验等于把「持有 token」升级成「设置一个永久密码」。
     */
    void changePassword(MemberPasswordDTO memberPasswordDTO);

    /** 分页查询车主（管理端）。条件匹配方式见 {@code MemberMapper.xml} 里 {@code page} 上方的注释 */
    PageResult page(MemberPageQueryDTO query);

    /** 按 id 查车主详情（管理端） */
    MemberVO getById(Long id);

    /**
     * 启用 / 禁用车主（管理端）
     *
     * <p> 禁用不会撤销已经签发的 token：拦截器只验签名、从不查库，而 member token
     * 的有效期是 7 天 —— 一个刚被禁用的车主，手上的 token 最长还能用 7 天。
     * 这个窗口靠有资金影响的端点自己重新查 {@code member.status} 来兜，见 {@link #assertActive}。
     *
     * @throws com.chargeops.exception.ParamErrorException 状态取值不是 0/1
     */
    void updateStatus(Long id, Integer status);

    /**
     * 确认车主存在且未被禁用，返回该车主
     *
     * <p> 有资金影响的端点每次都要调它 —— token 有效 ≠ 账号可用。按「这个接口有没有
     * 资金影响」判断，不是按读写判断：{@code pageMine} / {@code getMine} 都是只读但仍然查；
     * 反过来 {@code GET /api/member/me} 和「我的车辆」不查，被禁用的车主看看自己的资料
     * 不产生任何后果。唯一的例外是 {@code pay}，那里不能查，理由见方法上方的注释。
     *
     * @return 车主实体（调用方通常还要用它的其他字段）
     * @throws com.chargeops.exception.AccountLockedException 账号已禁用
     */
    Member assertActive(Long memberId);
}
