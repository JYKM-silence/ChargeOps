package com.chargeops.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评估集的「常跑」校验：不需要 Ollama、不需要数据库，每次构建都执行。
 *
 * <p> 评估集的质量事故只有一种：<b>知识库文档改了，标注还指着旧标题</b>。
 * 那种情况下 {@code RagRetrievalEvalTest} 会报「召回率掉了」，而真实原因是没人更新标注 ——
 * 于是要么误改检索代码，要么干脆不再跑评估。这个类把「文档与标注对不上」
 * 与「检索质量下降」拆成两个信号，前者在这里红，后者才归评估测试。
 */
class RagEvalSetTest {

    private final RagEvalSet.Root evalSet = RagEvalSet.load();

    @Test
    @DisplayName("标注集结构完整：id 不重复、否定样本不带期望、追问带上下文")
    void evalSetShouldBeWellFormed() {
        assertThat(evalSet.version()).isEqualTo(1);
        assertThat(evalSet.cases()).as("评估集太小就量不出东西").hasSizeGreaterThanOrEqualTo(40);

        Set<String> ids = new HashSet<>();
        for (RagEvalSet.Case c : evalSet.cases()) {
            assertThat(ids.add(c.id())).as("重复的 id：%s", c.id()).isTrue();
            assertThat(c.question()).as("%s 的 question 不能为空", c.id()).isNotBlank();

            if (RagEvalSet.isNegative(c)) {
                // 否定样本的意义是「本来就没有答案」，给它标期望就自相矛盾了
                assertThat(c.expect()).as("否定样本 %s 不该有 expect", c.id()).isNullOrEmpty();
            } else {
                assertThat(c.expect()).as("%s 至少要有主答案", c.id()).isNotEmpty();
            }

            if (RagEvalSet.isFollowUp(c)) {
                // 追问组必须有 user 在前：改写靠的就是这段历史，缺了它测不出任何东西
                assertThat(c.context().getFirst().role()).as("%s 的上下文第一条应是 user", c.id())
                        .isEqualTo("user");
            }
        }
    }

    @Test
    @DisplayName("每条期望都能在语料里找到对应的 source 与 section")
    void everyExpectationShouldExistInKnowledge() {
        for (RagEvalSet.Case c : evalSet.cases()) {
            // 否定样本本来就没有期望，它由「结构完整」那条保证 expect 为空
            if (RagEvalSet.isNegative(c)) {
                continue;
            }
            for (RagEvalSet.Expect e : c.expect()) {
                assertThat(RagEvalSet.SOURCES).as("%s 引用了未知文档 %s", c.id(), e.source())
                        .contains(e.source());
                assertThat(sectionsOf(e.source()))
                        .as("%s 期望的「%s · %s」在文档里不存在了，改过标题就要同步标注",
                                c.id(), e.source(), e.section())
                        .contains(e.section());
            }
        }
    }

    @Test
    @DisplayName("每个知识节都至少被一条标注覆盖，没有漏网之鱼")
    void everyKnowledgeSectionShouldBeCovered() {
        // 覆盖率是评估集有效性的前提：只标「好召回的那几节」等于自己给自己出简单题
        Set<String> covered = evalSet.cases().stream()
                .filter(c -> !RagEvalSet.isNegative(c))
                .flatMap(c -> c.expect().stream())
                .map(e -> e.source() + " · " + e.section())
                .collect(Collectors.toSet());

        List<String> uncovered = new ArrayList<>();
        for (String source : RagEvalSet.SOURCES) {
            for (String section : sectionsOf(source)) {
                if (!covered.contains(source + " · " + section)) {
                    uncovered.add(source + " · " + section);
                }
            }
        }

        assertThat(uncovered).as("这些知识节没有任何标注覆盖").isEmpty();
    }

    /** 与 {@code KnowledgeBaseServiceImpl.split} 同一套切法：按 {@code ## } 取二级标题 */
    private Set<String> sectionsOf(String source) {
        return RagEvalSet.readSource(source).lines()
                .filter(line -> line.startsWith("## "))
                .map(line -> line.substring(3).trim())
                .collect(Collectors.toSet());
    }
}
