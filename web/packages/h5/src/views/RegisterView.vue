<script setup lang="ts">
/**
 * 车主端注册。
 *
 * ★★ 后端**注册即登录** —— 返回值和登录完全一样（含 token）★★
 *   所以这里注册成功后**直接进首页**，不要跳回登录页让用户再输一遍。
 *   （已核对 `MemberAuthController.register` 返回 `Result<MemberLoginVO>`。）
 *
 * ★ `nickname` 可选：不填时后端用什么由后端决定，前端不猜。
 *   这里留空就不发这个字段（`cleanParams` 会把 undefined 丢掉），
 *   而不是发一个空字符串 —— 空串和「没填」在后端是两件事。
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

const form = ref({ phone: '', password: '', confirm: '', nickname: '' })
const { pending, run } = useSubmitLock()

const phoneInvalid = computed(() => form.value.phone !== '' && !isMobile(form.value.phone))
const pwdInvalid = computed(() => form.value.password !== '' && !isPassword(form.value.password))

/** ★ 两次密码不一致是**只在确认框填了之后**才提示 —— 边输边报错很烦人。 */
const confirmInvalid = computed(
  () => form.value.confirm !== '' && form.value.confirm !== form.value.password,
)

const canSubmit = computed(
  () =>
    form.value.phone !== '' &&
    form.value.password !== '' &&
    form.value.confirm !== '' &&
    !phoneInvalid.value &&
    !pwdInvalid.value &&
    !confirmInvalid.value,
)

async function onSubmit(): Promise<void> {
  if (!canSubmit.value) return

  const outcome = await run(() =>
    session.register({
      phone: form.value.phone,
      password: form.value.password,
      // ★ 空串 → undefined：让后端走「没填昵称」的分支
      nickname: form.value.nickname.trim() || undefined,
    }),
  )
  if (outcome.status !== 'done') return

  // ★ 注册即登录，直接进首页 —— 不要再跳登录页
  showSuccessToast('注册成功，已自动登录')

  const redirect = route.query.redirect
  const target =
    typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
      ? redirect
      : '/'
  await router.replace(target)
}

function gotoLogin(): void {
  void router.replace({ path: '/login', query: route.query })
}
</script>

<template>
  <div class="register">
    <van-nav-bar title="注册" left-arrow @click-left="gotoLogin" />

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
          placeholder="6~20 位"
          clearable
          :error="pwdInvalid"
          :error-message="pwdInvalid ? '密码为 6~20 位' : ''"
        />
        <van-field
          v-model="form.confirm"
          type="password"
          label="确认密码"
          placeholder="再输一次"
          clearable
          :error="confirmInvalid"
          :error-message="confirmInvalid ? '两次输入的密码不一致' : ''"
        />
        <van-field
          v-model.trim="form.nickname"
          label="昵称"
          placeholder="可不填"
          maxlength="20"
          clearable
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
          loading-text="注册中…"
        >
          注册并登录
        </van-button>
      </div>

      <p class="tip">
        注册成功后会<b>直接登录</b>，不用再输一次密码。
      </p>
    </van-form>
  </div>
</template>

<style scoped>
.register {
  min-height: 100vh;
  background: var(--co-bg-page);
}

.form {
  padding: 16px 4px 0;
}

.actions {
  padding: 28px 16px 0;
}

.tip {
  margin: 14px 24px 0;
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
  color: var(--co-text-3);
}

.tip b {
  font-weight: 600;
  color: var(--co-text-2);
}
</style>
