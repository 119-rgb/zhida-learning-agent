# 知答分模块学习与实现路线

目标是能独立解释和修改 Java 后端项目。迁移到 LangChain4j + Spring MVC 后，按下面顺序阅读并实现；每个模块完成一个可运行结果，再进入下一个。当前迁移正在执行，最终验收状态见 PROJECT_STATUS.md。

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
