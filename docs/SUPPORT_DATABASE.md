# 售后数据库说明（模块 1–2）

本说明对应已实现的工单核心、虚构产品和模拟订单。它只新增售后表，并为模块 1 的工单表补一个可空 `order_id`，不修改已有聊天、知识库、任务或用户数据。模块由 `SupportConfiguration` 在售后、鉴权和持久化均启用时执行 `db/support-schema.sql`；应用数据库账户因此需要建表、加列、加外键、建索引和日常读写权限。真实 MySQL 本次未验收，正式部署前须评估权限及兼容性。

## 开关与前置条件

```powershell
$env:ZHIDA_SUPPORT_ENABLED="true"
$env:ZHIDA_AUTH_ENABLED="true"
$env:ZHIDA_PERSISTENCE_ENABLED="true"
$env:DB_URL="jdbc:mysql://<host>:3306/<database>?characterEncoding=UTF-8&serverTimezone=UTC"
$env:DB_USERNAME="<application-user>"
$env:DB_PASSWORD="<application-password>"
$env:ZHIDA_JWT_SECRET="<at-least-32-byte-random-secret>"
mvn spring-boot:run
```

所有值均为占位符；不要把密码、JWT 密钥或真实数据库地址提交到版本库。应用不会自动创建演示账户、默认密码、客服或管理员。

## 独立表

`support-schema.sql` 创建以下表：

| 表 | 用途 |
| --- | --- |
| `support_account_role` | 用户的售后角色；旧用户默认 `USER` |
| `support_category` | 管理员维护的分类，可停用而不物理删除 |
| `support_category_event` | 分类变更审计 |
| `support_product` / `support_product_event` | 明确标记为模拟数据的产品及创建审计 |
| `support_order` / `support_order_event` | 模拟订单、初始付款/开通状态及创建审计 |
| `support_ticket` | 工单、状态、版本、发起用户、处理客服、确认评价 |
| `support_ticket_reply` | 用户补充与客服回复/方案记录 |
| `support_ticket_event` | 工单状态与版本审计事件 |

关键约束包括工单 `(user_id, request_id)`、模拟订单 `(created_by, request_id)` 幂等键，以及 `(ticket_id, version)` / `(order_id, version)` 审计版本主键。订单 CHECK 只允许待付款未开通、已付款未开通和已付款已开通；产品、订单的 `simulated` 必须为 true。新表通过外键引用既有 `user_account`，但不对旧聊天表做迁移或更新。

初始化脚本创建表和约束，`SupportConfiguration` 随后按数据库元数据检查索引。模块 2 会为既有模块 1 的 `support_ticket` 自动补可空 `order_id`、订单外键和索引；旧工单保持 NULL，不回填或删除数据。新增订单查询索引为 `(user_id, created_at, id)`，工单另有 `order_id` 索引。已有同名索引不自动替换；当前仍只支持单实例初始化，不宣称多个实例同时执行 DDL 安全。

写事务使用数据源默认隔离级别，条件更新确保只有一个预期版本成功；数据库死锁/序列化冲突在回滚后返回 409，由调用者刷新版本再提交。详情使用独立只读可重复读事务，让工单、回复和事件来自同一快照。

## 角色配置

普通注册只产生 `USER`。由具备数据库权限的运维人员为既有账户添加或调整客服、管理员角色；下面仅是参数化模板，账户名和角色值必须由实际运维流程提供。

```sql
INSERT INTO support_account_role (user_id, role)
SELECT id, :role
FROM user_account
WHERE username = :username;
```

在支持更新的数据库客户端中，可用同样的参数形式变更角色：

```sql
UPDATE support_account_role
SET role = :role
WHERE user_id = (
  SELECT id FROM user_account WHERE username = :username
);
```

允许的业务角色为 `USER`、`CUSTOMER_SERVICE`、`ADMIN`。不要由 HTTP 请求、前端隐藏字段或模型输出决定角色，也不要在文档中存放真实账户或凭据。

## 模拟订单边界

产品和订单只能由管理员接口录入，响应固定返回 `simulated=true`。订单初始状态写入与 CREATED 审计事件同事务提交，管理员的 `requestId` 防止重试重复造数。普通用户查询 SQL 同时匹配 `id` 和认证用户 `user_id`，其他用户的订单统一返回 404。

订单 HTTP 与业务服务均没有付款、退款或开通状态修改方法。当前三个状态仅用于查询、演示及工单关联；后续 AI 工具不得绕过服务层写数据库。

## 工单数据边界

创建工单需要标题、描述、有效分类、requestId 和明确确认，可选关联 `orderId`。服务端在创建事务中以当前 JWT 用户校验订单归属；跨用户或不存在统一返回 404。`orderId` 进入请求摘要，因此同一 requestId 不能改绑其他订单。初始分类列表为空，须由管理员创建分类后才能建单。停用分类不删除历史工单或分类记录。

工单变更需提供 `expectedVersion`。服务端先校验调用者和当前状态，再用状态、版本和处理人条件更新；工单本体、回复/处理记录与事件审计在同一事务中提交。DDL 和真实 MySQL 验收是否完成应以当前项目状态和实际执行记录为准。
