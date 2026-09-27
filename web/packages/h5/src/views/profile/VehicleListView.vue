<script setup lang="ts">
/**
 * 我的车辆（子页）。
 *
 * ★★ 一、判「默认车」必须用 `isDefault === 1`，不能写真值判断 ★★
 *   后端这个字段是**数字**（1/0），不是布尔。`if (v.isDefault) ` 在数字 `0`
 *   上恰好也对，但只要哪天后端把它序列化成字符串，`"0"` 是 **truthy** ——
 *   所有车都会挂上「默认」标签，而且不报错。`=== 1` 才与契约绑死。
 *
 *   ★ 另一条来自后端注释的约束：**默认车不一定要自己找**。后端的列表 SQL 是
 *     `ORDER BY is_default DESC, id DESC`，默认车就在第一条。这里用 `=== 1`
 *     来渲染标签（要显示「哪一辆是默认」），但**不**用「找不到默认车」去做分支 ——
 *     数据坏了（手工 SQL 造出两辆默认车）时，取第一条只是多显示一个标签的事。
 *
 * ★★ 二、删除是**硬删**，后端没有「有订单就不能删」这道守卫 ★★
 *   （`VehicleController.delete` 的注释写明：`charging_order.plate_no` 那张
 *   快照列存在的理由**正是**允许车辆被删除。）所以这里不能向用户承诺
 *   「有进行中的订单就删不掉」—— 那是假的。
 *   但删除仍然可能失败（网络、并发、将来后端补上的守卫），所以失败路径必须
 *   有出口：把后端文案原样显示，并给一个「去看看进行中的订单」的入口，
 *   而不是让用户对着一个红条干瞪眼。
 *
 * ★ 删除成功后**必须重拉列表**，不能只在本地把那一行删掉：删掉的是默认车时，
 *   后端会把剩下的第一辆**自动提升**为默认车（`VehicleServiceImpl.delete`），
 *   这个副作用只有重新拉列表才看得到。
 */

import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import { PLATE_COLOR, formatMoney, isActionLocked, withActionLock, type VehicleVO } from '@chargeops/shared'
import { deleteVehicle, listVehicles, setDefaultVehicle } from '@/api/vehicle'
import { useAsyncData } from '@/composables/useAsyncData'
import { useSubmitLock } from '@/composables/useSubmitLock'
import PageState from '@/components/PageState.vue'

/** 删除动作的冷却窗口：同 payload 的第二次提交在窗口内被直接拒绝。 */
const DELETE_LOCK_MS = 3_000

const router = useRouter()
const { pending, run } = useSubmitLock()

const { data, loading, failed, reload } = useAsyncData<VehicleVO[]>(listVehicles)

const vehicles = computed<VehicleVO[]>(() => data.value ?? [])

/** 品牌与型号合成一行；只有其中一个时不要显示成「比亚迪 / undefined」。 */
function modelText(vo: VehicleVO): string {
  return [vo.brand, vo.model].filter((x): x is string => !!x && x.trim() !== '').join(' ')
}

async function onSetDefault(vo: VehicleVO): Promise<void> {
  const outcome = await run(() =>
    withActionLock(`vehicle-default:${vo.id}`, () => setDefaultVehicle(vo.id), DELETE_LOCK_MS),
  )
  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') {
    // ★ 被动作锁拦下时 http 层是静默的，这里补一句，否则是「点了没反应」
    if (isActionLocked(outcome.error)) showToast('刚刚已操作过，请稍候几秒再试')
    return
  }

  showSuccessToast('已设为默认车')
  // ★ 重拉而不是本地改标记：后端是「先清旧的、再置新的」两步，
  //   本地改会漏掉「另一辆车上的默认标记被清掉」这一半，于是出现两辆默认车。
  await reload()
}

async function onDelete(vo: VehicleVO): Promise<void> {
  try {
    await showConfirmDialog({
      title: '删除车辆',
      message: `确定删除 ${vo.plateNo}？删除后无法恢复，历史订单里仍会保留当时的车牌。`,
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonColor: 'var(--co-danger)',
    })
  } catch {
    // 取消时 `showConfirmDialog` 是 reject —— 那是正常操作，不该被当成错误
    return
  }

  const outcome = await run(() => withActionLock(`vehicle-delete:${vo.id}`, () => deleteVehicle(vo.id), DELETE_LOCK_MS))
  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    if (isActionLocked(outcome.error)) {
      showToast('刚刚已提交过，请稍候几秒再试')
      return
    }
    // ★ 后端文案原样显示（`ApiError.message` 就是后端 msg），
    //   再补一条去路。这里**不翻译、不改写**后端的话 —— 前端重写一遍
    //   只会制造两套说法，而且改错的时候没人会发现。
    const message =
      outcome.error instanceof Error && outcome.error.message
        ? outcome.error.message
        : '删除失败，请稍后重试。'
    try {
      await showConfirmDialog({
        title: '未能删除',
        message,
        confirmButtonText: '看看进行中的订单',
        cancelButtonText: '知道了',
      })
      void router.push({ name: 'orders' })
    } catch {
      // 用户选了「知道了」——什么都不做
    }
    return
  }

  showSuccessToast('已删除')
  // ★ 必须重拉：删掉默认车时后端会把剩下的第一辆自动提升为默认车
  await reload()
}

function gotoNew(): void {
  void router.push({ name: 'vehicle-new' })
}

function gotoEdit(vo: VehicleVO): void {
  void router.push({ name: 'vehicle-edit', params: { id: String(vo.id) } })
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="我的车辆" left-arrow @click-left="router.back()" />

    <PageState v-if="loading && vehicles.length === 0" kind="loading" />

    <PageState
      v-else-if="failed && vehicles.length === 0"
      kind="error"
      description="车辆列表没加载出来。你的车辆不会因为这次失败而丢失。"
      @retry="reload()"
    />

    <PageState
      v-else-if="vehicles.length === 0"
      kind="empty"
      title="还没有添加车辆"
      description="添加车辆后，充电时可以选择车牌，订单里也能看到是哪辆车充的。"
    >
      <van-button size="small" round type="primary" @click="gotoNew">添加车辆</van-button>
    </PageState>

    <template v-else>
      <article v-for="vo in vehicles" :key="vo.id" class="card">
        <div class="card-head">
          <span class="plate co-num">{{ vo.plateNo }}</span>
          <!-- ★ `=== 1` 而不是真值判断 —— 见文件头第一节 -->
          <span v-if="vo.isDefault === 1" class="default-tag">默认</span>
          <span class="color-tag">{{ PLATE_COLOR.label(vo.plateColor) }}</span>
        </div>

        <p v-if="modelText(vo)" class="model">{{ modelText(vo) }}</p>
        <p v-if="vo.batteryCapacityKwh" class="battery co-num">
          电池 {{ formatMoney(vo.batteryCapacityKwh) }} kWh · 仅用于估算
        </p>

        <div class="actions">
          <van-button
            v-if="vo.isDefault !== 1"
            size="small"
            plain
            type="primary"
            :disabled="pending"
            @click="onSetDefault(vo)"
          >
            设为默认
          </van-button>
          <van-button size="small" plain type="primary" @click="gotoEdit(vo)">编辑</van-button>
          <!-- ★ 危险操作：描边红（`plain`），不是实心大红 ——
               实心红会把「删除」变成这一屏最显眼的按钮。 -->
          <van-button size="small" plain type="danger" :disabled="pending" @click="onDelete(vo)">
            删除
          </van-button>
        </div>
      </article>

      <p class="foot-note">
        删除车辆不影响历史订单：订单里存的是充电当时的车牌快照，不是这辆车的引用。
      </p>
    </template>

    <!-- ★ 悬浮「+」：固定定位要避开底部安全区（iPhone 的小黑条会盖住它） -->
    <button type="button" class="fab" aria-label="添加车辆" @click="gotoNew">＋</button>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: calc(84px + env(safe-area-inset-bottom));
  background: var(--co-bg-page);
}

/* ── 车辆卡 ───────────────────────────────────────────────────────── */
.card {
  margin: var(--co-gap-md) var(--co-gap-md) 0;
  padding: var(--co-gap-lg);
  background: var(--co-bg-card);
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-lg);
  box-shadow: var(--co-shadow-sm);
}

.card-head {
  display: flex;
  align-items: center;
  gap: var(--co-gap-sm);
}

.plate {
  font-size: var(--co-font-lg);
  font-weight: 600;
  letter-spacing: 0.5px;
  color: var(--co-text-1);
}

.default-tag,
.color-tag {
  padding: 1px 7px;
  border-radius: var(--co-radius-sm);
  font-size: var(--co-font-xs);
  line-height: 1.7;
}

.default-tag {
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
}

.color-tag {
  background: var(--co-info-weak);
  color: var(--co-info-strong);
}

.model {
  margin: 6px 0 0;
  font-size: var(--co-font-sm);
  color: var(--co-text-2);
}

.battery {
  margin: 4px 0 0;
  font-size: var(--co-font-xs);
  color: var(--co-text-3);
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--co-gap-sm);
  margin-top: var(--co-gap-md);
  padding-top: var(--co-gap-md);
  border-top: 1px solid var(--co-border-light);
}

.foot-note {
  margin: var(--co-gap-lg) var(--co-gap-lg) 0;
  font-size: var(--co-font-xs);
  line-height: 1.7;
  color: var(--co-text-3);
}

/* ── 悬浮按钮 ─────────────────────────────────────────────────────── */
.fab {
  position: fixed;
  right: var(--co-gap-lg);
  bottom: calc(var(--co-gap-lg) + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border: 0;
  border-radius: 50%;
  background: var(--co-primary);
  color: var(--co-bg-card);
  font-size: 26px;
  line-height: 1;
  box-shadow: var(--co-shadow-md);
  cursor: pointer;
  /* ★ 触摸反馈在 `:active` 上，不用 hover —— 手机上 hover 会「粘住」 */
  transition: transform var(--co-duration-fast) var(--co-ease);
}

.fab:active {
  transform: scale(0.92);
  background: var(--co-primary-active);
}
</style>
