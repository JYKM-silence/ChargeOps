<script setup lang="ts">
/**
 * 管理端登录页。
 *
 * ★ 左侧品牌区 + 右侧表单的左右分栏：这是「后台也值得做得好看」的最省力做法 ——
 *   纯居中表单会显得像内部工具，一个深色品牌面板就能把观感拉起来。
 *   H5 端没有这一层（屏幕太小），所以那边是单列。
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import CoIcon from '@/components/CoIcon.vue'
import { useSubmitLock } from '@/composables/useSubmitLock'
import { useSessionStore } from '@/stores/session'
import { MAX_LEN } from '@chargeops/shared'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })

/**
 * ★ 登录的校验**刻意宽松**：后端 `StaffLoginDTO` 只有 `@NotBlank`，
 *   没有格式校验。前端加格式限制会让某些历史账号**永远登不进来** ——
 *   而那种问题只会表现为「密码明明是对的」（其实是账号格式被前端拦了）。
 *   所以这里只挡空值，长度上限照抄 DTO。
 */
const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const { pending, run } = useSubmitLock({ throttleMs: 300 })

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const outcome = await run(() =>
    session.login({ username: form.username.trim(), password: form.password }),
  )

  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    // 失败提示由 api/http.ts 的全局 onError 统一弹出（后端的中文 msg 就是文案），
    // 这里不重复弹一次。
    return
  }

  ElMessage({ type: 'success', message: '登录成功', duration: 1500 })
  // ★ 回到登录前想去的页面；没有就进工作台
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
  void router.replace(redirect)
}
</script>

<template>
  <div class="login">
    <section class="brand-panel">
      <div class="brand-inner">
        <div class="brand-logo">
          <CoIcon name="logo" :size="22" />
          <span>ChargeOps</span>
        </div>
        <h1>充电运营管理后台</h1>
        <p class="brand-desc">
          站点、充电桩、计费模板、套餐与订单，一处统一管理。
        </p>
        <ul class="brand-points">
          <li><CoIcon name="check" :size="15" /> 尖峰平谷分时计费，规则可视化编辑</li>
          <li><CoIcon name="check" :size="15" /> 订单全流程状态机，资金流水可追溯</li>
          <li><CoIcon name="check" :size="15" /> 车主权益与钱包调账，操作留痕</li>
        </ul>
      </div>
      <!-- 装饰光斑：一个柔和的径向渐变，让纯色面板不至于太平 -->
      <div class="glow" aria-hidden="true" />
    </section>

    <section class="form-panel">
      <div class="form-inner">
        <h2>登录</h2>
        <p class="form-desc">请使用运维账号登录</p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="onSubmit"
        >
          <el-form-item label="账号" prop="username">
            <el-input
              v-model="form.username"
              placeholder="请输入账号"
              :maxlength="MAX_LEN.code"
              autocomplete="username"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
              @keyup.enter="onSubmit"
            />
          </el-form-item>

          <el-button
            type="primary"
            class="submit"
            size="large"
            :loading="pending"
            @click="onSubmit"
          >
            登录
          </el-button>
        </el-form>

        <p class="hint">
          演示账号：<code>admin</code> / <code>123456</code>
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  min-height: 100vh;
}

/* ── 品牌区 ─────────────────────────────────────────────────────────── */
.brand-panel {
  position: relative;
  flex: 1 1 46%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  overflow: hidden;
  background: linear-gradient(150deg, #141c28 0%, var(--co-sidebar-bg) 55%, #22304a 100%);
  color: #fff;
}

.brand-inner {
  position: relative;
  z-index: 1;
  max-width: 380px;
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.3px;
}

.brand-logo :deep(.co-icon) {
  padding: 5px;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: linear-gradient(135deg, var(--co-primary) 0%, #4d8bff 100%);
}

.brand-panel h1 {
  margin: 28px 0 12px;
  font-size: 27px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: 0.5px;
}

.brand-desc {
  margin: 0 0 30px;
  color: rgba(255, 255, 255, 0.62);
  font-size: 14px;
  line-height: 1.7;
}

.brand-points {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.brand-points li {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13.5px;
  color: rgba(255, 255, 255, 0.8);
}

.brand-points :deep(.co-icon) {
  color: #4d8bff;
}

.glow {
  position: absolute;
  right: -140px;
  bottom: -160px;
  width: 460px;
  height: 460px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(11, 95, 255, 0.34) 0%, transparent 68%);
}

/* ── 表单区 ─────────────────────────────────────────────────────────── */
.form-panel {
  flex: 1 1 54%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  background: var(--co-bg-card);
}

.form-inner {
  width: 100%;
  max-width: 340px;
}

.form-inner h2 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
  color: var(--co-text-1);
}

.form-desc {
  margin: 0 0 28px;
  font-size: 13.5px;
  color: var(--co-text-3);
}

.submit {
  width: 100%;
  margin-top: 4px;
  letter-spacing: 2px;
}

.hint {
  margin: 22px 0 0;
  text-align: center;
  font-size: 12.5px;
  color: var(--co-text-3);
}

.hint code {
  padding: 1px 5px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-2);
  font-size: 12px;
}

/* 窄屏：品牌区太占地方，直接收起来 */
@media (max-width: 860px) {
  .brand-panel {
    display: none;
  }
}
</style>
