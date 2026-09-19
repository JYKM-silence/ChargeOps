package com.chargeops.exception;

/**
 * 充电枪已被占用
 *
 * <p>抛出的时机很具体：执行枪口互斥的条件更新
 * <pre>
 *   UPDATE connector SET status=3, current_order_id=#{oid}
 *   WHERE id=#{id} AND status=1 AND current_order_id IS NULL
 * </pre>
 * 之后，发现<b>影响行数为 0</b>，就抛这个异常。
 *
 * <p><b>为什么不是「先查一下枪是不是空闲，再更新」：</b>
 * 那是典型的 TOCTOU（检查时/使用时不一致）：
 * 查的时候空闲，但在「查完」和「更新」之间，另一个请求可能已经把枪占了。
 * 两个请求都会通过检查，然后都去更新 —— 结果是同一把枪挂上两个订单。
 * 条件更新把「检查」和「写入」合并成了一条原子语句，
 * 影响行数是 0 就说明在我之前已经有人抢到了，不存在中间窗口。
 *
 * <p><b>为什么影响行数不是 1 就一定要抛异常，而不是「重试一下」：</b>
 * 重试是给「偶发冲突」准备的策略。用户点了「启动充电」之后被告知
 * 「枪已被占用」是正确的业务反馈，应该立刻返回让他换一把枪；
 * 重试只会让他多等几秒再收到同样的结果。
 */
public class ConnectorOccupiedException extends BaseException {

    public ConnectorOccupiedException() {
    }

    public ConnectorOccupiedException(String msg) {
        super(msg);
    }
}
