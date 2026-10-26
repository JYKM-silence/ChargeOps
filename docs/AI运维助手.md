# AI 运维助手（P2）

> 从「运维翻三份文档对故障码」到「直接问一句」。这里写清三件事：RAG 的切片与检索、Tool Calling 的工具边界、以及「Agent 编排」到底在哪。

充电桩运维原来靠人翻手册、对故障码，一个「E-021 通信中断」要查三份文档。这一节做的事是
让运维直接问：「7 号桩通信中断告警怎么处理」，由助手去查实时状态、查历史工单、查手册，
把结论和处理步骤一次性给出来。

对话模型是本机的 **Ollama + `qwen3:8b`**，向量模型是 `bge-m3`，**零 API key**。

### 三个能力落在哪

| 能力 | 在代码里的位置 | 做了什么 |
|---|---|---|
| **RAG 知识库问答** | `KnowledgeBaseServiceImpl` + `AiOpsServiceImpl.systemPrompt` | 三份 markdown 按 `## ` 切片 → `bge-m3` 向量化 → 存 pgvector。提问时先检索 topK 片段，拼进系统提示词再生成 |
| **Tool Calling** | [`ai/OpsTools.java`](../src/main/java/com/chargeops/ai/OpsTools.java) | 4 个 `@Tool` 方法，模型自己决定调哪个、参数填什么 |
| **Agent 编排** | `AiOpsServiceImpl.chat` | 见下方「Agent 到底在哪里」 |

**4 个工具**：

| 工具 | 回答什么问题 |
|---|---|
| `getPileStatus(pileId)` | 这台桩现在什么状态？每把枪呢？最后心跳是什么时候？ |
| `getRecentAlarms(pileId)` | 最近报过什么警？哪个故障码？恢复了没？ |
| `findSimilarResolvedTickets(alarmCode)` | 这个故障码以前是怎么修的？（**只取已完成的工单**） |
| `createMaintenanceTicket(chargerId, title, content, alarmId)` | 派个单给运维 |

### Agent 到底在哪里

**这里没有硬编码的编排代码**，`chat()` 里也不存在「先查状态、再查告警、最后建单」这样的流程。
`AiOpsServiceImpl.chat` 只做三件事：检索知识库 → 把片段拼进系统提示词 → 发一次
`chatClient.prompt().tools(opsTools)`。

**多步是模型自己走的**：它看到问题后决定先调 `getRecentAlarms` 拿故障码，拿到之后再调
`findSimilarResolvedTickets` 查历史，觉得信息够了就组织答案，判断需要派人时才调
`createMaintenanceTicket`。Spring AI 负责的是工具调用的**协议**（把 `@Tool` 方法的签名
翻译成模型能懂的 schema、解析它吐回来的调用请求、执行、把结果塞回对话再问一轮）。

所以「Agent 编排」在这个项目里的正确说法是：**编排是模型的行为，不是我们的代码**。
我们的代码负责的是**把工具设计好** —— 边界清楚、坏输入不抛异常、返回值模型读得懂
（见决策 24）。这也是为什么 `OpsTools` 里有一半的代码在处理「模型编了个不存在的 id」。

一次**典型**的调用链（问「1 号桩通信中断告警怎么处理」；实际顺序和调不调某个工具由模型决定）：

```
模型 → getRecentAlarms(1)              → 拿到 E-021，未恢复
     → findSimilarResolvedTickets("E-021") → 拿到 3 条历史工单的处理结论
     ← 知识库片段里 E-021 的处理步骤（检索发生在第一轮之前）
     → 组织答案；如果用户说「派单」 → createMaintenanceTicket(...)
```

### 5 个接口

```bash
# 提问（多轮：把上一轮返回的 sessionId 带回来，就记得上下文）
curl -X POST localhost:8081/api/admin/ai-ops/chat \
  -H 'token: <管理端 token>' -H 'Content-Type: application/json' \
  -d '{"question":"1号桩通信中断告警怎么处理"}'

# 只检索、不调模型 —— 排查「知识库没内容」还是「模型没用好内容」
curl 'localhost:8081/api/admin/ai-ops/knowledge/search?query=E-021' -H 'token: <token>'

# 改完 knowledge/ 下的 markdown 后重建索引，不用重启（幂等）
curl -X POST localhost:8081/api/admin/ai-ops/knowledge/reload -H 'token: <token>'

# 工单列表 / 改状态
curl 'localhost:8081/api/admin/maintenance-ticket/page?status=0' -H 'token: <token>'
curl -X PUT localhost:8081/api/admin/maintenance-ticket/1/status \
  -H 'token: <token>' -H 'Content-Type: application/json' -d '{"status":2}'
```

`chat` 是**同步**的，一次请求要等模型多轮往返，耗时按秒计 —— 读超时配在
`spring.http.clients.read-timeout`，那里给的 60 秒就是上限。流式（SSE）在 P2.5。

### 两条值得知道的实现约束

- **RAG 是手写的，不是 `QuestionAnswerAdvisor`。** 那个类在 Spring AI 2.0 里**不存在**。
  手写之后反而多了一个排查接口（只检索不生成）。见决策 20。
- **`OpsTools` 上不能加 `@Transactional` 之类的注解。** `.tools(bean)` 不做 AOP 方法解析，
  代理之后 `@Tool` 注解读不到，失败方式是**工具一个都不注册、且日志里什么都没有**。见决策 21。

### 追问改写（已实现）

**「那第二点具体怎么做」这类问题，整句向量化后召回为空** —— 句子里没有任何实词。
修法是先做一次**查询改写**：过一道指代启发式闸门（出现「那 / 它 / 第二点 / 呢」这类特征词
才值得多花一次模型调用），命中后用**独立的 `ChatClient`** 把问题补全成
「E-021 的第二步具体怎么做」，**只用于检索**；真正发给对话模型的仍是用户原话。

为什么要独立 `ChatClient`：挂记忆 advisor 的那个走一遍「系统提示 + 历史 + 改写指令 → 结果」，
这次往返会被写进会话历史 —— 用户下一轮会看到一段自己没说过的话，而改写指令本身是元语言，
模型很容易把它当成业务内容接着聊。

任何一步失败都退回原问题：改写是锦上添花，不该让一次本来能回答的问答变成报错。
评估集里专门有一组「追问」样本量这件事（`RagRetrievalEvalTest`，默认不进 `mvn test`）。

---
