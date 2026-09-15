# LangChain4j + Spring MVC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 完成已批准的 Java 框架迁移，并关联、同步本地与现有 GitHub 仓库。

**Architecture:** MVC 控制器在响应前准备 ResearchSession，线程池执行任务并用 SseEmitter 回调推送。LangChain4j 管理模型消息、工具和向量检索，MySQL 事务管理业务状态。PDFBox 解析本地原文，远程 API 生成向量。

**Tech Stack:** Java 17, Spring Boot 3.5.7, LangChain4j, Spring MVC, PDFBox, MySQL, Spring Security JWT.

**Spec:** docs/superpowers/specs/2026-09-15-langchain4j-mvc-design.md

## Global Constraints

- 保留当前未提交改动与所有业务 HTTP 路径/JSON/SSE 名称。
- 默认测试离线且强制关闭开发环境持久化/认证；真实 MySQL 显式 opt-in。
- 完全移除 Spring AI/Alibaba/WebFlux/Reactor/ONNX 生产和测试依赖。
- 密钥通过环境变量注入，不输出、不提交；远程 Embedding 单独配置。
- 保留本地与远程 Git 历史，不强推。

### Task 1: 基线、依赖与远程关联（主代理）

- [ ] `mvn test -q` 验证迁移前基线；检查 `.env.example`、忽略规则与待提交文件，创建可回退提交。
- [ ] 配置 origin，fetch 现有 main，检查两边差异并保留历史；使用 codex/langchain4j-mvc 分支开发。
- [ ] 修改 pom.xml 为 MVC、LangChain4j core/open-ai、PDFBox；下载与检查实际 Java API，统一版本通知执行者。

### Task 2: MVC 与鉴权（接口执行代理）

Files: src/main/java/com/zhida/agent/api/*.java, auth/AuthController.java, auth/SecurityConfiguration.java, auth/RequestRateFilter.java; HTTP/认证冒烟测试。

Consumes: `ResearchSession.execute(Consumer<AgentEvent>)`, `cancel()`；两个 prepare 方法见设计。Produces: 原路径的普通 JSON、MultipartFile 和 SseEmitter 接口。

- [ ] 先更新 HTTP 测试为 TestRestTemplate/MockMvc，观察原 WebFlux 实现无法满足 Servlet 测试。
- [ ] 迁移普通控制器与上传临时文件清理；迁移 JWT 与限流 Servlet filter。
- [ ] 有界研究线程池提交前准备 session；发送失败、超时、拒绝队列及主动取消均清理任务。
- [ ] 运行 HTTP、认证、跨用户和演示 SSE 验证；审查状态码与所有权边界。

### Task 3: Agent 执行与生命周期（执行代理）

Files: application/ResearchOrchestrator.java, application/ResearchSession.java, agent/ResearchAgentConfiguration.java, conversation/ConversationHistory.java, ConversationContext.java, ConversationSummaryConfiguration.java, observability/*Interceptor.java, ToolTracePublisher.java, tool/ResearchTools.java; 对应执行与生命周期测试。

Produces: prepare 方法和 ResearchSession；LangChain4j ChatMessage 上下文、流式模型工具循环。

- [ ] 保留并迁移原执行、存储失败、预算、取消、超时测试，先验证旧实现不满足新接口。
- [ ] 用 StreamingChatModel 回调与普通 Java 并发机制替换响应式编排，保持任务终态事务语义。
- [ ] 工具调用绑定任务/知识库作用域、预算与可观测事件；用量每次供应商调用记录一次。
- [ ] 保留演示、长对话摘要、记忆引用记录；执行后清理会话并发锁和任务资源。
- [ ] 运行有意义的执行测试并提交审查结果。

### Task 4: PDF 与远程向量知识库（知识库执行代理）

Files: knowledge/KnowledgeBaseService.java, LocalVectorKnowledgeIndex.java, 新业务片段类型与 Embedding 配置、common/config/ZhidaProperties.java 的 Rag 部分；KnowledgeBaseServiceTest/DocumentChunkerTest/新向量测试。

Consumes: 独立 Embedding 配置。Produces: 保持 KnowledgeSearchResponse 对外结构的解析/向量服务。

- [ ] 先写真实 PDF 按页解析、向量命名空间隔离、模型变化及旧目录可重试测试。
- [ ] PDFBox 解析替换 PagePdfDocumentReader；业务片段替换 Spring AI Document。
- [ ] 远程 Embedding 与 LangChain4j 内存向量存储；保留 ID、过滤、阈值和原子持久化。
- [ ] 默认新索引文件，不覆盖旧 BGE 数据；旧 READY 文档恢复为可重新处理状态。
- [ ] 验证异步成功/失败/重试/删除恢复与模型未配置错误，不触碰用户真实文档。

### Task 5: 集成、文档、验证与发布（主代理）

- [ ] 统一 application.yml/.env.example、部署限制和 health 状态；检查所有残留 import 和旧技术声明。
- [ ] `mvn clean test` 与 `mvn package`；使用假模型/Embedding 服务验证真实协议与 SSE，检查浏览器页面和上传。
- [ ] 更新 README/DEVELOPMENT_SPEC/DEPLOYMENT 与 docs 中的实际框架说明，新增可讲清楚的简历项目文本和面试地图。
- [ ] 审查完整差异、发布范围和密钥；提交并非强制推送到已验证 GitHub 仓库，验证远程 SHA/upstream 与本地状态。

## Integration ledger

Task 2 consumes Task 3 prepare/ResearchSession contracts; both share no implementation files. Task 3 uses knowledge public service methods unchanged by Task 4. Task 4 owns Rag properties; Task 1 owns dependency versions. Tests由各执行者分区修改，主代理统一集成。用户已批准整体方向与 GitHub 提交推送，因此连续执行，不重复要求确认。
