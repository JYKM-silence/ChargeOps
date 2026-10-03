package com.chargeops.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * AI 运维助手的回答
 *
 * <p> 除了 {@code answer} 还回 {@code references}：答案是模型生成的，可能对也可能编，
 * 而召回的那几段文档是确定的事实。前端把依据和答案一起显示，运维人员才有判断的余地。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiChatVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话 id，前端下一轮带上它 */
    private String sessionId;

    private String answer;

    /** 这次回答用到的知识片段，按相似度从高到低 */
    private List<KnowledgeChunkVO> references;
}
