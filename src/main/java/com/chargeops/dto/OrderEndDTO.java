package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结束充电上报
 *
 * <p> 同 {@link OrderStartDTO}：这是「设备上报」的替身，挂在管理端。
 *
 * 为什么 {@code endTime} 由调用方给，而不是服务端取 {@code now()}
 *
 * <p>设备上报的时间是设备时钟，和服务器时钟可能差几分钟，
 * 而计费窗口用哪个会直接改变金额（跨时段边界时差一个电价档）。
 *
 * <p> 这个项目选择用上报时间，因为计费的依据是「电是什么时候被用掉的」，
 * 那是物理事实；服务器时钟只能回答「消息是什么时候到的」。代价是要接受设备的
 * 时钟偏差 —— 它由设备侧的 NTP 同步来管，而不是靠服务端偷偷改一个本来该属于
 * 设备的字段。可空时服务端取 {@code now()}，这是降级，不是等价：
 * 判据是「设备有没有给」。
 */
@Data
@Schema(description = "结束充电上报（管理端代设备录入）")
public class OrderEndDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 结束电表读数，累计值
     *
     * <p> 必填（和 start 不同）。没有结束读数就算不出电量，而结算的
     * 全部依据就是电量 —— 那笔订单只可能进异常挂账。与其让它走完整个流程再挂账，
     * 不如在入口就拒绝：一个能指名的入参问题，不该变成一个需要人工排查的挂账单。
     */
    @NotNull(message = "结束电表读数不能为空")
    @Schema(description = "结束电表读数（累计值）", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal endMeterKwh;

    @Schema(description = "结束电量百分比 0~100，可空")
    private Integer endSoc;

    /**
     * 可空，而且 {@code NULL} 有信息量 —— 只有「充电真的开始过」的订单
     * 才有停止原因。见 {@code StopReason} 的类注释。
     */
    @Schema(description = "停止原因，可空；见 StopReason")
    private Integer stopReason;

    /** 设备上报的结束时刻。 可空时服务端取 {@code now()}，见类注释 */
    @Schema(description = "设备上报的结束时刻，可空（缺省用服务端当前时间）")
    private LocalDateTime endTime;
}
