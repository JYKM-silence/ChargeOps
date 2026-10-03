package com.chargeops.config;

import com.chargeops.properties.AiOpsProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * AI 的两个 bean。
 *
 * <p> 条件是供应商开关而不是「bean 存在与否」：{@code @ConditionalOnBean} 在这里不可用，
 * 普通 {@code @Configuration} 比自动配置先解析，那时候 {@code ChatModel} 还没注册，
 * 条件永远不成立。写死供应商名的代价是换供应商要动这里一行。
 *
 * <p> 条件不成立时 bean 不注册，用到的地方注入 {@code ObjectProvider}，
 * 走「AI 未启用」分支而不是启动失败。
 */
@Configuration
public class AiConfiguration {

    /**
     * 多轮对话的记忆，按 {@code CONVERSATION_ID} 分片。
     *
     * <p> 放在内存里而不是 Redis：重启后助手忘掉之前聊过什么是可接受的，
     * 而多存一份会话就让「同一份对话存在两处、需要清理策略」这件事多一份。
     *
     * <p> 窗口按条数不按 token：一条中文运维问题大约几十 token，20 条约等于
     * 一次请求上下文的零头，不会把 {@code num_ctx} 撑爆。
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "ollama")
    ChatMemory opsChatMemory() {
        return MessageWindowChatMemory.builder().maxMessages(20).build();
    }

    /**
     * 助手的 ChatClient。
     *
     * <p> 记忆 advisor 挂在这里而不是每次请求现传：它是助手的固有行为，
     * 不是某一次调用的参数。每次请求只传会话 id 进去。
     *
     * <p> 系统提示词不挂默认值——它每轮都带着当轮检索到的文档片段，是变的。
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "ollama")
    ChatClient opsChatClient(ChatClient.Builder builder, ChatMemory opsChatMemory) {
        return builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(opsChatMemory).build())
                .build();
    }

    /**
     * 向量库。
     *
     * <p> {@code @Lazy} 是必须的，不是优化：{@code initializeSchema(true)} 让这个 bean 在
     * <b>构造时</b>就连 PG 建表。不加懒加载的话，PG 没起来时失败发生在容器刷新阶段 ——
     * 那不是「AI 用不了」，而是<b>应用起不来、全部 400 条测试一起报上下文加载失败</b>。
     * 实测：停掉 pgvector 容器后，一个和 AI 毫无关系的 {@code StationServiceImplTest}
     * 5 条全错在 {@code Error creating bean with name 'vectorStore'} 上。
     *
     * <p> 懒加载之后创建推迟到第一次真正检索，那时才失败，且失败落在 AI 的调用路径里 ——
     * 和这个类「AI 未启用不影响其余接口」的立场一致。代价是消费方拿到的那个代理
     * 永远不是 null，{@code requireVectorStore()} 判不出「PG 连不上」，
     * 这种情况会以 JDBC 异常的形式冒到接口上。可以接受：PG 是运维自己要装的依赖，
     * 而「Ollama 没装」比「PG 没装」常见得多。
     *
     * <p> 连接池不注册成 {@code DataSource} bean 是必须的，不是风格问题：容器里已经有一个
     * DataSource（Druid 管的 MySQL 主库），而 Druid 的自动配置带
     * {@code @ConditionalOnMissingBean(DataSource.class)}，再放一个同类型的 bean 进去，
     * 它就直接退避，结果是全部业务 SQL 打到这个向量库上。
     *
     * <p> 也因此没用 {@code PgVectorStoreAutoConfiguration}：它取容器里那个 {@code JdbcTemplate}，
     * 也就是 MySQL 的，建表时死在 {@code type "vector" does not exist} 上。
     *
     * <p> 返回类型写具体的 {@code PgVectorStore} 而不是 {@code VectorStore} 接口，是为了让上面那个
     * 自动配置退避 —— 它方法上的 {@code @ConditionalOnMissingBean} 不带属性，按**返回类型**匹配。
     * 写接口时它看不见这个 bean，会照样注册一个同名的 {@code vectorStore}，启动报名字冲突。
     *
     * <p> {@code DriverManagerDataSource} 不是池，每次检索新建一条连接。这个功能的负载是
     * 「一次问答一次检索」，为这点流量管一个连接池的生命周期不划算。
     *
     * <p> 建 vector 扩展和表这两步归 Spring AI，不进 Flyway：Flyway 管的是 MySQL，两个库各管各的。
     */
    @Bean
    @Lazy
    @ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "ollama")
    PgVectorStore vectorStore(EmbeddingModel embeddingModel, AiOpsProperties properties) {
        AiOpsProperties.VectorStore config = properties.getVectorStore();

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(config.getUrl());
        dataSource.setUsername(config.getUsername());
        dataSource.setPassword(config.getPassword());

        return PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
                .vectorTableName(config.getTableName())
                .dimensions(config.getDimensions())
                .initializeSchema(true)
                .build();
    }
}
