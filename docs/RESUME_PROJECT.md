# 知答：简历项目与面试地图

以下是迁移后的项目描述，使用前应完成 MODULE_ROADMAP.md 对应模块的学习，能从源码解释每条。真实供应商、压测和上线状态以 PROJECT_STATUS.md 为准。

## 智能售后工单平台（模块 1–2 已实现、隔离验收）

以下表述只适用于能够从当前源码和验证记录中解释的工单与模拟订单内容；不要写成完整 AI 售后系统。

**知答——智能售后工单平台（工单与模拟订单模块）**

技术栈：Java 17、Spring Boot、Spring MVC、MySQL、Spring Security、JWT。

- 设计并实现独立售后工单模型，覆盖 `PENDING`、`PROCESSING`、`AWAITING_CONFIRMATION`、`CLOSED` 状态，以及用户补充、客服接单/回复/方案、用户确认评价和退回处理等服务端状态约束。
- 基于 JWT 身份和数据库角色实施 USER、CUSTOMER_SERVICE、ADMIN 权限边界；注册账户默认 USER，客服与管理员角色由运维配置，资源读取按工单归属与处理人校验。
- 使用用户级 `requestId` 实现创建幂等，并以 `expectedVersion` 和条件更新处理并发冲突；在同一事务中提交工单变更、回复/处理记录和审计事件。
- 设计明确标记为虚构数据的产品/订单模型，覆盖待付款、已付款未开通和已开通场景；订单查询按认证用户归属过滤，工单关联订单时再次校验 owner，拒绝跨用户访问。
- 管理员模拟订单写入以 `(created_by, requestId)` 唯一约束防重复，并与审计事件同事务提交；查询侧不提供支付、退款或开通状态修改能力，为后续只读 AI 工具保留安全边界。

不要声称售后 RAG/Agent、三端页面、真实支付集成或真实 MySQL 压测已经完成。自动测试数量以 PROJECT_STATUS.md 的最新实际执行记录为准，不将测试数量表述为生产经验或性能数据。

| 面试问题 | 对应源码与测试 | 解释要点 |
| --- | --- | --- |
| 如何限制角色和归属？ | support/SupportActorResolver、SupportTicketService；SupportTicketHttpTest | JWT 给出身份，角色每次从数据库重查，资源还需归属校验 |
| 为什么有四种状态？ | SupportTicketService.change；状态转换测试 | 客服提供方案后仍需用户确认，未解决可退回处理 |
| 重复和并发如何处理？ | Service.create、Repository.update；并发创建/接单测试 | 唯一幂等键绑定规范化内容；版本、状态、客服条件更新 |
| 为什么业务与审计同事务？ | Service.write、Repository.event/reply；审计失败测试 | 同提交、同回滚，失败不保留部分工单变更 |
| 详情会读到混合版本吗？ | Repository.readTransaction；并发详情测试 | 独立可重复读事务读取本体与关联记录 |
| 订单为什么不会串用户？ | ProductOrderRepository.ownedOrder/orders；SupportOrderHttpTest | owner 条件进入 SQL，JWT 用户不能由请求覆盖，跨用户统一 404 |
| 模拟订单为何仍要幂等和审计？ | ProductOrderService.createOrder；ProductOrderServiceTest | 管理员重试也会重复造数；唯一键裁决并发，订单与 CREATED 事件同事务 |
| AI 能修改付款或开通吗？ | SupportOrderController、ProductOrderService | 只有管理员创建初始模拟状态，没有状态更新业务方法；后续工具仅接查询 |

售后项目口述：项目提供用户、客服、管理员的工单接口，以及虚构产品和模拟订单。用户只能查询自己的订单，关联订单建单时后端再次校验归属。客服接单回复并提交方案，用户确认后关闭评价，未解决可退回处理。后端以状态和角色限制操作，用唯一 requestId 防重复、版本条件更新防覆盖，并将业务变化与审计同事务提交。当前已完成工单和模拟订单后端，售后 RAG、Agent 与页面是后续阶段。

## 原研究助手的简历项目文本

**知答——学习与研究助手（兼容保留）**

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
