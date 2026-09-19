package com.chargeops.exception;

/**
 * 入参不合法 —— 单字段校验之外的、需要跨字段或跨行才能发现的问题
 *
 * <h2>★ 它和 DTO 上的 {@code @NotBlank} / {@code @Min} 是分工关系，不是重复</h2>
 *
 * <p>DTO 上的注解只能看<b>单个字段自己</b>。凡是需要「把几个字段放在一起看」
 * 才知道合不合法的规则，都落在这里。P1 里具体有三类：
 *
 * <ol>
 *   <li><b>跨字段</b>：{@code charge_package} 的 {@code package_type=1}
 *       要求 {@code energy_kwh} 必填、其余三个类型专属列必须为 null。
 *       「哪些列必填」取决于另一个字段的值，单字段注解表达不了</li>
 *   <li><b>跨行</b>：{@code tariff_rule} 的时段必须完整覆盖 24 小时 ——
 *       每一条单独看都是合法的（08:00 &lt; 10:00），合起来才有缺口。
 *       而且校验必须对着<b>合并后的完整集合</b>做，不是逐条做</li>
 *   <li><b>跨版本</b>：更新时 {@code package_type} 取自库里读出的行，
 *       而类型专属字段取自请求体。DTO 层根本拿不到库里的那一半</li>
 * </ol>
 *
 * <p>把这类规则硬塞进 DTO 的 {@code @AssertTrue} 是行不通的：
 * 更新接口里 DTO 只有「要改的字段」，没传的字段是 null，
 * 注解会把它当成「用户要清空这个字段」而误报。
 *
 * <p><b>和 {@link DataConflictException} 的区别：</b>
 * 这个说的是「你给的数据本身有问题」（重发一次同样的请求，结果一样是失败）；
 * 那个说的是「你给的数据没问题，但当前状态不允许」（换了时机可能就成了）。
 * 前端对这两者的处理不同 —— 前者要改表单，后者提示重试或刷新。
 */
public class ParamErrorException extends BaseException {

    public ParamErrorException() {
    }

    public ParamErrorException(String msg) {
        super(msg);
    }
}
