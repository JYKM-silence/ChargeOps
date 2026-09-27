package com.chargeops.mybatis;

import com.chargeops.annotation.AutoFill;
import com.chargeops.constant.AutoFillConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公共字段自动填充 —— 用 <b>MyBatis 插件</b>实现，不是 Spring AOP
 *
 * <h2>★ 为什么不是切面：一个真实踩过的坑</h2>
 *
 * <p>这个类最初写成 {@code @Aspect} + {@code @Pointcut("@annotation(AutoFill)")}，
 * 也就是大多数教程的写法。它<b>编译通过、启动成功、一个报错都没有，但永远不生效</b>。
 * 排查时 aspectjweaver 在、自动代理创建器注册了、切面 Bean 存在、切点表达式也匹配
 * {@code StaffMapper.insert} —— 最后发现 {@code staffMapper} <b>不是 Spring AOP 代理</b>。
 *
 * <p><b>根因：MyBatis 的 Mapper 永远不会被 Spring AOP 代理。</b>
 * 那个 bean 的实例是 {@code jdk.proxy2.$Proxy123}，看着像动态代理，
 * 很容易误判成「代理生效了」，但那其实是 <b>MyBatis 自己的 {@code MapperProxy}</b>。
 * 机制上：Mapper 的 bean 定义 class 是 {@code MapperFactoryBean}（一个 FactoryBean），
 * 会走 BeanPostProcessor 的是这个工厂对象，而真正被调用的 {@code MapperProxy}
 * 由 {@code getObject()} 返回 —— 那一步发生在<b>所有 BeanPostProcessor 之后</b>，
 * 而 Spring AOP 的自动代理正是靠在 {@code postProcessAfterInitialization} 织入的。
 *
 * <p><b>结论：{@code @AutoFill} 标在 Mapper 方法上 + Spring AOP 切面 = 静默不生效。</b>
 * 不是配置问题，是机制上不可能：调 {@code proxy-target-class}、换 CGLIB、
 * 加 {@code @EnableAspectJAutoProxy} 全都没用，方向就不对。
 *
 * <h2>为什么改成 MyBatis 插件</h2>
 *
 * <p>插件（{@code Interceptor}）在 <b>MyBatis 自己的执行链</b>上工作，拦的是
 * {@code Executor.update()}，和 Spring AOP 无关，所以机制上一定生效。
 * 顺带的语义好处：填充发生在 SQL 执行前的最后一刻，审计字段本来就该记
 * 「真正落库那一刻」，早填反而可能在长事务里记下一个偏早的时间。
 *
 * <h2>为什么只拦 {@code Executor.update}</h2>
 *
 * <p>MyBatis 把 <b>insert / update / delete 全部归到 {@code update}</b>
 * （{@code SqlSession.insert()} 内部调的就是 {@code update()}），
 * 所以拦这一个就覆盖了所有写操作，而查询一次都不会被拦到。
 *
 * <h2>注册方式</h2>
 *
 * <p>不用配置类：{@code MybatisAutoConfiguration} 的构造器上有个
 * {@code ObjectProvider<Interceptor[]>}，会把容器里所有 {@code Interceptor} Bean
 * 收集起来传给 {@code SqlSessionFactoryBean.setPlugins()}，这里只需一个 {@code @Component}。
 *
 * <p>★ 这也意味着「插件到底装上了没有」可以验证：装上才会有填充行为，
 * {@code StaffMapperTest} 里那三条断言就是它的探针。
 *
 * <p>和 {@link AutoFill} 的约定不变：仍标在 Mapper 方法上，实体必须是<b>第一个参数</b>。
 */
@Intercepts(@Signature(
        type = Executor.class,
        method = "update",
        args = {MappedStatement.class, Object.class}))
@Component
public class AutoFillInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(AutoFillInterceptor.class);

    /**
     * MyBatis 把方法参数包装成 ParamMap 时，第一个参数的固定别名。
     *
     * <p>方法只有一个参数且没写 {@code @Param} 时对象是<b>原样</b>传下来的（不是 Map）；
     * 一旦写了 {@code @Param} 或有多个参数，参数就被包进 {@code MapperMethod.ParamMap}，
     * 第一个参数此时有两个固定别名：{@code arg0}（按位置）和 {@code param1}（按序号）。
     *
     * <p>★ 用 {@code containsKey} 而不是 {@code get}：{@code ParamMap} 重写了 {@code get}，
     * key 不存在时抛 {@code BindingException}，而 {@code containsKey} 是父类 HashMap 的实现。
     */
    private static final String[] FIRST_PARAM_KEYS = {"arg0", "param1"};

    /**
     * MappedStatement id &#8594; 填充类型。
     *
     * <p>解析要 {@code Class.forName} + 遍历方法 + 读注解，每一次 insert/update
     * 都会走到这里，缓存后每条语句只解析一次。{@code ConcurrentHashMap} 是因为
     * {@code intercept} 会在多个业务线程上并发调用。
     *
     * <p>★ 值用 {@link Optional} 包一层是为了把「解析过了，这个方法没标
     * {@code @AutoFill}」也缓存下来 —— <b>缓存「没有」和缓存「有」一样重要</b>，
     * 否则每个没标注解的 update 每次调用都要重新 {@code getMethods()} 扫一遍，
     * 而它每次返回新数组，在热点路径上是实打实的垃圾。
     * （{@code ConcurrentHashMap} 不允许 null，所以不能直接缓存 null。）
     */
    private final Map<String, Optional<OperationType>> specCache = new ConcurrentHashMap<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        MappedStatement ms = (MappedStatement) args[0];
        Object parameter = args[1];

        Optional<OperationType> spec = specCache.computeIfAbsent(ms.getId(), this::resolve);

        // 没标 @AutoFill 的语句（占绝大多数）直接放行，除上面那次 Map 查找外无开销
        if (spec.isPresent()) {
            Object entity = findEntity(parameter);
            if (entity != null) {
                apply(entity, spec.get());
            }
        }

        return invocation.proceed();
    }

    /**
     * 读出某个 MappedStatement 对应的 Mapper 方法上的 {@code @AutoFill}。
     *
     * <p>{@code ms.getId()} 的格式是「Mapper 接口全限定名 + "." + 方法名」，
     * 从最后一个点切开就是类名和方法名。
     */
    private Optional<OperationType> resolve(String msId) {
        int lastDot = msId.lastIndexOf('.');
        if (lastDot < 0) {
            return Optional.empty();
        }
        String className = msId.substring(0, lastDot);
        String methodName = msId.substring(lastDot + 1);

        Class<?> mapperInterface;
        try {
            // 类加载器显式指定为 AutoFillInterceptor 自己的，而不是 Class.forName(String)
            // 默认的「调用者类加载器」—— 两者在 fat jar 里不一定相同。
            // initialize = false：只读注解，不触发静态初始化
            mapperInterface = Class.forName(className, false, AutoFillInterceptor.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // 走到这里说明 msId 不是「接口名.方法名」的形式（比如 XML 里手写的
            // statement id）。不是错误，只是这类语句不支持自动填充
            log.debug("无法加载 Mapper 接口 {}，该语句跳过自动填充", className);
            return Optional.empty();
        }

        for (Method method : mapperInterface.getMethods()) {
            if (method.getName().equals(methodName) && method.isAnnotationPresent(AutoFill.class)) {
                return Optional.of(method.getAnnotation(AutoFill.class).value());
            }
        }
        return Optional.empty();
    }

    /** 从 MyBatis 传下来的参数对象里找出那个实体（{@link AutoFill} 里「实体必须是第一个参数」的落地处） */
    private static Object findEntity(Object parameter) {
        if (parameter == null) {
            return null;
        }
        if (parameter instanceof Map<?, ?> paramMap) {
            for (String key : FIRST_PARAM_KEYS) {
                if (paramMap.containsKey(key)) {
                    Object value = paramMap.get(key);
                    if (value != null) {
                        return value;
                    }
                }
            }
            // 参数是 Map 但取不到第一个参数 —— 大概率是「Mapper 方法直接收一个 Map
            // 当参数」的写法，那种写法本来就不该标 @AutoFill
            return null;
        }
        return parameter;
    }

    /**
     * 真正写字段。
     *
     * <p>★ setter 找不到就<b>直接抛异常</b>，不做「打条日志继续跑」：审计字段为 null
     * 没有任何即时症状（接口照样返回成功，可空列照样收），只有几个月后想查
     * 「这条记录是谁建的」才会发现查不到，那时已无从追溯。抛异常能让错误在
     * 写下 {@code @AutoFill} 的当天就暴露。
     *
     * <p>用 {@code getMethod} 而不是 {@code getDeclaredMethod}：前者沿继承链往上找，
     * 实体继承基类、setter 写在基类上时也能工作。
     */
    private static void apply(Object entity, OperationType operationType) {
        LocalDateTime now = LocalDateTime.now();
        // 当前登录者。没有登录上下文时（比如设备上报、定时任务）为 null，
        // 这是符合预期的 —— 「谁创建的」本来就该是 NULL，而不是 0 或某个假的 id
        Long currentId = BaseContext.getCurrentId();

        if (operationType == OperationType.INSERT) {
            write(entity, AutoFillConstant.SET_CREATE_TIME, LocalDateTime.class, now);
            write(entity, AutoFillConstant.SET_CREATE_USER, Long.class, currentId);
        }
        // UPDATE 只写这两个 —— 创建人和创建时间是「历史事实」，
        // 被修改操作覆写掉的话，数据看起来完全正常，但语义已经被悄悄换掉了
        write(entity, AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class, now);
        write(entity, AutoFillConstant.SET_UPDATE_USER, Long.class, currentId);
    }

    private static void write(Object entity, String setter, Class<?> paramType, Object value) {
        Class<?> clazz = entity.getClass();
        Method method;
        try {
            method = clazz.getMethod(setter, paramType);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(String.format(
                    "@AutoFill 尝试填充 %s，但它没有 %s(%s) 方法。"
                            + "请确认该参数是实体类（含审计字段），或检查实体上的 setter 命名",
                    clazz.getName(), setter, paramType.getSimpleName()), e);
        }
        try {
            method.invoke(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(String.format(
                    "@AutoFill 调用 %s.%s 失败", clazz.getName(), setter), e);
        }
    }
}
