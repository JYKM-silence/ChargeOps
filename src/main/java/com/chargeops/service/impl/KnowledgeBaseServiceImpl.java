package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.exception.AiUnavailableException;
import com.chargeops.properties.AiOpsProperties;
import com.chargeops.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库实现：markdown 切片 + 向量库读写。
 *
 * <p> 切片按 {@code ## } 二级标题，没有引 markdown 解析器。三份文档是自己写的，
 * 二级标题就是「一个故障码」「一条 FAQ」，这个切法恰好等于语义切分，而引一个解析器
 * 还得再决定按几个字符二次切分。
 *
 * <p> 每个片段把标题一起带上再向量化。标题本身带语义（{@code E-021 通信中断}），
 * 正文里往往只写「通信中断」；只嵌入正文的话，用户问「E-021 怎么处理」时
 * 这个片段反而排不到前面。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    /** 语料清单。加一份文档只用改这里，但改完要在管理端点一次「重建知识库」 */
    private static final List<String> SOURCES = List.of("故障码说明.md", "运维手册.md", "常见问题.md");

    private static final String CLASSPATH_DIR = "knowledge/";

    /** 元数据的两个键名。{@code source} 同时是删除时的过滤条件，改名要同时改两处 */
    private static final String KEY_SOURCE = "source";
    private static final String KEY_SECTION = "section";

    private final ObjectProvider<VectorStore> vectorStoreProvider;

    private final AiOpsProperties aiOpsProperties;

    /**
     * 启动后把知识库灌一遍，让 clone 下来的人不用先点按钮。
     *
     * <p> 失败只记日志不抛出：这一步要调向量模型，Ollama 没起来它就失败，
     * 而 Ollama 没起来不该让整个应用起不来 —— 不用 AI 功能的那些接口一个都不受影响。
     *
     * <p> 监听 {@code ApplicationReadyEvent} 而不是实现 {@code ApplicationRunner}：
     * 那时候 HTTP 端口已经开了，灌库慢的时候 {@code /actuator/health} 照样能答，
     * 容器探针不会因为一次可选功能预热把实例判死。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp(ApplicationReadyEvent event) {
        if (!Boolean.TRUE.equals(aiOpsProperties.getReloadOnStartup())) {
            return;
        }
        try {
            int chunks = reload();
            log.info("知识库加载完成：{} 份文档，{} 个片段", SOURCES.size(), chunks);
        } catch (Exception e) {
            log.warn("知识库加载失败，AI 助手这次召回不到资料。"
                    + "确认 Ollama 和 pgvector 起来后，调 POST /api/admin/ai-ops/knowledge/reload 重建。原因：{}",
                    e.getMessage());
        }
    }

    @Override
    public int reload() {
        VectorStore vectorStore = requireVectorStore();

        int total = 0;
        for (String source : SOURCES) {
            List<Document> chunks = split(source, read(source));

            // 先删后写，重复执行不会让片段翻倍。按 source 删而不是清空整表：
            // 以后会有别的来源（工单摘要之类）进来，清空会把它们一起带走
            vectorStore.delete(new FilterExpressionBuilder().eq(KEY_SOURCE, source).build());
            vectorStore.add(chunks);

            total += chunks.size();
            log.info("知识库写入 {}：{} 个片段", source, chunks.size());
        }
        return total;
    }

    @Override
    public List<Document> search(String query, int topK) {
        return requireVectorStore().similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());
    }

    /** 按 {@code ## } 切。一级标题及其下面的前言不进库：那几行是给人看的文档说明，不是运维知识 */
    private List<Document> split(String source, String markdown) {
        List<Document> docs = new ArrayList<>();
        String section = null;
        StringBuilder body = new StringBuilder();

        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("## ")) {
                addChunk(docs, source, section, body);
                section = line.substring(3).trim();
                body.setLength(0);
            } else if (section != null) {
                body.append(line).append('\n');
            }
        }
        addChunk(docs, source, section, body);

        return docs;
    }

    private void addChunk(List<Document> docs, String source, String section, StringBuilder body) {
        String text = body.toString().trim();
        if (section == null || text.isEmpty()) {
            return;
        }
        // 标题拼进正文再向量化，理由见类注释
        docs.add(new Document(section + "\n" + text,
                Map.of(KEY_SOURCE, source, KEY_SECTION, section)));
    }

    private String read(String fileName) {
        ClassPathResource resource = new ClassPathResource(CLASSPATH_DIR + fileName);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 语料是随包发布的资源，读不到说明打包出了问题，属于启动就该发现的那类错误
            throw new IllegalStateException("读取知识库文档失败：" + fileName, e);
        }
    }

    private VectorStore requireVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            // 到这里的条件是 spring.ai.model.embedding 不是 ollama，见 AiConfiguration
            throw new AiUnavailableException(MessageConstant.AI_NOT_ENABLED);
        }
        return vectorStore;
    }
}
