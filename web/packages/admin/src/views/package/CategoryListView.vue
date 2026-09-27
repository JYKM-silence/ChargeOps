<script setup lang="ts">
/**
 * 套餐分类（列表 + 两个类型档）。
 *
 * ★★ 这个页面有两处**必须做对**的地方，都不是样式问题 ★★
 *
 *   1. **分类按 `type` 彻底隔离**。`uk_category_type_name` 是 `(type, name)`，
 *      所以「充电套餐分类 / 增值服务分类」两个域里可以同时存在同名分类。
 *      由此推出两条界面纪律：
 *        · 列表用 `el-tabs` 分成两档，任何时候用户都看得见自己站在哪个域里；
 *        · **新建时的「类型」必须由用户明确选**，不许从当前 Tab 静默推断 ——
 *          推断错了会把分类建到另一个语义域，而界面上看不出任何异常
 *          （分类确实建成功了，只是在另一个 Tab 里），等到给套餐选分类时才发现。
 *      这与后端 `CategoryDTO` 把 `type` 定成唯一没有默认值的字段是同一个理由。
 *
 *   2. **两个接口是两条路，不能只走一条**：
 *        · `pageCategories`（`/page`，分页信封）—— 本页的列表；
 *        · `categoryOptionsOf`（`/list`，**裸数组，没有分页信封**）—— 下拉框与
 *          「该类型下已有」预览。后端 `/list` 把 `status = 1` 硬编码在 SQL 里，
 *          所以它**只返回启用中的分类**，要看得停用的分类只能走 `/page`。
 *
 * ★ 重名的后端文案**原样显示，不加工**（见下面 `DUPLICATE_SUFFIX` 的注释）：
 *   那是一句暴露了数据库索引列的原始报错，但它是排查时唯一可信的原文。
 *
 * ★ 删除是守卫式硬删：分类下还有套餐就会被拒绝，而前端**预先查不到**这个数量
 *   （`CategoryVO` 上刻意没有「套餐数」，后端也没有这个接口），所以只能让后端拒绝，
 *   再把那句中文加工成一条**指向套餐列表的出路**。
 */

import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  CATEGORY_TYPE,
  MAX_LEN,
  diffUpdate,
  formatServerTime,
  invalidateOptionsByTag,
  msgStartsWith,
  type CategoryDTO,
  type CategoryPageQuery,
  type CategoryUpdateDTO,
  type CategoryVO,
} from '@chargeops/shared'
import CoIcon from '@/components/CoIcon.vue'
import DangerConfirm from '@/components/DangerConfirm.vue'
import EmptyState from '@/components/EmptyState.vue'
import FilterBar from '@/components/FilterBar.vue'
import PagePanel from '@/components/PagePanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import { TAG, categoryOptionsOf } from '@/api/options'
import {
  CATEGORY_HAS_PACKAGE,
  createCategory,
  deleteCategory,
  pageCategories,
  updateCategory,
} from '@/api/category'
import { useOptions } from '@/composables/useOptions'
import { usePagedList } from '@/composables/usePagedList'
import { useSubmitLock } from '@/composables/useSubmitLock'

const router = useRouter()

// ── 类型档（Tab）──────────────────────────────────────────────────────────

/**
 * 当前所在的类型域。
 *
 * ★★ 它**刻意不是** `filters` 的一个字段 ★★
 *   放进 filters 会带来两个问题：
 *     · `dirty`（「重置」按钮的亮灭）会把「切了 Tab」也算成「改了筛选条件」，
 *       于是用户切一下档案，重置按钮就亮着 —— 而重置并不该把人送回另一个档；
 *     · `reset()` 会把 Tab 悄悄改回初始值，界面上的 Tab 和列表数据同时对不上，
 *       而两者都「看起来是好的」。
 *   所以类型档是**页面的作用域**，筛选条件是**作用域内的条件**，两者分开。
 */
const activeType = ref<number>(CATEGORY_TYPE.list[0].code)

/**
 * ★ 用 `watch` 而不是 `el-tabs` 的 `tab-change` 事件：事件参数是
 *   `TabPaneName`（`string | number`），接过来还得再收窄一次；
 *   而 `activeType` 本来就是 `number`，这里直接就是收窄好的类型。
 *   （同一个取舍见 `MemberDetailView` 的 Tab 注释。）
 */
watch(activeType, () => {
  // 换档 = 换一个数据集，必须回第 1 页；`search()` 已经做了这件事
  void search()
})

// ── 列表 ───────────────────────────────────────────────────────────────────

const {
  rows,
  total,
  pageNum,
  pageSize,
  loading,
  loaded,
  error,
  filters,
  search,
  reset,
  reload,
  goPage,
  dirty,
  afterRemove,
} = usePagedList<CategoryVO, CategoryPageQuery>({
  // ★ `type` 在这里**从 `activeType` 现读**，而不是从 `filters` 里取：
  //   它就是「当前档」这个作用域本身。
  fetch: (q) => pageCategories({ ...q, type: activeType.value }),
  initial: { name: '', status: undefined },
})

/**
 * 是否有实质筛选条件。
 *
 * ★ 输入框清空给的是 `''`、下拉清空给的是 `undefined`（Element Plus 的
 *   `valueOnClear` 默认就是 `undefined`），两种都表示「不过滤」。
 *   所以这里统一按「有内容才算填了」判断，不写成 `!== undefined` ——
 *   那样输入框清空之后会被当成「还在筛」。
 */
const hasFilter = computed(
  () => [filters.name, filters.status].some((v) => v !== undefined && v !== null && String(v).trim() !== ''),
)

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

// ── 状态显示 ───────────────────────────────────────────────────────────────

/**
 * 分类状态的**本地**标签表。
 *
 * ★ `dicts.ts` 里没有分类状态的字典（那里有站点、车主、枪、桩…就是没有分类），
 *   而后端的 `CategoryServiceImpl.labelOfStatus` 是 1「启用」/ 0「停用」。
 *   两条纪律：文案与后端**逐字一致**（不是「已启用」「已下架」）；
 *   取值只有 0/1，遇到不认识的码按「停用」的色调兜底，**不编造第三种状态**。
 */
const CATEGORY_STATUS_OPTIONS = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
] as const

/** ★ 优先用后端的 `statusLabel`（有真源就用真源），为 null 时才退回本地表。 */
function statusText(row: CategoryVO): string {
  return row.statusLabel ?? (row.status === 1 ? '启用' : '停用')
}

/** 停用不是错误，是「退役」—— 所以是 info 而不是 danger，避免整屏红色噪声。 */
function statusTone(status: number): 'success' | 'info' {
  return status === 1 ? 'success' : 'info'
}

// ── 新建 / 编辑弹窗 ────────────────────────────────────────────────────────

/**
 * ★ 用 `type` 而不是 `interface`：`diffUpdate` 的约束是
 *   `T extends Record<string, unknown>`，interface 没有隐式索引签名，会编译不过。
 */
type CategoryForm = {
  name: string
  type: number | undefined
  sort: number
  status: number
}

function emptyForm(): CategoryForm {
  return {
    name: '',
    // ★★ 刻意留空，**不预设当前 Tab 的类型** ★★
    //   见文件头第 1 条：猜错会把分类建到另一个语义域，而界面上看不出异常。
    //   多花用户一次点击，换掉一类「静默建错」的问题，值。
    type: undefined,
    sort: 0,
    status: 1,
  }
}

const dialogOpen = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<CategoryForm>(emptyForm())
/** 增量提交的基准。必须是**服务端原值**，不是上一次提交的值。 */
const originalForm = ref<CategoryForm>(emptyForm())
const editingId = ref<number | null>(null)
/** 后端最近一次拒绝的原文。非空时在弹窗里显示（弹窗**不关**）。 */
const submitError = ref<string | null>(null)

const isEditing = computed(() => editingId.value !== null)

const { pending: submitting, run: runSubmit } = useSubmitLock({ throttleMs: 400 })

const FIELD_LABEL: Record<string, string> = {
  name: '分类名称',
  sort: '排序',
  status: '状态',
}

/**
 * 「该类型下已有」的预览列表 —— **这就是 `/list` 那个接口在本页的用途**。
 *
 * ★ 它是**只读的**：`/list` 把 `status = 1` 写死在 SQL 里，所以停用的分类不在这里。
 *   预览只是提醒用户「这个域名下已经有什么」，让 `uk(type, name)` 的冲突在
 *   点提交之前就能被看见 —— 而真正的判定永远在后端（这个列表有缓存、也会过期）。
 *
 * ★ 类型还没选时不发请求（`useOptions` 收到 `null` 会清空列表）：
 *   拉全量分类再在前端挑，只会多一次没用的请求。
 */
const { options: sameTypeCategories } = useOptions(() =>
  form.type === undefined ? undefined : categoryOptionsOf(form.type),
)

const sameTypeNames = computed(() => sameTypeCategories.value.map((o) => o.label))

/** Tab 与弹窗里都用字典**生成**类型选项，避免两处各写一遍中文字符串。 */
const typeOptions = CATEGORY_TYPE.options()

/** 规则逐条对应 `CategoryDTO` / `CategoryUpdateDTO` 上的注解（松紧都要一致）。 */
const rules: FormRules = {
  type: [{ required: true, message: '请选择分类类型', trigger: 'change' }],
  name: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { max: MAX_LEN.categoryName, message: `名称不能超过 ${MAX_LEN.categoryName} 个字符`, trigger: 'blur' },
  ],
}

function fillForm(row: CategoryVO): void {
  form.name = row.name
  form.type = row.type
  form.sort = row.sort
  form.status = row.status
}

function openCreate(): void {
  editingId.value = null
  submitError.value = null
  Object.assign(form, emptyForm())
  originalForm.value = { ...form }
  dialogOpen.value = true
  // el-dialog 的内容是懒渲染的，首次打开时 formRef 还没挂上
  void nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row: CategoryVO): void {
  editingId.value = row.id
  submitError.value = null
  fillForm(row)
  originalForm.value = { ...form }
  dialogOpen.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

function toCreateDTO(): CategoryDTO {
  return {
    // ★ 校验已保证非空，这里断言只为消掉 `number | undefined`
    name: form.name.trim(),
    type: form.type as number,
    sort: form.sort,
    status: form.status,
  }
}

/**
 * 剔除增量里「被清空」的键。
 *
 * ★ `UpdateDTO` 的语义是「不传 = 不修改」，**不是**「传空 = 清空」：
 *   分类名称**填了就抹不掉**（后端 `CategoryMapper.update` 里 `name != ''` 才会写，
 *   空串会被静默跳过 —— 用户以为删掉了，其实一点没变）。所以直接不发，并回报字段名。
 */
function dropCleared(payload: Partial<CategoryForm>): string[] {
  const bag = payload as Record<string, unknown>
  const dropped: string[] = []
  for (const key of Object.keys(bag)) {
    const value = bag[key]
    if (value === null || value === undefined || (typeof value === 'string' && value.trim() === '')) {
      delete bag[key]
      dropped.push(key)
    }
  }
  return dropped
}

type SubmitResult = 'saved' | 'nochange'

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const outcome = await runSubmit<SubmitResult>(async () => {
    if (editingId.value === null) {
      await createCategory(toCreateDTO())
      return 'saved'
    }

    // ★ 在这里读一次 id：上面的早退已经把 `editingId.value` 收窄成 `number`，
    //   而 `await` 之后 TS 不再对属性做窄化，所以不要在提交那一刻再读它
    //   （那会逼出一个 `as number`，而那是一个**真的会写错 id** 的断言）。
    const id = editingId.value

    const { payload, unsettable } = diffUpdate(originalForm.value, form)
    // ★ 编辑时**不可能**改类型：`CategoryUpdateDTO` 里没有 `type`（编译期事实）。
    //   但表单里有这个键，所以还是显式摘掉，免得它被当成一次「改动」发出去。
    //   不要写成 `delete (payload as CategoryUpdateDTO).type` —— 那个 `as` 会把 type
    //   断言掉，于是 TS 认为这个键不存在，编译直接报错（见 ChargerListView 的同类注释）。
    delete payload.type

    const blocked = [...unsettable, ...dropCleared(payload)]
    if (blocked.length > 0) {
      ElMessage({
        type: 'warning',
        message: `这些字段填过之后无法清空，已保持原值：${blocked
          .map((k) => FIELD_LABEL[k] ?? k)
          .join('、')}`,
        duration: 5000,
        showClose: true,
      })
    }

    if (Object.keys(payload).length === 0) return 'nochange'

    await updateCategory(id, payload as CategoryUpdateDTO)
    return 'saved'
  })

  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    // ★ 后端原文（重名、类型非法…）**原样**留在弹窗里，弹窗**不关** ——
    //   全局 onError 的 toast 4 秒就没了，而用户还需要照着这句去改名字。
    submitError.value = errorText(outcome.error)
    return
  }

  dialogOpen.value = false

  if (outcome.value === 'nochange') {
    ElMessage({ type: 'info', message: '没有改动，未提交', duration: 1600 })
    return
  }

  ElMessage({ type: 'success', message: isEditing.value ? '已保存' : '已创建', duration: 1800 })
  // ★ 分类改名/停用/删除都会影响所有「按类型取分类」的下拉（套餐表单里那个），
  //   连 'package' 一起失效，因为套餐列表的「分类」列也是拿分类选项反查的。
  invalidateOptionsByTag(TAG.category, TAG.package)
  await reload()
}

/**
 * 「重名」判定用的后缀。
 *
 * ★ 出处：`GlobalExceptionHandler` 捕获 `DuplicateKeyException` 后，用正则
 *   `Duplicate entry '(.*?)' for key` 取出 group(1)，拼上
 *   `MessageConstant.ALREADY_EXISTS`（= "已存在"）。所以文案**一定**以它结尾，
 *   `endsWith` 是精确的（shared 明令禁止 `includes`：模糊匹配会误命中）。
 */
const DUPLICATE_SUFFIX = '已存在'

/**
 * ★★ 这段文案里那句原文**不做任何加工**，这是刻意的 ★★
 *
 *   后端把 MySQL 的 `Duplicate entry '2-smoke-valadd' for key 'uk_category_type_name'`
 *   加工成了 `2-smoke-valadd已存在` —— 前缀 `2` 是 `type` 编码（不是分类名），
 *   中间用 `-` 连接索引列。这个形状对用户毫无意义，是**后端缺陷**
 *   （把索引键拼成了给人看的文案）。
 *
 *   但前端必须**原样显示**：改成「名称已存在」虽然好读，却会让屏幕上的文字
 *   和库里真正冲突的那一行对不上 —— 排查时唯一可信的就是这句原文，
 *   而运营往往是靠它去反查「到底撞上的是哪一条」。
 *   → 所以做法是**另起一句解释它**，而不是替换它。
 */
const isDuplicate = computed(() => submitError.value?.endsWith(DUPLICATE_SUFFIX) ?? false)

function onDialogClosed(): void {
  formRef.value?.clearValidate()
}

// ── 删除 ───────────────────────────────────────────────────────────────────

const deleteOpen = ref(false)
const deleteTarget = ref<CategoryVO | null>(null)
/** 后端拒绝的原文。非空时在确认弹窗里显示，并附上唯一的出路。 */
const deleteError = ref<string | null>(null)
const { pending: removing, run: runRemove } = useSubmitLock({ throttleMs: 500 })

/** 「分类下还有套餐」这句话的原文前缀（常量在 `api/category.ts`，与后端逐字对齐）。 */
const blockedByPackages = computed(() =>
  deleteError.value !== null && msgStartsWith(deleteError.value, CATEGORY_HAS_PACKAGE),
)

function askRemove(row: CategoryVO): void {
  deleteTarget.value = row
  deleteError.value = null
  deleteOpen.value = true
}

async function doRemove(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return

  // ★ 参数是**对象** `{ silent: true }`，不是布尔 —— 与 `deleteConnector(id, true)`
  //   不同族。写错了的类型检查会拦下来（这正是保留这套签名差异的意义：
  //   5 个 api 文件里 4 种删除签名，各自对应后端是否给出「可加工的拒绝原因」）。
  const outcome = await runRemove(() => deleteCategory(target.id, { silent: true }))
  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    // 原文照显：后端那句「该分类下还有 3 个充电套餐，请先删除或调整分类」
    // 比任何转述都清楚（数量是它算的，前端算不出来）。
    deleteError.value = errorText(outcome.error)
    return // 弹窗**不关**：原因和出路都在这里
  }

  deleteOpen.value = false
  invalidateOptionsByTag(TAG.category, TAG.package)
  ElMessage({ type: 'success', message: `已删除分类「${target.name}」`, duration: 2000 })
  await afterRemove()
}

/**
 * 去套餐列表看这个分类下的套餐。
 *
 * ★ 带上 `categoryId` 是有意义的：套餐列表**会**读这个 query 并预填「分类」筛选，
 *   所以这一步真的把用户送到了有内容的地方，而不是一个空列表首页。
 */
function gotoPackages(): void {
  const id = deleteTarget.value?.id
  deleteOpen.value = false
  if (id === undefined) return
  void router.push({ path: '/package', query: { categoryId: String(id) } })
}
</script>

<template>
  <div class="page">
    <PagePanel
      title="套餐分类"
      subtitle="分类属于两个彼此隔离的域：充电套餐只能挂在「充电套餐分类」下，增值服务只能挂在「增值服务分类」下。两个域里可以有同名分类。"
      flush
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <CoIcon name="plus" :size="15" />
          新建分类
        </el-button>
      </template>

      <!-- ★ 类型档：两档由字典生成，不在模板里手写中文字符串（措辞只有一个真源） -->
      <el-tabs v-model="activeType" class="scope-tabs">
        <el-tab-pane v-for="t in CATEGORY_TYPE.list" :key="t.code" :label="t.label" :name="t.code" />
      </el-tabs>

      <FilterBar :dirty="dirty" :loading="loading" @search="search" @reset="reset">
        <el-input
          v-model="filters.name"
          placeholder="分类名称（模糊）"
          clearable
          :maxlength="MAX_LEN.categoryName"
          @keyup.enter="search"
        />
        <el-select v-model="filters.status" placeholder="状态" clearable>
          <el-option
            v-for="o in CATEGORY_STATUS_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
      </FilterBar>

      <el-table v-loading="loading" :data="rows" row-key="id" size="default">
        <el-table-column prop="name" label="分类名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="150">
          <template #default="{ row }">
            <StatusTag
              :label="row.typeLabel ?? CATEGORY_TYPE.label(row.type)"
              :tone="CATEGORY_TYPE.tone(row.type)"
              size="sm"
            />
          </template>
        </el-table-column>
        <el-table-column label="排序" width="90" align="right" class-name="co-num">
          <template #default="{ row }">{{ row.sort }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusTag :label="statusText(row)" :tone="statusTone(row.status)" size="sm" />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">
            <span class="co-num">{{ formatServerTime(row.createTime) ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="askRemove(row)">删除</el-button>
          </template>
        </el-table-column>

        <!-- 空态三态分开：真的没有 / 被筛掉了 / 加载失败 -->
        <template #empty>
          <EmptyState v-if="loaded && error" kind="error" :description="errorText(error)" @retry="reload" />
          <EmptyState v-else-if="loaded && hasFilter" kind="filtered" @retry="reset" />
          <EmptyState
            v-else-if="loaded"
            kind="empty"
            :title="`「${CATEGORY_TYPE.label(activeType)}」下还没有分类`"
            description="分类只是给套餐分组，先建一个分类，再去「充电套餐」里挂套餐。另一个类型域的分类不会出现在这里 —— 两个域是彼此隔离的。"
          />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          :current-page="pageNum"
          :page-size="pageSize"
          :total="total"
          :pager-count="7"
          layout="total, prev, pager, next"
          background
          @current-change="goPage"
        />
      </div>
    </PagePanel>

    <!-- ── 新建 / 编辑 ─────────────────────────────────────────────────── -->
    <el-dialog
      v-model="dialogOpen"
      :title="isEditing ? `编辑分类「${originalForm.name}」` : '新建分类'"
      width="600px"
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      @closed="onDialogClosed"
    >
      <!-- ★ 后端拒绝时留在弹窗里。原文**逐字**显示，不做任何美化 ——
           见 script 里 `isDuplicate` 上面那段注释（那是后端缺陷，
           但改写了会让屏幕上的值与库里实际冲突的那一行对不上）。 -->
      <el-alert v-if="submitError" class="submit-alert" type="warning" :closable="false" show-icon>
        <template #title>{{ submitError }}</template>
        <p v-if="isDuplicate" class="alert-text">
          上面那句是数据库唯一索引打出来的原始报错，不是分类名。唯一键是「类型 + 名称」，
          所以句子开头那个数字是<b>类型编码</b>：
          {{ CATEGORY_TYPE.list.map((t) => `${t.code} 是${t.label}`).join('、') }}，
          后面接着的才是名称。它的意思是：<b>这个类型下</b>已经有同名分类 ——
          换一个名字，或者换个类型再试试。
        </p>
        <p v-else class="alert-text">
          这是后端返回的原文。改完上面的内容再提交一次即可，弹窗不会自动关。
        </p>
      </el-alert>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="分类类型" prop="type">
          <!-- ★★ 编辑时只读、新建时**必须由用户明确选** ★★
               `CategoryUpdateDTO` 里没有 `type`（分类的语义域建立后不可改），
               而新建时**不许**默认选中当前 Tab 的类型：见文件头第 1 条。
               后端把 `type` 定成 `CategoryDTO` 上唯一没有默认值的字段，理由是同一个。 -->
          <el-input v-if="isEditing" :model-value="CATEGORY_TYPE.label(form.type)" disabled />
          <el-radio-group v-else v-model="form.type" :disabled="submitting">
            <el-radio-button v-for="o in typeOptions" :key="o.value" :value="o.value">
              {{ o.label }}
            </el-radio-button>
          </el-radio-group>
          <p v-if="isEditing" class="field-note">
            分类类型建立后不可修改：它决定这个分类能挂哪种套餐，改了会让已经挂上来的套餐落进错的域。
            要换域只能删掉重建（且要先把它下面的套餐挪走）。
          </p>
          <!-- ★ 这里是 `/list` 的用途：让用户**在提交之前**就能看见这个域里已有什么。
               它只含启用中的分类（后端把 status = 1 写死在 SQL 里），
               所以它只是提醒，判定永远在后端。 -->
          <p v-else-if="form.type !== undefined" class="field-note">
            该类型下已有 {{ sameTypeNames.length }} 个分类<span v-if="sameTypeNames.length">
              ：{{ sameTypeNames.join('、') }}</span>
            。同一类型内名称不能重复（不同类型之间可以同名）。
          </p>
          <p v-else class="field-note">
            请先选类型 —— 两个域彼此隔离，选错了会建到另一个域里，而列表上（按域分档）看不出异常。
          </p>
        </el-form-item>

        <div class="grid-2">
          <el-form-item label="分类名称" prop="name">
            <el-input
              v-model="form.name"
              placeholder="如 快充套餐"
              :maxlength="MAX_LEN.categoryName"
              :disabled="submitting"
            />
          </el-form-item>
          <el-form-item label="排序" prop="sort">
            <el-input-number
              v-model="form.sort"
              :min="0"
              :controls="false"
              :disabled="submitting"
              style="width: 100%"
            />
          </el-form-item>
        </div>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status" :disabled="submitting">
            <el-radio v-for="o in CATEGORY_STATUS_OPTIONS" :key="o.value" :value="o.value">
              {{ o.label }}
            </el-radio>
          </el-radio-group>
          <p class="field-note">
            停用的分类<strong>不会出现在任何下拉框里</strong>（后端 `/list` 只返回启用中的），
            但已经挂在它下面的套餐不受影响 —— 停用是「不再出现在选择列表里」，不是「存量失效」。
          </p>
        </el-form-item>

        <ul class="form-notes">
          <li>
            排序只影响<b>下拉框里的顺序</b>（下拉走按排序值升序的接口）；本页列表按创建时间倒序，
            所以改排序后本页不会动，这是正常的。
          </li>
          <li>分类名称<strong>填过之后无法清空</strong>：后端「不传」表示「不修改」，要改就直接填新名字。</li>
          <li>删除分类前要先把它下面的套餐清空或挪走，否则后端会拒绝（并告诉你还剩几个）。</li>
        </ul>
      </el-form>

      <template #footer>
        <el-button :disabled="submitting" @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">
          {{ isEditing ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ── 删除 ───────────────────────────────────────────────────────── -->
    <DangerConfirm
      v-model="deleteOpen"
      :title="`删除分类「${deleteTarget?.name ?? ''}」`"
      description="删除后不可恢复。分类名会被释放，之后可以用同一个名字重建。"
      :consequences="[
        '分类下还挂着套餐时会被后端拒绝，它会告诉你还剩几个 —— 请先把那些套餐删掉或改到别的分类。',
        '只想让它不再出现在下拉框里，用「编辑 → 状态 → 停用」即可，不必删除。',
        '硬删存在的理由：唯一键「类型 + 名称」不看状态，一个错别字分类名停用之后仍然永久占着那个名字。',
      ]"
      action-name="删除"
      :require-typed-value="deleteTarget?.name ?? null"
      typed-label="请输入分类名称以确认删除"
      :loading="removing"
      @confirm="doRemove"
    >
      <!-- ★ 后端拒绝时**留在弹窗里**，并且这次有一条真正的出路：
           套餐列表会读 `categoryId` 这个 query 并预填筛选，
           所以点过去看到的就是「挡着删除的那几个套餐」。 -->
      <el-alert v-if="deleteError" class="submit-alert" type="warning" :closable="false" show-icon>
        <template #title>{{ deleteError }}</template>
        <p v-if="blockedByPackages" class="alert-text">
          上面是后端的原文（数字是它现场数出来的，前端拿不到这个数量）。
          点下面的按钮会带着这个分类去「充电套餐」页，那里已经按分类筛好了 ——
          把那些套餐删掉或改到别的分类，再回来删这个分类。
        </p>
        <p v-else class="alert-text">这是后端返回的原文。可以在下面重试，弹窗不会自动关。</p>
        <el-button v-if="blockedByPackages" size="small" :disabled="removing" @click="gotoPackages">
          去看看这个分类下的套餐
        </el-button>
      </el-alert>
    </DangerConfirm>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ★ Tab 与下面的筛选条之间留出呼吸：两层都是「作用域」，视觉上要能分辨。
   左右内边距与 FilterBar 对齐（18px），否则两行的左边缘会差 2px。 */
.scope-tabs {
  padding: 4px 18px 0;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 8px;
}

.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

@media (max-width: 640px) {
  .grid-2 {
    grid-template-columns: minmax(0, 1fr);
  }
}

.field-note {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.field-note strong {
  color: var(--co-text-2);
  font-weight: 600;
}

.form-notes {
  margin: 4px 0 0;
  padding: 12px 14px 12px 30px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-2);
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--co-text-3);
}

.form-notes li + li {
  margin-top: 4px;
}

.form-notes strong {
  color: var(--co-text-2);
  font-weight: 600;
}

.submit-alert {
  margin-bottom: 16px;
}

.alert-text {
  margin: 6px 0 10px;
  font-size: 12.5px;
  line-height: 1.75;
}

.alert-text b {
  font-weight: 600;
}
</style>
