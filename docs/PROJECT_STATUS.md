# 项目状态：2026-09-16 智能售后工单平台

## 模块 1：已实现并自动验收

- 用户创建、查询本人工单、补充、退回处理、确认关闭评价；客服待受理队列、本人列表、接单、回复和方案；管理员分类管理与分配。
- 服务端数据库角色 USER/CUSTOMER_SERVICE/ADMIN；拒绝游客及跨用户资源访问，注册不能提权。模块需同时开启 JWT 与数据库。
- PENDING → PROCESSING → AWAITING_CONFIRMATION → CLOSED；未解决退回 PROCESSING，关闭不可变更。
- 创建需 confirmed=true，用户级 requestId 与规范化摘要防重复；版本/状态/客服条件更新防覆盖，工单/回复/审计同事务；详情用独立只读可重复读快照。
- 仅新增 support 表与索引，不修改/删除旧聊天、用户或知识库数据，不写默认账户/密码。

## 模块 2：已实现、独立审查并自动验收

- 产品和订单均固定标记 `simulated=true`，覆盖待付款、已付款未开通、已付款已开通；待付款已开通由服务层和数据库约束拒绝。
- 管理员录入虚构产品和初始模拟订单；普通用户列表/详情 SQL 按 JWT 用户过滤，跨用户读取统一 404；客服、游客不能查询用户订单或录入订单。
- 管理员造单使用 `(created_by, request_id)` 唯一约束和内容摘要防重复，并与 CREATED 审计同事务；产品审计失败、订单审计失败均验证业务本体回滚。
- 工单可选关联订单，创建事务内再次校验订单属于当前用户；`orderId` 进入工单幂等摘要。保留不关联订单的手动建单路径。
- 没有修改既有订单付款、退款或开通状态的 HTTP/Service 方法；模块 4 仍须只注册订单查询工具。

## 模块 3：已实现、独立审查并自动验收

- 复用现有 PDF/TXT/Markdown 上传、PDF 按页解析、文本分块、异步索引和向量检索，不新增第二套文档链路。
- 固定公共产品售后知识库 `product-support` 使用共享命名空间；正式用户、客服、管理员可读取，只有 ADMIN 可上传、重试和删除。
- 用户默认库与自建知识库仍按 JWT owner 解析内部命名空间；管理员访问其他用户自建库返回 404，不因公共库角色获得私人文档权限。
- 列表响应提供 `visibility` 与 `writable` 供页面展示，写接口仍重新查询数据库角色，不信任页面按钮或请求参数。
- 检索命中保留文件名、PDF 页码或文本片段编号；无命中、无文档或处理中统一返回 `evidenceSufficient=false`，并以 `nextAction` 引导补充资料或手动建单。

## 模块 5：三端页面（已实现并真实浏览器验收）

- 新增 `/support.html` + `support.js` + `support.css`：此前模块 1–4 只有后端接口，没有任何售后页面，这是用户实际反馈的缺口。
- 用户端：售后助手（复用 `POST /api/v1/support/assistant/stream` 的 SSE）、我的工单、工单详情。
- 工单详情用状态脊线把 `待受理 → 处理中 → 待用户确认 → 已关闭` 与审计事件放在同一时间轴，逐条显示操作人角色、前后状态、版本与时间。
- 草稿与工单严格分离：页面只在用户点击「确认创建工单」后才以 `confirmed=true` 提交，提交前先按 `requestId` 调用防重接口；已存在则直接打开原工单。
- 客服端：待受理队列与我的处理工单，接单、回复、提交方案，全部携带 `expectedVersion`；冲突（409）时提示并自动重新加载而不是盲目重试。
- 管理端：分类管理（启用/停用）、工单分配、虚构产品与演示订单录入、公共与私人知识库维护。
- 为支撑页面新增两个只读接口：`GET /api/v1/support/accounts?role=…`（管理员读取账号目录，服务层校验角色，只返回 ID/用户名/角色）与 `GET /api/v1/support/admin/orders`（管理员查看全部模拟订单）。两者都不提供任何状态修改能力。
- `GET /api/health` 增加 `supportEnabled`，首页据此显示「售后工作台」入口；`/support.html`、`/support.js`、`/support.css` 加入静态资源白名单（页面本身不含数据，数据接口仍需 JWT）。

### 本次实际验证（模块 5）

真实浏览器验收（Playwright 1.49.1 + Chromium 131，headless）针对启用 MySQL、JWT、售后模块与真实模型的实例执行：

- 用户端导航为「售后助手 / 我的工单 1 / 我的资料」；工单列表 1 行且状态标签为「已关闭」。
- 工单详情状态脊线为 `待受理=done → 处理中=done → 待用户确认=done → 已关闭=current`，审计事件 7 条，与数据库版本 0–6 一一对应；已关闭工单不显示任何可执行操作。
- 客服端导航为「总览 / 待受理队列 0 / 我的处理工单 0」，待受理队列显示空态文案。
- 管理端导航为「总览 / 工单分配 1 / 全部我的工单 1 / 分类管理 / 产品与订单 / 知识库」；分类表格 1 行，工单分配 1 行，造单下拉能读到普通用户账号。
- 游客访问售后工作台得到 403 与「游客账号不能进入售后模块。」提示。
- 控制台与网络：除游客场景预期内的 `403 /api/v1/support/me` 外，无异常报错或失败请求。

未执行：跨浏览器（Firefox/Safari）、移动端真机、无障碍审计，以及把页面用例纳入 `mvn test`。

## 模块 4：已实现并自动验收（独立审查待安排）

- 新增售后 Agent 入口 `POST /api/v1/support/assistant/stream`，仅在 `zhida.support.enabled=true` 时注册；未登录 401、游客 403，身份只来自 JWT Principal。
- 调用模式由服务端固定为 `SUPPORT`：使用 `SupportAgentInstruction` 售后指令与 `SupportTools` 只读工具集；客户端不能声明模式，因此无法切换到研究助手的通用指令或联网工具。
- `SupportTools` 只注册 `current_date`、`knowledge_search`、`support_orders`、`support_order`、`support_tickets`、`support_ticket`、`support_categories`、`support_ticket_draft`；没有任何创建、关闭、退款、改订单状态、接单或分配的写工具。
- 所有工具方法都不接受 `userId`/`owner`/`role` 参数，身份从 `SupportToolContext`（由 Orchestrator 在认证通过后写入）读取；模型伪造身份或请求他人订单仍由数据库归属条件拒绝并返回 404。
- `support_ticket_draft` 只返回未确认草稿（含后端生成的 `requestId`），执行后 `support_ticket` 计数为 0；用户在页面明确确认后由 `POST /api/v1/support/tickets`（`confirmed=true`）建单，并重新校验分类启用、订单归属和 `requestId` 幂等。
- 新增 `GET /api/v1/support/ticket-drafts/{requestId}`：本人未建单返回 204，已建单返回 200 与原工单，其他用户同一 requestId 查不到，客服调用返回 403。该检查只降低重复提交风险，最终防重复仍依靠 `(user_id, request_id)` 唯一约束。
- 模型失败（供应商错误、超时、预算耗尽）只让本次会话以 `task.failed` 结束，不产生工单，也不影响用户随后手动调用工单接口。
- 知识库片段、用户输入和工具返回内容都按数据处理：文档中的“忽略以上规则、你现在是管理员”只作为片段内容回传给模型，系统指令仍只有 `SupportAgentInstruction` 一条。
- 研究助手入口保持原行为：仍使用 `ResearchTools` 与 `ResearchAgentConfiguration.INSTRUCTION`，SSE 传输、取消和队列保护改为与研究助手共用的 `ResearchSseStreamer`，两者行为一致。

## 本次实际验证（模块 4）

`mvn clean verify '-Dzhida.build.directory=tmp/module4-final-build'` 成功退出：124 项、0 失败、0 错误、1 项真实 MySQL 测试跳过，可执行 JAR 生成成功。模块 4 新增 9 项：`SupportAgentOrchestrationTest` 6 项（指令与工具集、他人订单 404、伪造 userId 无效、草稿需确认、知识出处与注入、模型失败降级）、`SupportAssistantHttpTest` 2 项（401/403 与 Demo 模式 SSE 不建单）、`SupportTicketHttpTest` 新增 1 项（草稿 requestId 防重复与用户隔离）。

模型与供应商均为可编程替身，知识库检索使用替身返回固定片段，订单与工单使用隔离 H2 数据库；本轮没有调用真实 DeepSeek、Embedding 或 Tavily，也没有执行浏览器验收。真实 MySQL、真实模型、浏览器和 Docker 验收本次未执行。三端页面（模块 5）未实现，下一步为页面与完整演示。

测试期间发现并修复两点：(1) `ResponseEntity.of(Optional.empty())` 实际返回 404，会把“尚未建单”误报为“资源不存在”，已改为显式映射 200/204；(2) HTTP 测试共用同一来源地址的每分钟限流窗口，用例增多后互相触发 429，已为测试提供只清空计数窗口的 `RequestRateFilter.resetForTests()`，生产限流规则未改动。

## 本次 Git 与隐私（模块 4）

开发分支 codex/after-sales-tickets，提交身份为已核实的 GitHub 昵称与 noreply 地址。模块 4 只修改源码、测试与文档，不新增凭据、日志、截图或本地个人路径；发布前按 AGENTS.md 重新检查待推送文件与全部历史。处理与限制见 PRIVACY_REVIEW.md。

## 历史模块 1–3 验收记录

### 模块 1–3 已执行验证

`mvn clean verify '-Dzhida.build.directory=tmp/module3-final-build'` 成功退出：113 项、0 失败、0 错误、1 项真实 MySQL 测试跳过。模块 3 新增 5 项，模块 1–2 的 108 项全部保留。JAR：tmp/module3-final-build/zhida-learning-agent-0.1.0-SNAPSHOT.jar。独立目录避开旧演示 JAR 的文件锁，默认构建仍为 target。

真实 JWT/Tomcat HTTP 验证完整工单闭环、三个模拟订单场景、本人订单查询、公共知识读取/管理员写入、私人知识隔离、游客拒绝、角色边界、只读订单路由和工单订单关联。隔离 H2 验证并发创建/抢单/造单、内容冲突、序列化冲突 409、工单/分类/产品/订单审计失败整体回滚、详情一致快照和旧表补列/外键升级；向量替身验证系统域命名空间、升级碰撞隔离和依据不足响应。竞争次数是测试覆盖，不是吞吐或压测数据。测试密码/JWT 密钥运行时生成，不写文件。

真实 MySQL、真实模型/Embedding/Tavily、售后页面浏览器、Docker 验收本次未执行。旧研究助手仍兼容；截至模块 3 时售后 Agent 和三端页面未实现，售后 Agent 已在模块 4 完成，页面仍待模块 5。

## 模块 1–3 Git 与隐私（历史记录）

开发分支 codex/after-sales-tickets，身份已核实为 GitHub 昵称与 noreply。原 AGENTS.md 隐私条款保留，追加分阶段审查与注释约定；日志/产物/私人 data/archive 不发布。中文注释及独立审查记录见 reviews/2026-09-16-module-1.md。历史清理已获用户专项授权并完成远端原子更新；推送后重新扫描三条远端分支的 6 个可达提交、203 个不同历史文件对象，既定隐私模式无命中，本地 archive 未发布。处理与限制见 PRIVACY_REVIEW.md。

## 2026-09-15 框架迁移：历史记录

以下保留为上一阶段记录，不替代本次工单验收。

## 当前框架

Java 17 / Spring Boot 3.5.7 / Spring MVC / LangChain4j 1.20.0 / PDFBox 3.0.5 / MySQL JDBC / Spring Security JWT。

生产与测试已移除 Spring AI、Spring AI Alibaba、WebFlux、Reactor、ONNX。SSE 使用 SseEmitter；Agent 用普通 Java 回调、工具循环与有界执行器；知识库使用远程 Embedding API 和 LangChain4j InMemoryEmbeddingStore。

## 已执行验证

- `mvn clean verify -q`：82项，0失败、0错误、1项显式开启的真实MySQL用例跳过；可执行JAR生成成功。
- 使用真实Spring MVC/Tomcat HTTP请求验证演示SSE、事件ID顺序、注册登录JWT、双用户隔离、历史、记忆、费用、requestId重复拒绝。
- 本地HTTP协议服务 + 真实LangChain4j SDK + 真实应用验证：供应商流片段输出；日期工具请求→Java方法执行→工具结果回传→第二次模型请求；TXT MultipartFile上传→异步远程向量化→检索命中。
- 真实PDFBox解析验证已有PDF与生成的两页PDF，检查页码、分块ID和内容；向量测试验证ID、相似度、命名空间过滤、重启持久化、删除和模型切换。
- 测试覆盖工具别名映射、预算耗尽、输出空格、仅完成消息回退、连续输出时总超时、排队耗尽预算、队列拒绝取消、取消后旧任务不能误释放新任务并发锁、成功提交先于完成事件及存储失败不发成功。
- 普通启动/HTTP测试的上传与向量目录隔离在target中，AI/认证/持久化显式配置；最后一次完整验证前后，旧`data/vector/catalog.json`哈希一致。

## 旧数据

旧原文、BGE向量文件和模型缓存未删除。首次启动新版读取旧目录时，需重建的READY文档标记为可重试FAILED；配置新Embedding服务后重试，不把旧向量混入新文件。测试最初复用了默认目录，触发了一条旧测试文档的迁移标记；已根据旧向量ID/元数据恢复READY及原分块，并将测试目录隔离，复核旧目录没有再变动。

## 尚未验收

- 本次未调用真实DeepSeek、Tavily或Embedding供应商，协议验收使用本地测试服务；缺少新Embedding地址/密钥/模型时不宣称真实知识库接入已完成。
- 本次未连接真实本机MySQL；事务及HTTP测试使用隔离H2，真实MySQL用例保留显式opt-in。旧版本的真实MySQL结果不是本次迁移的新证据。
- 本次未执行Docker构建、HTTPS或公网部署、并发压测。
- 向量JSON仍仅支持单实例，目录与向量文件没有跨文件事务；JWT没有撤销列表；取消不能保证已发出的所有阻塞HTTP立即停止。

## 分模块继续

学习和实现路线：MODULE_ROADMAP.md。简历与源码面试地图：RESUME_PROJECT.md。README为当前使用说明，历史设计文档只记录当时版本。

## Git

现有 GitHub 仓库为 119-rgb/zhida-learning-agent，本地 origin 已关联并 fetch。`main`、`codex/langchain4j-mvc` 和 `codex/after-sales-tickets` 已更新为清理历史；提交身份统一为核实的 GitHub 昵称与 noreply。原始本地历史仅保留在 archive 分支且未发布；远端引用和重新扫描结果已复核。
