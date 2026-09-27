package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 性别
 *
 * <p>对应 {@code member.gender}：{@code 0未知 1男 2女}。
 *
 * <h2>★ 不要拿它和 {@code staff.sex} 互相套用 —— 这是两套不同的约定</h2>
 *
 * <table border="1">
 *   <caption>同一个概念，两张表两种存法</caption>
 *   <tr><th></th><th>{@code member.gender}</th><th>{@code staff.sex}</th></tr>
 *   <tr><td>列类型</td><td>{@code tinyint}</td><td>{@code char(1)}</td></tr>
 *   <tr><td>取值</td><td>{@code 0未知 1男 2女}</td><td>{@code '0'女 '1'男}</td></tr>
 *   <tr><td>「女」是几</td><td><b>2</b></td><td><b>'0'</b>（<b>不一样</b>）</td></tr>
 *   <tr><td>有没有「未知」</td><td>有（0）</td><td>没有</td></tr>
 * </table>
 *
 * <p>{@code staff} 那套是沿袭旧系统的 {@code char(1)}，没有「未知」这一档；
 * 两边的「女」分别是 {@code 2} 和 {@code '0'}，<b>照抄过去会静默地把性别存反</b>——
 * 不会撞任何约束，只是数据是错的。
 *
 * <p>所以这里刻意<b>不</b>复用 {@code staff} 的写法、也<b>不</b>给它补一个枚举：
 * {@code staff.sex} 至今没有任何读写方，为一个没人用的字段建枚举，
 * 只会多一个将来要和 DDL 对齐的地方。
 *
 * <h2>★ 为什么是枚举而不是又一个 {@code StatusConstant}</h2>
 *
 * <p>{@code StatusConstant} 只管「启用/禁用」这种二值开关。性别是三值，
 * 硬塞进去就出现了 {@code ENABLE} 表示「男」这种读不懂的代码。
 * <b>有几个值，就有几个枚举。</b>
 */
public enum Gender {

    /** 未知 —— 注册时没填就是这一档，<b>它是一个合法的终态，不是「没填」</b> */
    UNKNOWN(0, "未知"),

    /** 男 */
    MALE(1, "男"),

    /** 女 */
    FEMALE(2, "女");

    private final int code;

    private final String label;

    Gender(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 按数据库里存的值取枚举
     *
     * <p>★ 取值不在枚举里时<b>抛</b>，不返回 {@code null}：那意味着库里有一个
     * 写这段代码的人没预料到的值，静默返回 null 会让它一路漂到前端显示成空白，
     * 抛出来至少能定位到是哪一行数据。
     *
     * <p>★ <b>但只读路径上要 catch 后降级</b>：列表里有 1 行脏数据不该让整个列表
     * 500（降级写法见 {@code StationServiceImpl.labelOf}）。
     * 这条分工是项目统一的：<b>写路径严格，读路径降级。</b>
     */
    public static Gender of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("性别为空");
        }
        for (Gender gender : values()) {
            if (gender.code == code) {
                return gender;
            }
        }
        throw new ParamErrorException("未知的性别：" + code);
    }
}
