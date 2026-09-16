# 项目状态：2026-09-16 智能售后工单平台

## 模块 1：已实现并自动验收

- 用户创建、查询本人工单、补充、退回处理、确认关闭评价；客服待受理队列、本人列表、接单、回复和方案；管理员分类管理与分配。
- 服务端数据库角色 USER/CUSTOMER_SERVICE/ADMIN；拒绝游客及跨用户资源访问，注册不能提权。模块需同时开启 JWT 与数据库。
- PENDING → PROCESSING → AWAITING_CONFIRMATION → CLOSED；未解决退回 PROCESSING，关闭不可变更。
- 创建需 confirmed=true，用户级 requestId 与规范化摘要防重复；版本/状态/客服条件更新防覆盖，工单/回复/审计同事务；详情用独立只读可重复读快照。
- 仅新增 support 表与索引，不修改/删除旧聊天、用户或知识库数据，不写默认账户/密码。

## 本次实际验证

`mvn clean verify -q '-Dzhida.build.directory=tmp/support-build'` 成功退出：99 项、0 失败、0 错误、1 项真实 MySQL 测试跳过。新增 17 项，保留原 82 项。JAR：tmp/support-build/zhida-learning-agent-0.1.0-SNAPSHOT.jar。独立目录避开旧演示 JAR 的文件锁，默认构建仍为 target。

真实 JWT/Tomcat HTTP 验证完整闭环、记录、评价、用户隔离、游客/普通用户越权、角色伪造、分配与分类停用。隔离 H2 验证并发创建和抢单（各 5 次竞争）、内容冲突、序列化冲突 409、创建/回复/分类审计失败整体回滚、详情并发一致快照。竞争次数是测试覆盖，不是吞吐或压测数据。新增 HTTP 测试的密码/JWT 密钥运行时生成，不写文件。

真实 MySQL、真实模型/Embedding/Tavily、售后页面浏览器、Docker 验收本次未执行。旧研究助手仍兼容，订单、售后 RAG、售后 Agent 和三端页面未实现；下一步为虚构产品与订单及归属校验。

## 本次 Git 与隐私

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
