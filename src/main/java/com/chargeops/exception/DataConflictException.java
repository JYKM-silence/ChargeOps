package com.chargeops.exception;

/**
 * 数据冲突 —— 请求本身没问题，但当前数据状态不允许这个操作
 *
 * <p>P1 里的四类触发场景，它们的共同点是<b>「换一个时机或换一种做法就能成」</b>：
 *
 * <ol>
 *   <li><b>有子记录，不能删</b>：站点下还有充电桩。管理员应该先删桩，
 *       或者改用「停用」而不是删除</li>
 *   <li><b>乐观锁失败</b>：{@code connector} 用客户端回传的 {@code version} 更新，
 *       影响行数为 0 说明在他看页面到点保存之间，别人先改了。
 *       他应该刷新后重新编辑</li>
 *   <li><b>状态不允许</b>：管理员想把枪设成「充电中」，但那个状态归订单状态机所有</li>
 *   <li><b>被引用，不能改</b>：{@code tariff_template} 已经被 {@code station_tariff}
 *       绑定，此时改它的计费规则会让「电价调整不影响在途订单」这条保证失效</li>
 * </ol>
 *
 * <h2>★ 消息里要说清楚「那该怎么办」</h2>
 *
 * <p>这四类场景的共同点是「用户的做法没错，只是顺序或时机不对」，
 * 所以提示语不能只说「不允许」—— 那会让用户以为是无解的限制，转头去提工单。
 * 要说清楚替代做法：
 * <pre>
 *   ✗  「该站点下存在关联数据」
 *   ✓  「该站点下还有 3 个充电桩，请先删除或转移」
 * </pre>
 *
 * <p>所以抛出点的消息是拼出来的（带上具体的数量或对象名），
 * 而 {@code MessageConstant} 里只放那句话的模板部分。
 */
public class DataConflictException extends BaseException {

    public DataConflictException() {
    }

    public DataConflictException(String msg) {
        super(msg);
    }
}
