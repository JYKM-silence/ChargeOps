<script setup lang="ts">
/**
 * 修改密码。
 *
 * ★★ 一、这是「改密码」，**不是**「找回密码」★★
 *   后端要**当前密码**（`MemberPasswordDTO.oldPassword`，`@NotBlank`）——
 *   这是一个「已经登录、也知道旧密码的人改密码」的流程。
 *   所以这里**不放「忘记密码？」入口**：点了要么跳到别的地方，
 *   要么什么都不会发生，而两者都比没有这个链接更糟 ——
 *   用户会以为「密码可以靠短信找回来」，而这件事在本项目里根本不存在。
 *
 * ★★ 二、密码规则必须与后端**逐字一致**（`isPassword` = 6~32 位）★★
 *   前端比后端严 → 合法密码输入不了（`validators.ts` 里那个「登录不套手机号正则」
 *   的例子就是这种事故）；比后端松 → 白跑一趟，用户还得重填两次密码。
 *   ★ 「当前密码」**不做长度校验**：后端对它只有 `@NotBlank`。
 *     旧密码是历史遗留数据，用现在的规则去卡它，会让某些账号永远改不了密码。
 *
 * ★★ 三、两次输入一致只能在前端校验 ★★
 *   确认框是**前端概念**，后端根本收不到它，所以这一条没有「后端的说法」可抄。
 *   ★ 报错时机：**确认框有内容之后**才报不一致 ——
 *     刚敲第一个字符就红着脸说「不一致」，是把「正在输入」当成了错误。
 *
 * ★★ 四、改完之后**不做任何假设**★★
 *   这个接口**不返回 token**（`Result<null>`），也无法从响应里看出
 *   「已签发的旧 token 是否被撤销」（本项目禁用账号都不会撤销 token，
 *   见 `MemberStatus`/`UseMemberProbe` 的说明）。所以这里只报成功、
 *   不强制登出、也不提示「请重新登录」—— 真的需要重新登录时，
 *   那个 401 会由 http 层统一接住并弹出登录页。
 */

import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import { isActionLocked, isPassword, withActionLock } from '@chargeops/shared'
import { changePassword } from '@/api/member'
import { useSubmitLock } from '@/composables/useSubmitLock'

/** 改密码的动作锁窗口：连点两次的第二次会拿「旧密码错误」打回来，那是假故障。 */
const PASSWORD_LOCK_MS = 3_000

/**
 * 密码长度上下界，出处 `MemberPasswordDTO:91` 的 `@Size(6,32)`。
 * ★ 共享层只导出了 `isPassword()` 函数、没有导出这两个数字，
 *   所以这里写一次并标出处 —— 不要另造一套规则。
 */
const PWD_MIN = 6
const PWD_MAX = 32

const router = useRouter()
const { pending, run } = useSubmitLock()

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const submitted = ref(false)
/** 是否明文显示。★ 手机上盲打密码的出错率很高，给一个能看一眼的开关。 */
const showText = ref(false)

const oldError = computed(() => {
  // 只有 `@NotBlank`。刻意不套 6~32：旧密码可能是历史数据
  if (oldPassword.value === '') return submitted.value ? '请输入当前密码' : ''
  return ''
})

const newError = computed(() => {
  if (newPassword.value === '') return submitted.value ? '请输入新密码' : ''
  if (!isPassword(newPassword.value)) {
    return `密码长度需为 ${PWD_MIN}~${PWD_MAX} 位`
  }
  return ''
})

const confirmError = computed(() => {
  // ★ 空 → 只在提交过之后提示；有内容 → 立刻比对。见文件头第三节
  if (confirmPassword.value === '') return submitted.value ? '请再次输入新密码' : ''
  if (confirmPassword.value !== newPassword.value) return '两次输入的新密码不一致'
  return ''
})

const canSubmit = computed(
  () =>
    oldPassword.value !== '' &&
    newPassword.value !== '' &&
    confirmPassword.value !== '' &&
    oldError.value === '' &&
    newError.value === '' &&
    confirmError.value === '',
)

async function onSubmit(): Promise<void> {
  submitted.value = true
  if (!canSubmit.value) return

  // ★ 锁的 key **不含密码**：密码是敏感值，不该被拿去做任何键名；
  //   而且这里不存在「同一动作、不同 payload」的区分需求（表单只有一份）。
  const outcome = await run(() =>
    withActionLock(
      'member-password-change',
      () =>
        changePassword({
          oldPassword: oldPassword.value,
          newPassword: newPassword.value,
        }),
      PASSWORD_LOCK_MS,
    ),
  )
  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') {
    // ★ 「当前密码错误」这类是**后端判定**的，文案由 http 层统一 toast。
    //   被动作锁拦下时 http 层是静默的，这里补一句，否则是「点了没反应」。
    if (isActionLocked(outcome.error)) showToast('刚刚已提交过，请稍候几秒再试')
    return
  }

  showSuccessToast('密码已修改')
  // ★ 清空而不是保留：留着一串明文密码在输入框里没有任何好处，
  //   而且用户往往会再点一次「确定」。
  oldPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  submitted.value = false
  showText.value = false
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="修改密码" left-arrow @click-left="router.back()" />

    <van-form @submit="onSubmit">
      <van-cell-group inset class="group">
        <van-field
          v-model="oldPassword"
          :type="showText ? 'text' : 'password'"
          label="当前密码"
          placeholder="请输入正在使用的密码"
          autocomplete="current-password"
          :error="oldError !== ''"
          :error-message="oldError"
        >
          <template #right-icon>
            <van-icon
              :name="showText ? 'eye-o' : 'closed-eye'"
              class="eye"
              @click="showText = !showText"
            />
          </template>
        </van-field>

        <van-field
          v-model="newPassword"
          :type="showText ? 'text' : 'password'"
          label="新密码"
          :placeholder="`${PWD_MIN}~${PWD_MAX} 位字符`"
          autocomplete="new-password"
          :maxlength="PWD_MAX"
          :error="newError !== ''"
          :error-message="newError"
        />

        <van-field
          v-model="confirmPassword"
          :type="showText ? 'text' : 'password'"
          label="确认新密码"
          placeholder="再输入一次新密码"
          autocomplete="new-password"
          :maxlength="PWD_MAX"
          :error="confirmError !== ''"
          :error-message="confirmError"
        />
      </van-cell-group>

      <p class="note">
        需要先验证当前密码，所以这里不是「找回密码」。如果已经登不进来，
        这条路径帮不上忙。
      </p>
      <p class="note">
        改完之后当前设备的登录状态不受影响；其它设备上已登录的会话是否失效，
        由服务端决定，本页不做假设、也不会强制你重新登录。
      </p>

      <div class="actions">
        <van-button
          block
          round
          type="primary"
          native-type="submit"
          :loading="pending"
          :disabled="!canSubmit"
          loading-text="提交中…"
        >
          确认修改
        </van-button>
      </div>
    </van-form>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: var(--co-gap-2xl);
  background: var(--co-bg-page);
}

.group {
  margin-top: var(--co-gap-md);
}

.eye {
  padding: 0 4px;
  color: var(--co-text-3);
  font-size: 18px;
  cursor: pointer;
}

.note {
  margin: var(--co-gap-md) var(--co-gap-lg) 0;
  font-size: var(--co-font-xs);
  line-height: 1.7;
  color: var(--co-text-3);
}

.actions {
  padding: var(--co-gap-xl) var(--co-gap-lg) 0;
}
</style>
