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
 * <p>这个注解<b>看起来</b>就该配一个 {@code @Aspect}，但那个写法在本项目里是
 * <b>静默失效</b>的：编译通过、启动成功、零报错，而审计字段永远是 null。
 * MyBatis 的 Mapper 由 {@code MapperFactoryBean} 生产，产出的 {@code MapperProxy}
 * 不经过 Spring 的 BeanPostProcessor，Spring AOP 根本没机会织入代理。
 * 完整排查过程见 {@link com.chargeops.mybatis.AutoFillInterceptor} 的类注释。
 *
 * <h2>★ 用之前必须知道的两条约定</h2>
 *
 * <p><b>约定一：实体对象必须是方法的第一个参数。</b>
 * 插件只认第一个参数，不看参数名、不看类型。把实体放第二个参数，插件不会报错 ——
 * 它会去给第一个参数（比如一个 {@code Long id}）反射找 setter，然后失败（见约定二）。
 * 之所以不遍历所有参数去找带审计字段的那个：方法里有两个实体参数时，
 * 猜错了就会把审计字段填到另一个对象上，而且完全不报错。
 *
 * <p><b>约定二：参数类型必须真的有那四个 setter。</b>
 * 它得是个实体类（Lombok {@code @Data} 生成的也算），
 * 不能是 {@code Long}、{@code String} 或者一个没有审计字段的 DTO。
 * 违反这条时插件会<b>直接抛异常</b>而不是打条日志继续跑 ——
 * 理由是「审计字段静默为 null」这种问题几个月后才会被发现，而那时已经很难查了。
 *
 * <h2>★ 和数据库默认值的关系</h2>
 *
 * <p>DDL 里 {@code create_time} 有 {@code DEFAULT CURRENT_TIMESTAMP(3)}、
 * {@code update_time} 有 {@code ON UPDATE CURRENT_TIMESTAMP(3)}，不填数据库也会给值。
 * 那为什么还要这个插件？{@code create_user} / {@code update_user} 数据库给不了 ——
 * 「谁创建的」只有应用知道。另外时间以应用为准而非数据库为准：充电订单的
 * {@code start_time} 是 Java 侧生成的，审计时间跟着走能保证
 * 「同一条记录里所有时间来自同一个时钟」，对账时不会出现
 * 「创建时间比业务时间早 3 秒」这种解释不清的现象。
 *
 * <p>换句话说：数据库默认值是<b>兜底</b>，插件是<b>主路径</b>；
 * 兜底的存在恰好是这个插件可以「失败得比较响」的原因。
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
     * 而不是默默按某个默认语义执行。填错方向的后果是「修改时把创建人改成了当前操作人」——
     * 数据看起来完全正常，比「新增时 create_user 是空的」更糟。
     */
    OperationType value();
}
