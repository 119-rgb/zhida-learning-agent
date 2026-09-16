# 售后知识库设计与学习说明

模块 3 复用现有 `KnowledgeBaseService`，没有另造一套 RAG。上传后先保存原文和目录记录，再由有界线程池解析 PDF/TXT/Markdown、分块、调用 Embedding 并写入向量索引。PDF 每页单独解析，因此片段能保留页码；文本没有页码时用片段编号定位。

## 公共与私人边界

| 类型 | 用户可见 ID | 读取 | 上传/重试/删除 | 内部命名空间 |
| --- | --- | --- | --- | --- |
| 公共产品售后库 | `product-support` | 所有正式账号 | 仅 ADMIN | 带系统域分隔的固定哈希 |
| 用户默认库 | `default` | 当前 JWT 用户 | 当前 JWT 用户 | owner 与 ID 生成的隔离键 |
| 用户自建库 | UUID | 创建者 | 创建者 | 校验归属后生成隔离键 |

`KnowledgeBaseAccessService` 是唯一解析边界。公共库读取先确认账号是售后模块中的正式账号，写入再从数据库检查 ADMIN。集合接口里的 `writable` 只帮助页面显示按钮，不是授权凭据；上传、重试和删除接口每次都会重新校验。管理员对公共库有维护权，但访问其他用户自建库仍返回 404。

公共库不在 `knowledge_base` 表里创建用户归属行，而是售后模块提供的固定资源。这样所有用户检索同一个向量命名空间，私人库仍沿用原有 owner 隔离。内部使用超过外部 ID 长度上限的系统域哈希，避免升级前名为 `product-support` 的本地私人命名空间被误公开；HTTP 响应仍展示逻辑 ID。公共库未启用时保留字也不会退化为某个本地用户的私人命名空间。可重复演示资料位于 [demo/fictional-product-support.md](demo/fictional-product-support.md)，内容和承诺均明确标记为虚构。

## 检索响应与降级

`KnowledgeSearchResponse` 返回：

- `results`：片段正文、相似度、文件名、页码或片段编号；
- `evidenceSufficient`：是否实际取得可用片段；
- `message`：本次检索结论；
- `nextAction`：依据不足时等待处理、补充资料或创建售后工单的建议。

页面或 Agent 只能在 `evidenceSufficient=true` 时把片段作为知识依据，并应展示文件名与页码/片段编号。`false` 不等于业务失败：用户仍能绕过模型，使用模块 1 的手动建单接口。

## 学习顺序

1. 从 `KnowledgeController` 看读操作与写操作如何选择不同的授权解析方法。
2. 读 `KnowledgeBaseAccessService`，解释固定公共命名空间、数据库角色重查和私人 owner 校验。
3. 读 `KnowledgeBaseService.submit/process/parseAndChunk/search`，画出原文保存、异步处理、向量化和出处返回链路。
4. 读 `SupportKnowledgeHttpTest` 与 `KnowledgeBaseServiceTest`，解释公共共享、写入拒绝、私人隔离、证据不足和 PDF 页码断言。

自动测试使用隔离 H2 和向量索引替身，不调用付费模型或真实 Embedding。真实 MySQL、真实向量供应商和浏览器展示仍须分别验收；当前 JSON 向量存储按单实例设计。
