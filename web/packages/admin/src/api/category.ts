/**
 * 套餐分类接口。
 *
 * ★ 这里**没有** `listCategories()`：分类下拉一律走 `api/options.ts` 的
 *   `categoryOptionsOf(type)`，那里已经把「`GET /list` 返回裸数组而不是
 *   分页信封」这件事故化掉了，并且带上了 `category` 标签的缓存失效。
 *   本文件只负责 CRUD 与分页 —— 多一个 `list` 就会多一条会写错的路。
 */

import type {
  CategoryDTO,
  CategoryPageQuery,
  CategoryUpdateDTO,
  CategoryVO,
  Paged,
} from '@chargeops/shared'
import { http } from './http'

export function pageCategories(query: CategoryPageQuery): Promise<Paged<CategoryVO>> {
  return http.page<CategoryVO>('/api/admin/category/page', { ...query })
}

export function getCategory(id: number): Promise<CategoryVO | null> {
  return http.get<CategoryVO>(`/api/admin/category/${id}`)
}

export function createCategory(payload: CategoryDTO): Promise<number | null> {
  return http.post<number>('/api/admin/category', payload)
}

/**
 * 修改分类。
 *
 * ★ 传进来的 `payload` **不可能**带 `type` —— `CategoryUpdateDTO` 里根本没有
 *   这个字段，这是编译期事实，不是「记得排除」。所以调用方就算把整个表单
 *   原样丢过来，后端也只会忽略类型。
 */
export function updateCategory(id: number, payload: CategoryUpdateDTO): Promise<void | null> {
  return http.put<void>(`/api/admin/category/${id}`, payload)
}

/**
 * 删除分类（守卫式硬删）。
 *
 * ★ 前端**无法预判**这个分类下有没有套餐 —— 后端没有「该分类下的套餐数」
 *   这样的接口，列表页也没有这个字段（`CategoryVO` 上刻意没有它，
 *   理由见后端类注释）。所以这里必然要允许失败，由调用方把
 *   `该分类下还有 N 个充电套餐` 这句翻成一条**指向套餐列表的出路**。
 */
export function deleteCategory(id: number, options?: { silent?: boolean }): Promise<void | null> {
  return http.del<void>(`/api/admin/category/${id}`, { silent: options?.silent })
}

/**
 * 后端「分类下还有套餐」的**逐字前缀**。
 *
 * ★ 出处：`MessageConstant.CATEGORY_HAS_PACKAGE = "该分类下还有 %d 个充电套餐，请先删除或调整分类"`。
 *   带 `%d` 所以只能前缀匹配 —— 也正因为它带数量，用户看到的那句话
 *   比任何前端编造的解释都清楚，直接透传即可。
 */
export const CATEGORY_HAS_PACKAGE = '该分类下还有'
