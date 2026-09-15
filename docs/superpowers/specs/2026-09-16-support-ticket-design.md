# 智能售后平台：工单核心设计

## 目标与边界

单体应用，沿用 Java 17、Spring Boot、MVC、MySQL、Security/JWT、LangChain4j。逐模块交付：工单核心、虚构订单、售后知识库、售后 Agent、三端页面。当前交付仅实现模块 1，不将旧研究助手冒充售后 Agent。所有新演示身份与问题均虚构。

## 核心规则

- 注册只能得到 USER。CUSTOMER_SERVICE、ADMIN 由数据库管理者显式配置，服务端每次从数据库读取角色；不接受请求或模型提供的角色、owner。
- 新业务必须同时启用数据库和 JWT，不沿用旧助手的 local-user 或游客身份。
- USER 创建、列出、查看自己的工单、补充说明、确认解决并评价；客服查看待受理列表或本人处理工单，接单、回复、提交方案；管理员管理分类及分配客服，可查看工单记录。
- 四种状态：PENDING → PROCESSING → AWAITING_CONFIRMATION → CLOSED。未解决时 AWAITING_CONFIRMATION → PROCESSING。管理员可分配 PENDING（进入 PROCESSING），也可重新分配 PROCESSING；不能替用户关闭。
- 创建请求 confirmed 必须为 true。requestId 按用户唯一，相同规范化请求重放返回原工单，不同内容返回 409。不使用“标题相似”作为幂等判断。
- 工单所有变更先读授权状态，再以 version/status/assignee 条件更新；冲突返回 409。处理记录、工单版本与状态审计在同一事务提交，失败全部回滚。每个版本唯一一条事件；创建为版本 0。
- 已关闭不可回复、补充、接单、分配或再次关闭。仅已接单客服可回复/提交方案。用户只能在待确认状态确认/退回。
- 用户补充允许 PENDING、PROCESSING、AWAITING_CONFIRMATION，不改变状态。关闭必须提供 1–5 分及评价（评价可为空）。
- 分类采用停用而非物理删除，旧工单仍可读取。管理员配置分类的变更与分类审计同事务。

## 数据与接口

独立 support 模块；新增 support_account_role、support_category、support_category_event、support_ticket、support_ticket_reply、support_ticket_event。角色、工单、回复、事件引用现有 user_account；新增表不修改现有聊天数据。唯一约束 (user_id,request_id)、(ticket_id,version)；队列、用户列表、客服列表使用组合索引。

REST 路径 /api/v1/support：categories、tickets、tickets/{id}、tickets/{id}/comments、claim、replies、solution、confirm、reopen、assign；客服队列 GET tickets?view=pending/mine，管理员 GET tickets?view=all。客户端变更请求携带 expectedVersion；创建不包含 userId。列表最多 100 条并明确此演示范围。

## 验收

隔离 H2/MySQL 模式集成测试：真实 JWT HTTP 权限、跨用户 404、游客拒绝、角色伪造无效、完整状态闭环、非法转换、重复与并发创建、两客服同时接单一个成功、事务审计失败回滚、停用分类、版本冲突。模块 1 业务不依赖模型及知识库。真实 MySQL、真实模型、浏览器分别记录未执行，不编造压测数据。

## 发布

使用已核实的 GitHub 昵称及 noreply 身份。检查全部新增提交和全部历史的文件及身份，不推送 archive。当前远端祖先含个人邮箱，未获专项授权前不重写历史、不强推，也不将含该历史的新分支继续公开。保留本地开发成果并给出脱敏审计结果。
