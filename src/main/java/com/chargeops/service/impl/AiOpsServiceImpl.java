package com.chargeops.service.impl;

import com.chargeops.ai.OpsTools;
import com.chargeops.constant.MessageConstant;
import com.chargeops.dto.AiChatDTO;
import com.chargeops.exception.AiUnavailableException;
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
 * <p> 已知短板：追问里的指代检索不到东西。「那第二点具体怎么做」整句拿去向量化，
 * 和任何文档片段都不像，所以那一轮召回是空的，模型只能靠会话历史答。
 * 要修的话得先用历史把问题改写成一个完整的问题再检索（多一次模型调用），这一步没做。
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

    private final AiOpsProperties aiOpsProperties;

    private final OpsTools opsTools;

    @Override
    public AiChatVO chat(AiChatDTO dto) {
        ChatClient chatClient = requireChatClient();

        String sessionId = StringUtils.hasText(dto.getSessionId())
                ? dto.getSessionId()
                : UUID.randomUUID().toString();

        List<Document> docs = knowledgeBaseService.search(dto.getQuestion(), aiOpsProperties.getTopK());

        String answer = chatClient.prompt()
                .system(systemPrompt(docs))
                .user(dto.getQuestion())
                // 会话 id 走 advisor 的参数，不拼进提示词：它是记忆的分片键，
                // 拼进去模型会把它当内容，还多占 token
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, sessionId))
                .tools(opsTools)
                .call()
                .content();

        log.info("AI 问答完成：sessionId={}, 召回 {} 个片段, 回答 {} 字",
                sessionId, docs.size(), answer == null ? 0 : answer.length());

        return new AiChatVO(sessionId, answer, toChunks(docs));
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
