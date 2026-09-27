package com.chargeops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * ChargeOps 新能源充电桩运营平台 —— 启动类
 *
 * <p><b>为什么包名是 com.chargeops 而不是 com.chargeops.server：</b>
 * 单模块工程里没有第二个包要区分，多一层 server 只会在
 * {@code @SpringBootApplication} 的扫描范围上多一个需要解释的概念。
 * 组件扫描默认从当前包往下扫，所以这个类必须放在所有业务包的**最外层**，
 * 一旦挪进子包（比如 com.chargeops.config），
 * 同级或上级的 @Service / @Mapper 就全部扫不到，
 * 而报错是「找不到 Bean」，完全不会提示你「启动类放错位置了」。
 */
@SpringBootApplication
// 开启注解式事务。充电业务里 @Transactional 是刚需：
// 「抢到枪 + 建订单 + 冻结余额」必须是一个原子操作，中途失败要整体回滚，
// 否则会出现「枪被占着但没有订单」这种对不上账的状态。
@EnableTransactionManagement
// 开启定时任务。规划了四件事：
//   1. 待启动订单超时取消（用户扫了码但一直没插枪）
//   2. 设备离线巡检（心跳超过 5 分钟没来就置为离线）
//   3. 卡在「充电结束待结算」的订单补偿结算
//   4. 支付失败兜底扫描 —— ★ 四个里唯一已实现的，见 ChargingOrderSweepJob
// 这几件事都是「不依赖任何外部请求、自己周期性醒过来干活」，
// 也只有它们适合放在这里 —— 不要用它做业务逻辑，那会变成难以测试的隐式调用。
//
// ★★ 关于第 4 条已经落地这件事，有两点必须知道：★★
//   · 它的开关是 chargeops.scheduling.enabled，在 dev/prod 里是 true，
//     在 src/test/resources/application.properties 里是 false ——
//     测试关掉它不是图省事：@SpringBootTest 加载完整上下文，
//     一个每 5 分钟把「待支付」翻成「支付失败」的任务，会在
//     整轮测试执行期间随机改掉 ChargingOrderServiceImplTest 正在断言的订单。
//   · 前三条目前<b>仍然没有实现</b>，别把上面这份清单当成现状。
@EnableScheduling
@Slf4j
public class ChargeOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChargeOpsApplication.class, args);
        log.info("ChargeOps 启动完成");
    }
}
//http://localhost:5173/
//http://localhost:5174/