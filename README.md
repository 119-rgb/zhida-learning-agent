# 知答——智能售后工单平台

知答面向虚构的软件订阅产品，按模块构建售后流程。**模块 1 的工单后端已实现并通过隔离自动验收**，仍兼容原研究助手。订单关联、售后 RAG、售后 Agent 和三端页面是后续模块，不能视为已完成。

本项目于 2026-09-15 迁移到 LangChain4j 与 Spring MVC，原有 Spring AI Alibaba / WebFlux / 本地 BGE ONNX 版本留在 Git 历史中。当前代码不需要部署本地推理模型。

## 架构

工单业务不依赖模型：HTTP 请求 → JWT 验证 → SupportActorResolver 数据库角色 → SupportTicketService 权限、状态和参数校验 → 工单条件更新、回复、审计同事务 → MySQL（测试为隔离 H2）。详情使用独立的可重复读事务。

以下为兼容保留的研究助手链路，尚未改成售后 Agent：

```text
浏览器页面
  ↓ JSON / 文件上传 / POST SSE
Spring MVC Controller + Spring Security JWT
  ↓ 服务端校验 owner、知识库、并发及 requestId
ConversationHistory / ResearchOrchestrator
  ↓ 有界线程池 + ResearchSession 生命周期
LangChain4j StreamingChatModel ↔ 工具调用循环
  ├─ Tavily 搜索 → 安全网页读取
  ├─ PDFBox 按页解析 → 文本分块 → 远程 Embedding → 向量检索
  └─ MySQL：消息 / 摘要 / 任务 / 工具事件 / Token 用量 / 长期记忆
  ↓ SseEmitter 回调
逐段回答、任务完成或失败事件
```

业务层采用普通 Java 方法、回调和线程池；HTTP 使用 MVC 控制器和 MultipartFile，流式传输使用 SseEmitter。模型接入、消息类型、工具描述和向量检索使用 LangChain4j。

## 功能

- 演示模式无密钥启动，不发送模型和搜索请求。
- DeepSeek 兼容接口流式调用；按需执行日期、联网搜索、网页读取和 `knowledge_search`。
- 规则生成回答计划，回答页保留专注对话界面；工具过程与错误留在后台执行记录中。
- PDF、TXT、Markdown 上传；20MB 单文件限制、SHA-256 去重、800/120 字符分块与 PDF 页码引用。
- 有界线程池异步文档处理，提供 PROCESSING / READY / FAILED / DELETING 状态、失败重试与重启清理。
- 远程 Embedding API 生成向量；LangChain4j InMemoryEmbeddingStore 按知识库命名空间检索、JSON 文件持久化。
- 可选 MySQL：聊天记录、任务终态、执行事件、供应商 Token 用量、长会话增量摘要与用户主动保存的记忆。
- 恢复较早摘要与最近最多 10 轮完整问答；历史资料不作为系统指令。
- 可选注册、登录、游客访问与 JWT 鉴权；会话、任务、记忆和文档按服务端 owner 隔离。
- requestId 数据库幂等、会话并发限制、任务取消、总超时、工具预算、每日额度和请求限流。
- 根据供应商 Token 用量估算 DeepSeek 费用上限；未知模型、缺失统计与调用时间明确标为不完整。

## 启动

环境：JDK 17、Maven 3.9+。

```powershell
mvn spring-boot:run
```

打开 <http://localhost:8080>，健康状态为 <http://localhost:8080/api/health>。默认 AI、数据库和鉴权关闭，仅适合本机演示。`.env.example` 是变量清单，普通 Java 启动不会自动读取 `.env`。

真实模型需要在本机环境变量中设置 `DEEPSEEK_API_KEY`，然后开启：

```powershell
$env:ZHIDA_AI_ENABLED="true"
$env:DEEPSEEK_BASE_URL="https://api.deepseek.com/v1"
$env:DEEPSEEK_CHAT_MODEL="deepseek-flash"
mvn spring-boot:run
```

默认模型名称保留现有项目配置；可替换为账号实际可用的模型。Tavily 联网搜索独立需要 `TAVILY_API_KEY`。不要将密钥写进命令参数、聊天、配置文件或 Git。

## 知识库配置与旧数据迁移

知识库需要支持兼容 `/embeddings` 的远程服务，独立设置三个环境变量：

| 变量 | 含义 |
| --- | --- |
| EMBEDDING_BASE_URL | 服务的 API 基础地址，如供应商给出的 `/v1` 地址 |
| EMBEDDING_API_KEY | Embedding 服务密钥 |
| EMBEDDING_MODEL | 服务商实际支持的向量模型名称 |

DeepSeek 聊天密钥不会自动用于 Embedding。未配置时仍可启动页面和聊天，文档索引/检索会明确提示配置错误，不会用关键词检索代替向量检索。向量化会将文档片段与查询发送给所配置的供应商；费用与数据处理规则以该供应商为准。

原文继续保存在 `data/uploads/`。新默认向量文件是 `data/vector/langchain4j-vector-store.json`，记录模型与 API 地址身份；旧 `data/vector/vector-store.json` 和模型文件保留。旧文档或模型变化导致索引不兼容时，目录里的 READY 文档变为可重试的 FAILED；配置新服务后，在文档列表点“重试”重新索引即可。不要手动把旧 BGE 向量复制进新文件。重建会备份不兼容的新格式索引再替换，不删除原始文档。

当前向量存储适合单实例学习演示，尚无跨文档目录/向量文件事务或多实例写入能力。

## 数据库与登录

MySQL 数据库 `zhida_agent` 需提前创建，应用账户需具备该库读写和建表权限。通过环境变量设置 DB_URL、DB_USERNAME、DB_PASSWORD，再开启 `ZHIDA_PERSISTENCE_ENABLED=true`。默认关闭数据库时不提供持久化幂等和重启后聊天恢复。

登录额外要求 `ZHIDA_AUTH_ENABLED=true` 和至少 32 字节的随机 `ZHIDA_JWT_SECRET`。使用 BCrypt cost 12 保存密码，JWT HS256 验签并校验 issuer/过期时间；业务身份从 Principal 解析，不能通过前端资源 ID 修改 owner。跨用户资源访问返回 404。

账户、游客额度和注销局限见 [AUTH_DESIGN.md](AUTH_DESIGN.md)。部署步骤和剩余验收见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## 售后工单核心（模块 1，开发中）

工单模块仅在 `zhida.support.enabled=true`、`ZHIDA_AUTH_ENABLED=true` 和 `ZHIDA_PERSISTENCE_ENABLED=true` 同时启用时运行；它要求 JWT 身份和数据库持久化，不能使用旧研究助手的游客或本地身份。注册账户默认只有 `USER` 角色；`CUSTOMER_SERVICE` 与 `ADMIN` 由数据库运维人员显式配置，服务端从数据库读取角色，接口不接受客户端提交的角色或工单所有者。

模块 1 的工单创建仅包含 `title`、`description`、`categoryId` 和确认字段；没有订单关联。状态流转为 `PENDING`、`PROCESSING`、`AWAITING_CONFIRMATION`、`CLOSED`。用户创建和查看自己的工单、补充说明、确认并评价；客服查看待受理队列或自己的工单、接单、回复和提交方案；管理员管理分类、分配客服并查看全部工单。

服务端接口位于 `/api/v1/support`：`GET /me`、分类 `GET/POST /categories` 与 `PUT /categories/{id}`、工单 `POST/GET /tickets`、详情 `GET /tickets/{id}`，以及 `comments`、`claim`、`replies`、`solution`、`confirm`、`reopen`、`assign` 等变更操作。`GET /tickets?view=mine` 是默认用户本人或客服本人列表，`view=pending` 是客服队列，`view=all` 仅管理员可用。详情返回 `ticket`、`replies`、`events`。创建需要 `confirmed=true`；所有变更请求都需要 `expectedVersion`，用来拒绝并发冲突。用户的 `requestId` 具有幂等语义：同一规范化内容重放返回原工单（201），同一标识但内容不同返回 409。

数据库表、DDL 权限和角色配置示例见 [docs/SUPPORT_DATABASE.md](docs/SUPPORT_DATABASE.md)。无页面演示的 HTTP 流程见 [docs/SUPPORT_DEMO.md](docs/SUPPORT_DEMO.md)。

## 常用接口

| 接口 | 用途 |
| --- | --- |
| GET /api/health | AI、搜索、Embedding 配置与运行状态 |
| POST /api/v1/research/stream | 提问，JSON 包含 message、可选 conversationId / requestId / knowledgeBaseId |
| GET /api/v1/conversations | 历史会话 |
| GET /api/v1/conversations/{id} | 读取消息 |
| GET /api/v1/conversations/{id}/tasks | 会话任务 |
| GET /api/v1/tasks/{id} | 自己的任务状态 |
| POST /api/v1/tasks/{id}/cancel | 请求取消运行中任务 |
| GET /api/v1/tasks/{id}/usage | 模型用量 |
| GET /api/v1/tasks/{id}/cost | 保守费用上限与告警 |
| GET /api/v1/tasks/{id}/memories | 本次实际参考的记忆 |
| GET /api/v1/knowledge-bases | 自己的知识库 |
| POST /api/v1/knowledge-bases/{id}/documents | MultipartFile 上传，返回 202 |
| GET /api/v1/knowledge-bases/{id}/documents | 文档与处理状态 |
| POST /api/v1/knowledge-bases/{id}/documents/{documentId}/retry | 失败文档重试 |
| GET /api/v1/knowledge-bases/{id}/search?q=问题&topK=5 | 独立向量检索 |
| GET /api/v1/support/me | 当前 JWT 对应的售后角色 |
| GET/POST /api/v1/support/categories；PUT /categories/{id} | 分类读取与管理员管理 |
| POST/GET /api/v1/support/tickets | 创建和按 `view` 查询工单 |
| GET /api/v1/support/tickets/{id} | 工单、回复和审计事件的一致快照 |
| POST /api/v1/support/tickets/{id}/comments、claim、replies、solution、reopen、confirm、assign | 对应用户、客服、管理员操作；必需 expectedVersion |

SSE 事件包括 task.started、plan.created、step.started、answer.started、tool.started / completed / failed、answer.delta、task.completed / failed。事件编号按发送顺序递增。没有调用工具时不生成工具事件。

任务默认总时限 90 秒、最多 30 次工具调用；第 31 次拒绝。浏览器等待保护为 120 秒。取消阻止后续工具和任务成功入库，已经发出的外部请求不保证立即终止。断线查询任务状态，不自动重提、不提供 SSE 断点续传。

同一 requestId 重提返回 409，不自动重放旧回答。任务成功状态与助手消息在同一事务中提交；数据库写入失败不会发送成功完成事件。失败/取消不会保存半段助手消息。

## 测试与交付

模块 1 全量回归：99 项、0 失败、0 错误、1 项真实 MySQL 测试跳过；新增工单测试 17 项，可执行 JAR 已打包。旧演示占用 target JAR 时可使用独立目录：`mvn clean verify '-Dzhida.build.directory=tmp/support-build'`。日志、构建产物和私人文件不入 Git。

```powershell
mvn clean test
mvn package
```

默认测试使用隔离数据源或测试替身，不连接 DeepSeek、Tavily、本机 MySQL 或真实 Embedding 服务。普通启动测试显式关闭 AI、鉴权与持久化，避免环境变量污染。真实 MySQL 用例需显式设置 `ZHIDA_MYSQL_TEST=true` 并在本机配置数据库凭证。

迁移后的验证记录与真实供应商验收范围见 [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)。旧版本中记录的 BGE、PagePdfDocumentReader、Netty 和 Spring AI 验收结果仅适用于旧版本，不能用作新版本的验收证据。

简历项目说明及源码面试地图见 [docs/RESUME_PROJECT.md](docs/RESUME_PROJECT.md)。完整开发要求见 [DEVELOPMENT_SPEC.md](DEVELOPMENT_SPEC.md)。

## 来源

项目最初参考 [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba) 和其官方示例，现在将模型与向量接入迁移到 [LangChain4j](https://github.com/langchain4j/langchain4j)。参考仓库位于被忽略的 `_reference/`，实际业务代码在 `src/`。历史设计与计划文档记录当时实现，请以本 README、当前源码和最新验证记录为准。
