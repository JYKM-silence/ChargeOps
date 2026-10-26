package com.chargeops.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QueryRewriter} 里两个纯函数的测试：闸门与输出清洗。
 *
 * <p> 不启动 Spring、不连 Ollama。这两步恰好是整条改写链路上唯一能离线确定的部分：
 * 中间那次模型调用是概率性的，它的输出长什么样只能靠评估集去量，不该写成「必须等于某句话」
 * 的断言 —— 那种测试在换模型或调提示词之后必红，红了也只能改期望值，等于没测。
 *
 * <p> 这里测的是「模型给出什么形状的输出，代码还认不认」：带前缀的、包引号的、
 * 多行的、超长的，都得落在一个确定的结果上。
 */
@DisplayName("QueryRewriter 追问判定与输出清洗")
class QueryRewriterTest {

    @Nested
    @DisplayName("闸门：什么样的提问才值得付一次改写调用")
    class Gate {

        @Test
        @DisplayName("指代词触发改写")
        void shouldDetectReferringMarkers() {
            assertThat(QueryRewriter.looksLikeFollowUp("那第二点具体怎么做")).isTrue();
            assertThat(QueryRewriter.looksLikeFollowUp("它一般是什么原因")).isTrue();
            assertThat(QueryRewriter.looksLikeFollowUp("上面说的那个流程再讲一遍")).isTrue();
            assertThat(QueryRewriter.looksLikeFollowUp("还有别的排查方法吗")).isTrue();
            assertThat(QueryRewriter.looksLikeFollowUp("接着说")).isTrue();
        }

        @Test
        @DisplayName("序号引用不需要指代词也触发：追问里最常见的一种")
        void shouldDetectOrdinalReference() {
            assertThat(QueryRewriter.looksLikeFollowUp("第 3 步要断电吗")).isTrue();
            assertThat(QueryRewriter.looksLikeFollowUp("第一条是什么意思")).isTrue();
        }

        @Test
        @DisplayName("本来就完整的问题不触发，白付一次模型调用没有收益")
        void shouldSkipSelfContainedQuestions() {
            assertThat(QueryRewriter.looksLikeFollowUp("E-021 怎么处理")).isFalse();
            assertThat(QueryRewriter.looksLikeFollowUp("充电枪插上后没反应")).isFalse();
            assertThat(QueryRewriter.looksLikeFollowUp("3 号站今天的充电量是多少")).isFalse();
        }

        @Test
        @DisplayName("空问题不触发：闸门不该把空值放行到后面去")
        void shouldSkipBlank() {
            assertThat(QueryRewriter.looksLikeFollowUp(null)).isFalse();
            assertThat(QueryRewriter.looksLikeFollowUp("")).isFalse();
            assertThat(QueryRewriter.looksLikeFollowUp("   ")).isFalse();
        }
    }

    @Nested
    @DisplayName("清洗：模型输出的各种包装都要落到一个可检索的问题上")
    class Normalize {

        private static final String ORIGINAL = "那第二点具体怎么做";

        @Test
        @DisplayName("干净输出原样采用")
        void shouldKeepCleanOutput() {
            assertThat(QueryRewriter.normalize("E-021 通信中断告警的处理步骤", ORIGINAL))
                    .isEqualTo("E-021 通信中断告警的处理步骤");
        }

        @Test
        @DisplayName("剥掉「改写后：」这类前缀和包裹的引号")
        void shouldStripPrefixAndQuotes() {
            assertThat(QueryRewriter.normalize("改写后：E-021 通信中断告警的处理步骤", ORIGINAL))
                    .isEqualTo("E-021 通信中断告警的处理步骤");
            assertThat(QueryRewriter.normalize("「E-021 通信中断告警的处理步骤」", ORIGINAL))
                    .isEqualTo("E-021 通信中断告警的处理步骤");
            assertThat(QueryRewriter.normalize("\"E-021 通信中断告警的处理步骤\"", ORIGINAL))
                    .isEqualTo("E-021 通信中断告警的处理步骤");
        }

        @Test
        @DisplayName("多行输出只取第一行：首行之外大概率是解释，会把检索带偏")
        void shouldKeepFirstLineOnly() {
            assertThat(QueryRewriter.normalize(
                    "E-021 通信中断告警的处理步骤\n\n说明：这里补充了上下文里提到的故障码。", ORIGINAL))
                    .isEqualTo("E-021 通信中断告警的处理步骤");
        }

        @Test
        @DisplayName("空输出、超长输出回退到原问题")
        void shouldFallBackWhenUnusable() {
            assertThat(QueryRewriter.normalize(null, ORIGINAL)).isEqualTo(ORIGINAL);
            assertThat(QueryRewriter.normalize("", ORIGINAL)).isEqualTo(ORIGINAL);
            assertThat(QueryRewriter.normalize("   ", ORIGINAL)).isEqualTo(ORIGINAL);
            // 超长说明模型在展开而不是改写，这种文本拿去向量化比原问题更糟
            assertThat(QueryRewriter.normalize("故".repeat(200), ORIGINAL)).isEqualTo(ORIGINAL);
        }

        @Test
        @DisplayName("模型判断原问题已完整而原样返回时，结果就是原问题，调用方看不出差别")
        void shouldReturnOriginalWhenModelSaysComplete() {
            assertThat(QueryRewriter.normalize(ORIGINAL, ORIGINAL)).isEqualTo(ORIGINAL);
        }
    }
}
