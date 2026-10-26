package com.chargeops.service.impl;

import com.chargeops.ai.OpsTools;
import com.chargeops.ai.QueryRewriter;
import com.chargeops.constant.MessageConstant;
import com.chargeops.dto.AiChatDTO;
import com.chargeops.exception.AiUnavailableException;
import com.chargeops.exception.BaseException;
import com.chargeops.properties.AiOpsProperties;
import com.chargeops.service.AiOpsService;
import com.chargeops.service.KnowledgeBaseService;
import com.chargeops.vo.AiChatVO;
import com.chargeops.vo.KnowledgeChunkVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * AI 运维助手实现
 *
 * <p> 检索是每一轮现做的，不存进会话历史。会话里存的是「问过什么、答过什么」，
 * 而知识片段属于这一轮的提示词 —— 存进去的话，第三轮会带着第一轮召回的文档一起发给模型，
 * 上下文越滚越大，而且模型可能拿旧文档回答新问题。
 *
 * <p> 追问先过一遍 {@link QueryRewriter}：「那第二点具体怎么做」整句拿去向量化，
 * 和任何文档片段都不像，那一轮就会召回空。改写后的问题只用来检索，
 * 发给模型的仍然是用户原话 —— 模型手里有完整会话历史，不需要改写版。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiOpsServiceImpl implements AiOpsService {

    /** 片段给前端展示时的截断长度。检索本身用的是全文，这里只影响展示 */
    private static final int SNIPPET_LENGTH = 160;

    private static final String SYSTEM_PROMPT = """
            你是充电桩运维助手，服务对象是充电站的一线运维人员。

            回答要求：
            1. 优先依据下面「知识库检索结果」里的资料。资料里没有的，直接说知识库里没有这一条，
               不要凭经验编造故障码的含义或处理步骤。
            2. 需要设备的实时信息（桩的状态、近期告警）时调用工具去查，不要假设，
               也不要把用户说的现象当成已核实的事实。
            3. 给结论的同时给依据。引用资料时说清来自哪份文档的哪一节。
            4. 处理步骤按先后顺序列出，能落地的操作（断电、复位、换件）排在前面。
            5. 用中文回答，简洁，不要复述用户的提问。

            知识库检索结果：
            %s
            """;

    private static final String NO_REFERENCE = "（本次没有检索到相关资料）";

    private final ObjectProvider<ChatClient> chatClientProvider;

    private final KnowledgeBaseService knowledgeBaseService;

    private final QueryRewriter queryRewriter;

    private final AiOpsProperties aiOpsProperties;

    private final OpsTools opsTools;

    @Override
    public AiChatVO chat(AiChatDTO dto) {
        ChatClient chatClient = requireChatClient();

        String sessionId = StringUtils.hasText(dto.getSessionId())
                ? dto.getSessionId()
                : UUID.randomUUID().toString();

        String retrievalQuery = queryRewriter.rewriteForRetrieval(sessionId, dto.getQuestion());
        List<Document> docs = knowledgeBaseService.search(retrievalQuery, aiOpsProperties.getTopK());

        String answer;
        try {
            answer = chatClient.prompt()
                    .system(systemPrompt(docs))
                    // 发原话不发改写版：改写只解决「检索找不到」，模型这边有完整历史，
                    // 换成改写版反而可能把用户真正问的意思改没了
                    .user(dto.getQuestion())
                    // 会话 id 走 advisor 的参数，不拼进提示词：它是记忆的分片键，
                    // 拼进去模型会把它当内容，还多占 token
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .tools(opsTools)
                    .call()
                    .content();
        } catch (BaseException e) {
            // 工具或下游已经给出了明确的业务结论，不要把它盖成「AI 不可用」
            throw e;
        } catch (RuntimeException e) {
            // Ollama 没起来 / 超时 / 模型返回解析不了：都是 MessageConstant.AI_UNAVAILABLE
            //   描述的那一类，给调用方一句「稍后重试或手工录入」比「未知错误」有用得多
            log.warn("AI 调用失败，本轮按不可用处理：sessionId={}，原因：{}",
                    sessionId, e.getMessage());
            throw new AiUnavailableException(MessageConstant.AI_UNAVAILABLE);
        }

        log.info("AI 问答完成：sessionId={}, 召回 {} 个片段, 回答 {} 字",
                sessionId, docs.size(), answer == null ? 0 : answer.length());

        return new AiChatVO(sessionId, answer, retrievalQuery, toChunks(docs));
    }

    @Override
    public List<KnowledgeChunkVO> searchKnowledge(String query) {
        return toChunks(knowledgeBaseService.search(query, aiOpsProperties.getTopK()));
    }

    private String systemPrompt(List<Document> docs) {
        if (docs.isEmpty()) {
            return String.format(SYSTEM_PROMPT, NO_REFERENCE);
        }

        StringBuilder sb = new StringBuilder();
        for (Document doc : docs) {
            sb.append(String.format("【%s · %s】%n%s%n%n",
                    doc.getMetadata().get("source"),
                    doc.getMetadata().get("section"),
                    doc.getText()));
        }
        return String.format(SYSTEM_PROMPT, sb);
    }

    private List<KnowledgeChunkVO> toChunks(List<Document> docs) {
        return docs.stream()
                .map(doc -> new KnowledgeChunkVO(
                        String.valueOf(doc.getMetadata().get("source")),
                        String.valueOf(doc.getMetadata().get("section")),
                        doc.getScore(),
                        truncate(doc.getText())))
                .toList();
    }

    private String truncate(String text) {
        if (text == null || text.length() <= SNIPPET_LENGTH) {
            return text;
        }
        return text.substring(0, SNIPPET_LENGTH) + "…";
    }

    private ChatClient requireChatClient() {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            // 到这里的条件是 spring.ai.model.chat 不是 ollama，见 AiConfiguration。
            // 报「未启用」而不是让注入失败：AI 是可选的，模型没配好不该让管理端整个打不开
            throw new AiUnavailableException(MessageConstant.AI_NOT_ENABLED);
        }
        return chatClient;
    }
}
