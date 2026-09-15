# 知答 Java 框架迁移设计

用户已批准方向：Java 17 + Spring Boot 3.5.7 + LangChain4j；Spring MVC + SseEmitter；远程 Embedding API；保留 MySQL/JDBC/JWT 和现有业务能力。用户已授权关联本地 Git 与现有 GitHub 仓库并提交推送。

## 边界与数据

移除 Spring AI、Spring AI Alibaba、WebFlux、Reactor 和本地 ONNX 依赖。保留当前未提交的功能与界面改动，迁移前提交基线以便回退。演示模式不请求外部服务；默认测试不读取开发数据库凭证、不下载模型。远程 Embedding 服务的地址、模型、密钥独立配置；DeepSeek 聊天密钥不自动用于 Embedding。

## 执行与接口

保持原 HTTP 路径、响应结构、POST SSE 事件名称。MVC 控制器返回普通值和 MultipartFile；鉴权使用 Servlet SecurityFilterChain 与 OncePerRequestFilter。研究任务由有界线程池运行，SseEmitter 通过回调发送事件。

共享接口：`ResearchSession` 提供 `void execute(Consumer<AgentEvent> sink)` 与 `boolean cancel()`。`ResearchOrchestrator.prepare(ResearchRequest, String owner)` 和 `ConversationHistory.prepare(ResearchRequest, ResearchOrchestrator, String owner)` 返回该接口。prepare 在建立 SSE 响应前校验知识库、并发、所有权、幂等及额度；拒绝任务返回原 HTTP 状态。取消、超时和发送失败停止后续事件/工具/成功入库；已经发出的远程请求可能不能立刻撤销。任务完成事件只在成功提交助手消息与终态之后发送。

LangChain4j StreamingChatModel 执行模型与工具循环，模型响应中的供应商 Token 用量每次调用只记录一次。上下文使用 ChatMessage，恢复摘要、完整历史窗口和用户选择的记忆，禁止把历史资料提升为系统指令。工具预算、总时限、用户/知识库作用域继续由服务端控制。

## 知识库

PDFBox 按页解析，业务分块对象保留片段 ID、文件名、页码、知识库命名空间。LangChain4j EmbeddingModel 和 InMemoryEmbeddingStore 执行向量检索，JSON 文件原子保存。旧 Spring AI/BGE 索引不能直接与新模型混用：保留旧文件，新默认索引使用独立文件；启动识别旧目录时把需要重新索引的 READY 文档标为可重试的 FAILED，原文不删除。新索引记录模型/地址身份，切换模型必须重建。Embedding 未配置时明确报错，不用关键词检索冒充向量检索。

## 验证和交付

先运行迁移前完整测试；迁移后测试覆盖 MVC 请求、JWT/越权、SSE 事件顺序、存储失败、幂等、取消/超时、工具预算、真实 PDF 解析、异步文档恢复及向量隔离。通过本地假服务验证聊天/Embedding 协议，真实供应商验收须使用用户本机配置，缺少新 Embedding 密钥时诚实标明。更新 README、配置、部署和简历/面试说明，删除过时的当前技术声明。

GitHub 目标：119-rgb/zhida-learning-agent。保留远程和本地历史，不强推；检查发布文件不包含密钥、本地数据或模型。提交后验证远程 SHA 与本地一致并配置 upstream。
