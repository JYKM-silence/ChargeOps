package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 钱包流水分页查询条件
 *
 * <p>★ 只有分页参数，<b>没有 memberId / type / 时间范围</b>：
 * 车主只能看自己的全部流水。加筛选条件要连查询一起改，
 * 而目前没有任何界面需要它 —— 流水的量级本来就是"一屏看完最近几十条"。
 */
@Data
@Schema(description = "钱包流水分页查询条件")
public class WalletTransactionPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;
}
