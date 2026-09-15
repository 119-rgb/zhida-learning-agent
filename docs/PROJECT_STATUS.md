# 项目状态：2026-09-15 框架迁移

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

现有GitHub仓库为119-rgb/zhida-learning-agent，本地origin已关联并fetch；两边原有内容与历史已检查。迁移分支保留远程main祖先，未强推。未发布历史中的实名提交身份改为GitHub别名，原始本地历史保留在archive分支；不推送archive分支。以实际远程ref与本地HEAD对齐验证作为发布完成证据。
