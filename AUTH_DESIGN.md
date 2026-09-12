# 登录注册模块说明

## 这部分解决什么问题

未登录的本地版本把所有数据归到 `local-user`。开启认证后，每个账号需要拥有独立的会话、任务、记忆和知识库，后端不能相信前端传来的用户编号。

## 完整链路

```text
注册：用户名和密码 -> 参数校验 -> BCrypt 哈希 -> user_account -> 签发 JWT
登录：用户名和密码 -> 查询哈希 -> BCrypt 校验 -> 签发 JWT
请求：Bearer JWT -> Spring Security 验签及校验过期时间 -> Principal.sub -> OwnerResolver
业务：owner + 资源 ID -> Repository 条件查询 -> 返回当前用户的数据
```

JWT 使用 HS256，包含 `iss=zhida`、`sub=用户 UUID`、签发时间、过期时间和随机 `jti`。服务端要求 `ZHIDA_JWT_SECRET` 至少 32 字节。令牌有效期一小时；尚未过期且剩余不足五分钟时，前端调用 `/api/v1/auth/refresh` 更新令牌。

密码没有明文入库。`BCryptPasswordEncoder(12)` 为每个密码生成独立盐并计算哈希；登录时用 `matches` 校验。用户名统一转为小写。密码限制为 8～64 个字符且 UTF-8 不超过 72 字节，这是 BCrypt 的输入边界。

资源隔离依赖服务端解析出的 `Principal.sub`。Controller 通过 `OwnerResolver` 获得 owner，Repository 的查询和修改同时匹配 `user_id` 与资源 ID。访问其他用户的资源返回 404，既拒绝访问，也不暴露该资源是否存在。前端传入的会话 ID、任务 ID或知识库 ID不能改变 owner。

游客入口会在同一数据库事务中写入账号和来源摘要。来源摘要由服务端 secret 与客户端 IP 计算，不保存原始 IP，用于让同一来源共享游客每日额度。认证接口另有每 IP 每分钟限制。

## 主要接口

| 接口 | 用途 | 是否需要 JWT |
| --- | --- | --- |
| `GET /api/v1/auth/config` | 告诉页面是否开启认证 | 否 |
| `POST /api/v1/auth/register` | 注册并返回 JWT | 否 |
| `POST /api/v1/auth/login` | 校验密码并返回 JWT | 否 |
| `POST /api/v1/auth/guest` | 创建游客并返回 JWT | 否 |
| `GET /api/v1/auth/me` | 验证令牌并读取当前账号 | 是 |
| `POST /api/v1/auth/refresh` | 在旧令牌有效时续期 | 是 |

## 测试覆盖

`AuthIsolationTest` 通过真实 HTTP 请求覆盖注册、重复用户名、错误密码、正确登录、读取当前账号、令牌刷新、游客入口、匿名访问失败、伪造令牌失败，以及两个账号之间的会话、任务、记忆和文档隔离。

## 简历写法

可以写：

> 基于 Spring Security Resource Server 与 Nimbus JWT 实现注册、登录、游客访问及无状态鉴权，使用 BCrypt（cost 12）存储密码；以 JWT `sub` 作为服务端用户身份，在会话、任务、长期记忆和 RAG 知识库的数据访问层实施 owner 约束，并通过双用户 HTTP 集成测试验证越权访问拦截。

面试时不要说已经实现“完整用户中心”。当前没有邮箱验证、密码找回、后台账号管理和 JWT 撤销列表；退出登录只会清除浏览器 `sessionStorage` 中的令牌，已签发令牌会在一小时后自然失效。公网产品还应考虑 HttpOnly Cookie 与 CSRF、短 Access Token 加可撤销 Refresh Token、密钥轮换和反向代理下的分布式限流。

## 面试回答示例

如果面试官问“登录链路怎么跑”，可以回答：

> 注册时我先校验用户名和密码，用 BCrypt cost 12 生成带随机盐的哈希，只把哈希保存到 MySQL。登录时根据规范化后的用户名查出哈希，再用 `matches` 校验。成功后服务端用 HS256 签发一小时 JWT，把用户 UUID 放在 `sub`。后续请求由 Spring Security 的 Resource Server 过滤器读取 Bearer Token，Nimbus 负责验签并校验 issuer 和过期时间，验证成功后把 `sub` 放进 Principal。业务接口只从 Principal 获取 owner，所有资源查询都同时匹配 owner 和资源 ID，所以修改前端参数不能读取别人的数据。

如果追问“为什么不用 Session”，可以回答：

> 这个项目的后端主要提供 API 和 SSE，JWT 无状态鉴权不需要服务端保存会话，部署和横向扩展更直接。代价是令牌签发后不容易立即撤销，所以当前版本只适合学习演示；正式上线会使用更短的 Access Token，并增加可撤销的 Refresh Token 或令牌版本机制。
