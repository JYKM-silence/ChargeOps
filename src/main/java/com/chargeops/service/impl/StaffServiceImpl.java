package com.chargeops.service.impl;

import com.chargeops.constant.JwtClaimsConstant;
import com.chargeops.constant.MessageConstant;
import com.chargeops.constant.StatusConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.dto.StaffLoginDTO;
import com.chargeops.entity.Staff;
import com.chargeops.exception.AccountLockedException;
import com.chargeops.exception.AccountNotFoundException;
import com.chargeops.exception.PasswordErrorException;
import com.chargeops.exception.UserNotLoginException;
import com.chargeops.mapper.StaffMapper;
import com.chargeops.properties.JwtProperties;
import com.chargeops.service.StaffService;
import com.chargeops.utils.JwtUtil;
import com.chargeops.vo.StaffLoginVO;
import com.chargeops.vo.StaffVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 运维人员服务实现
 *
 * <p>登录这条链路本身很短，但它是后面<b>所有接口的模板</b>：
 * 车主端登录、以及将来每一个需要「拿到当前登录者」的接口，
 * 都会照着这里的形状写。所以注释写得比较细。
 */
@Service
@Slf4j
@RequiredArgsConstructor 
public class StaffServiceImpl implements StaffService {

    private final StaffMapper staffMapper;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;

    /**
     * <b>★ 构造器注入，本类特有的理由是：这个类要被单元测试。</b>
     * 测试可以直接 {@code new StaffServiceImpl(staffMapper, jwtProperties, encoder)}，
     * 三个依赖各传一个 mock / 真实对象即可 —— 不需要启动 Spring 容器。
     *
     * <p><b>★ 注入的是 {@code PasswordEncoder} 接口而不是 {@code BCryptPasswordEncoder} 实现类。</b>
     * 哪天想把 BCrypt 换成 Argon2，只要换 {@code PasswordConfig} 里的 Bean，
     * 这个类一行都不用改；写成实现类的话，换算法时所有注入点都要跟着改 ——
     * 而「哪些地方用了密码加密」恰恰是换算法时最怕漏掉的。
     */


    /**
     * 管理端登录
     *
     * <p><b>★ 三个检查的顺序是有讲究的，不是随便排的：</b>
     * <pre>
     *   1. 账号存在吗        —— 一次主键/唯一索引查询，很快
     *   2. 账号被禁用了吗    —— 纯内存判断，几乎不花时间
     *   3. 密码对吗          —— ★ BCrypt 校验，故意设计得慢（约 100ms）
     * </pre>
     *
     * <p>把最慢的一步放最后，意味着「账号不存在」和「账号被禁用」的请求
     * <b>不会消耗 BCrypt 的计算量</b>：BCrypt 的设计目标就是让每次校验都
     * 昂贵，而登录接口通常没有限流，攻击者可以用大量不存在的账号名打进来，
     * 每个请求都先跑一遍 BCrypt 就是白烧服务端 CPU。
     *
     * <p><b>★ 「账号不存在」和「密码错误」分开提示</b>会泄露「某个账号名
     * 是否注册过」（用户枚举）。这里选择分开，是因为管理端是内部系统、
     * 账号名是工号，枚举价值很低，而这两种信息对使用者完全不同。
     * <b>这是一次有意识的取舍：</b>如果是面向公众的车主端，
     * 就该合并成一句模糊提示。
     */
    @Override
    public StaffLoginVO login(StaffLoginDTO staffLoginDTO) {
        String username = staffLoginDTO.getUsername();

        // 1. 账号是否存在
        Staff staff = staffMapper.getByUsername(username);
        if (staff == null) {
            // ★ 日志只记账号名，不记整个 DTO。
            // DTO 的 toString() 由 @Data 生成，会带出明文密码 ——
            // log.info("登录参数：{}", staffLoginDTO) 这一行就能把密码写进日志文件
            log.info("登录失败：账号不存在，username={}", username);
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        // 2. 账号是否被禁用（便宜的判断放前面，避免为禁用账号浪费 BCrypt 的算力）
        // 常量在左边，staff.getStatus() 为 null 时也不会 NPE。
        // ★ 不要写成 status == StatusConstant.DISABLE —— 那是 Integer 引用比较，
        //   只因 Integer 缓存了 -128~127 才恰好正确；状态值域一旦扩到 128 就会坏掉。
        if (StatusConstant.DISABLE.equals(staff.getStatus())) {
            log.info("登录失败：账号被禁用，username={}, staffId={}", username, staff.getId());
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        // 3. 密码校验
        // ★ matches 的参数顺序是 (明文, 密文)，写反了不会报错、只会一直返回 false
        if (!passwordEncoder.matches(staffLoginDTO.getPassword(), staff.getPassword())) {
            log.info("登录失败：密码错误，username={}, staffId={}", username, staff.getId());
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        // 4. 签发 token
        //
        // claims 里只放身份标识，不放姓名/角色这些业务属性。
        // ★ JWT 的载荷是「Base64 编码」不是「加密」，谁拿到 token 都能解出内容。
        //   放身份 id 可以（本来就要用），但放角色会带来「管理员降权后，旧 token
        //   里还是老角色，仍能以原权限操作直到过期」—— 会变的属性应该每次请求
        //   从数据库读，而不是烙进 token 里。
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.STAFF_ID, staff.getId());

        String token = JwtUtil.createJWT(
                jwtProperties.getAdminSecretKey(),
                jwtProperties.getAdminTtl(),
                claims);

        log.info("登录成功：username={}, staffId={}", username, staff.getId());

        return StaffLoginVO.builder()
                .id(staff.getId())
                .username(staff.getUsername())
                .name(staff.getName())
                .role(staff.getRole())
                .stationId(staff.getStationId())
                .token(token)
                .build();
    }

    /**
     * 查询当前登录者信息
     *
     * <p>这条链路展示了「从 token 到业务数据」的完整路径
     * （{@code BaseContext.getCurrentId()} → 查库 → 转 VO，其中 id 是拦截器
     * 解析 token 后写入的），后面每个车主端接口都会重复这个形状。
     */
    @Override
    public StaffVO getCurrentStaff() {
        // ★ 这个 if 在正常路径下永远不会成立（能走到这里说明拦截器已经放行并
        //   setCurrentId 过）。之所以还写：「永远不会成立」只对当前的拦截器配置
        //   成立 —— 哪天有人给这个接口加了放行规则、或者把它挪走，这里就会静默
        //   拿到 null，然后变成「查 id 为 null 的记录 → 返回 null → 前端拿到空对象」，
        //   一个不报错但明显不对的结果。
        Long staffId = BaseContext.getCurrentId();
        if (staffId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        Staff staff = staffMapper.getById(staffId);

        // ★ token 有效 ≠ 用户还在：token 只能证明「签发的那一刻这个 id 是存在的」，
        //   而 JWT 无状态、服务端不保存会话，所以这个中间状态必须在这里处理。
        if (staff == null) {
            log.warn("token 有效但账号已不存在，staffId={}", staffId);
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        StaffVO vo = new StaffVO();
        // ★ BeanUtils.copyProperties 在这里是安全的，在别处往往不是。
        //   危险的方向是把 DTO 复制进实体（客户端能借此覆盖 id、status 等字段），
        //   或者把实体复制进另一个宽对象（password 跟着过去）。这里是实体 → 窄 VO，
        //   而 StaffVO 里根本没有 password 字段 —— 安全性由目标类型保证，
        //   而不是由「我记得它有哪几个字段」保证。
        //   判断标准：目标是白名单（VO、DTO）→ 随便用；是宽类型（实体）→ 不要用。
        BeanUtils.copyProperties(staff, vo);

        return vo;
    }
}
