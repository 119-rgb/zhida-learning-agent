# 知答：通用学习与研究 Agent

这是一个基于 Spring AI Alibaba 官方 `ReactAgent` 示例改造的 Java 学习与研究 Agent。

用户输入问题后，应用会先生成一份可检查的任务计划，再由 Agent 按需调用联网搜索、网页读取、本地知识库检索和日期工具，最后通过 SSE 流式输出回答。当前已实现 MySQL 会话、任务、工具事件和模型用量保存、上下文恢复、用户主动保存的长期记忆，以及可选的 JWT 登录与数据隔离。公网生产部署仍需完成安全与运维验收。

## 当前已实现

- Spring Boot 3.5 + Java 17 工程骨架。
- Spring AI Alibaba `ReactAgent` 接入。
- 未启用数据库时使用会话级 `MemorySaver`；启用后从数据库恢复“较早对话摘要 + 最近 10 轮”。
- 规则版问题分类与可视化任务计划。
- Tavily 搜索工具、网页正文读取工具、当前日期工具。
- PDF、TXT、Markdown 异步上传处理、SHA-256 去重、失败重试和文本切片。
- 本地中文 BGE ONNX Embedding，不需要额外的 Embedding API Key。
- `SimpleVectorStore` 向量检索及 JSON 文件持久化。
- `knowledge_search` Agent 工具，返回文件名、页码/片段号和相似度。
- POST + SSE 流式事件接口。
- 工具调用过程可视化：展示工具名称、参数、耗时、结果摘要和失败信息。
- 健康检查与页面状态会区分真实 Agent、演示模式和联网搜索是否可用。
- 最终答案和工具事件中的来源 URL 会转换为安全的可点击链接。
- SSE 事件编号严格按实际发送顺序递增。
- 无 API Key 也可启动的演示模式。
- 简单浏览器页面、健康检查和核心单元测试。
- 类 DeepSeek 的专注对话界面；分析过程使用小白语言展示，并保留可折叠的工具技术细节。
- 每个任务记录实际参考的长期记忆；历史详情可查看当前内容，记忆删除后只显示“已删除”，不保留内容副本。

## 环境要求

- JDK 17
- Maven 3.9+
- DeepSeek API Key（真实 Agent 模式）
- Tavily API Key（可选，仅联网搜索需要）
- 首次上传文档时可联网，以下载约 24MB 的中文向量模型及本地推理组件

## 直接启动（演示模式）

```powershell
cd "."
mvn spring-boot:run
```

访问：<http://localhost:8080>

健康检查：<http://localhost:8080/api/health>

演示模式会展示完整事件流程，但不会请求真实大模型。

## 启动真实 Agent

```powershell
$env:ZHIDA_AI_ENABLED="true"
$env:ZHIDA_SUMMARY_ENABLED="true"  # 可选，默认开启长对话摘要
$env:DEEPSEEK_API_KEY="你的DeepSeek Key"
$env:TAVILY_API_KEY="你的Tavily Key"   # 可选，联网搜索才需要
mvn spring-boot:run
```

模型默认使用 `deepseek-chat`，可通过 `DEEPSEEK_CHAT_MODEL` 环境变量切换（如 `deepseek-reasoner`）。

本地知识库的向量计算不使用 DeepSeek，也不需要新增 Key。模型首次下载后缓存在 `data/models/`；原文件和向量数据分别保存在 `data/uploads/`、`data/vector/`，这些目录已被 Git 忽略。

密钥只设置在环境变量中，不要写进 `application.yml`，也不要提交到 Git。

## 接口

### 健康检查

```http
GET /api/health
```

### 流式研究

```http
POST /api/v1/research/stream
Content-Type: application/json
Accept: text/event-stream

{
  "conversationId": "demo-001",
  "message": "Spring AI 和 LangChain4j 有什么区别？"
}
```

事件包括 `task.started`、`plan.created`、`step.started`、`answer.started`、`tool.started`、`tool.completed`、`tool.failed`、`answer.delta` 和 `task.completed`。没有调用工具时不会产生工具事件。

### 上传知识库文档

```http
POST /api/v1/knowledge-bases/default/documents
Content-Type: multipart/form-data

file=@你的文档.pdf
```

支持 PDF、TXT、Markdown，单文件不超过 20MB。接口保存原文件后返回 `202 Accepted` 和 `PROCESSING` 状态，后台线程继续解析、切片和建立向量索引；页面会自动轮询，直到变为 `READY` 或 `FAILED`。首次处理可能需要下载本地模型，但不会继续占用上传请求。

- `GET /api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}`：查询单个文档处理状态。
- `POST /api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/retry`：重新处理失败文档。
- 处理中不能删除；服务重启会把遗留的 `PROCESSING` 标记为 `FAILED`，由用户决定是否重试。

### 查看已上传文档

```http
GET /api/v1/knowledge-bases/default/documents
```

调试向量检索（不调用 DeepSeek）：

```http
GET /api/v1/knowledge-bases/default/search?q=你的问题&topK=5
```

当前 `default` 是单用户 MVP 知识库。`SimpleVectorStore` 适合本地学习演示，不适合多实例和生产环境，后续可替换为 PgVector 或 Redis Vector。

## M5：聊天记录持久化与上下文恢复（可选）

已接入 JDBC + HikariCP 和 MySQL 驱动。开启后，每次提问保存用户消息，回答成功后保存完整助手消息；侧栏可读取并打开历史记录。失败或中断的回答目前不会作为完整助手消息保存。数据库写入失败时不会发送成功完成事件。

由数据库管理员先创建独立的 `zhida_agent` 数据库（建议 utf8mb4），给应用账户该库的建表和读写权限，然后在启动终端设置：

```powershell
$env:ZHIDA_PERSISTENCE_ENABLED="true"
$env:DB_URL="jdbc:mysql://127.0.0.1:3306/zhida_agent?characterEncoding=UTF-8&serverTimezone=UTC"
$env:DB_USERNAME="zhida"
# DB_PASSWORD 在本机环境变量中设置，不要发送到聊天或提交到代码库。
mvn spring-boot:run
```

启动时幂等创建会话、消息和摘要等表。`.env.example` 只是配置示例，应用不会自动加载 `.env`。不开启此模块时无需数据库，原有演示和真实 Agent 功能仍可启动。

- `GET /api/v1/conversations`：最近 100 个会话。
- `GET /api/v1/conversations/{id}`：按顺序读取消息。
- 原有 `/api/v1/research/stream` 自动创建会话并保存成功消息。

开启持久化和真实 Agent 后，每次成功回答都会异步检查长对话。超过 10 轮时，系统把较早的完整问答发送给已配置的 DeepSeek 模型，合并成最多 1200 字的摘要并保存到 `conversation_summary`；下一次提问使用“较早摘要 + 最近最多 10 轮完整问答 + 当前问题”。摘要只是低权限上下文资料，不会作为系统指令。失败/中断留下的未回答问题不会进入摘要，摘要失败也不会影响正常回答。可设置 `ZHIDA_SUMMARY_ENABLED=false` 完全关闭摘要和相关模型请求。

最近 10 轮仍受 24000 个 Java 字符预算约束，单轮超过预算时会被排除。当前问题最多 8000 字符，不占历史预算；字符预算不是精确 Token 预算。摘要在回答完成后由单线程有界队列处理，不阻塞本次 SSE 返回；每次最多向摘要模型提供 16000 字新增旧对话，超长单轮保留首尾内容。

数据库模式不再使用 MemorySaver，每个任务独立运行，避免数据库历史与内存历史重复叠加；重启后通过数据库重新组装上下文。恢复的是用户与助手消息，不是中途工具执行现场，也不是跨会话长期记忆。

任务与工具记录：`research_task` 保存问题、状态、错误码与起止时间，`research_task_event` 保存计划与工具事件。成功状态与助手消息在同一事务中提交，终态不可再次覆盖。失败不保存半段助手消息；浏览器取消连接会异步标记 CANCELLED。进程崩溃来不及清理的 RUNNING 任务尚无自动恢复机制。

- `GET /api/v1/conversations/{id}/tasks`：最近 100 个任务。
- `GET /api/v1/conversations/{id}/tasks/{taskId}/events`：按顺序读取计划、工具调用参数摘要、结果摘要及耗时。
- `GET /api/v1/tasks/{id}/memories`：查看该任务实际参考过的记忆；只保存记忆 ID，删除记忆后不会继续暴露原内容。
- 历史会话底部“查看这些回答的执行记录”可展开回看。

长期记忆：侧栏“希望知答记住的事”支持主动保存和删除，每条最多 500 字符，新请求参考最近 5 条。不自动从聊天中提取隐私信息。删除仅影响之后构建的记忆上下文，不删除原聊天中已出现的内容，也不会撤回已发送给模型的请求。

- `GET /api/v1/memories`：当前用户的记忆列表。
- `POST /api/v1/memories`，JSON `{"content":"我是 Java 初学者"}`：主动保存。
- `DELETE /api/v1/memories/{id}`：删除自己的记忆。

执行保护：真实 Agent 默认总时限 90 秒，即使模型持续输出也会触发总超时；默认最多 8 次工具调用。可使用 `ZHIDA_TASK_TIMEOUT_SECONDS`（1～600）和 `ZHIDA_MAX_TOOL_CALLS`（1～50）调整。前端保留 120 秒等待保护，若提高后端时限还需同步调整前端。停止按钮通过 AbortController 取消请求，服务端取消订阅并阻止后续工具调用；已经发出的外部请求不保证立即终止。

数据库模式支持可选 UUID `requestId`：相同编号仅创建一次任务，再次提交返回 HTTP 409，不重复执行。前端每次新提问生成新编号；API 调用方重试同一次请求应复用原编号。当前拒绝重复提交，不自动重放旧 SSE，也不自动重试整条 Agent 链路。未启用数据库时不提供持久化幂等保证。

身份边界：默认关闭认证，仅供本机单用户使用（`local-user`）。启用 `ZHIDA_AUTH_ENABLED=true` 时必须同时启用持久化，并设置不少于 32 字节的随机 `ZHIDA_JWT_SECRET`。登录后的会话、任务、记忆、文档按服务端解析的用户身份隔离；不会把旧 local-user 数据自动分配给新账号。知识库使用用户与知识库 ID 派生的内部命名空间。公网部署前仍需完成下述部署安全验收。

## 新增功能（截至 2026-09-11）

- 注册、登录、游客入口，JWT 有效期 1 小时；页面过期后要求重新登录。刷新接口只接受尚有效的令牌，不是长期 refresh token。
- 多知识库创建与切换、文档删除；记忆编辑和默认关闭的参考开关。只有开启后才参考最近 5 条记忆。
- 会话删除（运行中拒绝删除），前端入口已接入。独立保存的记忆不会随会话删除。
- `GET /api/v1/tasks/{id}` 查询自己的任务，不受最近 100 条列表窗口限制。
- `POST /api/v1/tasks/{id}/cancel` 请求取消自己的运行中任务，返回 `accepted`；取消信号不代表外部 HTTP 请求已立即停止，最终状态以查询结果为准。
- 前端异常断线后查询任务状态，不自动重新提交。尚不支持 SSE 断点续传。
- 每分钟请求限制，账号每日默认 100 次、游客同来源每日共享 5 次额度（UTC 换日）；失败和取消也消耗已开始的任务额度。重复 requestId 不重复扣额度。
- 每分钟清理超过 12 分钟仍 RUNNING 的任务并标记 INTERRUPTED；不是自动续跑。
- 搜索及网页读取对部分短暂故障最多重试两次；不重试整条 Agent 链路。部署说明见 [DEPLOYMENT.md](DEPLOYMENT.md)。
- 逐次记录模型调用的服务商输入/输出 Token 数、实际模型名称和结束状态，历史任务展开后可查看汇总。`GET /api/v1/tasks/{id}/usage` 只允许任务所有者读取。流式累计用量只记一次；未返回统计时保存 null，页面明确显示部分用量未知。删除会话时级联删除用量记录。
- 真实 DeepSeek 用量验收已通过：本次开发验收收到服务商返回的 922 输入、2 输出 Token。此数字是该次请求结果，不是固定消耗。尚未换算费用，也没有计入 Tavily 或 Embedding 服务费用。
- 文档上传改为有界线程池异步处理，提供 `PROCESSING / READY / FAILED` 状态、单文档状态查询和失败重试；前端按状态轮询，不再让上传请求等待向量化完成。
- 长会话超过 10 轮后异步生成增量摘要；摘要按用户和会话隔离，失败自动回退到最近消息窗口，可通过 `ZHIDA_SUMMARY_ENABLED=false` 关闭。
- 任务开始时原子保存本次实际参考的记忆 ID；历史任务展开后可核对引用情况，跨用户请求返回 404。

以上更新取代前文关于“尚无自动恢复”“记忆总是启用”的早期说明。尚未完成的项目要求见开发文档最后的实施状态，不把部署模板等同于上线验收。

验证（2026-09-11）：开启 `ZHIDA_MYSQL_TEST=true` 并在当前进程加载 DB_URL/DB_USERNAME/DB_PASSWORD 后运行 `mvn test`，47 项通过、0 跳过。真实 MySQL 测试创建缺失的项目表，测试业务数据全部回滚；测试同时覆盖真实 PDF 解析与页码元数据、异步文档成功、失败重试、重启恢复、长对话增量摘要与失败回退、任务级记忆使用记录、跨 owner 拒绝、取消、流异常、总超时、调用预算、重复请求和消息角色/窗口限制。默认未开启 MySQL 测试时跳过该项。不测试登录的 HTTP 用例显式关闭鉴权，避免本机 `ZHIDA_AUTH_ENABLED` 环境变量污染结果；登录与越权边界由独立鉴权集成测试覆盖。

真实 DeepSeek 跨进程验收已通过：第一次告知测试代号“蓝鲸731”，停止独立预览 Java 进程并重新启动，同一会话提问后准确返回“蓝鲸731”。浏览器已验收历史读取、两次任务完成状态和计划展开。两条开发验收会话保留在本地库供回看。独立新版预览使用 `http://127.0.0.1:18081/`，仅监听本机，常规启动仍默认 8080。

真实 PDF RAG 跨进程验收已通过：上传 `src/test/resources/fixtures/zhida-rag-e2e.pdf` 后异步生成 2 个分块，通过唯一编号 `ZHIDA-PDF-4729` 可检索到文件名和第 1 页；重启 Java 进程后仍能命中相同内容，验收结束后已删除测试文档。自动化用例使用真实 `PagePdfDocumentReader`，向量索引使用替身以保证常规测试离线、稳定且不下载模型；真实本地 BGE 向量化与持久化由上述 HTTP 验收覆盖。

## 运行测试

```powershell
mvn test
```

## 代码来源说明

项目参考：

- <https://github.com/alibaba/spring-ai-alibaba>
- <https://github.com/spring-ai-alibaba/examples>

参考仓库保存在 `_reference/`，实际业务代码位于本项目 `src/`，没有直接在参考仓库中开发。

完整目标、边界和里程碑见 [DEVELOPMENT_SPEC.md](DEVELOPMENT_SPEC.md)。

登录鉴权的实现链路、测试范围、局限和面试回答见 [AUTH_DESIGN.md](AUTH_DESIGN.md)。
