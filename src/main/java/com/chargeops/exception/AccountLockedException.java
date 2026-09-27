package com.chargeops.exception;

/**
 * 账号被禁用
 *
 * <p>本项目的 {@code staff.status} 和 {@code member.status} 都是「1 启用 / 0 禁用」，登录时校验。
 *
 * <p><b>为什么要有这个状态，而不是直接删账号：</b>
 * 运维人员离职后如果直接删记录，所有 {@code create_user / update_user}
 * 指向他的历史数据就变成了孤儿，查「这单是谁操作的」会查不到人。
 * 这也是本项目刻意不使用外键的代价之一 —— 数据库不会阻止你删掉被引用的行，
 * 得靠应用层自己守住这条规矩。
 */
public class AccountLockedException extends BaseException {

    public AccountLockedException() {
    }

    public AccountLockedException(String msg) {
        super(msg);
    }
}
