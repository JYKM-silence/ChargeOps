<script setup lang="ts">
/**
 * 编辑资料（昵称 / 头像 / 性别）。
 *
 * ★★ 一、`MemberUpdateDTO` 里**没有**手机号，所以手机号是只读的 ★★
 *   后端这个 DTO 只有 `nickname` / `avatar` / `gender` ——
 *   手机号是账号标识（登录用的就是它），改它属于另一个流程，本项目没做。
 *   → 做成只读 cell 并写清楚原因，而不是给一个改了不生效的输入框。
 *
 * ★★ 二、`UpdateDTO` 家族：**空串和 null 都是「不修改」**，所以清空做不到 ★★
 *   `MemberUpdateDTO` 的注释写明「不传的字段 = 不修改」，而 Mapper 里是
 *   `if (nickname != null and nickname != '')`。于是「把昵称删干净再保存」的
 *   结果是**保存成功、什么都没变** —— 一次静默的假成功。
 *   → 输入框**不放清空按钮**（`clearable` 一律不开），并在下面写明
 *     「留空表示不修改，无法清空」。用户真的手动删空了，就在提交前拦住他
 *     （见 `nicknameError`），而不是让他拿着一个假的「已保存」离开。
 *
 * ★★ 三、头像上传**失败会抛异常**，绝不能吞成空串 ★★
 *   `uploadAvatar()` 失败时抛（`uploadFile` 里 `throw new Error(check.message)`），
 *   原因是「空串」会一路变成 `avatar: ''` —— 而空串在这个 DTO 里
 *   正好表示「不修改」（见上一条），于是**上传失败看起来像上传成功**，
 *   而且永远不会报错。所以这里：失败就提示原因、**不动**原来的头像。
 *
 * ★ 白名单 `jpg/jpeg/png/gif/webp`（**没有 svg**）、单文件 10MB，
 *   与后端 `FileUploadService` 逐字一致；`accept` 用共享的 `ACCEPT_ATTR`，
 *   让系统文件选择器也过滤一遍 —— 少一次「选了 10MB 的图再被拒绝」。
 *
 * ★ 原值来自**这一页自己拉的 `/me`**，不用 `session.profile`：
 *   后者可能是 `restore()` 造的占位资料（只有 id/phone，`gender` 恒为 0），
 *   拿它当基准会把「用户没碰过的字段」当成改动提交上去 ——
 *   比如把真实的性别 2 覆盖成 0。
 */

import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast, showToast, type UploaderFileListItem } from 'vant'
import {
  ACCEPT_ATTR,
  GENDER,
  MAX_LEN,
  checkUploadFile,
  diffUpdate,
  isActionLocked,
  resolveAssetUrl,
  withActionLock,
  type MemberProfileVO,
  type MemberUpdateDTO,
} from '@chargeops/shared'
import { getProfile, updateProfile, uploadAvatar } from '@/api/member'
import { useSessionStore } from '@/stores/session'
import { useAsyncData } from '@/composables/useAsyncData'
import { useSubmitLock } from '@/composables/useSubmitLock'
import PageState from '@/components/PageState.vue'

/** 资料保存的动作锁窗口：连点两次会写两次 `update_time`，且第二次未必有改动。 */
const SAVE_LOCK_MS = 3_000

interface ProfileForm {
  nickname: string
  /** 服务端地址（同源路径）。它同时是 `<img src>` 和要提交的值。 */
  avatar: string
  gender: number
}

const router = useRouter()
const session = useSessionStore()
const { pending, run } = useSubmitLock()

const { data: loaded, loading, failed, reload } = useAsyncData<MemberProfileVO>(getProfile)

const form = reactive<ProfileForm>({ nickname: '', avatar: '', gender: 0 })
/** 服务端原值快照 —— 增量提交的基准。 */
const original = ref<ProfileForm>({ ...form })

watch(loaded, (vo) => {
  if (!vo) return
  const next: ProfileForm = {
    nickname: vo.nickname ?? '',
    // ★ 归一成同源路径：换过 `url-prefix` 的历史头像可能是绝对地址
    //   （比如 `http://localhost:8081/img/x.png`），直接用会在别的机器上裂图。
    avatar: resolveAssetUrl(vo.avatar),
    gender: vo.gender,
  }
  Object.assign(form, next)
  original.value = { ...next }
})

/** 上传中。这段时间内头像不可再点，避免同时飞两个上传。 */
const uploading = ref(false)
/** 只作为文件输入的载体：预览用的是**服务端地址**（`:preview-image="false"`）。 */
const fileList = ref<UploaderFileListItem[]>([])

const avatarLetter = computed(() => (loaded.value?.nickname || loaded.value?.phone || '车').slice(0, 1))
const genderLabel = computed(() => GENDER.label(form.gender))
const genderVisible = ref(false)

function chooseGender(code: number): void {
  form.gender = code
  genderVisible.value = false
}

/**
 * 选文件时的**前置**校验：不通过就直接拦下，连读都不读。
 * ★ 返回 `false` 会让 `van-uploader` 放弃这次选择 —— 于是不会走到 `afterRead`。
 */
function onBeforeRead(file: File | File[]): boolean {
  const single = Array.isArray(file) ? file[0] : file
  const check = checkUploadFile(single)
  if (!check.ok) {
    showToast(check.message ?? '这个文件不能上传')
    return false
  }
  return true
}

async function onAfterRead(items: UploaderFileListItem | UploaderFileListItem[]): Promise<void> {
  const item = Array.isArray(items) ? items[0] : items
  const file = item?.file
  // 载体用完就清：见 `fileList` 的说明
  fileList.value = []

  if (!file) return
  uploading.value = true
  try {
    const url = await uploadAvatar(file)
    // ★ 拿到地址**先摆出来**（用户马上能看到图变了），但它还没提交 ——
    //   真正的写入发生在「保存」那一步，所以这里只是改表单值。
    form.avatar = url
  } catch (error) {
    // ★ 失败时提示原因、并且**保持原头像不变**。
    //   如果把失败吞成 `avatar = ''`，这个空串在 UpdateDTO 里正好表示「不修改」——
    //   于是「上传失败」的表现是「保存成功但头像没变」，不报错、查不出来。
    showToast(error instanceof Error && error.message ? error.message : '头像上传失败，请重试')
  } finally {
    uploading.value = false
  }
}

// ── 校验 ──────────────────────────────────────────────────────────────

/** 用户把原本有内容的昵称删空了 —— 这件事后端做不到，要提前说。 */
const nicknameCleared = computed(
  () => original.value.nickname !== '' && form.nickname.trim() === '',
)

/**
 * ★ 只有「**把原本有内容的昵称删空**」才算错。
 *   原本就是空（后端给 null）时**不报错**：那是「本来就没有昵称」，
 *   此时用户可能只想改头像，拦下他等于这个页面不可用。
 */
const nicknameError = computed(() =>
  nicknameCleared.value ? '留空表示不修改，无法清空昵称' : '',
)

const canSubmit = computed(() => nicknameError.value === '' && !uploading.value)

// ── 提交 ──────────────────────────────────────────────────────────────

async function onSubmit(): Promise<void> {
  if (!canSubmit.value) {
    if (nicknameCleared.value) showToast('昵称无法清空，请填写一个昵称或撤销这次修改')
    return
  }

  const edited: ProfileForm = {
    nickname: form.nickname.trim(),
    avatar: form.avatar,
    gender: form.gender,
  }

  // ★ 只提交真正改动过的字段：这个 DTO 家族「传了就改、不传就不动」，
  //   整表单原样提交会把 `update_time` 白刷一遍（而且谁都没改的时候
  //   还会写出一条毫无意义的审计记录）。
  const { payload } = diffUpdate({ ...original.value }, { ...edited })
  if (Object.keys(payload).length === 0) {
    showToast('没有需要保存的修改')
    return
  }

  const outcome = await run(() =>
    withActionLock('member-profile-update', () => updateProfile(payload as MemberUpdateDTO), SAVE_LOCK_MS),
  )
  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') {
    // 后端文案由 http 层统一 toast；被动作锁拦下时是静默的，这里补一句
    if (isActionLocked(outcome.error)) showToast('刚刚已提交过，请稍候几秒再试')
    return
  }

  // ★ 用**强制探针**刷新 store 里的资料，而不是本地改 `session.profile`：
  //   服务端对昵称是否做了归一（去空格、截断）我们并不知道，本地先改会显示一个
  //   「服务端并没有这么存」的值。探针是软失败的 —— 它挂了也不影响这次保存结果。
  void session.probe(true)

  showSuccessToast('已保存')
  router.back()
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="编辑资料" left-arrow @click-left="router.back()" />

    <PageState v-if="loading && loaded === null" kind="loading" />

    <PageState
      v-else-if="failed && loaded === null"
      kind="error"
      description="没拿到你的资料，改出来的内容会以这份旧数据为基准，所以这里先不让改。"
      @retry="reload()"
    />

    <template v-else>
      <!-- ── 头像 ───────────────────────────────────────────────────── -->
      <section class="avatar-box">
        <van-uploader
          v-model="fileList"
          :max-count="1"
          :preview-image="false"
          :accept="ACCEPT_ATTR"
          :before-read="onBeforeRead"
          :after-read="onAfterRead"
          :disabled="uploading"
        >
          <div class="avatar">
            <img v-if="form.avatar" :src="form.avatar" alt="头像" />
            <span v-else class="avatar-fallback">{{ avatarLetter }}</span>
            <div v-if="uploading" class="avatar-mask">
              <van-loading size="18" color="var(--co-bg-card)" />
            </div>
          </div>
        </van-uploader>
        <p class="avatar-tip">{{ uploading ? '上传中…' : '点击头像更换' }}</p>
        <p class="avatar-tip sub">支持 jpg / png / gif / webp，不超过 10MB</p>
      </section>

      <!-- ── 表单 ───────────────────────────────────────────────────── -->
      <van-form @submit="onSubmit">
        <van-cell-group inset class="group">
          <van-field
            v-model="form.nickname"
            label="昵称"
            placeholder="怎么称呼你"
            :maxlength="MAX_LEN.nickname"
            show-word-limit
            :error="nicknameError !== ''"
            :error-message="nicknameError"
          />
          <van-cell title="性别" :value="genderLabel" is-link @click="genderVisible = true" />
          <van-cell title="手机号" :value="loaded?.phone || '—'" />
        </van-cell-group>

        <p class="note">
          昵称留空表示「不修改」，无法清空 —— 这是后端更新接口的约定，
          把内容删掉再保存不会生效。
        </p>
        <p class="note">手机号是登录账号，暂不支持修改。</p>

        <div class="actions">
          <van-button
            block
            round
            type="primary"
            native-type="submit"
            :loading="pending"
            :disabled="!canSubmit"
            loading-text="保存中…"
          >
            保存
          </van-button>
        </div>
      </van-form>
    </template>

    <!-- 性别：选项来自共享字典（`GENDER`），不在页面里另写一份中文映射 -->
    <van-popup v-model:show="genderVisible" position="bottom" round>
      <div class="picker-head">性别</div>
      <van-cell
        v-for="item in GENDER.list"
        :key="item.code"
        :title="item.label"
        is-link
        :class="{ current: item.code === form.gender }"
        @click="chooseGender(item.code)"
      />
      <div class="picker-gap" />
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: var(--co-gap-2xl);
  background: var(--co-bg-page);
}

/* ── 头像 ─────────────────────────────────────────────────────────── */
.avatar-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24px 0 8px;
}

.avatar {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 84px;
  height: 84px;
  overflow: hidden;
  border-radius: 50%;
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-size: 30px;
  font-weight: 600;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--co-text-1);
  opacity: 0.55;
}

.avatar-tip {
  margin: 10px 0 0;
  font-size: var(--co-font-sm);
  color: var(--co-text-2);
}

.avatar-tip.sub {
  margin-top: 2px;
  font-size: var(--co-font-xs);
  color: var(--co-text-3);
}

/* ── 表单 ─────────────────────────────────────────────────────────── */
.group {
  margin-top: var(--co-gap-md);
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

.picker-head {
  padding: var(--co-gap-lg) var(--co-gap-lg) var(--co-gap-sm);
  font-size: var(--co-font-lg);
  font-weight: 600;
  color: var(--co-text-1);
}

.picker-gap {
  height: calc(var(--co-gap-lg) + env(safe-area-inset-bottom));
}

.current {
  background: var(--co-primary-weak);
}

.current :deep(.van-cell__title) {
  color: var(--co-primary-strong);
  font-weight: 600;
}
</style>
