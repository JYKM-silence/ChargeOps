package com.chargeops.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 一条被召回的知识片段，给前端展示「这次回答依据的是哪几段」。
 *
 * <p> 有这个 VO 才有引用溯源：运维人员看到答案后面挂着《故障码说明.md · E-021》，
 * 才能判断模型是在照文档说还是在编。只回一个 answer 的话，错了也不知道错在哪一步。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class KnowledgeChunkVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 来源文档名，如「故障码说明.md」 */
    private String source;

    /** 文档里的二级标题，如「E-021 通信中断」 */
    private String section;

    /** 余弦相似度，0~1。只用于排序展示，不作为「够不够相关」的判据 */
    private Double score;

    /** 截断后的正文，够前端展示个大概，不追求完整 */
    private String snippet;
}
