<script setup lang="ts">
/**
 * 车主端登录。
 *
 * ★★ 演示账号有个坑，页面上必须写出来 ★★
 *
 *   种子数据里的车主 `13800000001` 的 `password` 字段是 **NULL**，
 *   也就是说**它无法用密码登录**（后端 `BCrypt.matches` 比对 null 必然失败）。
 *   拿它演示会得到「密码错误」，而密码并没有错 —— 排查方向会被完全带偏。
 *
 *   → 演示账号是 **`13900000001` / `123456`**（自助注册的演示车主）。
 *     这条写进占位提示里，省掉以后每一次「为什么登不上」的追问。
 */

import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast } from 'vant'
import { isMobile, isPassword } from '@chargeops/shared'
import { useSessionStore } from '@/stores/session'
import { useSubmitLock } from '@/composables/useSubmitLock'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const form = ref({ phone: '', password: '' })
const { pending, run } = useSubmitLock()

const phoneInvalid = computed(() => form.value.phone !== '' && !isMobile(form.value.phone))
const pwdInvalid = computed(() => form.value.password !== '' && !isPassword(form.value.password))

/** ★ 两个都填了才允许提交，但**不在空的时候显示红字** —— 那是「还没填」不是「填错了」。 */
const canSubmit = computed(
  () => form.value.phone !== '' && form.value.password !== '' && !phoneInvalid.value && !pwdInvalid.value,
)

async function onSubmit(): Promise<void> {
  if (!canSubmit.value) return

  const outcome = await run(() => session.login({ ...form.value }))
  // skipped = 连点被挡（静默）；failed = 请求失败（toast 已由 http 层弹过，
  // 不必在这里再弹一次同样的话，否则用户会看到两个红条）
  if (outcome.status !== 'done') return

  showSuccessToast('登录成功')

  // ★ redirect 来自路由守卫（用户是从某个页面被踢出来的）。
  //   校验它以 `/` 开头且不是 `//` —— 否则就是一个开放重定向。
  const redirect = route.query.redirect
  const target =
    typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
      ? redirect
      : '/'
  await router.replace(target)
}

function useDemoAccount(): void {
  form.value.phone = '13900000001'
  form.value.password = '123456'
}

function gotoRegister(): void {
  void router.push({ path: '/register', query: route.query })
}
</script>

<template>
  <div class="login">
    <header class="brand">
      <div class="logo">
        <span class="bolt">⚡</span>
      </div>
      <h1>ChargeOps</h1>
      <p>扫码充电 · 随时结算</p>
    </header>

    <van-form class="form" @submit="onSubmit">
      <van-cell-group inset>
        <van-field
          v-model.trim="form.phone"
          type="tel"
          label="手机号"
          placeholder="请输入手机号"
          maxlength="11"
          clearable
          :error="phoneInvalid"
          :error-message="phoneInvalid ? '请输入 11 位手机号' : ''"
        />
        <van-field
          v-model="form.password"
          type="password"
          label="密码"
          placeholder="请输入密码"
          clearable
          :error="pwdInvalid"
          :error-message="pwdInvalid ? '密码为 6~20 位' : ''"
        />
      </van-cell-group>

      <div class="actions">
        <van-button
          block
          round
          type="primary"
          native-type="submit"
          :loading="pending"
          :disabled="!canSubmit"
          loading-text="登录中…"
        >
          登录
        </van-button>
        <van-button block round plain type="primary" @click="gotoRegister">注册新账号</van-button>
      </div>
    </van-form>

    <div class="demo">
      <p class="demo-title">演示账号</p>
      <p class="demo-body">
        手机号 <b>13900000001</b> · 密码 <b>123456</b>
        <button type="button" class="demo-fill" @click="useDemoAccount">填入</button>
      </p>
      <!-- ★ 这条必须写出来：种子车主 13800000001 的 password 是 NULL，
           拿它演示会看到「密码错误」而密码并没有错，排查方向完全被带偏。 -->
      <p class="demo-warn">不要用 13800000001 —— 种子车主的密码是空的，无法用密码登录。</p>
    </div>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  padding: 0 0 40px;
  background: var(--co-bg-page);
}

.brand {
  padding: 64px 24px 36px;
  text-align: center;
}

.logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: var(--co-radius-xl);
  /* ★ 用品牌色到强调色的渐变：纯色块在这个尺寸上显得平，
     而渐变是最省力的「有质感」信号。 */
  background: linear-gradient(135deg, var(--co-primary) 0%, var(--co-accent) 100%);
  box-shadow: 0 8px 20px rgba(11, 95, 255, 0.28);
}

.bolt {
  font-size: 30px;
  line-height: 1;
  /* ★ emoji 的黄色和我们的品牌色打架，用 filter 统一成白色 */
  filter: grayscale(1) brightness(3);
}

.brand h1 {
  margin: 16px 0 6px;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: 0.4px;
  color: var(--co-text-1);
}

.brand p {
  margin: 0;
  font-size: 13px;
  color: var(--co-text-3);
}

.form {
  padding: 0 4px;
}

.actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 28px 16px 0;
}

.demo {
  margin: 32px 16px 0;
  padding: 12px 14px;
  border-radius: var(--co-radius-md);
  background: var(--co-bg-card);
  border: 1px dashed var(--co-border);
}

.demo-title {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--co-text-2);
}

.demo-body {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 12.5px;
  color: var(--co-text-2);
}

.demo-body b {
  font-family: var(--co-font-money);
  font-variant-numeric: tabular-nums;
  color: var(--co-text-1);
}

.demo-fill {
  margin-left: auto;
  padding: 3px 10px;
  border: 1px solid var(--co-primary-weak);
  border-radius: 999px;
  background: var(--co-primary-weak);
  color: var(--co-primary);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}

.demo-warn {
  margin: 8px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--co-warning-strong);
}
</style>
