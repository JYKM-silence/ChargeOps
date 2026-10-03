package com.chargeops.service;

import com.chargeops.dto.AiChatDTO;
import com.chargeops.vo.AiChatVO;
import com.chargeops.vo.KnowledgeChunkVO;

import java.util.List;

/**
 * AI 运维助手（Agent 编排那一层）。
 *
 * <p> 一次问答做三件事：拿问题去向量库检索知识片段、把片段和问题一起交给模型、
 * 模型在这个过程里自己决定调不调工具、调哪几个（查桩状态、查告警、查历史工单、
 * 必要时建单）。这几步的先后不是代码写死的，是模型看着提示词和工具描述决定的 ——
 * 所以这里看不到状态机，只有一次带工具的调用。
 */
public interface AiOpsService {

    /** 提问。多轮靠 {@code sessionId} 串起来，不传就是新会话 */
    AiChatVO chat(AiChatDTO dto);

    /**
     * 只做检索，不调模型。
     *
     * <p> 排查「AI 答得不对」时用它先分清是哪一步坏了：这里返回空 = 知识库没这篇内容，
     * 返回的内容不相关 = 切分或向量模型的问题，都正常而模型还是胡说 = 提示词或模型能力的问题。
     */
    List<KnowledgeChunkVO> searchKnowledge(String query);
}
