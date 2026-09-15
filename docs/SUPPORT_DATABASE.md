# 售后工单数据库说明（模块 1）

本说明对应已实现的工单核心。它只新增售后表，不修改已有聊天、知识库、任务或用户数据。模块由 `SupportConfiguration` 在售后、鉴权和持久化均启用时执行 `db/support-schema.sql`；应用数据库账户因此需要对目标库拥有建表、建索引和日常读写权限。真实 MySQL 本次未验收，正式部署前须评估权限及兼容性。

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
| `support_ticket` | 工单、状态、版本、发起用户、处理客服、确认评价 |
| `support_ticket_reply` | 用户补充与客服回复/方案记录 |
| `support_ticket_event` | 工单状态与版本审计事件 |

关键约束包括 `(user_id, request_id)` 防止同一用户重复创建，以及 `(ticket_id, version)` 保证每个工单版本只有一个事件。用户列表、客服本人列表和待受理队列由组合索引支持。新表通过外键引用既有 `user_account`，但不对旧聊天表做迁移或更新。

初始化脚本创建表和约束，`SupportConfiguration` 随后按数据库元数据检查并创建索引：`(user_id, updated_at, id)`、`(status, updated_at, id)`、`(assigned_to, updated_at, id)`。已有同名索引不自动替换，升级既有索引需运维显式评估；当前支持单实例初始化，不宣称多实例同时建表安全。历史表不存在删列或删除数据迁移。

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

## 数据边界

模块 1 创建工单需要标题、描述、有效分类、requestId 和明确确认；尚不支持订单关联及订单权限校验。初始分类列表为空，须由管理员创建分类后才能建单。停用分类不删除历史工单或分类记录。

工单变更需提供 `expectedVersion`。服务端先校验调用者和当前状态，再用状态、版本和处理人条件更新；工单本体、回复/处理记录与事件审计在同一事务中提交。DDL 和真实 MySQL 验收是否完成应以当前项目状态和实际执行记录为准。
