# 售后工单 HTTP 演示（模块 1）

此演示使用虚构业务，面向已启用售后、JWT 与持久化的隔离环境。模块 1 没有网页流程；以下是 HTTP 调用顺序，不代表用户、客服或管理员页面已经完成。先按 [SUPPORT_DATABASE.md](SUPPORT_DATABASE.md) 配置数据库和角色，并分别取得虚构用户、客服、管理员的 JWT。

示例中的 `<user-jwt>`、`<agent-jwt>`、`<admin-jwt>`、`<ticket-id>` 与 `<category-id>` 均为占位符，不能替换为或记录真实凭据。

## 1. 管理员创建一个虚构分类

```http
POST /api/v1/support/categories
Authorization: Bearer <admin-jwt>
Content-Type: application/json

{"name":"虚构订阅服务","enabled":true}
```

创建工单必须选择有效分类，初始分类列表为空。非管理员只能读取启用的分类，不能创建或修改。

## 2. 用户创建工单

```http
POST /api/v1/support/tickets
Authorization: Bearer <user-jwt>
Content-Type: application/json

{
  "title":"虚构订单已付款但服务未开通",
  "description":"虚构场景：我的订单已经付款，但服务没有开通。无真实支付或订单关联。",
  "categoryId":"<category-id>",
  "requestId":"demo-subscription-refresh-001",
  "confirmed":true
}
```

成功创建为 `PENDING` 并返回 201。相同用户以相同 `requestId` 重放完全相同的规范化内容，也返回同一工单（201）；用同一 `requestId` 改变标题、描述或分类时返回 409。模块 1 不接受订单字段。

## 3. 客服查看并接单

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

## 4. 补充、方案与退回处理

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

## 5. 用户确认与评价

```http
POST /api/v1/support/tickets/<ticket-id>/confirm
Authorization: Bearer <user-jwt>
Content-Type: application/json

{"expectedVersion":3,"rating":5,"evaluation":"虚构评价：刷新后已恢复。"}
```

确认仅允许工单发起人在 `AWAITING_CONFIRMATION` 阶段操作。评分必须是 1–5，评价可以为空；成功后状态为 `CLOSED`。已关闭工单不能继续回复、补充、接单、分配或再次确认。

## 查询和权限检查

`GET /api/v1/support/tickets?view=mine` 为默认查询：用户只看到自己的工单，客服只看到本人处理的工单。客服可用 `view=pending` 查看待受理队列，管理员可用 `view=all` 查看全部记录。`GET /api/v1/support/tickets/<ticket-id>` 返回 `ticket`、`replies`、`events`。

自动验收已用真实 JWT/HTTP 验证权限及完整状态闭环，用隔离 H2 验证并发、幂等和事务回滚。全量 99 项（1 项真实 MySQL 跳过），工单新增 17 项。真实 MySQL、真实模型和售后页面浏览器验收本次未执行。
