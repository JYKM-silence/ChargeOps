<script setup lang="ts">
/**
 * 个人资料（当前登录的运维账号）。
 *
 * ★★ 这一页有两件事是「没有」，而它们比「有」更需要写出来 ★★
 *
 *   1. **没有行级权限。**
 *      后端的 `staff.role` / `staff.station_id` **不被任何业务代码读取**：
 *      `BaseContext` 里只放 `currentId`，拦截器只验 token 签名。
 *      也就是说 `2 站点运维` 和 `1 平台管理员` 在服务端的能力**完全一样** ——
 *      任何运维都能改任何站点、给任何车主调账。
 *      → 所以这一页：
 *        · 必须把这句实话写在页面上，不能让运营误以为权限已经分开了；
 *        · **不能**按角色隐藏任何菜单或按钮（`layouts/AdminLayout.vue` 与
 *          路由表里也没有这个机制）。藏起来只会造成「我这个号不能用那个功能」的
 *          错觉，而真实情况是后端根本不分权 —— 藏了反而更难排查。
 *        角色当前**只用于显示**（比如给人看「这个号是什么角色」）。
 *
 *   2. **没有编辑入口，也没有「最后登录时间」。**
 *      `/api/admin/staff/**` 下**只有** `GET /me` 一个端点：没有 `PUT`，没有运维列表。
 *      所以这一页是只读的（放一个点了报错的「编辑」按钮比不放更糟）。
 *      而「最后登录时间」更彻底：`staff` 表里**没有这个列**
 *      （`StaffMapper.xml` 的 `Base_Column_List` 里没有，`StaffVO` 上也没有），
 *      所以它不是「没取到」，是**不存在** —— 这一行要直说，不能让用户以为是加载失败。
 */

import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ADMIN_TOKEN_TTL_MS, formatServerTime, labelOf, type StaffVO } from '@chargeops/shared'
import { getStaffMe } from '@/api/staff'
import { stationOptions } from '@/api/options'
import { useOptions } from '@/composables/useOptions'
import { useSessionStore } from '@/stores/session'
import PagePanel from '@/components/PagePanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import CoIcon from '@/components/CoIcon.vue'

const router = useRouter()
const session = useSessionStore()

const me = ref<StaffVO | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const vo = await getStaffMe()
    // 后端成功时一定带数据；返回 null 说明契约变了，不要静默显示一个空资料页
    if (!vo) error.value = new Error('接口没有返回运维信息。')
    me.value = vo
  } catch (e) {
    error.value = e
    me.value = null
  } finally {
    loading.value = false
  }
}

void load()

/* ── 取值映射 ─────────────────────────────────────────────────────────── */

/**
 * 角色 / 性别 / 状态的文字。
 *
 * ★ 用**局部映射**而不是往 shared 的字典里加：`StaffVO` 上没有 `roleLabel`
 *   这类字段，而展示角色全项目只有这一处；加进 shared 会让字典文件里多出一个
 *   谁都不知道该不该用的 `STAFF_ROLE`。
 *   取值**逐条抄自** `StaffVO.java` 的 `@Schema`（role 1 平台管理员 / 2 站点运维，
 *   stationId 0 为平台级，status 1 启用 0 禁用，sex 是**字符串** `0 女 1 男`）。
 *   ★ `sex` 是字符串，所以**不能**用 shared 的 `GENDER` 字典（那是数字枚举，车主端的）。
 */
const ROLE_LABEL: Record<number, string> = { 1: '平台管理员', 2: '站点运维' }
const SEX_LABEL: Record<string, string> = { '0': '女', '1': '男' }

const roleText = computed(() => {
  const role = me.value?.role
  if (role === undefined) return '-'
  return ROLE_LABEL[role] ?? `未知角色（${role}）`
})

const sexText = computed(() => {
  const sex = me.value?.sex
  if (sex === null || sex === undefined || sex === '') return '未填写'
  return SEX_LABEL[sex] ?? sex
})

const statusText = computed(() => {
  const status = me.value?.status
  if (status === 1) return '启用'
  if (status === 0) return '禁用'
  // 枚举加了新值而前端没跟上时露出原始码，不要显示成空白
  return status === undefined ? '-' : `未知(${status})`
})

const statusTone = computed(() => {
  const status = me.value?.status
  return status === 1 ? 'success' : status === 0 ? 'danger' : 'info'
})

/** 站点 id → 名称。**失败不影响这一页**：拿不到名称就退回显示 id。 */
const { options: stations, error: stationsError } = useOptions<number>(stationOptions)

const stationText = computed(() => {
  const id = me.value?.stationId
  // ★ `0` 是后端约定的「平台级」（`StaffVO.stationId` 的 @Schema），
  //   它不是「一个 id 为 0 的站点」—— 用 `labelOf` 会显示成字面的 0。
  if (id === null || id === undefined || id === 0) return '平台级（不绑定站点）'
  return labelOf(stations.value, id)
})

/* ── 退出登录 ─────────────────────────────────────────────────────────── */

/**
 * ★ 用轻确认（`ElMessageBox`）而不是 `DangerConfirm`：
 *   退出登录**没有资金后果**，而且它就是「重新登录一下」就能撤销的动作 ——
 *   把「手打手机号」那种仪式感用在这里，会让真正需要它的资金动作贬值。
 */
async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('退出后需要重新登录才能继续操作。', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    // 用户点了取消 —— 不是错误，不弹提示
    return
  }
  session.logout()
  void router.replace('/login')
}
</script>

<template>
  <div class="page">
    <PagePanel
      title="个人资料"
      subtitle="当前登录的运维账号。这一页是只读的：后端 /api/admin/staff 下只有 GET /me 一个端点，没有编辑接口。"
    >
      <EmptyState
        v-if="error"
        kind="error"
        title="没能加载运维信息"
        :description="errorText(error)"
        @retry="load"
      />

      <div v-else v-loading="loading" class="body">
        <!-- ── 身份 ─────────────────────────────────────────────────── -->
        <div class="identity">
          <span class="avatar">{{ (me?.name || me?.username || '?').slice(0, 1) }}</span>
          <div class="identity-text">
            <div class="name-row">
              <b>{{ me?.name || '未填写姓名' }}</b>
              <StatusTag :label="statusText" :tone="statusTone" size="sm" />
              <span class="role-chip">{{ roleText }}</span>
            </div>
            <span class="sub co-num">
              {{ me?.username ?? '-' }}
              <template v-if="me?.phone"> · {{ me.phone }}</template>
            </span>
          </div>
        </div>

        <dl class="facts">
          <div class="fact">
            <dt>登录账号</dt>
            <dd class="co-num">{{ me?.username ?? '-' }}</dd>
          </div>
          <div class="fact">
            <dt>姓名</dt>
            <dd>{{ me?.name || '未填写' }}</dd>
          </div>
          <div class="fact">
            <dt>手机号</dt>
            <dd class="co-num">{{ me?.phone || '未填写' }}</dd>
          </div>
          <div class="fact">
            <dt>性别</dt>
            <dd>{{ sexText }}</dd>
          </div>
          <div class="fact">
            <dt>角色</dt>
            <dd>
              {{ roleText }}
              <span class="fact-note">
                只是这一次显示用的标注：后端不按角色做任何判断，所以它不决定你能做什么。
              </span>
            </dd>
          </div>
          <div class="fact">
            <dt>负责站点</dt>
            <dd>
              {{ stationText }}
              <span v-if="stationsError" class="fact-note">
                （站点名称没取到，显示的是原始的站点 id —— 接口失败不影响这一页的其它内容）
              </span>
              <span v-else class="fact-note">
                同样是标注：后端不按 <code>station_id</code> 限制任何操作。
              </span>
            </dd>
          </div>
          <div class="fact">
            <dt>账号创建</dt>
            <dd class="co-num">{{ formatServerTime(me?.createTime) ?? '-' }}</dd>
          </div>
          <div class="fact">
            <dt>最后登录</dt>
            <dd>
              <span class="no-data">系统不记录</span>
              <span class="fact-note">
                <code>staff</code> 表里没有登录时间这个列（<code>StaffMapper.xml</code>
                的字段列表里没有，<code>StaffVO</code> 上也没有），
                所以它不是「加载失败」，是<b>不存在</b>。
                想在登录事件上留痕，要靠后端的登录日志，而本项目还没有。
              </span>
            </dd>
          </div>
        </dl>

        <!-- ── 权限说明（这一页存在的主要理由之一）────────────────── -->
        <section class="notice">
          <h3>
            <CoIcon name="warning" :size="15" />
            本系统暂未启用行级权限：所有运维账号权限相同
          </h3>
          <ul>
            <li>
              后端的 <code>staff.role</code> 与 <code>staff.station_id</code>
              <b>不被任何业务代码读取</b>：登录后只把 <code>currentId</code> 放进上下文，
              拦截器只验 token 签名、不查库。
            </li>
            <li>
              所以「站点运维」也能改别的站点、给任何车主调账、作废任何权益 ——
              上面那两个字段目前只是<b>记录</b>，不是权限。
            </li>
            <li>
              正因为后端不分权，这一页（以及左侧菜单）<b>不按角色隐藏任何按钮</b>：
              藏起来只会让人以为「权限已经分开了」，而实际上换个账号进来能力完全一样，
              真出了问题会更难查。需要分权必须先在后端做，前端单方面隐藏不构成权限。
            </li>
            <li>
              与「权限」无关但容易混的一件事：<b>登录凭证一旦签发就无法撤销</b>，
              有效期 {{ Math.round(ADMIN_TOKEN_TTL_MS / 3600000) }} 小时。
              所以「退出登录」只是清掉本机保存的凭证，不能当作安全措施 ——
              在共用电脑上用完，请让浏览器不要保存密码，并确认没有把凭证拷走。
            </li>
          </ul>
        </section>

        <!-- ── 退出 ─────────────────────────────────────────────────── -->
        <section class="logout">
          <div class="logout-text">
            <b>退出登录</b>
            <span>清掉本机保存的登录凭证并回到登录页。没有未保存的编辑内容，退出不会丢数据。</span>
          </div>
          <el-button type="danger" plain @click="handleLogout">
            <CoIcon name="logout" :size="14" />
            退出登录
          </el-button>
        </section>
      </div>
    </PagePanel>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.body {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.identity {
  display: flex;
  align-items: center;
  gap: 14px;
}

.avatar {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-size: 18px;
  font-weight: 600;
}

.identity-text {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.name-row b {
  font-size: 15px;
  font-weight: 600;
  color: var(--co-text-1);
}

.role-chip {
  padding: 1px 7px;
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-2);
  font-size: 11px;
  color: var(--co-text-3);
}

.sub {
  font-size: 12.5px;
  color: var(--co-text-3);
}

.facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 24px;
  margin: 0;
}

.fact {
  display: flex;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--co-border-light);
  font-size: 13px;
}

.fact dt {
  flex-shrink: 0;
  width: 78px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.fact dd {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  margin: 0;
  min-width: 0;
  line-height: 1.7;
  color: var(--co-text-1);
}

.fact-note {
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--co-text-3);
}

/* 「不记录」和「是空」必须看起来就不一样 */
.no-data {
  color: var(--co-text-4);
}

.notice {
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--co-warning) 28%, transparent);
  border-radius: var(--co-radius-lg);
  background: var(--co-warning-weak);
}

.notice h3 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 9px;
  font-size: 13px;
  font-weight: 600;
  color: var(--co-warning-strong);
}

.notice ul {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.85;
  color: var(--co-text-2);
}

.notice li + li {
  margin-top: 3px;
}

.notice b {
  color: var(--co-text-1);
  font-weight: 600;
}

.notice code {
  padding: 1px 5px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-card);
  color: var(--co-text-2);
  font-size: 12px;
}

.logout {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-lg);
  background: var(--co-bg-2);
}

.logout-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.logout-text b {
  font-size: 13px;
  font-weight: 600;
  color: var(--co-text-1);
}

.logout-text span {
  font-size: 12px;
  line-height: 1.6;
  color: var(--co-text-3);
}

.co-num {
  font-variant-numeric: tabular-nums;
}
</style>
