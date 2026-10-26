package com.chargeops.service.impl;

import com.chargeops.exception.AiUnavailableException;
import com.chargeops.properties.AiOpsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量库不可用时的错误翻译。
 *
 * <p> 不起 Spring：被测的就是「依赖没起来时，接口最终拿到哪一句话」，而这句话完全由
 * {@code requireVectorStore()} / {@code search()} 的 catch 决定，不需要容器。
 *
 * <p> 为什么值得专门测：{@code AiConfiguration} 把向量库 bean 标了 {@code @Lazy}，
 * 它没起来不会再让应用启动失败，但失败会推迟到第一次检索时才发生 —— 那时抛的是
 * 框架的 {@code BeanCreationException}。没有这层翻译，用户看到的是兜底的「未知错误」，
 * 日志里是一条 error 级系统异常，而事实只是「pgvector 容器没起」。
 */
@DisplayName("知识库：依赖不可用要报「AI 暂时不可用」，不能落到兜底的「未知错误」")
class KnowledgeBaseServiceImplTest {

    @Test
    @DisplayName("PG 没起：ObjectProvider 抛 BeanCreationException，翻译成 AI_UNAVAILABLE")
    void shouldTranslateVectorStoreCreationFailure() {
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                throwingProvider(), new AiOpsProperties());

        assertThatThrownBy(() -> service.search("E-021 怎么处理", 4))
                .as("MessageConstant.AI_UNAVAILABLE 早就定义好了，只是之前没有路径用到它")
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("暂时不可用");
    }

    @Test
    @DisplayName("bean 没注册（模型供应商不是 ollama）：仍是「未启用」，不翻译成「暂时不可用」")
    void shouldKeepNotEnabledMessageWhenBeanIsAbsent() {
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                absentProvider(), new AiOpsProperties());

        assertThatThrownBy(() -> service.search("E-021", 4))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("未启用");
    }

    /** 模拟 @Lazy bean 在第一次 getIfAvailable() 时才连 PG，并且连不上 */
    private static ObjectProvider<VectorStore> throwingProvider() {
        return new ObjectProvider<>() {
            @Override
            public VectorStore getObject() {
                throw vectorStoreFailure();
            }

            @Override
            public VectorStore getObject(Object... args) {
                throw vectorStoreFailure();
            }

            @Override
            public VectorStore getIfAvailable() {
                throw vectorStoreFailure();
            }

            @Override
            public VectorStore getIfUnique() {
                throw vectorStoreFailure();
            }
        };
    }

    /** 模拟 {@code spring.ai.model.embedding} 不是 ollama：bean 根本没注册 */
    private static ObjectProvider<VectorStore> absentProvider() {
        return new ObjectProvider<>() {
            @Override
            public VectorStore getObject() {
                return null;
            }

            @Override
            public VectorStore getObject(Object... args) {
                return null;
            }

            @Override
            public VectorStore getIfAvailable() {
                return null;
            }

            @Override
            public VectorStore getIfUnique() {
                return null;
            }
        };
    }

    /** 和实测堆栈一致：BeanCreationException 包着 JDBC 的连接失败 */
    private static BeanCreationException vectorStoreFailure() {
        return new BeanCreationException("vectorStore",
                "Error creating bean with name 'vectorStore'",
                new IllegalStateException("Failed to obtain JDBC Connection"));
    }
}
