package com.chargeops.ai;

import com.chargeops.properties.AiOpsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 追问的查询改写：把「那第二点具体怎么做」补全成能独立检索的问题。
 *
 * <p> 不改写的话这类问题的召回是空的 —— 整句向量化之后和任何文档片段都不像，
 * 模型只能靠会话历史回答，知识库这部分等于没用上。改写后的问题只用于<b>检索</b>，
 * 原始问题照旧发给对话模型（它的上下文里有完整历史，不需要改写版）。
 *
 * <p> <b>用独立的 ChatClient，不碰 {@code opsChatClient}。</b> 后者挂着记忆 advisor，
 * 借它做改写，这一次「系统提示 + 历史 + 改写指令 → 改写结果」的往返会被写进会话历史：
 * 用户下一轮看到的上下文里多了一段自己没说过的话，而改写指令是元语言，模型很容易
 * 把它当成业务内容接着聊。这里从 {@code ChatClient.Builder} 现建一个不挂 advisor 的。
 *
 * <p> 先过一道便宜的闸门再谈改写。改写是一次额外的模型调用，对「E-021 怎么处理」
 * 这种本来就完整的问题没有收益；只有出现指代特征（那、它、第二点、呢）时才值得付这个成本。
 *
 * <p> 任何一步失败都退回原问题。改写是锦上添花，它挂掉不该让一次能回答的问答变成报错。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QueryRewriter {

    /**
     * 指代与省略的标记词。
     *
     * <p> 宁可多触发几次：误触发的代价是一次模型调用，而改写提示词里写了
     * 「原问题已经完整就原样输出」，误触发通常原样返回；漏触发的代价是那一轮
     * 召回为空，用户看到的是答非所问。
     */
    private static final List<String> REFERRING_MARKERS = List.of(
            "那", "这", "它", "上面", "前面", "刚才", "你刚", "你说的", "还有",
            "继续", "接着", "具体", "呢");

    /**
     * 「第二点」「第 3 条」这类序号引用，出现即视为追问。
     *
     * <p> 数字两侧允许空格：中文里夹阿拉伯数字时习惯写成「第 3 步」，
     * 不留空格的话这一类追问（恰恰是最典型的一类）会被闸门放过。
     */
    private static final Pattern ORDINAL =
            Pattern.compile("第\\s*[一二三四五六七八九十\\d]+\\s*(点|条|步|项)");

    /** 改写结果超过这个长度就不像是「一个可以直接检索的问题」了，按失败处理 */
    private static final int MAX_REWRITE_LENGTH = 100;

    private static final String REWRITE_PROMPT = """
            你是检索查询改写器。下面是一段充电桩运维对话，最后一条用户消息里可能有指代或省略，
            例如「它」「那第二点」「具体怎么操作」。

            请把它改写成一句不依赖上下文、可以独立拿去检索资料库的完整问题。

            要求：
            1. 只输出改写后的问题本身。不要解释、不要引号、不要「改写后：」这类前缀。
            2. 保留原问题的意图和范围，不要补充对话里没有的信息。
            3. 如果它本身已经完整，原样输出。
            """;

    /** 模型有时会把答案包一层前缀或引号，按失败处理之前先剥掉 */
    private static final Pattern PREFIX = Pattern.compile("^(改写后的问题|改写后|改写结果|改写|答案)\\s*[:：]\\s*");

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    private final ObjectProvider<ChatMemory> chatMemoryProvider;

    private final AiOpsProperties aiOpsProperties;

    private volatile ChatClient chatClient;

    /**
     * 返回这一轮检索该用的问题：追问且改写成功时是新问题，其余情况是原问题。
     */
    public String rewriteForRetrieval(String sessionId, String question) {
        if (!Boolean.TRUE.equals(aiOpsProperties.getRewrite().getEnabled())
                || !looksLikeFollowUp(question)) {
            return question;
        }

        List<Message> history = history(sessionId);
        if (history.isEmpty()) {
            // 没有历史就没有可补全的上下文，这多半是第一轮，原问题即全部信息
            return question;
        }

        ChatClient client = chatClientOrNull();
        if (client == null) {
            return question;
        }

        try {
            List<Message> messages = new ArrayList<>(history);
            messages.add(new UserMessage(question));

            String raw = client.prompt()
                    .system(REWRITE_PROMPT)
                    .messages(messages)
                    // 显式钉住温度：改写是格式转换不是创作，而这个调用不该受对话那边
                    // 调温度的影响（两者共用 Ollama 的默认选项）
                    .options(ChatOptions.builder().temperature(0.0))
                    .call()
                    .content();

            String rewritten = normalize(raw, question);
            if (!rewritten.equals(question)) {
                log.info("追问改写：{} -> {}", question, rewritten);
            }
            return rewritten;
        } catch (Exception e) {
            log.warn("追问改写失败，本轮按原问题检索：{}（原因：{}）", question, e.getMessage());
            return question;
        }
    }

    /** 命中指代特征才值得付一次模型调用，见类注释 */
    static boolean looksLikeFollowUp(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        if (ORDINAL.matcher(question).find()) {
            return true;
        }
        return REFERRING_MARKERS.stream().anyMatch(question::contains);
    }

    /**
     * 清洗模型输出，不可用就回退到原问题。
     *
     * <p> 宁可回退得多：一段带着解释的「改写」拿去向量化，召回的东西比原问题更偏，
     * 而失败是静默的 —— 用户只会看到答案不对，看不出是改写那一步引入的。
     */
    static String normalize(String raw, String original) {
        if (raw == null) {
            return original;
        }
        String text = raw.strip();
        text = PREFIX.matcher(text).replaceFirst("");
        text = stripQuotes(text);
        // 多行输出只认第一行：要求是「只输出问题」，出现多行时首行之外的
        // 大概率是解释，而解释里的词会把检索带偏
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline).strip();
        }
        if (text.isBlank() || text.length() > MAX_REWRITE_LENGTH) {
            return original;
        }
        return text;
    }

    private static String stripQuotes(String text) {
        if (text.length() < 2) {
            return text;
        }
        boolean wrapped = (text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("“") && text.endsWith("”"))
                || (text.startsWith("「") && text.endsWith("」"));
        return wrapped ? text.substring(1, text.length() - 1).strip() : text;
    }

    /** 最近几条历史，每条截前缀。截断是复制，不动 {@code ChatMemory} 里那一份 */
    private List<Message> history(String sessionId) {
        ChatMemory memory = chatMemoryProvider.getIfAvailable();
        if (memory == null) {
            return List.of();
        }
        List<Message> all = memory.get(sessionId);
        if (all == null || all.isEmpty()) {
            return List.of();
        }

        int contextMessages = aiOpsProperties.getRewrite().getContextMessages();
        int from = Math.max(0, all.size() - contextMessages);
        int contextChars = aiOpsProperties.getRewrite().getContextChars();

        List<Message> recent = new ArrayList<>();
        for (Message message : all.subList(from, all.size())) {
            recent.add(truncate(message, contextChars));
        }
        return recent;
    }

    private static Message truncate(Message message, int maxChars) {
        String text = message.getText();
        if (text == null || text.length() <= maxChars) {
            return message;
        }
        String cut = text.substring(0, maxChars);
        // 只保留 user/assistant 两种：会话记忆里本来也只有这两种，其余类型
        // 复制成文本会丢结构（工具调用），不如当作用户消息带过去
        return message.getMessageType() == MessageType.ASSISTANT
                ? new AssistantMessage(cut)
                : new UserMessage(cut);
    }

    /** 懒建并缓存。AI 未启用时返回 null，调用方按原问题走 */
    private ChatClient chatClientOrNull() {
        ChatClient client = chatClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (chatClient == null) {
                ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
                if (builder == null) {
                    return null;
                }
                chatClient = builder.build();
            }
            return chatClient;
        }
    }
}
