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
     * 相似度下限，低于它的片段不进提示词，也不出现在检索探针里。
     *
     * <p> 0.0 表示不过滤，也就是「检索到什么就给什么」：模型拿到不相关的片段时，
     * 系统提示词里那句「资料里没有的就说没有」是唯一的防线。设成一个量出来的正数之后，
     * 「知识库里没有」的问题在检索这一层就被挡住，模型看到的是空资料。
     *
     * <p> 值不靠猜：跑 {@code RagRetrievalEvalTest}（-Drag.eval=true）会输出阈值扫描表
     * 和两组分数 —— 命中条目的分数、否定样本的最高分，阈值落在这两组之间。
     * 代价是调高会让部分弱相关但真有用的召回消失，所以扫描表里两个数要一起看。
     */
    private Double similarityThreshold = 0.0;

    private Rewrite rewrite = new Rewrite();

    /** 追问改写：把「那第二点具体怎么做」补全成能独立检索的问题 */
    @Data
    public static class Rewrite {

        /**
         * 关闭后 {@code chat} 完全按原问题检索，行为与改写功能上线前一致。
         * 关掉它也是评估里「改写前」那一列的做法。
         */
        private Boolean enabled = true;

        /**
         * 带进改写上下文的历史消息条数。
         *
         * <p> 取最近几条而不是按 token 截：追问的指代几乎总是指向上一条回答，
         * 更早的内容已经在会话窗口里被压缩过语境，带进去反而让改写模型抓错重点。
         */
        private Integer contextMessages = 6;

        /**
         * 每条历史消息截断的字符数。
         *
         * <p> 回答是结论在前、展开在后，「第二点」指的内容一定在这条回答的开头，
         * 所以截前缀而不是截尾部。
         */
        private Integer contextChars = 200;
    }

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
