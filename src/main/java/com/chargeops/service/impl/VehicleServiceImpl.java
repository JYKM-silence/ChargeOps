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
 * <p>形状和别的管理端 Service 有一处根本不同：
 *
 * <h2>★★ 一、每个方法的第一件事都是 {@link #requireCurrentMemberId()}</h2>
 *
 * <p>身份来自 {@code BaseContext}（拦截器解 token 后写入），<b>方法签名里没有任何
 * {@code memberId} 参数</b> —— 「查 A 车主却读到 B 车主的车」在这个类里<b>没有表达
 * 方式</b>，这不是靠自觉，是靠签名。★ 真正的防线在<b>下一层</b>：每一条 SQL 的
 * {@code WHERE} 里都带着 {@code member_id}。两层各自独立成立 —— 上面那层让「传错
 * id」不可能发生，下面那层让「就算传错了也读不到」（见 {@code VehicleMapper} 类注释第一节）。
 *
 * <h2>★ 二、{@link #setDefault} 和 {@link #delete} 的写顺序</h2>
 *
 * <p>{@code setDefault} 是 {@code clearDefaultExcept(target) → markDefault(target)}，
 * {@code delete} 是 {@code deleteByIdAndMember(...) → markDefault(剩下的第一辆)} ——
 * 两者都在一个事务里做两件写，而<b>顺序是正确性的一部分</b>。
 *
 * <p>★ {@code clearDefaultExcept} 带 {@code id <> #{id}}，所以「先清后置」和
 * 「先置后清」在单线程下结果相同 —— <b>但不要依赖这个巧合</b>：写死一个顺序，
 * 读代码的人就不必重新验证那个排除条件是否还在。它一旦被去掉（见
 * {@code VehicleMapper} 类注释第二节第 1 条），「先置后清」会静默地清掉刚设的标记。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final VehicleMapper vehicleMapper;

    // ==================== 读 ====================

    @Override
    public List<VehicleVO> listMine() {
        Long memberId = requireCurrentMemberId();

        // ★ SQL 里已经 ORDER BY is_default DESC, id DESC，这里不再排序 ——
        //   在内存里再排一次会掩盖「SQL 忘了写 ORDER BY」这个错误。
        List<Vehicle> rows = vehicleMapper.listByMemberId(memberId);
        return rows.stream().map(this::toVo).toList();
    }

    @Override
    public VehicleVO getMine(Long id) {
        return toVo(requireMine(id));
    }

    // ==================== 写 ====================

    /**
     * 新增车辆
     *
     * <p>★ 三个步骤的顺序有讲究：先 {@code countByMemberId}（必须在 insert <b>之前</b> ——
     * 插完再数结果至少是 1，「是不是第一辆车」就永远为假）→ insert（要拿到自增 id
     * 才能清旧默认）→ {@code clearDefaultExcept}（只有真的要求成默认车时才跑）。
     *
     * <p>★★ <b>第 3 步的 {@code id <> #{id}} 是正确性要求</b>：此刻新行在<b>同一个
     * 事务里已经可见</b>，而且它的 {@code is_default} 已经是 1 —— 没有那个排除条件，
     * 这一步会把自己刚设的标记立刻清掉。<b>而且它是静默的</b>：接口返回成功，
     * 用户的第二辆车就是没成为默认车。
     */
    @Override
    @Transactional
    public Long create(VehicleDTO vehicleDTO) {
        Long memberId = requireCurrentMemberId();

        // ★ 先数，再插 —— 顺序错了这个判断永远为假（见方法注释）
        boolean isFirstVehicle = vehicleMapper.countByMemberId(memberId) == 0;

        Vehicle vehicle = new Vehicle();
        BeanUtils.copyProperties(vehicleDTO, vehicle);

        // ★ 归属在这里被【赋】上去，而不是从请求里读 ——
        //   VehicleDTO 里根本没有 memberId 字段。
        vehicle.setMemberId(memberId);

        // ★★ 车牌规范化。新增和修改走的是同一个方法 —— 只有新增走的话，改牌
        //    就是一条绕过它的旁路（车主可以把车牌改成小写，同一人名下出现两种写法）。
        //    （这【不是】为了唯一性 —— 排序规则本来就大小写不敏感，见 Vehicle 类注释第二节。）
        vehicle.setPlateNo(normalizePlateNo(vehicleDTO.getPlateNo()));

        // ★ is_default 必须兜底：字段初始值挡不住显式的 {"isDefault": null}，
        //   而 vehicle.is_default 是 NOT NULL 且走全列插入 —— 传 null 是 errno 1048。
        //   用的是 VehicleDTO 上那个公开常量，那正是它声明成 public static final 的原因。
        if (vehicle.getIsDefault() == null) {
            vehicle.setIsDefault(VehicleDTO.DEFAULT_IS_DEFAULT);
        }

        // ★★ 第一辆车强制为默认车，不管请求里传了什么 ——「有车的车主一定有且
        //    只有一辆默认车」这条不变量的一部分，完整理由见 VehicleDTO 的类注释第三节。
        if (isFirstVehicle) {
            vehicle.setIsDefault(1);
        }

        vehicleMapper.insert(vehicle);

        // ★ 只有真的要求成默认车时才去清旧的。第一辆车走上来时 isFirstVehicle
        //   已经把它置 1 了，而它必然是唯一一辆，于是 clearDefaultExcept 会匹配
        //   0 行 —— 两种情况都安全，不需要为「第一辆」单独写一个分支。
        if (Integer.valueOf(1).equals(vehicle.getIsDefault())) {
            // ★ 返回值刻意不检查：匹配 0 行是正常情况
            //   （该车主其它车本来就不是默认车）。见 VehicleMapper 类注释第二节第 3 条。
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

        // ★★ 先读一次，而且这次读是【带 member_id 的】—— 它同时完成两件事：
        //    确认车存在、确认车是我的。读不到就抛「车辆不存在」而不是「无权访问」，
        //    完整理由见 VehicleService 的类注释第一节。
        Vehicle existing = requireMine(id, memberId);

        // ★ 合并方向：DTO 的非空值覆盖到从库里读出的行上。
        //   不用 BeanUtils.copyProperties —— 那是全量覆盖，会把 DTO 里的 null
        //   也写进去，于是「不传」变成「清空」，和 <set> + <if> 的语义直接矛盾
        //   （完整论证见 ChargePackageServiceImpl.mergeNonNull 那段）。
        mergeNonNull(vehicleUpdateDTO, existing);

        // ★ 车牌要重新规范化 —— 和新增用的是同一个方法，见 create 里的说明。
        if (vehicleUpdateDTO.getPlateNo() != null) {
            existing.setPlateNo(normalizePlateNo(vehicleUpdateDTO.getPlateNo()));
        }

        // ★ 注意这里【没有】任何关于 is_default 的处理，而且不该有 ——
        //   VehicleUpdateDTO 里没有那个字段，XML 的 <set> 里也没有那一列：
        //   默认车在这个类里只有 setDefault 一条写入路径。
        vehicleMapper.update(existing);

        log.info("修改车辆：id={}, memberId={}, plateNo={}", id, memberId, existing.getPlateNo());
    }

    /**
     * 删除车辆，并在必要时把剩下的第一辆提升为默认
     *
     * <p>★ 顺序：<b>先读（记住它是不是默认车）→ 删 → 按需提升</b>——
     * 不能在删完之后再判断「刚才那辆是不是默认车」，那一行已经不存在了。
     */
    @Override
    @Transactional
    public void delete(Long id) {
        Long memberId = requireCurrentMemberId();

        // ★ 读一次有两个作用：确认存在 / 是我的（否则 404），
        //   以及【记住它是不是默认车】—— 删完就查不到了。
        Vehicle existing = requireMine(id, memberId);

        // ★ 返回值是影响行数，理论上可能是 0（并发下另一个请求刚把同一辆车删掉了），
        //   但上面那次读刚刚确认过它存在，所以不做二次判断 —— 真出现 0 行说明数据被
        //   并发改动过，而结果是「删了两次，第二次什么也没做」，是幂等的正确行为。
        vehicleMapper.deleteByIdAndMember(id, memberId);

        // ★★ 删掉的是默认车 → 把剩下的第一辆提升为默认。必须做这一步：不做的话
        //   「有车的车主一定有且只有一辆默认车」这条不变量就断了，而 VehicleVO 让
        //   前端「取列表第一条」当默认车正是依赖它 —— 断了之后前端拿到的是一个
        //   「没有默认车」的列表，下单页默认选中的车辆就空了。
        if (Integer.valueOf(1).equals(existing.getIsDefault())) {
            promoteFirstRemaining(memberId, id);
        }

        log.info("删除车辆：id={}, memberId={}, plateNo={}, wasDefault={}",
                id, memberId, existing.getPlateNo(), existing.getIsDefault());
    }

    /**
     * 设为默认车
     *
     * <p>★ 两步：先清旧的，再置新的。两步都带 {@code member_id} 条件，
     * 所以<b>归属校验就是写入条件本身</b>，不是一次单独的前置检查。
     *
     * <p>★ {@code markDefault} 的返回值这里不检查 —— 归属已经在 {@link #requireMine}
     * 里确认过。见 {@code VehicleMapper} 类注释第二节第 3 条：<b>这两个返回值不该被
     * 对称地处理</b>，{@code clearDefaultExcept} 的 0 是正常的、{@code markDefault}
     * 的 0 才是异常 —— 而那个异常已经在前一步被排除了。
     */
    @Override
    @Transactional
    public void setDefault(Long id) {
        Long memberId = requireCurrentMemberId();

        Vehicle target = requireMine(id, memberId);

        // ★ 先清旧、再置新 —— 顺序写死，理由见类注释第二节。
        vehicleMapper.clearDefaultExcept(target);
        vehicleMapper.markDefault(target);

        log.info("设为默认车辆：id={}, memberId={}, plateNo={}", id, memberId, target.getPlateNo());
    }

    // ==================== 私有 ====================

    /**
     * 取当前登录者 id
     *
     * <p>★ 这个 if 在正常路径下永远不会成立 —— 请求能走到这里，说明
     * {@code JwtTokenMemberInterceptor} 已经放行过。写它是因为「永远不会成立」
     * 只对当前的拦截器配置成立：哪天有人给 {@code /api/member/**} 加了放行规则，
     * 这里就会静默地拿到 null，然后往下走变成 {@code WHERE member_id = NULL} ——
     * 一个<b>永远匹配不到任何行</b>的查询（SQL 里 {@code = NULL} 恒为假，要写
     * {@code IS NULL}）。表现是「列表永远为空、加车永远失败」，而且不报错。
     *
     * <p>★ 这是这个类里唯一允许有 {@code BaseContext} 的地方 ——
     * 其余方法都通过它拿 id，不各自去读 ThreadLocal。
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

    /**
     * 按 id + memberId 取车，取不到就抛「车辆不存在」
     *
     * <p>★★ <b>不存在和不是你的，返回同一句话。</b>分开提示会变成「这个 id 存在，
     * 只是不属于你」—— 那等于免费告诉攻击者哪些 id 是有效的，他可以拿一个 id
     * 从 1 数到 10000，把系统里所有有效的车辆 id 标出来
     * （完整论证见 {@code VehicleMapper} 的类注释第一节）。
     *
     * <p>★ 这个判断<b>不是</b>权限检查的主防线 —— 真正的防线是每条 SQL 的
     * {@code WHERE} 里的 {@code member_id}。它只负责把「0 行」翻译成一句合适的
     * 错误消息；即使被绕过（比如将来某个调用方忘了调它），写入语句依然改不到别人的车。
     */
    private Vehicle requireMine(Long id, Long memberId) {
        Vehicle vehicle = vehicleMapper.getByIdAndMember(id, memberId);
        if (vehicle == null) {
            log.info("车辆不存在或不属于当前车主：id={}, memberId={}", id, memberId);
            throw new NotFoundException(MessageConstant.VEHICLE_NOT_FOUND);
        }
        return vehicle;
    }

    /**
     * 删掉默认车之后，把剩下的第一辆提升为默认
     *
     * <p>★ {@code listByMemberId} 已经按 {@code is_default DESC, id DESC} 排序，
     * 而刚删掉的那辆已经不在了 —— 所以<b>第一条就是「剩下的第一辆」</b>，
     * 不需要额外排序或过滤。
     *
     * <p>★ 可以直接把查出来的实体交给 {@code markDefault}：它带着 {@code id} 和
     * {@code member_id}，而那条 SQL 的 {@code WHERE} 正是这两个条件 —— 传一个从库里
     * 读出来的真对象，比造一个只带两个 id 的探针对象干净。
     *
     * <p>★ 空列表是正常情况（删掉的是最后一辆车）：那时没有车，「有车的车主一定有
     * 默认车」这条不变量<b>前提不成立</b>，不需要处理。
     */
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
     * 车牌规范化：<b>去掉首尾空白 + 转大写</b>
     *
     * <h2>★★ 一、转大写<b>不是</b>为了唯一性</h2>
     *
     * <p>库 / 列排序规则是 {@code utf8mb4_0900_ai_ci}，<b>大小写不敏感</b>，所以
     * {@code uk_vehicle_member_plate} <b>本来</b>就把 {@code 粤BD12345} 和
     * {@code 粤bd12345} 当成同一个值。转大写的目的是<b>展示统一</b>：列表页不会
     * 同时出现两种写法，客服按车牌反查时也不用试两种大小写
     * （三条理由见 {@code Vehicle} 的类注释第二节）。
     *
     * <h2>★★ 二、{@code Locale.ROOT} 去掉会引入一个真实的 bug</h2>
     *
     * <p>{@code toUpperCase()} 的无参重载用的是<b>默认区域设置</b>：土耳其语区域下
     * 小写 {@code 'i'} 的大写是 {@code 'İ'}（带点的 I）而不是 {@code 'I'}。车牌里
     * 会用到 {@code I}，而更麻烦的是这个 bug <b>只在区域设置为土耳其语的服务器上
     * 出现</b> —— 本机测试永远复现不了，后果是同一块车牌在不同机器上存成不同的值。
     * 凡是为「统一」而做的字符串大小写转换，都该显式指定 {@code Locale.ROOT}。
     *
     * <h2>★ 三、{@code trim()} 去掉的尾随空格是一个正确性问题</h2>
     *
     * <p>{@code utf8mb4_0900_ai_ci} 是 <b>NO PAD</b> 排序规则，和旧版的
     * {@code utf8mb4_general_ci} 不同，它<b>认为尾随空格是有意义的</b>：
     * {@code '粤BD12345' = '粤BD12345 '} 返回 0。也就是说
     * <b>{@code uk_vehicle_member_plate} 拦不住带尾空格的重复车牌</b> ——
     * 同一个车主可以把同一块牌登记两次，一次带尾空格。这个 {@code trim()}
     * 是目前唯一挡住它的事情。
     *
     * <p>★ <b>不要改成 {@code strip()}</b>：{@code trim()} 去的是 {@code <= U+0020}
     * 的字符（含制表符、换行），而 {@code strip()} 用 {@code Character.isWhitespace}，
     * <b>不包含不换行空格 U+00A0</b> —— 前者更接近「把复制粘贴带进来的东西清干净」。
     *
     * <h2>★ 四、空串和纯空白串会变成空串</h2>
     *
     * <p>新增路径上不用担心（{@code VehicleDTO.plateNo} 有 {@code @NotBlank}）；
     * 修改路径上 {@code {"plateNo": "   "}} 会规范化成空串，而
     * {@code VehicleMapper.xml} 的 {@code <if>} 判的是 {@code != null and != ''}，
     * <b>空串会被跳过、即「不改」</b> —— 和「传 null 表示不改」语义一致，不是漏洞。
     */
    private String normalizePlateNo(String plateNo) {
        if (plateNo == null) {
            return null;
        }
        return plateNo.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 把 DTO 的非空字段覆盖到实体上
     *
     * <p>★ 刻意不用 {@code BeanUtils.copyProperties} —— 理由见
     * {@code ChargePackageServiceImpl.mergeNonNull}（那个注释是正本）。
     *
     * <p>★ <b>这里没有 {@code isDefault} 和 {@code memberId}，这是刻意的</b>：
     * DTO 里就没有这两个字段 —— 它是「两条写入路径通向同一个不变量，
     * 迟早有一条忘了清旧的」这条判断在这个方法里的落地。
     */
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
        // ★ plateNo 不在这里 —— 它需要走 normalizePlateNo，放在调用方那一行更显眼
        //   （一个「顺手」把车牌加进这个方法的改动会绕过规范化，而那是静默的）。
    }

    /**
     * 实体 → VO
     *
     * <p>★ 派生字段走<b>降级</b>：查不到标签返回 null，不抛 —— 项目统一的
     * 「写路径严格，读路径降级」，一行脏数据不该让整个车辆列表 500。
     *
     * <p>★ {@code plateColor} 可空（车主可以不填），所以 {@code plateColorLabel}
     * <b>有两种情况会是 null</b>：没填、或者填了但值非法。前端两种情况都按
     * 「没标签」渲染就行，不需要区分 —— 完整说明见 {@code VehicleVO} 的类注释。
     */
    private VehicleVO toVo(Vehicle vehicle) {
        VehicleVO vo = new VehicleVO();
        BeanUtils.copyProperties(vehicle, vo);
        vo.setPlateColorLabel(labelOfPlateColor(vehicle.getPlateColor()));
        return vo;
    }

    /** ★ 派生字段的降级策略见 {@code StationServiceImpl.labelOf} —— 失败返回 null，不抛 */
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
