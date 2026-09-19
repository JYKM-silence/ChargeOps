package com.chargeops.exception;

/**
 * 记录不存在
 *
 * <p>抛出时机：按 id 查出来是 null，或者条件更新影响了 0 行<b>且确认行已被删除</b>。
 *
 * <h2>★ 为什么按 id 查不到要抛异常，而不是返回 null</h2>
 *
 * <p>返回 null 的话，「查一个不存在的 id」和「这个对象存在但字段恰好都是 null」
 * 在调用方看起来一模一样。更实际的问题是：Service 返回 null 之后，
 * Controller 会把它包进 {@code Result.success(null)} —— <b>HTTP 200，code 是 1</b>，
 * 前端拿到一个 data 为 null 的「成功」响应。
 *
 * <p>前端此时没有任何依据判断这是「成功」还是「查不到」，
 * 只能显示一个空白页面。而管理员在地址栏里敲错一个 id 是很常见的事，
 * 他看到的应该是一句「数据不存在」，不是空白页。
 *
 * <p><b>同一条规则在 P0 已经用过一次</b>：{@code StaffServiceImpl.getCurrentStaff}
 * 里对 {@code BaseContext} 为 null 的处理，注释写的是
 * 「把『配置改错了』变成『一句明确的报错』」—— 这里是把
 * 「数据没了」变成一句明确的报错，同一个道理。
 */
public class NotFoundException extends BaseException {

    public NotFoundException() {
    }

    public NotFoundException(String msg) {
        super(msg);
    }
}
