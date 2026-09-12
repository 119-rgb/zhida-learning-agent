# 知答项目说明

知答是一个面向学习与资料研究的 Java Agent。它使用 Spring Boot 3、Spring AI 和 Spring AI Alibaba 构建，聊天模型使用 DeepSeek。

系统目前提供三类工具：Tavily 联网搜索、安全网页正文读取、本地知识库检索。用户可以看到任务计划、工具名称、调用参数、执行耗时和结果摘要，但不会看到模型隐藏的思维链。

本地知识库支持 PDF、TXT 和 Markdown。上传后，系统先校验文件，再计算 SHA-256 防止重复上传，然后解析文本并按自然句子边界切片。切片通过本地中文 BGE ONNX 模型转换成向量，最终保存到 SimpleVectorStore。

SimpleVectorStore 适合本地学习和演示，不适合生产环境。后续数据量增大时，可以把知识索引层替换成 PostgreSQL pgvector 或 Redis Vector，而不需要重写 Agent 工具和上传接口。
