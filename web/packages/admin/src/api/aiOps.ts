/**
 * AI 运维助手接口（管理端）。
 *
 * 两条和普通 CRUD 不一样的性质：
 *
 * 1. **`chat` 是慢请求。** 后端是同步的，一次提问要等模型多轮往返（带工具调用时至少两轮），
 *    耗时按秒计，后端自己的读超时配的是 60 秒。而 http 客户端的默认超时是 20 秒 ——
 *    不覆盖的话前端会先超时，用户看到「请求超时」而服务端其实还在正常算，
 *    重试一次就变成两笔模型调用（更糟的是可能建出两张工单）。
 *    所以这里显式给一个比后端更长的超时。
 *
 * 2. **多轮会话靠 `sessionId` 回传。** 它存在后端内存里，重启即失效；
 *    前端不该把它存进 localStorage 当长期标识用。
 */

import { http } from './http'
import type { AiChatDTO, AiChatVO, KnowledgeChunkVO } from '@chargeops/shared'

const BASE = '/api/admin/ai-ops'

/**
 * 必须比后端的读超时（60 秒）更长，否则超时的那一侧会是前端：
 * 用户看到失败并重试，而服务端第一次调用还在跑。
 */
const CHAT_TIMEOUT_MS = 90_000

export function chat(dto: AiChatDTO): Promise<AiChatVO | null> {
  return http.post<AiChatVO>(`${BASE}/chat`, dto, { timeout: CHAT_TIMEOUT_MS })
}

/**
 * 只检索知识库，不调模型。
 *
 * 排查「助手答得不对」时先调它：召回为空说明是语料问题（没灌进去 / 该问题没有对应文档），
 * 召回正常但答得不对才是提示词或模型的问题。
 */
export function searchKnowledge(query: string): Promise<KnowledgeChunkVO[] | null> {
  return http.get<KnowledgeChunkVO[]>(`${BASE}/knowledge/search`, { query })
}

/**
 * 重建知识库索引。改完 `src/main/resources/knowledge/` 下的 markdown 后调它，不用重启。
 *
 * 幂等：实现是「按来源先删后写」，重复调用不会让片段翻倍。
 * 返回重建后的片段总数，为 0 说明文档没读到，应当提示而不是当成功。
 */
export function reloadKnowledge(): Promise<number | null> {
  return http.post<number>(`${BASE}/knowledge/reload`, undefined, { timeout: 120_000 })
}
