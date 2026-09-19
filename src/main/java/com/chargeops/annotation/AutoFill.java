package com.chargeops.annotation;

import com.chargeops.enumeration.OperationType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 公共字段自动填充的标记注解
 *
 * <p>加在 Mapper 方法上，{@link com.chargeops.mybatis.AutoFillInterceptor} 会在 SQL
 * 执行前把 {@code create_time / create_user / update_time / update_user}
 * 四个审计字段反射写进第一个参数里。
 *
 * <pre>
 *   &#64;AutoFill(OperationType.INSERT)
 *   void insert(Station station);
 *
 *   &#64;AutoFill(OperationType.UPDATE)
 *   void update(Staff staff);
 * </pre>
 *
 * <h2>★ 为什么是 MyBatis 插件，不是 AOP 切面</h2>
 *
 * <p>这个注解<b>看起来</b>就该配一个 {@code @Aspect} —— 大多数教程也是那么写的。
 * 但那个写法在本项目里是<b>静默失效</b>的：编译通过、启动成功、零报错，
 * 而审计字段永远是 null。原因是 MyBatis 的 Mapper 由 {@code MapperFactoryBean}
 * 生产，产出的 {@code MapperProxy} 不经过 Spring 的 BeanPostProcessor，
 * Spring AOP 根本没机会织入代理。
 *
 * <p>完整的排查过程和机制解释写在
 * {@link com.chargeops.mybatis.AutoFillInterceptor} 的类注释里 ——
 * <b>那是一个比注解本身更值得读的故事</b>：它是「配置全对但功能不生效」
 * 这类问题的一个典型样本。
 *
 * <h2>★ 用之前必须知道的两条约定</h2>
 *
 * <p><b>约定一：实体对象必须是方法的第一个参数。</b>
 * 插件只认第一个参数，不看参数名、不看类型。
 * 把实体放第二个参数，插件不会报错 —— 它会去给第一个参数（比如一个
 * {@code Long id}）反射找 setter，然后失败。见下面约定二。
 *
 * <p>更好的做法其实是<b>遍历所有参数、找出带审计字段的那个</b>，
 * 但那会把「哪个参数是实体」变成一个运行时猜测：一个方法有两个实体参数时，
 * 猜错了就会把审计字段填到另一个对象上，而且完全不报错。
 * 固定成「第一个」是一条能一眼看懂的规则，
 * 代价只是「写 Mapper 时把实体放在第一位」这个习惯。
 *
 * <p><b>约定二：参数类型必须真的有那四个 setter。</b>
 * 也就是说它得是个实体类（用 Lombok {@code @Data} 生成的也算），
 * 不能是 {@code Long}、{@code String} 或者一个没有审计字段的 DTO。
 * 违反这条时插件会<b>直接抛异常</b>而不是打条日志继续跑 ——
 * 理由写在 {@code AutoFillInterceptor.apply} 的注释里，简单说就是
 * 「审计字段静默为 null」这种问题在几个月后才会被发现，而那时已经很难查了。
 *
 * <h2>★ 和数据库默认值的关系</h2>
 *
 * <p>本项目 DDL 里 {@code create_time} 有 {@code DEFAULT CURRENT_TIMESTAMP(3)}、
 * {@code update_time} 有 {@code ON UPDATE CURRENT_TIMESTAMP(3)}，
 * 也就是说<b>这两个时间字段即使不填，数据库也会给值</b>。那为什么还要这个插件？
 *
 * <p>三个理由，按重要性排列：
 * <ol>
 *   <li><b>{@code create_user} / {@code update_user} 数据库给不了。</b>
 *       「谁创建的」只有应用知道，这是这个插件真正不可替代的部分</li>
 *   <li><b>时间以应用为准而不是数据库为准。</b>多实例部署时各台机器的时钟、
 *       数据库服务器的时钟可能不一致；而充电订单的 {@code start_time}
 *       是 Java 侧生成的（见风险清单 B7），审计时间跟着走能保证
 *       「同一条记录里所有时间来自同一个时钟」，对账时不会出现
 *       「创建时间比业务时间早 3 秒」这种解释不清的现象</li>
 *   <li>某些批量/迁移场景写的是不带默认值的中间表</li>
 * </ol>
 *
 * <p>换句话说：数据库默认值是<b>兜底</b>，插件是<b>主路径</b>。
 * 兜底的存在恰好是这个插件可以「失败得比较响」的原因 ——
 * 真出了漏填，时间字段还是有值的，不会造成数据不可用。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoFill {

    /**
     * 数据库操作类型
     *
     * <p>{@link OperationType#INSERT} 填四个字段，
     * {@link OperationType#UPDATE} 只填 {@code update_time / update_user}。
     *
     * <p>故意<b>没有默认值</b>：写 {@code @AutoFill} 时忘了给参数会直接编译不过，
     * 而不是默默按某个默认语义执行。填错方向的后果是「新增时 create_user 是空的」
     * 或者「修改时把创建人改成了当前操作人」，后者更糟 —— 数据看起来完全正常。
     */
    OperationType value();
}
