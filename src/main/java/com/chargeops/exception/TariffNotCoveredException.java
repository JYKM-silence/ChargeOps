package com.chargeops.exception;

/**
 * 某个时间点没有任何计费规则覆盖它 —— 计费时段存在缺口
 *
 * <h2>★ 它为什么值得单独一个类型，而不是复用 {@link ParamErrorException}</h2>
 *
 * <p>缺口的本质是 <b>数据问题，不是用户输入问题</b>。同一个缺口可能在三处被发现：
 *
 * <ol>
 *   <li><b>写配置时</b>：管理员提交的 8 条时段规则少了 12:00-14:00，
 *       {@code TariffValidator} 拒绝保存 —— 这时它是「输入不合法」</li>
 *   <li><b>结算时</b>：库里已有的规则被人手工改出了缺口，
 *       {@code BillingEngine} 算不出某一段 —— 这时它是「数据损坏」</li>
 *   <li><b>模板被引用之后</b>：模板已冻结进在途订单，但规则缺了</li>
 * </ol>
 *
 * <p>三处的<strong>处理动作完全不同</strong>：第一处让管理员改表单，第二处要报警并人工介入。
 * 如果它们抛的是同一个类型，调用方只能靠解析消息文本区分 —— 那和没有类型一样。
 *
 * <p>所以这里给缺口一个独立的类型。前两处都抛它，因为它们表达的是同一件事：
 * <b>「这个时刻电价是多少」没有唯一答案</b>。
 *
 * <h2>与 {@link DataConflictException} 的分工</h2>
 *
 * <p>本类型是<b>没有规则匹配</b>（缺口），那个是<b>匹配到多条</b>（重叠）。
 * 两者都是「无法唯一确定电价」，但修法不同：缺口要补规则，重叠要删规则。
 * 消息里都点名具体的时间区间。
 */
public class TariffNotCoveredException extends BaseException {

    public TariffNotCoveredException() {
    }

    public TariffNotCoveredException(String msg) {
        super(msg);
    }
}
