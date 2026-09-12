# 知答：通用学习与研究 Agent 开发 Spec

> 文档状态：已确认，可执行  
> Spec 版本：v1.0  
> 项目目录：`.`  
> 参考项目：`_reference/spring-ai-alibaba-examples/spring-ai-alibaba-agent-example/playground-flight-booking`  
> 协作方式：Codex 基于官方 GitHub 项目生成、修改并验证代码；用户通过运行、阅读和提问掌握实现；Codex 负责逐模块讲解和面试追问。

## 1. 产品定义

“知答”是面向学生和自学者的通用学习与研究 Agent。用户给出一个问题，系统能够理解目标、拆分子问题、补充遗漏角度、按需查询公开网页或本地资料，最终给出带来源、可执行建议和不确定性说明的答案。

项目不是“套一层大模型接口”的聊天机器人。它必须具备可观察的任务计划、真实工具调用、执行状态、失败处理、来源引用和用户可控的记忆。

### 1.1 一句话价值

把用户的模糊问题变成一份经过规划、检索、阅读和整理的可靠学习结果。

### 1.2 目标用户

- 学习编程、语言、考试和课程知识的学生。
- 需要比较方案、制定学习计划或分析资料的自学者。
- 首版重点保证中文体验，同时允许处理英文资料。

### 1.3 典型问题

- “为什么秒杀使用 Redis + Lua，直接操作 MySQL 不行吗？”
- “Spring AI 和 LangChain4j 有什么区别，我应该先学哪个？”
- “我只有一个月，怎么准备 Java 后端实习？”
- “请读取我上传的课程 PDF，整理重点并给出复习计划。”
- “比较几篇公开资料的观点，并指出它们的共同点和分歧。”

## 2. 已确认的产品决策

| 决策项 | 结论 |
| --- | --- |
| 产品范围 | 通用学习与研究，不局限于计算机技术 |
| 核心架构 | 模块化单体 Spring Boot + 单个 ReAct Agent |
| Agent 流程 | 显式的“规划 → 工具执行 → 答案整理” |
| 首版工具 | 联网搜索、网页正文读取、本地文档检索 |
| 过程展示 | 展示计划、进度、搜索词、工具、来源、阶段发现和错误 |
| 思维展示 | 不展示模型原始隐藏思维，只展示可核对的计划和执行事实 |
| 自动执行 | 读取类操作自动进行；费用、隐私和外部写操作必须确认 |
| 记忆 | 当前会话自动记忆；长期记忆由用户主动开启并可删除 |
| 交付方式 | 本地可运行，并能部署到公网供面试官体验 |
| 开发方式 | 小步实现、真实测试、用户能亲自解释每个核心模块 |

## 3. 范围与非目标

### 3.1 MVP 必须完成

1. 创建会话并连续提问。
2. 将问题分类并生成结构化任务计划。
3. 使用单个 Agent 自主选择搜索、网页读取或知识库检索工具。
4. 通过 SSE 推送计划、进度、工具调用、来源和答案片段。
5. 对搜索结果提供可点击引用，并区分事实、推断和建议。
6. 上传 PDF、TXT、Markdown，完成切片、向量化和检索问答。
7. MySQL 保存会话、消息、任务、步骤、工具调用和来源。
8. 支持当前会话记忆；长期记忆默认关闭。
9. 具有超时、最大调用次数、重试、失败降级和取消任务能力。
10. 具有自动化测试、环境变量示例、启动文档和 Docker Compose。

### 3.2 MVP 不做

- 不做多 Agent、Supervisor、A2A 或复杂 Graph 编排。
- 不做微服务、服务注册中心或分布式事务。
- 不执行任意本地命令和用户提交的代码。
- 不自动发邮件、发消息、下单、付款或修改外部账号。
- 不承诺医疗、法律、投资问题的最终正确性。
- 不把 Redis、RocketMQ、MCP 仅作为简历关键词强行加入。
- 不在首版支持图片、音频、视频和复杂扫描件 OCR。

## 4. 成功标准

### 4.1 产品验收

- 用户提交问题后，2 秒内收到 `task.started` 或 `plan.created` 事件。
- 页面能够实时显示任务计划、当前步骤和工具状态。
- 需要最新信息时，最终答案至少包含 2 个有效来源；不足时必须说明。
- 文档问答的引用能够定位到文档名称和切片/页码元数据。
- 工具失败时任务不能静默卡死，应重试、降级或明确失败原因。
- 同一会话追问能够使用前文；新会话默认不读取其他会话内容。
- 用户关闭或删除长期记忆后，后续请求不再注入相应内容。

### 4.2 工程验收

- `mvn test` 全部通过。
- 核心应用服务、工具适配器和权限边界有单元测试。
- Agent 集成测试使用 Fake ChatModel/Fake Tool，不依赖真实付费 API。
- 配置中不存在明文 API Key；仓库包含 `.env.example`。
- `docker compose up` 能启动应用和 MySQL。
- README 包含环境要求、启动命令、配置说明、演示问题和故障排查。

## 5. 总体架构

```text
Vue Web
  │ POST /api/v1/tasks
  │ GET  /api/v1/tasks/{taskId}/events (SSE)
  ▼
API 层
  ▼
ResearchOrchestrator
  ├─ QuestionClassifier  识别解释、比较、规划、研究、文档问答
  ├─ QuestionPlanner     生成结构化任务计划
  ├─ ResearchAgent       ReAct 循环并选择只读工具
  ├─ AnswerComposer      整理答案、引用和不确定性
  └─ EventPublisher      发布可观察事件
       │
       ├─ WebSearchTool ───── TavilySearchProvider
       ├─ WebReaderTool ───── JsoupWebReader
       └─ KnowledgeTool ───── VectorStoreRetriever

MySQL：用户、会话、消息、任务、步骤、工具调用、来源、文档元数据
文件目录：用户上传的原始文档
SimpleVectorStore：MVP 本地演示；部署版后续迁移到持久化向量数据库
```

### 5.1 为什么选择模块化单体

- 可以清楚练习 Controller、Service、Repository、事务和测试。
- 调试与部署成本低，适合单人学习项目。
- 通过包边界和接口保留未来拆分能力。
- Agent、搜索、RAG 和存储仍然可以独立替换，不需要先上微服务。

### 5.2 为什么先使用单 Agent

- 一个 ReAct Agent 已经能够完成“判断 → 调工具 → 观察结果 → 继续处理”。
- 多 Agent 会额外引入路由、上下文传递、并发、成本和结果合并问题。
- 当单 Agent 的评测证明存在稳定瓶颈时，再考虑 Graph 或专门子 Agent。

## 6. 推荐技术栈

| 类别 | 首选 |
| --- | --- |
| Java | JDK 17 |
| Web 框架 | Spring Boot 3.5.x |
| Agent | Spring AI Alibaba Agent Framework 1.1.2.2 |
| Spring AI | 1.1.2，由 BOM 管理 |
| 模型 | DeepSeek，通过 Spring AI `ChatModel` 抽象（官方 DeepSeek starter） |
| 数据访问 | MyBatis-Plus + MySQL 8 |
| 流式输出 | Spring WebFlux SSE |
| 文档解析 | Spring AI DocumentReader；PDFBox/Tika 按需要补充 |
| 向量存储 | MVP：SimpleVectorStore；部署升级：Redis Vector 或 PgVector |
| 搜索 | `SearchProvider` 接口；首个真实适配器为 Tavily REST API |
| 网页读取 | Jsoup，限制正文长度并过滤脚本/样式 |
| 前端 | Vue 3 + TypeScript + Vite |
| 测试 | JUnit 5、Mockito、Spring Boot Test、Testcontainers（后期） |
| 部署 | Docker、Docker Compose、Nginx（公网阶段） |

版本在创建 `pom.xml` 时必须再次核对官方 BOM；不得混用参考项目中已经过时的版本。

## 7. 后端模块与包结构

```text
src/main/java/com/zhida/agent
├── ZhidaAgentApplication.java
├── api
│   ├── auth
│   ├── chat
│   ├── document
│   ├── memory
│   └── task
├── application
│   ├── ConversationService.java
│   ├── ResearchOrchestrator.java
│   ├── DocumentApplicationService.java
│   └── MemoryApplicationService.java
├── agent
│   ├── classifier
│   ├── planner
│   ├── executor
│   ├── composer
│   └── model
├── tool
│   ├── search
│   ├── web
│   ├── knowledge
│   └── common
├── rag
│   ├── loader
│   ├── splitter
│   ├── embedding
│   └── retriever
├── domain
│   ├── conversation
│   ├── research
│   ├── document
│   └── memory
├── infrastructure
│   ├── persistence
│   ├── model
│   ├── search
│   ├── web
│   ├── vector
│   └── storage
└── common
    ├── config
    ├── exception
    ├── response
    └── security
```

约束：`domain` 不依赖 Controller、数据库实现或第三方搜索 SDK；外部能力通过接口注入。

## 8. Agent 执行模型

### 8.1 任务状态

```text
RECEIVED
  → PLANNING
  → EXECUTING
  → SYNTHESIZING
  → COMPLETED

任意阶段 → FAILED
涉及敏感写操作 → WAITING_CONFIRMATION → EXECUTING / CANCELLED
用户主动取消 → CANCELLED
```

### 8.2 规划结果

`QuestionPlanner` 必须返回结构化对象，不允许依赖随意文本解析：

```java
public record ResearchPlan(
        String taskType,
        String interpretedGoal,
        List<String> assumptions,
        List<ResearchStep> steps,
        List<String> expectedOutputSections,
        boolean needsFreshInformation,
        boolean needsKnowledgeBase) {}
```

`ResearchStep` 至少包含：`stepId`、`title`、`goal`、`status`、`suggestedTools`。

### 8.3 默认执行规则

- 模糊但不影响总体方向：记录合理假设并继续。
- 歧义会导致完全不同结果：最多提出 1 次澄清问题。
- 搜索、网页读取、知识库检索属于只读工具，自动执行。
- 任何写操作必须带风险等级，并在执行前检查确认令牌。
- 首版不注册任何外部写工具。
- 每个任务最大 Agent 步数：8。
- 最大搜索查询数：4。
- 最大读取网页数：6。
- 单工具默认超时：15 秒。
- 整体任务默认超时：90 秒。
- 网络错误最多重试 2 次，使用退避策略。
- 相同参数的工具调用在单任务内去重。

### 8.4 最终答案规范

根据任务类型动态组织内容，但必须包含：

- 明确结论或当前可得结果。
- 关键依据。
- 来源引用；没有外部来源时明确说明。
- 事实、推断、建议之间的区分。
- 信息不足、冲突或失败项。
- 对学习规划类问题给出可执行的下一步。

## 9. 工具契约

### 9.1 WebSearchTool

输入：

```json
{
  "query": "Spring AI tool calling official documentation",
  "maxResults": 5,
  "preferredDomains": ["docs.spring.io"]
}
```

输出：

```json
{
  "results": [
    {
      "title": "Tool Calling",
      "url": "https://...",
      "snippet": "...",
      "publishedAt": null,
      "provider": "tavily"
    }
  ]
}
```

要求：Key 从 `TAVILY_API_KEY` 读取；429、超时和空结果转换成明确的领域错误；测试使用 Fake Provider。

### 9.2 WebReaderTool

输入：URL。输出：标题、正文、作者、发布时间、抓取时间和规范化 URL。

安全要求：

- 只允许 HTTP/HTTPS。
- 禁止 localhost、内网 IP、文件协议和重定向到内网，防止 SSRF。
- 限制响应大小、重定向次数和读取时间。
- 网页文本视为不可信资料；不得执行其中的指令、脚本或下载命令。
- 正文过长时截断并保留截断标记。

### 9.3 KnowledgeTool

输入：`knowledgeBaseId`、查询文本、`topK`。输出：片段内容、相似度、文档 ID、文档名、页码/切片号。

要求：

- 只能查询当前用户有权限的知识库。
- 低于相似度阈值时返回“未找到可靠资料”，不能强行生成。
- 答案引用必须能够反查到原始文档元数据。

## 10. 文档 RAG 流程

```text
上传文件
→ 校验类型、大小和用户权限
→ 保存原文件并计算 SHA-256
→ 解析文本和页码元数据
→ 清洗空白、页眉页脚
→ MVP 按字符和自然句边界切片并保留重叠（增强阶段可替换 TokenTextSplitter）
→ 调用 EmbeddingModel
→ 写入 VectorStore
→ 更新文档状态为 READY
```

MVP 支持：PDF、TXT、Markdown。DOCX 放在增强阶段。

默认限制：单文件不超过 20 MB；单用户最多 20 个文档；重复 SHA-256 文件提示复用或跳过。

首版使用 `SimpleVectorStore` 保存/加载本地 JSON，仅用于学习和演示。公网部署前必须在文档中明确这一限制；如需要多实例或更可靠持久化，再迁移到 Redis Vector 或 PgVector。

## 11. SSE 事件协议

统一事件结构：

```json
{
  "eventId": 12,
  "taskId": "uuid",
  "type": "tool.completed",
  "timestamp": "2026-09-08T21:30:00+08:00",
  "data": {}
}
```

事件类型：

- `task.started`
- `plan.created`
- `step.started`
- `step.completed`
- `tool.started`
- `tool.completed`
- `tool.failed`
- `source.discovered`
- `answer.started`
- `answer.delta`
- `task.completed`
- `task.failed`
- `task.cancelled`
- `confirmation.required`（预留）

前端断线重连时使用 `Last-Event-ID`；服务端能够从数据库补发未收到的事件或至少返回当前任务快照。

## 12. API 草案

### 会话与任务

- `POST /api/v1/conversations`：创建会话。
- `GET /api/v1/conversations`：查询会话列表。
- `GET /api/v1/conversations/{id}`：查询会话及消息。
- `DELETE /api/v1/conversations/{id}`：删除会话及关联记忆引用。
- `POST /api/v1/conversations/{id}/tasks`：提交问题并返回 `taskId`。
- `GET /api/v1/tasks/{taskId}`：查询任务快照。
- `GET /api/v1/tasks/{taskId}/events`：SSE 事件流。
- `POST /api/v1/tasks/{taskId}/cancel`：取消任务。

### 文档与知识库

- `POST /api/v1/knowledge-bases`：创建知识库。
- `POST /api/v1/knowledge-bases/{id}/documents`：上传文档。
- `GET /api/v1/documents/{id}`：查询解析状态。
- `DELETE /api/v1/documents/{id}`：删除文档及对应向量。

### 记忆

- `GET /api/v1/memories/settings`：查询长期记忆开关。
- `PUT /api/v1/memories/settings`：更新长期记忆设置。
- `GET /api/v1/memories`：查看已保存记忆。
- `PUT /api/v1/memories/{id}`：修改记忆。
- `DELETE /api/v1/memories/{id}`：删除记忆。

### 后期认证

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`

## 13. 数据模型草案

| 表 | 关键字段 |
| --- | --- |
| `user_account` | id, username, password_hash, status, created_at |
| `conversation` | id, user_id, title, created_at, updated_at |
| `chat_message` | id, conversation_id, role, content, created_at |
| `conversation_summary` | conversation_id, content, covered_at, updated_at |
| `research_task` | id, conversation_id, question, task_type, status, error_code, started_at, finished_at |
| `research_step` | id, task_id, step_no, title, goal, status, result_summary |
| `tool_call` | id, task_id, step_id, tool_name, arguments_json, result_summary, status, duration_ms |
| `source_reference` | id, task_id, title, url, source_type, published_at, retrieved_at |
| `knowledge_base` | id, user_id, name, created_at |
| `document` | id, knowledge_base_id, file_name, storage_path, sha256, mime_type, status, error_message |
| `user_memory` | id, user_id, memory_type, content, enabled, created_at, updated_at |
| `task_memory_usage` | task_id, memory_id, created_at；只保存引用关系，不保存记忆内容副本 |
| `task_event` | id, task_id, sequence_no, event_type, payload_json, created_at |

所有业务表使用逻辑删除时必须说明恢复策略；文档物理文件删除与数据库事务不一致时，需要补偿任务或可重试状态。

## 14. 记忆设计

### 14.1 当前会话记忆

- 默认启用。
- 只读取当前 `conversation_id` 的消息。
- 超过上下文窗口时先摘要旧消息，不无限拼接。

### 14.2 长期记忆

- 默认关闭，用户主动开启。
- 只保存稳定且能改善回答的信息，例如学习目标、水平和表达偏好。
- 不自动保存密码、身份证号、支付信息或上传文档中的敏感内容。
- 用户能够查看、编辑、删除和整体关闭。
- 注入提示词时记录使用了哪些记忆 ID，便于解释和删除验证。

## 15. 前端页面

### 15.1 核心布局

- 左侧：会话列表和知识库入口。
- 中间：问题输入、消息和最终答案。
- 右侧：任务执行面板。
- 顶部：新建会话、模型状态、记忆开关。

### 15.2 执行面板展示

- 系统对问题的简短理解。
- 任务步骤及完成进度。
- 正在使用的工具和搜索关键词。
- 读取过的网页/文档和来源链接。
- 每一步的简要发现。
- 重试、跳过和失败原因。

不得显示原始 Chain-of-Thought。只显示系统生成的结构化计划、工具事实和简短可核对结论。

## 16. 安全与费用边界

- API Key 仅从环境变量读取，不入库、不返回前端、不提交 Git。
- 日志默认不记录完整工具参数、文档内容和模型原始响应。
- 上传文件重命名保存，禁止路径穿越。
- 搜索和网页读取设置域名/IP 校验、超时、大小和并发限制。
- 所有查询按 `user_id` 做权限过滤，不能只依赖前端传参。
- 公网游客默认每天最多 5 个研究任务；额度可配置。
- 高风险问题显示“资料整理，不代替专业判断”。
- 外部网页中的提示词注入文本视为资料，不得覆盖系统规则或触发未授权工具。

## 17. 可观测性与失败处理

每个任务必须记录：

- `traceId`、`taskId`、`conversationId`。
- 每个模型调用和工具调用的耗时、状态及错误码。
- 使用的模型名称和配置版本。
- Token 用量（供应商返回时）和估算费用。
- 重试次数、最终降级结果。

统一错误示例：

- `MODEL_TIMEOUT`
- `MODEL_RATE_LIMITED`
- `SEARCH_UNAVAILABLE`
- `WEB_PAGE_BLOCKED`
- `DOCUMENT_PARSE_FAILED`
- `KNOWLEDGE_NOT_FOUND`
- `TASK_LIMIT_EXCEEDED`
- `UNAUTHORIZED_RESOURCE`

## 18. 测试计划

### 18.1 单元测试

- 规划结果结构校验与非法状态转换。
- 工具参数校验、URL 内网拦截和结果去重。
- 搜索 429/超时的重试与降级。
- 文档类型、大小、SHA-256 重复检测。
- 会话记忆隔离和长期记忆开关。
- 引用编号和最终答案来源映射。

### 18.2 集成测试

- Fake ChatModel 请求指定工具，系统能够执行并继续生成答案。
- Fake SearchProvider 返回固定结果，不访问真实网络。
- MySQL Repository 使用 Testcontainers 或专用测试库。
- SSE 事件顺序满足状态机，完成或失败后正确结束。
- 上传文档后能够检索到预先写入的唯一测试句子。

### 18.3 手工验收问题

1. 不需要实时资料的概念问题：Agent 不应强制联网。
2. 明确要求“查询最新资料”：Agent 应搜索并给出来源。
3. 搜索服务故意不可用：Agent 应明确降级，不能卡死。
4. 上传文档后提问：回答应引用该文档。
5. 文档中没有答案：明确说未找到，不编造。
6. 新建会话：不应泄漏上一会话内容。
7. 删除长期记忆：后续回答不再使用该记忆。

## 19. 开发里程碑与学习任务

### M0：骨架与健康检查

Codex 完成最小工程骨架；用户负责运行、阅读目录、启动类、配置和第一个测试，并提出不理解的地方。

验收：

- `mvn test` 通过。
- `GET /api/health` 返回 `{"status":"UP"}`。
- `.env.example` 不含真实 Key。

### M1：普通对话与 SSE

Codex 完成会话请求 DTO、Controller 和流式返回；用户负责运行并理解请求到 SSE 响应的主链路。

验收：页面能够接收逐段答案；断开后服务端能结束资源。

### M2：问题分类与结构化计划

Codex 实现 `ResearchPlan`、Planner 接口和计划事件；用户负责验证不同问题的计划是否合理。

验收：五类典型问题都能生成合法 JSON 计划；解析失败有一次修复重试。

### M3：搜索与网页工具

Codex 实现 `SearchProvider`、Tavily 适配器、Fake Provider 和 Jsoup Reader；用户负责运行真实问题并核对来源。

当前进度：已完成。真实 Tavily 搜索、网页读取、来源引用和工具调用事件均已验收。

验收：Agent 能根据问题自主搜索、读取页面并输出至少两个来源；URL 安全测试通过。

### M4：本地文档 RAG

Codex 完成上传校验、文档切片、向量写入和 KnowledgeTool；用户负责准备测试文档并核对检索结果。

当前进度：已完成 MVP。支持 PDF/TXT/Markdown、20MB 限制、SHA-256 去重、本地中文 BGE ONNX Embedding、`SimpleVectorStore` JSON 持久化、文档列表页面和 `knowledge_search` 工具。文档保存后返回 `PROCESSING`，后台完成解析和索引；支持状态查询、失败重试，以及服务重启后将遗留任务标记为 `FAILED`。真实 Markdown 与真实 PDF 上传均已验收；PDF 唯一句子在重启前后都能命中，并返回文件名、第 1 页和分块元数据。自动化测试使用真实 PDF 解析器验证目录恢复，实际 HTTP 验收覆盖本地 BGE 向量化与向量文件持久化。

验收：测试 PDF 的唯一句子可以被检索；无匹配时不编造。

### M5：MySQL 持久化和记忆

当前进度（2026-09-11）：已完成会话/消息持久化、每轮数据库上下文恢复和长对话增量摘要。成功问答超过 10 轮后，后台有界队列把较早完整问答交给 DeepSeek 合并摘要；下一轮使用“摘要 + 最近 10 轮 + 当前问题”。摘要失败回退到原消息窗口，可关闭，且摘要内容不提升为系统指令。真实数据库连接与表存在性已确认。M5 尚未全部完成。

新增进度：任务状态、计划/工具事件、用户主动保存与删除的长期记忆已完成；真实 DeepSeek 跨应用重启准确恢复测试代号，浏览器历史/任务回看已验收。可选 JWT 登录已经接入，双用户 HTTP 集成测试覆盖会话、任务、记忆和文档越权访问；默认关闭认证时仍使用 local-user。测试总数以当前 Surefire 报告为准。

Codex 设计并创建会话、消息、任务、工具和记忆表，实现 Repository；用户负责理解表之间的关系并运行验证。

验收：重启应用后会话仍存在；两个测试用户的数据互相不可见。

### M6：限制、重试与取消

当前进度：已实现真实 Agent 90 秒总时限（可配置）、每任务 8 次工具调用预算、前端停止按钮、取消/异常任务终态，以及数据库模式 UUID requestId 重复提交返回 409。成功状态和完整答案原子保存；停止订阅后不允许后续工具调用，但已发出的同步外部请求仍可能等待自身超时。29 项测试通过。

待完成：有限且明确分类的外部服务重试、崩溃遗留 RUNNING 任务处理、用户级限流和额度。当前不自动重试整条 Agent 链路，不保证无数据库模式幂等，不标记 M6 全部完成。

Codex 实现任务预算、超时、幂等提交、取消和错误映射；用户负责运行失败场景并确认提示是否清楚。

验收：重复请求不创建重复任务；超限时返回明确错误；取消后停止后续工具调用。

### M7：登录与公网部署

Codex 完成 JWT 登录、游客额度、Docker Compose 和部署说明；用户负责保管部署凭据并完成最终账号操作。

验收：公网 HTTPS 可访问；未登录用户不能访问他人会话；API Key 不出现在前端包和 Git 历史中。

### M8：项目收尾

- 补齐 README、架构图、API 文档和演示录屏。
- 跑完整测试并记录结果。
- 整理简历描述，但只写已经真实完成且能够解释的功能。
- 完成至少一轮项目模拟面试。

## 20. 每轮协作约定

1. Codex 先说明本轮目标、核心概念和验收条件。
2. Codex 基于官方项目生成或修改一个边界清晰的功能模块。
3. 每轮必须运行与改动风险匹配的真实测试，不以“代码看起来正确”代替验证。
4. 用户负责运行、阅读和提出不理解的地方，不要求用户从零手敲全部代码。
5. Codex 在交付后逐模块讲解关键代码、设计原因和替代方案。
6. 每轮控制在用户能够消化的范围，避免一次堆入多个新框架。
7. 最后进行 2～5 个面试追问，确保用户能够解释已完成的功能。

## 21. 环境变量草案

```dotenv
DEEPSEEK_API_KEY=
TAVILY_API_KEY=

DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=zhida_agent
DB_USERNAME=zhida
DB_PASSWORD=

APP_UPLOAD_DIR=./data/uploads
APP_VECTOR_DIR=./data/vector
APP_GUEST_DAILY_LIMIT=5
APP_AGENT_MAX_STEPS=8
APP_TASK_TIMEOUT_SECONDS=90
```

## 22. 风险与控制

| 风险 | 控制方式 |
| --- | --- |
| 模型编造 | 强制来源、相似度阈值、信息不足提示 |
| 搜索结果质量不稳定 | 优先官方域名、去重、记录发布时间和抓取时间 |
| 工具无限循环 | 最大步骤、调用预算、整体超时 |
| API 费用失控 | 游客额度、用户限流、Token 与费用记录 |
| 网页提示词注入 | 网页仅作为不可信资料，不允许改变工具权限 |
| 文档越权 | 所有检索绑定 user_id 和 knowledge_base_id |
| 项目过于复杂 | 严格按 M0～M8 递增，未通过验收不提前加新框架 |
| 自己讲不清 | 用户亲手完成核心模块，每阶段进行面试复盘 |

## 23. 开始开发前的检查清单

- [ ] 安装并确认 JDK 17、Maven、Node.js 和 MySQL。
- [ ] 确认 `DEEPSEEK_API_KEY`；无 Key 时先使用演示模式或 Fake ChatModel。
- [ ] 确认 `TAVILY_API_KEY`；无 Key 时先使用 Fake SearchProvider。
- [ ] 创建实际项目源码目录，保留 `_reference` 只读参考。
- [ ] 使用 BOM 管理 Spring AI Alibaba/Spring AI 兼容版本。
- [ ] 创建 `.gitignore`，排除 `.env`、`data/`、`target/`、`node_modules/`。
- [ ] 完成 M0 后再进入 Agent 功能，不提前加入 Redis、MQ 或多 Agent。

## 24. Definition of Done

项目只有同时满足以下条件才算完成：

- 能从全新环境按 README 启动。
- 至少有一条普通问答、一条联网研究和一条文档问答演示链路。
- 页面完整展示计划、进度、工具、来源、错误和最终答案。
- 数据隔离、删除记忆、任务限制和工具失败都经过测试。
- 不包含真实密钥、个人隐私或无法说明来源的项目描述。
- 用户能够解释：为什么需要 Agent、ReAct 如何循环、工具怎样调用、RAG 怎样检索、SSE 怎样推送、失败怎样恢复以及为什么首版不用多 Agent。

## 25. 实施状态（2026-09-11 更新）

已实现登录/游客和用户隔离、多知识库切换、文档删除、记忆编辑与默认关闭开关、每日额度、网络短暂故障重试、任务取消接口、过期任务标记中断、任务状态查询及前端断线查询。会话删除已接入前端。认证关闭时仍仅支持本地单用户，不自动迁移旧数据。

新增回归覆盖主动取消、100 条以外历史任务查询、跨 owner 拒绝、额度扣减与事务回滚。具体执行结果以本次 Maven 报告为准。

文档异步处理已完成：使用有界线程池执行解析和向量化，状态为 `PROCESSING / READY / FAILED`；失败可重试，处理中拒绝删除，前端自动轮询状态。暂未引入 MQ，多实例部署时再评估可靠消息队列。

仍待完成，不视为整份 spec 已交付：

1. 费用换算与费用告警。长对话增量摘要、任务级记忆使用记录和真实模型 Token 记录已完成；Token 记录包含逐次调用、用户隔离、流式累计去重和历史展示，并已完成一次真实 DeepSeek 验收。
2. 公网出口隔离、代理下可信客户端识别、完整账户生命周期。
3. Docker/HTTPS 实机部署、备份恢复演练和公网真实联网验收。Dockerfile、Compose、Nginx、容器健康依赖和部署前检查脚本已经补齐；当前 Windows 未安装 Docker，因此尚未完成镜像构建与容器启动。真实 PDF 文档检索的本地端到端验收已经完成。
4. M8 演示材料与面试复盘。

部署模板与限制详见 DEPLOYMENT.md；模板存在不代表公网部署成功。
