# 知答分模块学习与实现路线

目标是能独立解释和修改 Java 后端项目。售后平台模块 1 工单核心与模块 2 模拟订单已实现并通过隔离验收；原研究助手仍可单独学习。每个模块完成可运行结果和验证后再进入下一项，状态以 PROJECT_STATUS.md 和当前测试为准。

各阶段执行独立审查、修复复查与验证，记录在 [reviews](reviews/README.md)；模块 1 的核心与中文注释已完成独立审查。核心注释优先说明权限依据、状态合法性、事务边界、幂等和并发保证，避免逐行重复代码。

## 售后模块 1：工单核心（已实现、自动验证）

入口：`support` 业务包、`SupportConfiguration`、`/api/v1/support`。

学习：Spring Security 中的服务端角色解析、JDBC/TransactionTemplate、条件更新、乐观版本控制、状态机、唯一约束、审计事件与 HTTP 权限。

范围：新增独立的工单、回复、事件、分类和账户角色表，不修改旧聊天数据。注册账户默认 `USER`；客服与管理员角色由数据库显式配置。`PENDING → PROCESSING → AWAITING_CONFIRMATION → CLOSED`，用户可在待确认阶段退回处理。创建使用用户级 `requestId` 幂等，所有变更携带 `expectedVersion`。模块必须同时启用售后开关、JWT 和持久化。

完成标准：能解释为什么角色与 owner 不从请求读取，如何以状态/version/assignee 条件更新避免并发覆盖，以及为什么工单变更、处理记录和审计事件需要同一事务。不能把计划中的订单、RAG、Agent 或页面写成已实现。

## 售后模块 2：虚构产品与模拟订单（已实现、自动验证）

入口：`ProductOrderService`、`ProductOrderRepository`、`SupportOrderController`、`support-schema.sql`。

学习：认证主体与资源 owner 的区别、只读订单边界、合法状态组合、管理员造数幂等、业务与审计同事务，以及旧表增量升级。

范围：产品与订单均由服务端固定为 `simulated=true`；管理员录入，用户只查询本人订单。订单支持待付款、已付款未开通和已开通，待付款+已开通被服务层与数据库约束拒绝。工单可选关联本人订单，跨用户关联返回 404；没有付款、退款或开通状态修改接口。

完成标准：能解释为什么订单查询 SQL 必须带 user_id、为什么工单仍要再次校验订单归属、为什么 orderId 属于幂等摘要，以及 AI 工具将来只能调用查询方法。

后续售后模块依次为：

1. 售后知识库与检索（RAG）。
2. 售后 Agent 与人工客服协作边界。
3. 用户、客服、管理员三端页面与演示环境。

工单接口、独立表和虚构 HTTP 演示见 [SUPPORT_DATABASE.md](SUPPORT_DATABASE.md) 与 [SUPPORT_DEMO.md](SUPPORT_DEMO.md)。

下一售后模块验收：RAG 阶段验证公共与私人文档边界、出处和检索不足；Agent 阶段验证草稿确认、失败降级及提示注入边界；页面阶段用完整虚构闭环做浏览器验收。上述均为计划。

模块 1 新增 17 项自动测试，全量 99 项（1 项真实 MySQL 跳过）。先阅读 SupportTicketServiceTest、SupportTicketHttpTest、SupportConfigurationTest，解释真实并发、快照读取与审计故障注入。

模块 2 新增 9 项自动测试，全量 108 项（1 项真实 MySQL 跳过）。先阅读 ProductOrderServiceTest 与 SupportOrderHttpTest，解释 owner SQL、造单幂等、事务审计和只读订单边界；再读 SupportConfigurationTest 的旧表升级用例。

## 原研究助手学习路线

## 模块 1：普通聊天与模型接入

入口：agent/ResearchAgentConfiguration、application/ResearchOrchestrator、api/ResearchController。

学习：Spring Bean、配置注入、模型消息 user/assistant/system、HTTP 接口、LangChain4j StreamingChatModel。

练习：演示启动；用本机密钥完成一次真实回答；切换可用模型；区分“配置不全”“供应商401”“正常回答”。没有密钥时先跑本地协议测试。

完成标准：能画出浏览器→Controller→Service→模型API→回答的调用链，解释为何密钥不能提交。

## 模块 2：SSE 与任务执行

入口：api/ResearchController、api/ResearchExecutorConfiguration、application/ResearchSession。

学习：Consumer 回调、SseEmitter、线程池、有界队列、取消、超时、一次性资源释放。

练习：让回答逐段到达页面；模拟无响应和持续输出；点停止；验证取消后不再出现成功事件。用测试说明同一会话为什么只能运行一个任务。

完成标准：能解释一次请求里 Controller 返回后是谁继续工作，以及为何不用为每次请求随意 new Thread。

## 模块 3：MySQL 会话与任务

入口：conversation/ConversationRepository、TaskRepository、ConversationHistory。

学习：JDBC、HikariCP、事务、状态机、唯一约束、requestId 幂等。

练习：保存问答、读取历史、删除会话；同一 requestId 重提返回409；模拟存储失败，确认没有“完成”事件和半段助手消息。

完成标准：能解释用户消息与任务何时建立，助手消息与 COMPLETED 为何要在同一事务提交。

## 模块 4：工具调用

入口：tool/ResearchTools、tool/search、tool/web、observability/ObservableToolInterceptor。

学习：LangChain4j @Tool/@P、模型输出工具名称/JSON参数、执行工具、回传工具结果、继续模型调用。

练习：先只运行日期工具，再开启搜索和网页读取；模拟工具失败、预算耗尽与非法网页地址。

完成标准：能解释模型不直接访问数据库/互联网，Java 服务端才是执行工具和校验权限的主体。回答计划只是展示，不是严格工作流。

## 模块 5：知识库 RAG

入口：knowledge/KnowledgeBaseService、DocumentChunker、KnowledgeChunk、LocalVectorKnowledgeIndex。

学习：PDFBox按页解析、文本切块、Embedding、余弦相似度、topK、metadata过滤、文件持久化。

练习：先解析 TXT/PDF并验证页码，再通过远程服务生成向量；检索唯一句子；重启后仍能找到；不同知识库不能互相命中。旧BGE文档在新服务配置后重试重新索引。

完成标准：能说清“上传→原文保存→异步解析→切块→向量化→检索→模型结合片段回答”。说明远程向量接口会收到文档文本，且向量存储目前仅支持单实例。

## 模块 6：登录与数据隔离

入口：auth/AuthController、SecurityConfiguration、OwnerResolver、知识库AccessService与Repository。

学习：BCrypt、JWT、Spring Security、Principal、owner条件查询。

练习：双用户注册；A访问B的会话、任务、记忆和文档返回404；伪造或过期JWT返回401。

完成标准：能解释JWT验签与业务资源权限是两个不同步骤；前端隐藏按钮不能代替后端权限。

## 模块 7：摘要、记忆与用量

入口：conversation/ConversationContext、ConversationSummaryService、observability/ModelUsageInterceptor、ModelCostService。

学习：上下文窗口、增量摘要、后台队列、用户主动保存记忆、供应商Token统计与费用估算。

练习：超过10轮后生成摘要；摘要失败仍可聊天；记忆开关/删除影响后续上下文；供应商没返回用量时显示未知，不标零费用。

完成标准：能说明恢复的是问答上下文，不是中途工具现场；摘要不是长期记忆；费用上限不是供应商账单。

## 推荐里程碑

1. 模块1+2：可演示的聊天应用。
2. 模块3+6：可解释的Java后端基础版本。
3. 模块4+5：Java+AI Agent/RAG版本。
4. 模块7：补充成本与长会话能力。

简历只写已经完成、验证且自己能讲清楚的里程碑。不写未经压测的并发量、性能提升或生产上线。对应项目表述见 RESUME_PROJECT.md。
