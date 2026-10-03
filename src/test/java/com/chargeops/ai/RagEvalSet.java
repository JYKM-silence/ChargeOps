package com.chargeops.ai;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 检索评估集的读取器，{@link RagEvalSetTest} 和 {@link RagRetrievalEvalTest} 共用。
 *
 * <p> 标注集本身是 JSON 而不是 Java 常量：它要能被「改知识库文档的人」顺手改，
 * 而那个人不一定想动编译单元。代价是字段名失去编译期保护，由 {@link RagEvalSetTest}
 * 在每次构建时兜底。
 */
final class RagEvalSet {

    /** 语料清单。与 {@code KnowledgeBaseServiceImpl.SOURCES} 必须一致，由校验测试钉住 */
    static final List<String> SOURCES = List.of("故障码说明.md", "运维手册.md", "常见问题.md");

    private static final String CLASSPATH_FILE = "rag/eval-set.json";

    private RagEvalSet() {
    }

    record Root(int version, String description, List<Case> cases) {
    }

    /**
     * 一条标注。{@code expect} 的第一条是主答案，其余是同样可接受、不算失败的回答 ——
     * 三份文档之间本来就有合理的交叉（「桩离线」既在故障码里也在常见问题里），
     * 只认一条会把正确召回判成失败。
     */
    record Case(String id, String question, List<Expect> expect, List<Context> context, Boolean negative) {
    }

    record Expect(String source, String section) {
    }

    /** 追问组的前置对话。role 取 user / assistant，与模型消息的角色同名 */
    record Context(String role, String text) {
    }

    static Root load() {
        try (InputStream in = RagEvalSet.class.getClassLoader().getResourceAsStream(CLASSPATH_FILE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 下找不到 " + CLASSPATH_FILE);
            }
            return JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build()
                    .readValue(in, Root.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取评估集失败：" + CLASSPATH_FILE, e);
        }
    }

    /** 读语料 markdown，供校验测试核对 section 是否存在 */
    static String readSource(String source) {
        try (InputStream in = RagEvalSet.class.getClassLoader()
                .getResourceAsStream("knowledge/" + source)) {
            if (in == null) {
                throw new IllegalStateException("classpath 下找不到语料：" + source);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取语料失败：" + source, e);
        }
    }

    static boolean isNegative(Case c) {
        return Boolean.TRUE.equals(c.negative());
    }

    static boolean isFollowUp(Case c) {
        return c.context() != null && !c.context().isEmpty();
    }
}
