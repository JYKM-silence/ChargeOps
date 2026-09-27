package com.chargeops.annotation;

import com.chargeops.enumeration.OperationType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 公共字段自动填充的标记注解。{@link com.chargeops.mybatis.AutoFillInterceptor} 在 SQL 执行前把四个
 * 审计字段反射写进方法的第一个参数，所以实体必须放在第一位且真的有那四个 setter（违约时抛异常）。
 *
 * <p> 是 MyBatis 插件而不是 AOP 切面：MapperProxy 由 {@code MapperFactoryBean} 在 BeanPostProcessor
 * 之后才生成，切面来不及介入 —— 写成切面是静默失效，编译通过、启动成功，审计字段永远是 null。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoFill {

    /** INSERT 填四个字段，UPDATE 只填 {@code update_time / update_user}。故意没有默认值：忘了给参数会编译不过 */
    OperationType value();
}
