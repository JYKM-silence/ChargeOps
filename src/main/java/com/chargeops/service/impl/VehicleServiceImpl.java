package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.dto.VehicleDTO;
import com.chargeops.dto.VehicleUpdateDTO;
import com.chargeops.entity.Vehicle;
import com.chargeops.enumeration.PlateColor;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.exception.UserNotLoginException;
import com.chargeops.mapper.VehicleMapper;
import com.chargeops.service.VehicleService;
import com.chargeops.vo.VehicleVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 车辆服务实现
 *
 * <p>和其他 Service 不同：每个方法的第一件事都是 {@link #requireCurrentMemberId()}，身份只能
 * 来自 {@code BaseContext}，方法签名里没有 {@code memberId} 参数 —— 「查 A 车主却读到 B 车主的
 * 车」在这个类里没有表达方式；真正的防线是每条 SQL 的 {@code WHERE} 里的 {@code member_id}。
 *
 * <p> {@link #setDefault} 的「先清后置」是写死的顺序：{@code clearDefaultExcept} 上那个
 * {@code id <> #{id}} 一旦被去掉，「先置后清」会静默地清掉刚设的标记。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final VehicleMapper vehicleMapper;

    // ---------- 读 ----------

    @Override
    public List<VehicleVO> listMine() {
        Long memberId = requireCurrentMemberId();

        // SQL 里已经 ORDER BY is_default DESC, id DESC —— 在内存里再排一次会掩盖「SQL 忘了写 ORDER BY」
        List<Vehicle> rows = vehicleMapper.listByMemberId(memberId);
        return rows.stream().map(this::toVo).toList();
    }

    @Override
    public VehicleVO getMine(Long id) {
        return toVo(requireMine(id));
    }

    // ---------- 写 ----------

    /**
     * 新增车辆
     *
     * <p> 顺序有讲究：{@code countByMemberId} 必须在 insert 之前（插完再数结果至少是 1，
     * 「是不是第一辆车」就永远为假），insert 之后才拿得到自增 id。
     */
    @Override
    @Transactional
    public Long create(VehicleDTO vehicleDTO) {
        Long memberId = requireCurrentMemberId();

        // 先数，再插 —— 顺序错了这个判断永远为假（见方法注释）
        boolean isFirstVehicle = vehicleMapper.countByMemberId(memberId) == 0;

        Vehicle vehicle = new Vehicle();
        BeanUtils.copyProperties(vehicleDTO, vehicle);

        // 归属在这里被赋上去，不从请求里读 —— VehicleDTO 里根本没有 memberId 字段
        vehicle.setMemberId(memberId);

        // 新增和修改都走这个方法：只有新增走的话，改牌就是一条绕过规范化的旁路
        vehicle.setPlateNo(normalizePlateNo(vehicleDTO.getPlateNo()));

        // 字段初始值挡不住显式的 {"isDefault": null}，而该列 NOT NULL 且走全列插入（errno 1048）
        if (vehicle.getIsDefault() == null) {
            vehicle.setIsDefault(VehicleDTO.DEFAULT_IS_DEFAULT);
        }

        // 第一辆车强制为默认车：「有车的车主一定有且只有一辆默认车」这条不变量的一部分
        if (isFirstVehicle) {
            vehicle.setIsDefault(1);
        }

        vehicleMapper.insert(vehicle);

        if (Integer.valueOf(1).equals(vehicle.getIsDefault())) {
            // 返回值刻意不检查：匹配 0 行是正常情况（该车主其它车本来就不是默认车，第一辆则是唯一一辆）
            vehicleMapper.clearDefaultExcept(vehicle);
        }

        log.info("新增车辆成功：id={}, memberId={}, plateNo={}, isDefault={}, isFirst={}",
                vehicle.getId(), memberId, vehicle.getPlateNo(), vehicle.getIsDefault(), isFirstVehicle);
        return vehicle.getId();
    }

    @Override
    @Transactional
    public void update(Long id, VehicleUpdateDTO vehicleUpdateDTO) {
        Long memberId = requireCurrentMemberId();

        // 这次读是带 member_id 的，同时完成两件事：确认车存在、确认车是我的
        Vehicle existing = requireMine(id, memberId);

        // DTO 的非空值覆盖到库里读出的行上，不用 BeanUtils.copyProperties（那是全量覆盖，会把 null 也写进去）
        mergeNonNull(vehicleUpdateDTO, existing);

        // 车牌要重新规范化 —— 和新增用的是同一个方法，见 create 里的说明
        if (vehicleUpdateDTO.getPlateNo() != null) {
            existing.setPlateNo(normalizePlateNo(vehicleUpdateDTO.getPlateNo()));
        }

        // 这里没有任何关于 is_default 的处理，而且不该有：
        // 默认车在这个类里只有 setDefault 一条写入路径。
        vehicleMapper.update(existing);

        log.info("修改车辆：id={}, memberId={}, plateNo={}", id, memberId, existing.getPlateNo());
    }

    /** 删除车辆，并在必要时把剩下的第一辆提升为默认。顺序：先读（记住它是不是默认车）→ 删 → 按需提升，删完就查不到那一行了 */
    @Override
    @Transactional
    public void delete(Long id) {
        Long memberId = requireCurrentMemberId();

        // 读一次有两个作用：确认存在 / 是我的（否则 404），以及记住它是不是默认车
        Vehicle existing = requireMine(id, memberId);

        // 影响行数可能是 0（并发下另一个请求刚删掉同一辆车）：结果是「删了两次，
        // 第二次什么也没做」，幂等，所以不做二次判断。
        vehicleMapper.deleteByIdAndMember(id, memberId);

        // 删掉的是默认车 → 把剩下的第一辆提升为默认。不做的话「有车的车主一定有且只有
        // 一辆默认车」这条不变量就断了，而前端「取列表第一条」当默认车正是依赖它。
        if (Integer.valueOf(1).equals(existing.getIsDefault())) {
            promoteFirstRemaining(memberId, id);
        }

        log.info("删除车辆：id={}, memberId={}, plateNo={}, wasDefault={}",
                id, memberId, existing.getPlateNo(), existing.getIsDefault());
    }

    /** 设为默认车。两步都带 {@code member_id} 条件，所以归属校验就是写入条件本身；先清旧、再置新，顺序见类注释 */
    @Override
    @Transactional
    public void setDefault(Long id) {
        Long memberId = requireCurrentMemberId();

        Vehicle target = requireMine(id, memberId);

        vehicleMapper.clearDefaultExcept(target);
        vehicleMapper.markDefault(target);

        log.info("设为默认车辆：id={}, memberId={}, plateNo={}", id, memberId, target.getPlateNo());
    }

    // ---------- 私有 ----------

    /**
     * 取当前登录者 id。这个 if 在正常路径下永远不会成立 —— 但那只对当前的拦截器配置成立：
     * 哪天有人给 {@code /api/member/**} 加了放行规则，这里就会静默拿到 null，往下走变成
     * {@code WHERE member_id = NULL}（恒为假），表现是「列表永远为空、加车永远失败」。
     * 本类只有这一处读 {@code BaseContext}。
     */
    private Long requireCurrentMemberId() {
        Long memberId = BaseContext.getCurrentId();
        if (memberId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        return memberId;
    }

    /** 取当前登录者 id，再按 id + memberId 取车 */
    private Vehicle requireMine(Long id) {
        return requireMine(id, requireCurrentMemberId());
    }

    /** 按 id + memberId 取车，取不到就抛「车辆不存在」。不存在和不是你的共用一句话，分开提示等于免费告诉攻击者哪些 id 是有效的 */
    private Vehicle requireMine(Long id, Long memberId) {
        Vehicle vehicle = vehicleMapper.getByIdAndMember(id, memberId);
        if (vehicle == null) {
            log.info("车辆不存在或不属于当前车主：id={}, memberId={}", id, memberId);
            throw new NotFoundException(MessageConstant.VEHICLE_NOT_FOUND);
        }
        return vehicle;
    }

    /** 删掉默认车之后，把剩下的第一辆提升为默认：{@code listByMemberId} 已按 {@code is_default DESC, id DESC} 排序，第一条就是它 */
    private void promoteFirstRemaining(Long memberId, Long deletedId) {
        List<Vehicle> remaining = vehicleMapper.listByMemberId(memberId);
        if (remaining.isEmpty()) {
            log.info("删除了最后一辆车，无需提升默认车：memberId={}", memberId);
            return;
        }

        Vehicle promoted = remaining.get(0);
        vehicleMapper.markDefault(promoted);

        log.info("默认车被删除，已自动提升下一辆：deletedId={}, promotedId={}, memberId={}",
                deletedId, promoted.getId(), memberId);
    }

    /**
     * 车牌规范化：去掉首尾空白 + 转大写
     *
     * <p> 转大写不为唯一性（{@code utf8mb4_0900_ai_ci} 大小写不敏感），是为了展示统一；
     * {@code Locale.ROOT} 不能去掉 —— 无参 {@code toUpperCase()} 用默认区域设置，土耳其语
     * 区域下 {@code 'i'} 的大写是 {@code 'İ'}，本机永远复现不了。
     *
     * <p> {@code trim()} 是正确性要求：{@code utf8mb4_0900_ai_ci} 是 NO PAD 排序规则，它认为
     * 尾随空格有意义（{@code '粤BD12345' = '粤BD12345 '} 返回 0），唯一索引因此拦不住带尾空格
     * 的重复车牌 —— 这个 {@code trim()} 是唯一挡住它的东西。不要改成 {@code strip()}。
     */
    private String normalizePlateNo(String plateNo) {
        if (plateNo == null) {
            return null;
        }
        return plateNo.trim().toUpperCase(Locale.ROOT);
    }

    /** 把 DTO 的非空字段覆盖到实体上。不用 {@code BeanUtils.copyProperties}（全量覆盖会把 null 也写进去，「不传」变成「清空」） */
    private void mergeNonNull(VehicleUpdateDTO dto, Vehicle target) {
        if (dto.getPlateColor() != null) {
            target.setPlateColor(dto.getPlateColor());
        }
        if (dto.getBrand() != null) {
            target.setBrand(dto.getBrand());
        }
        if (dto.getModel() != null) {
            target.setModel(dto.getModel());
        }
        if (dto.getBatteryCapacityKwh() != null) {
            target.setBatteryCapacityKwh(dto.getBatteryCapacityKwh());
        }
        if (dto.getRemark() != null) {
            target.setRemark(dto.getRemark());
        }
        // plateNo 不在这里 —— 它需要走 normalizePlateNo，放在调用方那一行更显眼
        // （一个「顺手」把车牌加进这个方法的改动会绕过规范化，而那是静默的）。
    }

    /** 实体 → VO。派生标签走降级：{@code plateColorLabel} 为 null 有两种来源（没填 / 值非法），前端都按「没标签」渲染 */
    private VehicleVO toVo(Vehicle vehicle) {
        VehicleVO vo = new VehicleVO();
        BeanUtils.copyProperties(vehicle, vo);
        vo.setPlateColorLabel(labelOfPlateColor(vehicle.getPlateColor()));
        return vo;
    }

    /**  派生字段的降级策略见 {@code StationServiceImpl.labelOf} —— 失败返回 null，不抛 */
    private String labelOfPlateColor(Integer plateColor) {
        if (plateColor == null) {
            return null;
        }
        try {
            return PlateColor.of(plateColor).getLabel();
        } catch (ParamErrorException e) {
            log.warn("车牌颜色无法识别，plateColor={}", plateColor);
            return null;
        }
    }
}
