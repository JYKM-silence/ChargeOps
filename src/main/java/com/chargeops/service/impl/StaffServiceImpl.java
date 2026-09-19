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
     * <b>构造器注入，四个理由（前三个在
     * {@code JwtTokenAdminInterceptor} 里已展开，这里只说这个类特有的那个）：</b>
     *
     * <p>这个类要被单元测试。构造器注入让测试可以直接
     * {@code new StaffServiceImpl(staffMapper, jwtProperties, encoder)}，
     * 三个依赖各传一个 mock / 真实对象即可 ——
     * 不需要启动 Spring 容器，一次测试从几秒降到几毫秒。
     *
     * <p><b>★ 注入的是 {@code PasswordEncoder} 接口而不是 {@code BCryptPasswordEncoder} 实现类。</b>
     * {@code PasswordConfig} 里注册的 Bean 是后者，它能赋给前者。
     * 这样写好处的场景很具体：哪天想把 BCrypt 换成 Argon2
     * （或者想加一个「兼容旧 MD5 密码、登录成功后再重新加密」的迁移策略），
     * 只要换 {@code PasswordConfig} 里的 Bean，这个类一行都不用改。
     *
     * <p>反之，如果这里写的是 {@code BCryptPasswordEncoder}，那么换算法时
     * 所有注入点都要跟着改 —— 而「哪些地方用了密码加密」这件事，
     * 恰恰是换算法时最怕漏掉的。
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
     * <b>不会消耗 BCrypt 的计算量</b>。这不是省那 100ms 的问题：
     * BCrypt 的设计目标就是让每次校验都昂贵，而登录接口通常没有做限流，
     * 一个攻击者可以用大量不存在的账号名打登录接口，
     * 如果每个请求都先跑一遍 BCrypt，服务端的 CPU 会被白烧掉。
     * <b>让便宜且能确定拒绝的条件先执行</b>，是这类接口的通用写法。
     *
     * <p><b>★ 关于「账号不存在」和「密码错误」分开提示：</b>
     * 严格来说这会泄露「某个账号名是否注册过」（用户枚举）。
     * 更安全的做法是两种情况都返回「账号或密码错误」。
     * 这里选择分开，是因为管理端是内部系统、账号名是工号，
     * 枚举价值很低，而「密码错了」和「没这个账号」对使用者是完全不同的信息。
     * <b>这是一次有意识的取舍，面试被问到时应该能说出另一边的代价</b> ——
     * 如果这是面向公众的车主端，就该合并成一句模糊提示。
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
        // 用 Objects.equals? 这里用 StatusConstant.DISABLE.equals(...) 的写法：
        // 常量在左边，staff.getStatus() 为 null 时也不会 NPE。
        // ★ 另一种很常见的写法是 status == StatusConstant.DISABLE —— 用 == 比较
        //   Integer 是引用比较，只因 Integer 缓存了 -128~127 才恰好正确。
        //   status 的值域只有 0/1，缓存一定命中，所以那个写法「碰巧能跑」，
        //   但它是个一旦有人把状态扩到 128 就会坏掉的隐患。equals 没有这个问题。
        if (StatusConstant.DISABLE.equals(staff.getStatus())) {
            log.info("登录失败：账号被禁用，username={}, staffId={}", username, staff.getId());
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        // 3. 密码校验
        // ★ BCrypt 不是「解密后比较」，而是用密文里存着的 salt 把明文重新哈希一遍再比对，
        //   所以参数顺序是 (明文, 密文)，写反了不会报错、只会一直返回 false
        if (!passwordEncoder.matches(staffLoginDTO.getPassword(), staff.getPassword())) {
            log.info("登录失败：密码错误，username={}, staffId={}", username, staff.getId());
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        // 4. 签发 token
        //
        // claims 里只放身份标识，不放姓名/角色这些业务属性。
        // ★ 原因：JWT 的载荷是「Base64 编码」不是「加密」，任何人拿到 token
        //   都能解出里面的内容。放身份 id 是可以的（本来就要用），
        //   但放角色就会带来一个麻烦：管理员改了自己的角色，旧 token 里还是老角色，
        //   于是「降权后仍能以原权限操作，直到 token 过期」。
        //   角色这类会变的属性应该在每次请求时从数据库读，而不是烙进 token 里。
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
     * <p>这条链路短，但它展示了「从 token 到业务数据」的完整路径，
     * 后面每个车主端接口都会重复这个形状：
     * <pre>
     *   BaseContext.getCurrentId()  →  查库  →  转 VO
     *        ↑
     *   拦截器解析 token 后写入的
     * </pre>
     */
    @Override
    public StaffVO getCurrentStaff() {
        // ★ 这个 if 在正常路径下永远不会成立 —— 请求能走到这里，
        //   说明 JwtTokenAdminInterceptor 已经放行了，而它放行前必然 setCurrentId 过。
        //
        //   那为什么还写？因为「永远不会成立」只对当前的拦截器配置成立。
        //   哪天有人给这个接口加了个放行规则、或者把它挪到 /api/auth/ 下面，
        //   这里就会静默地拿到 null，然后往下走变成
        //   「查 id 为 null 的记录 → 返回 null → 前端拿到空对象」——
        //   一个不报错但明显不对的结果。
        //   加这三行，把「配置改错了」变成「一句明确的报错」。
        Long staffId = BaseContext.getCurrentId();
        if (staffId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        Staff staff = staffMapper.getById(staffId);

        // ★ token 有效 ≠ 用户还在。
        //   token 只能证明「签发的那一刻这个 id 是存在的」；
        //   从签发到这次请求之间，管理员完全可能已经被删掉了。
        //   JWT 是无状态的、服务端不保存会话，所以这个中间状态必须在这里处理。
        //   （这也是 JWT 相比 session 的一个固有代价：无法「远程踢人下线」，
        //     只能靠缩短有效期 + 每次查库来弥补。）
        if (staff == null) {
            log.warn("token 有效但账号已不存在，staffId={}", staffId);
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        StaffVO vo = new StaffVO();
        // ★ BeanUtils.copyProperties 在这里是安全的，在别处往往不是。
        //
        //   它是「按属性名复制」，只要源对象有同名属性就复制过去。
        //   危险的方向是：把 DTO 复制进实体（客户端能借此覆盖 id、status 等字段），
        //   或者把实体复制进另一个宽对象（password 跟着过去）。
        //   而这里是实体 → 窄 VO，而 StaffVO 里根本没有 password 字段，
        //   所以无论怎么复制都不可能泄露它 —— 安全性由目标类型保证，
        //   而不是由「我记得它有哪几个字段」保证。
        //
        //   判断 copyProperties 能不能用，就看目标类型是不是「白名单」。
        //   是白名单（VO、DTO）→ 随便用；是宽类型（实体）→ 不要用。
        BeanUtils.copyProperties(staff, vo);

        return vo;
    }
}
