# 售后工单 HTTP 演示（模块 1–4）

此演示使用虚构业务，面向已启用售后、JWT 与持久化的隔离环境。当前没有售后网页流程；以下是 HTTP 调用顺序，不代表用户、客服或管理员页面已经完成。先按 [SUPPORT_DATABASE.md](SUPPORT_DATABASE.md) 配置数据库和角色，并分别取得虚构用户、客服、管理员的 JWT。

示例中的 `<user-jwt>`、`<agent-jwt>`、`<admin-jwt>`、`<user-id>`、`<product-id>`、`<order-id>`、`<ticket-id>` 与 `<category-id>` 均为占位符，不能替换为或记录真实凭据。

## 0. 管理员维护公共售后知识库

知识库列表会展示公共库 `product-support`。管理员可上传虚构产品说明；普通用户和客服只能读取、检索，上传会返回 403。

```http
POST /api/v1/knowledge-bases/product-support/documents
Authorization: Bearer <admin-jwt>
Content-Type: multipart/form-data

file=@docs/demo/fictional-product-support.md
```

文档变为 READY 后，用户可检索：

```http
GET /api/v1/knowledge-bases/product-support/search?q=付款后为什么没有开通&topK=5
Authorization: Bearer <user-jwt>
```

命中结果包含 `filename`、PDF 的 `pageNumber` 或文本的 `chunkIndex`。没有可用片段时返回 `evidenceSufficient=false` 和 `nextAction`，明确提示资料不足并允许继续走手动建单流程。私人知识库仍属于创建用户，管理员不能借公共库维护权限访问。

## 1. 管理员创建一个虚构分类

```http
POST /api/v1/support/categories
Authorization: Bearer <admin-jwt>
Content-Type: application/json

{"name":"虚构订阅服务","enabled":true}
```

创建工单必须选择有效分类，初始分类列表为空。非管理员只能读取启用的分类，不能创建或修改。

## 2. 管理员录入虚构产品与模拟订单

```http
POST /api/v1/support/products
Authorization: Bearer <admin-jwt>
Content-Type: application/json

{"sku":"DEMO-SUB-PRO","name":"虚构专业版订阅","description":"仅用于项目演示，不代表真实商品。"}
```

管理员用用户账号 ID、产品 ID 和幂等 requestId 创建演示订单。下面是“已付款未开通”场景：

```http
POST /api/v1/support/admin/orders
Authorization: Bearer <admin-jwt>
Content-Type: application/json

{
  "requestId":"demo-order-paid-not-active-001",
  "userId":"<user-id>",
  "productId":"<product-id>",
  "paymentStatus":"PAID",
  "serviceStatus":"NOT_ACTIVATED"
}
```

另两个合法组合为 `PENDING_PAYMENT + NOT_ACTIVATED` 和 `PAID + ACTIVATED`。待付款却已开通会返回 400。产品与订单响应固定包含 `simulated=true`，接口不会连接真实支付或服务开通系统。

## 3. 用户查询自己的订单

```http
GET /api/v1/support/orders
Authorization: Bearer <user-jwt>
```

也可查询 `GET /api/v1/support/orders/<order-id>`。服务端从 JWT 获取用户并把 `user_id` 放进查询条件；其他用户访问同一订单返回 404。没有付款、退款或开通状态修改接口。

## 4. 用户创建工单并关联订单

```http
POST /api/v1/support/tickets
Authorization: Bearer <user-jwt>
Content-Type: application/json

{
  "title":"虚构订单已付款但服务未开通",
  "description":"虚构场景：我的订单已经付款，但服务没有开通。",
  "categoryId":"<category-id>",
  "orderId":"<order-id>",
  "requestId":"demo-subscription-refresh-001",
  "confirmed":true
}
```

成功创建为 `PENDING` 并返回 201。后端只接受当前用户自己的订单；不存在或跨用户订单返回 404。相同用户以相同 `requestId` 重放完全相同的规范化内容，也返回同一工单（201）；用同一 `requestId` 改变标题、描述、分类或订单时返回 409。不关联订单时可省略 `orderId`，保留手动建单降级路径。

## 5. 客服查看并接单

```http
GET /api/v1/support/tickets?view=pending
Authorization: Bearer <agent-jwt>
```

```http
POST /api/v1/support/tickets/<ticket-id>/claim
Authorization: Bearer <agent-jwt>
Content-Type: application/json

{"expectedVersion":0}
```

接单后工单进入 `PROCESSING`。两个客服同时以同一版本接单时，只有一个条件更新可以成功，另一个请求应收到冲突结果。

## 6. 补充、方案与退回处理

用户可在未关闭前补充虚构信息：

```http
POST /api/v1/support/tickets/<ticket-id>/comments
Authorization: Bearer <user-jwt>
Content-Type: application/json

{"expectedVersion":1,"content":"虚构补充：已重新打开页面，状态仍未更新。"}
```

已接单客服回复或提交方案。每次成功变更都会更新版本，因此调用前先读 `GET /api/v1/support/tickets/<ticket-id>`，使用返回工单的最新版本：

```http
POST /api/v1/support/tickets/<ticket-id>/solution
Authorization: Bearer <agent-jwt>
Content-Type: application/json

{"expectedVersion":2,"content":"虚构方案：重新登录后刷新订阅状态。"}
```

方案提交后状态为 `AWAITING_CONFIRMATION`。若虚构用户仍未解决，可调用 `reopen` 并携带当前版本和说明，状态回到 `PROCESSING`；随后客服可提交新方案。

## 7. 用户确认与评价

```http
POST /api/v1/support/tickets/<ticket-id>/confirm
Authorization: Bearer <user-jwt>
Content-Type: application/json

{"expectedVersion":3,"rating":5,"evaluation":"虚构评价：刷新后已恢复。"}
```

确认仅允许工单发起人在 `AWAITING_CONFIRMATION` 阶段操作。评分必须是 1–5，评价可以为空；成功后状态为 `CLOSED`。已关闭工单不能继续回复、补充、接单、分配或再次确认。

## 查询和权限检查

`GET /api/v1/support/tickets?view=mine` 为默认查询：用户只看到自己的工单，客服只看到本人处理的工单。客服可用 `view=pending` 查看待受理队列，管理员可用 `view=all` 查看全部记录。`GET /api/v1/support/tickets/<ticket-id>` 返回 `ticket`、`replies`、`events`。

## 8. 售后 Agent 会话与确认建单（模块 4）

售后助手入口使用与研究助手相同的请求体，服务端固定为售后模式。未登录返回 401，游客返回 403。

```http
POST /api/v1/support/assistant/stream
Authorization: Bearer <user-jwt>
Content-Type: application/json
Accept: text/event-stream

{"message":"我的订单已经付款，但服务没有开通","conversationId":"demo-assistant-001"}
```

事件顺序与研究助手一致：`task.started`、`plan.created`、`step.started`、`answer.started`、`answer.delta`、可能的 `tool.started`/`tool.completed`、`task.completed`；失败时为 `task.failed`。没有配置真实模型时进入演示模式，只说明未调用模型，不会生成草稿。

模型可用的工具只有 `current_date`、`knowledge_search`、`support_orders`、`support_order`、`support_tickets`、`support_ticket`、`support_categories`、`support_ticket_draft`。没有任何创建、关闭、退款、改订单状态、接单或分配的写工具，也没有联网搜索。

`support_ticket_draft` 返回的是**未确认草稿**，例如 `{"requestId":"...","title":"...","description":"...","categoryId":"...","categoryName":"...","orderId":"...","confirmed":false,"nextAction":"..."}`，并且不会写入数据库。用户核对后再显式调用建单接口（第 4 步），`requestId` 使用草稿返回的值。

提交前可先检查该 `requestId` 是否已经建单，避免重复：

```http
GET /api/v1/support/ticket-drafts/<draft-requestId>
Authorization: Bearer <user-jwt>
```

`204 No Content` 表示尚未建单，可以继续提交；`200` 与工单体表示该 `requestId` 已使用，页面应直接展示原工单。该查询只作用于当前用户，其他用户用同一 `requestId` 查不到，客服调用返回 403。

模型不可用时（供应商报错、超时、工具预算耗尽）只会让这次会话以 `task.failed` 结束，不会创建工单；第 4 步的手动建单和第 5–7 步的客服处理流程仍然可用。

## 自动验收范围

自动验收已用真实 JWT/HTTP 验证权限、公共/私人知识边界、订单归属及完整状态闭环，用隔离 H2、向量替身和可编程模型替身验证并发、幂等、事务回滚、旧表升级、命名空间隔离、只读工具集、伪造身份无效、草稿确认与失败降级。全量 124 项（1 项真实 MySQL 跳过），模块 4 新增 9 项。真实 MySQL、真实模型/Embedding/Tavily 和售后页面浏览器验收本次未执行。
