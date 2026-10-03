package com.chargeops.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 运维助手的参数，对应 {@code chargeops.ai-ops.*}
 *
 * <p> 向量库的连接信息写在这里而不是 {@code spring.datasource} 下：那组键归 Druid 的
 * MySQL 主库，写进去等于把主库连接改到 PG 上。
 */
@Component
@ConfigurationProperties(prefix = "chargeops.ai-ops")
@Data
public class AiOpsProperties {

    private VectorStore vectorStore = new VectorStore();

    /** 每次检索带几条文档片段给模型。给多了上下文变长、变慢、变贵，4 条够覆盖一个故障码 */
    private Integer topK = 4;

    /**
     * 启动后是否自动把知识库灌一遍。
     *
     * <p> 开发和生产都给 true，让 clone 下来的人不用先点按钮。测试里关掉：
     * 这一步要真的调向量模型，而每个 {@code @SpringBootTest} 上下文都会触发一次，
     * 灌的是同一个 PG 库，既拖慢测试又让测试之间互相删写。
     * 见 {@code src/test/resources/application.properties}
     */
    private Boolean reloadOnStartup = true;

    @Data
    public static class VectorStore {

        private String url;

        private String username;

        private String password;

        private String tableName = "vector_store";

        /** 必须等于向量模型的输出维度，不一致时写入报 expected 1024 dimensions, not N */
        private Integer dimensions = 1024;
    }
}
