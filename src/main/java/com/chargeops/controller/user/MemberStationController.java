package com.chargeops.controller.user;

import com.chargeops.dto.MemberStationPageQueryDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.MemberStationService;
import com.chargeops.vo.MemberConnectorVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车主端 - 站点与充电枪。路径 {@code /api/member/**}，由 {@code JwtTokenMemberInterceptor} 保护；
 * 两个端点补的是「建单要的三个 id 没有合法来源」那个缺口：车主先选站、再选枪。
 *
 * <p>用不到 {@code BaseContext}，也不查 {@code member.status}：站点和枪是全平台共享的数据，
 * 看一眼不产生任何后果。
 *
 * <p>{@code available} 是快照不是预订，为 true 之后仍可能建单失败；
 * 枪列表后端不排序（{@code gun_no} 是字符串，按它排出来是 {@code 1, 10, 2}），重排由前端做。
 */
@RestController
@RequestMapping("/api/member")
@Tag(name = "车主端-站点与充电枪")
@Slf4j
@RequiredArgsConstructor
public class MemberStationController {

    private final MemberStationService memberStationService;

    /** 只返回启用中的站点（维护中的也不返回，对车主「维护中」就是「现在别来」），所以 VO 里没有 {@code status} */
    @GetMapping("/station/page")
    @Operation(summary = "分页查询充电站",
            description = "只返回「启用」的站点，维护中/停用的不出现；含该站的枪总数与空闲数")
    public Result<PageResult> page(@Valid MemberStationPageQueryDTO query) {
        return Result.success(memberStationService.page(query));
    }

    /** 不过滤枪的状态：筛掉离线/故障/维护会让一个维护中的站显示成空列表。站点不存在时返回空列表，不是 404 */
    @GetMapping("/connector/by-station/{stationId}")
    @Operation(summary = "查询某站点下的全部充电枪",
            description = "含离线/故障/维护中的枪；available 是快照（不保证下单成功），顺序由前端重排")
    public Result<List<MemberConnectorVO>> listByStation(@PathVariable Long stationId) {
        return Result.success(memberStationService.listByStation(stationId));
    }
}
