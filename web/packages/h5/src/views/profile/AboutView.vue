<script setup lang="ts">
/**
 * 关于。
 *
 * ★ 这一页最容易变成「编造信息的展板」★
 *   演示项目里写一个不存在的公司名、400 客服电话、备案号，看起来更「完整」，
 *   但那是在**制造无法验证的信息** —— 用户照着打个电话过去，得到的是空号。
 *   所以这里只放**能对得上代码**的内容：应用名、版本、技术栈、
 *   演示账号（与登录页同源），以及一句「充值不产生真实扣款」。
 *
 * ★ 版本号取自 `packages/h5/package.json`（`resolveJsonModule` 已开）。
 *   注意它说的是**这个前端包**的版本，不是后端接口的版本 ——
 *   两者会各自漂移，所以下面写明了它的出处，别把它当成整个系统的版本。
 */

import { useRouter } from 'vue-router'
import pkg from '../../../package.json'

const APP_VERSION = pkg.version

const router = useRouter()
</script>

<template>
  <div class="page">
    <van-nav-bar title="关于" left-arrow @click-left="router.back()" />

    <header class="brand">
      <div class="logo" aria-hidden="true">⚡</div>
      <h1 class="name">ChargeOps</h1>
      <p class="ver co-num">车主端 v{{ APP_VERSION }}</p>
      <p class="desc">找站、扫码充电、买套餐、查订单和余额。</p>
    </header>

    <van-cell-group inset class="group">
      <van-cell title="应用名称" value="ChargeOps 车主端" />
      <van-cell title="版本" :value="`v${APP_VERSION}`" />
      <van-cell title="形态" value="移动端 H5" />
    </van-cell-group>

    <section class="block">
      <h2 class="block-title">演示说明</h2>
      <p class="block-body notice">本应用为演示项目，充值不产生真实扣款。</p>
      <p class="block-body">
        充电、套餐购买流程走的是同一套模拟实现，没有接入任何支付网关；
        金额、订单与流水都由后端按真实规则计算并落库，可以放心操作。
      </p>
    </section>

    <section class="block">
      <h2 class="block-title">演示账号</h2>
      <p class="block-body">
        手机号 <b class="co-num">13900000001</b> · 密码 <b class="co-num">123456</b>
      </p>
      <!-- ★ 与登录页写的是同一件事：种子车主 13800000001 的 password 是 NULL，
           拿它登录会看到「密码错误」，而密码并没有错 —— 必须在这里也说一遍，
           因为很多人是先点进「关于」看看有什么的。 -->
      <p class="block-body warn">
        不要用 13800000001 —— 那个种子车主的密码是空的，无法用密码登录。
      </p>
    </section>

    <section class="block">
      <h2 class="block-title">技术栈</h2>
      <p class="block-body">前端：Vue 3 · TypeScript · Vant 4 · Pinia · Vue Router</p>
      <p class="block-body">后端：Spring Boot · MyBatis · MySQL · Redis · JWT</p>
    </section>

    <p class="foot">
      页面内容与仓库里的实现一一对应，没有客服电话和公司信息 ——
      这是一个演示项目，编一个出来只会误导人。
    </p>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: var(--co-gap-2xl);
  background: var(--co-bg-page);
}

/* ── 品牌区 ───────────────────────────────────────────────────────── */
.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px var(--co-gap-lg) 24px;
  background: var(--co-bg-card);
  border-bottom: 1px solid var(--co-border-light);
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: var(--co-radius-lg);
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-size: 30px;
  line-height: 1;
}

.name {
  margin: var(--co-gap-md) 0 0;
  font-size: var(--co-font-xl);
  font-weight: 600;
  letter-spacing: 0.2px;
  color: var(--co-text-1);
}

.ver {
  margin: 4px 0 0;
  font-size: var(--co-font-xs);
  color: var(--co-text-3);
}

.desc {
  margin: var(--co-gap-sm) 0 0;
  font-size: var(--co-font-sm);
  color: var(--co-text-2);
}

/* ── 信息块 ───────────────────────────────────────────────────────── */
.group {
  margin-top: var(--co-gap-md);
}

.block {
  margin: var(--co-gap-md) var(--co-gap-md) 0;
  padding: var(--co-gap-lg);
  background: var(--co-bg-card);
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-lg);
  box-shadow: var(--co-shadow-sm);
}

.block-title {
  margin: 0 0 var(--co-gap-sm);
  font-size: var(--co-font-md);
  font-weight: 600;
  color: var(--co-text-1);
}

.block-body {
  margin: 0;
  font-size: var(--co-font-sm);
  line-height: 1.7;
  color: var(--co-text-2);
}

.block-body + .block-body {
  margin-top: 6px;
}

.block-body b {
  font-weight: 600;
  color: var(--co-text-1);
}

/* ★ 「不产生真实扣款」用 warning 而不是 danger：
     它不是风险提示，而是一句让人放心的说明，红色会读成「出事了」。 */
.notice {
  color: var(--co-warning-strong);
}

.warn {
  font-size: var(--co-font-xs);
  color: var(--co-text-3);
}

.foot {
  margin: var(--co-gap-lg) var(--co-gap-lg) 0;
  font-size: var(--co-font-xs);
  line-height: 1.7;
  color: var(--co-text-3);
}
</style>
