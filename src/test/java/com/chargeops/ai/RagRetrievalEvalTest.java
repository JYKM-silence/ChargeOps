package com.chargeops.ai;

import com.chargeops.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 检索质量的离线评估：跑一遍标注集，输出 recall@K / MRR 和阈值扫描表。
 *
 * <p> <b>默认不跑</b>（{@code -Drag.eval=true} 才启用），因为它需要三样不在测试契约里的东西：
 * Ollama 里装好向量模型、pgvector 容器在跑、语料能被重新灌一遍。缺任何一样都会先
 * {@code assumeTrue} 跳过并说明缺什么，而不是甩一个 JDBC 或 HTTP 原始异常 ——
 * 那两样东西和「检索质量」没有关系，看日志的人会追错方向。
 * 平时的 {@code mvn test} 一条都不需要它们，这个类连上下文都不会加载。
 *
 * <p> 命令行：{@code mvn test -Dtest=RagRetrievalEvalTest -Drag.eval=true}
 *
 * <p> 阈值扫描是「一次检索、客户端过滤」而不是每个阈值都查一遍库：每次 similaritySearch
 * 都要调一次 embedding，6 个阈值 × 44 条就是 264 次模型调用；拿回 top-10 的分数在内存里
 * 过一遍，等价且快一个数量级。
 *
 * <p> 表里要看的两个数：<b>召回</b>（期望的节有没有进 topK）和<b>否定样本的误召回</b>
 * （知识库里没有的问题，检索是不是也硬塞了几段给模型）。阈值就是在两者之间取平衡 ——
 * 这个数写进了 {@code application.yml} 的 {@code similarity-threshold}。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "rag.eval", matches = "true")
class RagRetrievalEvalTest {

    /** 与线上一致：给模型的就是 top-4 */
    private static final int TOP_K = 4;

    /** 扫描时多取几条：top-4 之外的分数也看一下，否则阈值调高后的召回变化看不出来 */
    private static final int SCAN_K = 10;

    private static final double[] THRESHOLDS = {0.0, 0.2, 0.3, 0.4, 0.5, 0.6};

    /**
     * 召回率下限。定的比实测值松一档：这个数的作用是「防止某次改动把检索悄悄改坏」，
     * 不是刷分 —— 定太紧会因为一次模型版本升级就红，然后被人加 @Disabled。
     */
    private static final double MIN_RECALL_AT_4 = 0.80;

    private static final Pattern PG_URL =
            Pattern.compile("jdbc:postgresql://([^:/]+):(\\d+)/");

    @Autowired
    private Environment environment;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    /**
     * 向量库不能在字段上直接 {@code @Autowired VectorStore}：bean 上的 {@code @Lazy}
     * 只推迟「没人用到它时的创建」，而字段注入就是「用到」，会在上下文装配阶段
     * 就去连 PG —— 那样探测端口、给出人话的失败原因这些事一件都轮不到。
     * 全项目消费方都用 ObjectProvider，这里是同一个道理。
     */
    @Autowired
    private ObjectProvider<VectorStore> vectorStoreProvider;

    private VectorStore vectorStore;

    private List<Retrieved> retrieved;

    record Retrieved(RagEvalSet.Case source, List<Document> docs) {
    }

    @BeforeAll
    void prepare() {
        String ollamaUrl = environment.getProperty("spring.ai.ollama.base-url", "http://localhost:11434");
        String embeddingModel = environment.getProperty("spring.ai.ollama.embedding.model", "bge-m3");
        assumeOrSkip(ollamaHasModel(ollamaUrl, embeddingModel),
                "Ollama（%s）里没有向量模型 %s。拉模型要走代理，见 README 2.5 节".formatted(ollamaUrl, embeddingModel));

        String jdbcUrl = environment.getProperty("chargeops.ai-ops.vector-store.url", "");
        Matcher m = PG_URL.matcher(jdbcUrl);
        assumeOrSkip(m.find() && portOpen(m.group(1), Integer.parseInt(m.group(2))),
                "pgvector 连不上（%s）。先 docker compose up -d pgvector".formatted(jdbcUrl));

        // 两次探测都过了才碰向量库：bean 在这里才真正创建、才去连 PG
        this.vectorStore = vectorStoreProvider.getIfAvailable();
        assertThat(vectorStore).as("spring.ai.model.embedding 不是 ollama，向量库 bean 不存在").isNotNull();

        int chunks = knowledgeBaseService.reload();
        assertThat(chunks).as("语料一个片段都没灌进去，后面的指标没有意义").isGreaterThan(0);
        System.out.printf("%n评估语料就绪：%d 个片段，%s%n", chunks, LocalDateTime.now());

        List<Retrieved> all = new ArrayList<>();
        for (RagEvalSet.Case c : RagEvalSet.load().cases()) {
            all.add(new Retrieved(c, search(c.question())));
        }
        this.retrieved = all;
    }

    @Test
    @DisplayName("输出阈值扫描表：召回、MRR 与否定样本误召回")
    void reportRetrievalQuality() {
        System.out.printf("%n%-8s %-10s %-10s %-8s %-14s%n", "阈值", "recall@1", "recall@4", "MRR", "否定样本误召回");

        double recallAt4WhenNoThreshold = 0;
        for (double threshold : THRESHOLDS) {
            int total = 0;
            int hit1 = 0;
            int hitK = 0;
            double mrr = 0;
            int negativeTotal = 0;
            int negativeHit = 0;

            for (Retrieved r : retrieved) {
                List<Document> kept = keep(r.docs(), threshold);
                if (RagEvalSet.isNegative(r.source())) {
                    negativeTotal++;
                    if (!kept.isEmpty()) {
                        negativeHit++;
                    }
                    continue;
                }
                // 追问组单独统计：它考的是查询改写，不是向量检索本身
                if (RagEvalSet.isFollowUp(r.source())) {
                    continue;
                }
                total++;
                int rank = firstMatchRank(kept, r.source());
                if (rank == 1) {
                    hit1++;
                }
                if (rank > 0) {
                    hitK++;
                    mrr += 1.0 / rank;
                }
            }

            double recall1 = (double) hit1 / total;
            double recallK = (double) hitK / total;
            System.out.printf("%-8.2f %-10.3f %-10.3f %-8.3f %d/%d%n",
                    threshold, recall1, recallK, mrr / total, negativeHit, negativeTotal);

            if (threshold == 0.0) {
                recallAt4WhenNoThreshold = recallK;
            }
        }

        reportScores();
        reportFollowUpBaseline();
        reportMisses();

        assertThat(recallAt4WhenNoThreshold)
                .as("recall@4 掉到 %.2f 以下，先看上面的漏召清单：是语料/切片变了，还是 topK 不够",
                        MIN_RECALL_AT_4)
                .isGreaterThanOrEqualTo(MIN_RECALL_AT_4);
    }

    /** 命中条目的分数分布 vs 否定样本的最高分：阈值该落在这两个区间中间 */
    private void reportScores() {
        List<Double> hitScores = new ArrayList<>();
        List<Double> negativeScores = new ArrayList<>();

        for (Retrieved r : retrieved) {
            if (RagEvalSet.isFollowUp(r.source())) {
                continue;
            }
            if (RagEvalSet.isNegative(r.source())) {
                r.docs().stream().map(RagRetrievalEvalTest::scoreOf)
                        .filter(s -> s != null).max(Double::compareTo).ifPresent(negativeScores::add);
                continue;
            }
            r.docs().stream().filter(d -> matches(d, r.source()))
                    .map(RagRetrievalEvalTest::scoreOf).findFirst().ifPresent(hitScores::add);
        }

        System.out.printf("%n分数分布（阈值两端）：%n");
        System.out.printf("  命中条目的首个匹配分数  min=%.3f 中位=%.3f max=%.3f（n=%d）%n",
                min(hitScores), median(hitScores), max(hitScores), hitScores.size());
        System.out.printf("  否定样本的最高分        min=%.3f 中位=%.3f max=%.3f（n=%d）%n",
                min(negativeScores), median(negativeScores), max(negativeScores), negativeScores.size());
    }

    /** 追问组不改写时的召回（对照组的「前」），改写后的数字在 QueryRewriter 落地后补 */
    private void reportFollowUpBaseline() {
        int total = 0;
        int hit = 0;
        for (Retrieved r : retrieved) {
            if (!RagEvalSet.isFollowUp(r.source())) {
                continue;
            }
            total++;
            if (firstMatchRank(keep(r.docs(), 0.0), r.source()) > 0) {
                hit++;
            }
        }
        if (total > 0) {
            System.out.printf("追问组（原问题直接检索）：recall@4 = %d/%d%n", hit, total);
        }
    }

    private void reportMisses() {
        List<String> misses = new ArrayList<>();
        for (Retrieved r : retrieved) {
            if (RagEvalSet.isNegative(r.source()) || RagEvalSet.isFollowUp(r.source())) {
                continue;
            }
            if (firstMatchRank(keep(r.docs(), 0.0), r.source()) == 0) {
                misses.add("%s（%s）".formatted(r.source().id(), r.source().question()));
            }
        }
        System.out.printf("阈值 0 处的漏召：%s%n%n", misses.isEmpty() ? "无" : String.join("、", misses));
    }

    /**
     * 环境不满足时跳过，并且把原因显式打在控制台上。
     *
     * <p> {@code assumeTrue} 的 message 在 {@code @BeforeAll} 里跳过时 surefire 不打印，
     * 报告长这样：「Tests run: 0, BUILD SUCCESS」—— 看起来像评估跑过了、还全绿。
     * 主动打一行，跳过才有可读的理由。
     */
    private static void assumeOrSkip(boolean condition, String reason) {
        if (!condition) {
            System.out.println("【跳过检索评估】" + reason);
            assumeTrue(false, reason);
        }
    }

    private List<Document> search(String query) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(SCAN_K)
                .similarityThreshold(0.0)
                .build());
    }

    private static List<Document> keep(List<Document> docs, double threshold) {
        return docs.stream()
                .filter(d -> {
                    Double score = scoreOf(d);
                    return score != null && score >= threshold;
                })
                .limit(TOP_K)
                .toList();
    }

    /** 第一个命中的名次，1 起；没命中是 0 */
    private static int firstMatchRank(List<Document> docs, RagEvalSet.Case c) {
        for (int i = 0; i < docs.size(); i++) {
            if (matches(docs.get(i), c)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean matches(Document doc, RagEvalSet.Case c) {
        Object source = doc.getMetadata().get("source");
        Object section = doc.getMetadata().get("section");
        return c.expect().stream()
                .anyMatch(e -> e.source().equals(source) && e.section().equals(section));
    }

    private static Double scoreOf(Document doc) {
        return doc.getScore();
    }

    private static double min(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
    }

    private static double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get(sorted.size() / 2);
    }

    private static boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean ollamaHasModel(String baseUrl, String model) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(500)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
            // /api/tags 里模型名形如 "bge-m3:latest"，前缀匹配即可
            return body.contains("\"" + model);
        } catch (Exception e) {
            return false;
        }
    }
}
