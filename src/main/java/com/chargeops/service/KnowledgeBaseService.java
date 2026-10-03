package com.chargeops.service;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 运维知识库（RAG 的检索那一半）。
 *
 * <p> 语料是 {@code src/main/resources/knowledge/} 下的三份 markdown，按二级标题切片。
 * 库是 PostgreSQL + pgvector，和业务库（MySQL）不是一个实例，表由 Spring AI 建。
 *
 * <p> 检索返回的是原始 {@link Document} 而不是 VO：调用方要拿 {@code getText()}
 * 拼进提示词，截断成给前端看的片段是表示层的事。
 */
public interface KnowledgeBaseService {

    /**
     * 重建索引：三份文档全部重新切片、向量化、入库。
     *
     * <p> 幂等，可以在管理端点任意次。改完 markdown 之后不用重启 —— 但也只有这一步
     * 会把改动带进去，应用启动时那次加载是同一个方法。
     *
     * @return 入库的片段数
     */
    int reload();

    /**
     * 向量检索。
     *
     * <p> 不设相似度阈值：知识库总共二十几个片段，按 {@code topK} 取最近的几个就够，
     * 而阈值是个会静默失效的旋钮 —— 调高了检索返回空，助手的表现是「知识库里没有这条」，
     * 看起来像知识库缺内容，不像配置写错了。
     */
    List<Document> search(String query, int topK);
}
