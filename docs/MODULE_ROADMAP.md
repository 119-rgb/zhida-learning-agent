# 知答分模块学习与实现路线

目标是能独立解释和修改 Java 后端项目。售后平台模块 1 工单核心、模块 2 模拟订单、模块 3 售后知识库和模块 4 售后 Agent 已实现并通过隔离验收；原研究助手仍可单独学习。每个模块完成可运行结果和验证后再进入下一项，状态以 PROJECT_STATUS.md 和当前测试为准。

各阶段执行独立审查、修复复查与验证，记录在 [reviews](reviews/README.md)；模块 1–3 已完成独立审查，模块 4 的独立审查待另一位审查者完成。核心注释优先说明权限依据、状态合法性、事务边界、幂等、并发保证和提示注入边界，避免逐行重复代码。

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

## 售后模块 3：公共与私人知识库（已实现、自动验证）

入口：`KnowledgeBaseAccessService`、`KnowledgeController`、`KnowledgeBaseService`、`ResearchTools.knowledgeSearch`。

学习：公共与私人资源授权、固定向量命名空间、PDF 按页解析、文本分块、异步索引、检索证据和资料不足降级。

范围：公共库固定为 `product-support`，所有正式账号可读取，只有数据库 ADMIN 可上传、重试和删除；私人默认库和自建库仍按 JWT owner 隔离，管理员不具备越权读取能力。检索结果用 `evidenceSufficient` 区分有无依据，命中片段带文件名、页码或片段编号；依据不足时返回 `nextAction` 引导手动建单。

完成标准：能解释为什么公共库使用固定命名空间、为什么页面上的 writable 不能替代写接口二次校验、如何避免公共权限穿透私人文档，以及 RAG 为什么必须返回可核对出处并允许资料不足。

## 售后模块 4：售后 Agent 与工具边界（已实现、自动验证）

入口：`SupportAssistantController`、`SupportAgentInstruction`、`SupportTools`、`SupportToolContext`、`ResearchOrchestrator`（`AgentMode`）、`SupportTicketService.draft/createdBy`。

学习：LangChain4j `@Tool` 子集选择、服务端模式与系统指令绑定、ThreadLocal 认证上下文、只读工具设计、草稿与业务写入分离、幂等查询接口、提示注入边界、失败降级。

范围：新增 `POST /api/v1/support/assistant/stream`，服务端固定 `SUPPORT` 模式，使用售后专用指令与只读工具集（订单/工单/分类查询、知识检索、草稿）。工具不接受身份参数，身份来自 `SupportToolContext`。草稿不写库；用户确认后由 `POST /api/v1/support/tickets` 建单，`GET /api/v1/support/ticket-drafts/{requestId}` 用于防重复提交。模型失败只结束本次会话，手动流程不受影响。研究与售后共用 `ResearchSseStreamer` 的取消、超时和队列保护。

完成标准：能解释为什么模式必须由服务端决定、为什么工具参数里不能有 userId、为什么“生成草稿”和“创建工单”必须分成两个入口、为什么模型失败不能让业务不可用，以及文档内容为什么不能覆盖系统指令。

## 售后模块 5：三端页面与完整演示（已实现、真实浏览器验收）

入口：`static/support.html`、`static/support.js`、`static/support.css`。

学习：按服务端角色渲染导航、浏览器端解析 SSE、草稿与业务写入分离、乐观版本冲突的前端处理、状态机与审计记录的可视化。

范围：用户端（售后助手对话与草稿确认、我的工单、工单详情）、客服端（待受理队列、我的处理工单、接单/回复/方案）、管理端（分类、分配、产品与订单、知识库）。工单详情用状态脊线把四态流程与审计事件放在同一时间轴。所有写操作携带 `expectedVersion`，冲突时提示并重新加载。为支撑页面新增两个只读接口：管理员账号目录与全部模拟订单视图。

完成标准：能解释为什么页面隐藏按钮不能代替后端权限、为什么草稿必须先展示再确认、为什么冲突后要重新加载而不是盲目重试，以及状态脊线与审计事件为什么来自同一次读取。

演示步骤、权限拒绝清单与限制见 [SUPPORT_WORKBENCH.md](SUPPORT_WORKBENCH.md)。页面验收使用 Playwright + Chromium headless，覆盖三种角色导航、工单列表、状态脊线、审计条数与游客拒绝；完整闭环脚本为 `scripts/verify-support-workbench.cjs`。

本轮（2026-09-17）在本模块内另外修复了两个页面缺陷并加固了验收脚本，值得作为前端学习点：

1. **长草稿弹窗**：问题描述最长 4000 字会把确认按钮挤出视口，仅限制宽度不够，必须同时给出 `max-height` 与 `overflow-y`，弹窗内部可滚动。
2. **并发渲染竞态**：`start()` 与 `hashchange` 会并发调用同一个 `dispatch()`，先发起、后返回的那次会把新页面覆盖成过期数据。修复方式是给每次渲染编号，只有最新一次允许写 DOM（`dispatchToken`）。这类「异步结果乱序」的坑在前端和在后端（条件更新、版本号）本质相同。
3. **验收脚本自身的坑**：`page.goto()` 到只差 hash 的同文档 URL 不会重新加载文档；用页面文案判定状态会匹配到历史事件。脚本改为整页 `reload` + 轮询后端状态，否则会出现「功能坏了但断言通过」或「功能正常但断言失败」。

后续工作：

1. 模块 4 与模块 5 本轮改动的独立审查记录已完成，见 [reviews/2026-09-17-module-5.md](reviews/2026-09-17-module-5.md)。
2. 跨浏览器与移动端验收、页面用例纳入自动测试、知识库文档状态自动刷新。
3. 页面遗留的已知限制：被拒路由重定向会在历史里留一条记录（尝试用 `location.replace` 修复会减少正常导航历史条目，已回退）；客服端没有知识库等入口。

工单接口、独立表和虚构 HTTP 演示见 [SUPPORT_DATABASE.md](SUPPORT_DATABASE.md) 与 [SUPPORT_DEMO.md](SUPPORT_DEMO.md)。

模块 1 新增 17 项自动测试。先阅读 SupportTicketServiceTest、SupportTicketHttpTest、SupportConfigurationTest，解释真实并发、快照读取与审计故障注入。

模块 2 新增 9 项自动测试。先阅读 ProductOrderServiceTest 与 SupportOrderHttpTest，解释 owner SQL、造单幂等、事务审计和只读订单边界；再读 SupportConfigurationTest 的旧表升级用例。

模块 3 新增 5 项自动测试。先阅读 SupportKnowledgeHttpTest，解释公共读/管理员写、私人 404、系统域哈希和旧命名空间碰撞回归；再读 KnowledgeBaseServiceTest 的依据不足与 PDF 出处断言。

模块 4 新增 9 项自动测试，全量 124 项（1 项真实 MySQL 跳过）。先阅读 SupportAgentOrchestrationTest，用可编程模型替身解释系统指令、工具子集、伪造身份无效、草稿需确认、注入内容和失败降级；再读 SupportAssistantHttpTest 的 401/403 与 Demo 模式不建单，以及 SupportTicketHttpTest 的草稿 requestId 隔离用例。

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
