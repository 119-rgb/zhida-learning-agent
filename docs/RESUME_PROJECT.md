# 知答：简历项目与面试地图

以下是迁移后的项目描述，使用前应完成 MODULE_ROADMAP.md 对应模块的学习，能从源码解释每条。真实供应商、压测和上线状态以 PROJECT_STATUS.md 为准。

## 简历项目文本

**知答——学习与研究助手**

技术栈：Java 17、Spring Boot、Spring MVC、LangChain4j、MySQL、Spring Security、JWT、PDFBox、SSE。

- 基于 LangChain4j 接入大模型流式接口，实现按需工具调用循环，集成联网搜索、网页读取与知识库检索；通过 Spring MVC SseEmitter 推送回答，并以有界线程池管理研究任务。
- 实现 PDF/TXT/Markdown 知识库处理链路，按页解析 PDF、文本切块、SHA-256 去重和异步索引；使用远程 Embedding 与向量相似度检索，按知识库命名空间过滤并返回文件、页码/片段引用，提供失败重试和索引重建。
- 基于 MySQL 持久化会话、消息、任务与工具事件，以唯一 requestId 拦截重复请求；在同一事务提交助手消息及成功终态，结合取消、总超时和工具预算限制异常任务。
- 基于 Spring Security/JWT 与 BCrypt 实现登录和无状态鉴权，服务端从 JWT 获取 owner，并在会话、任务、记忆及文档访问中实施所有权约束；补充历史窗口、异步摘要和供应商 Token 用量记录。

篇幅不足时保留前3条，将鉴权缩入第3条。尚未自行理解RAG时先写聊天/SSE/数据库/登录基础模块；补齐RAG后再加入知识库条目。不要写“高并发生产系统”“多实例向量数据库”“准确费用账单”或未经压测的提升百分比。

## 源码面试地图

| 问题 | 先看源码 | 回答要点 |
| --- | --- | --- |
| 一个问题如何得到答案？ | api/ResearchController、application/ResearchOrchestrator | 校验→准备session→线程池→流式模型→工具结果回传→SSE回答 |
| 为什么使用SSE？ | api/ResearchController、ResearchExecutorConfiguration | 请求一方持续接收后端输出；回调发送；连接失败取消任务；不是WebSocket双向通道 |
| 谁决定工具怎么执行？ | tool/ResearchTools、ObservableToolInterceptor | 模型提出工具名称/参数，Java服务端验证与执行，回传结果后继续模型调用 |
| RAG如何实现？ | knowledge/KnowledgeBaseService、LocalVectorKnowledgeIndex | 原文→切块→向量→topK过滤→片段与出处→工具回传模型；检索不是训练模型 |
| 为什么要重新索引？ | LocalVectorKnowledgeIndex | 旧模型与新模型向量空间不同，格式/维度/模型身份不能混用，保留原文后重建 |
| 如何防止重复和错保存？ | conversation/TaskRepository、ConversationHistory | 唯一requestId；任务终态条件更新；成功与助手消息同事务；完成事件在提交之后 |
| 取消能终止什么？ | ResearchSession、ResearchOrchestrator、ConversationHistory | 停止后续输出/工具/成功提交，尽可能取消流句柄；已发出的HTTP请求不保证立即撤销 |
| 如何防止越权？ | auth/SecurityConfiguration、OwnerResolver、Repository | JWT验签仅确认身份，数据查询还要匹配owner与资源ID，跨用户返回404 |
| 摘要和记忆有何区别？ | ConversationContext、ConversationSummaryService | 摘要压缩较早问答；记忆由用户主动保存，按开关选入参考上下文 |
| Token统计准确吗？ | ModelUsageInterceptor、ModelCostService | 每次供应商调用记录一次，未返回统计记未知；费率上限估算不是供应商账单 |

## 30秒项目介绍

知答是一个Java学习与研究助手。我用Spring Boot和Spring MVC提供接口，用LangChain4j接入模型并执行工具调用。用户可以聊天、联网查资料或上传文档，后端把回答通过SSE逐段输出。知识库会异步解析、切块和调用远程向量服务，检索时按用户的知识库范围过滤。MySQL保存历史与任务，JWT和owner约束保护用户数据。我主要需要讲清楚的是任务生命周期、数据库事务、工具调用循环和RAG处理链路；当前定位是单实例学习演示。

“我”对应的描述必须是自己完成或已经逐模块理解、能够修改和解释的工作。可将上述介绍改为“项目实现了”，避免把尚未掌握的辅助生成代码表述为独立研发经验。
