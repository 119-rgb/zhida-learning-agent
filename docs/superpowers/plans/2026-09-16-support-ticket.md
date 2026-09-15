# 工单核心实现计划

**Goal:** 完成模块 1 的服务端业务及隔离自动验收。

**Architecture:** support 业务包复用现有 Hikari/JDBC、TransactionTemplate、JWT。新增表独立初始化，只有 zhida.support.enabled=true 且鉴权/持久化开启才启用。

**Spec:** ../specs/2026-09-16-support-ticket-design.md

## 顺序与检查点

- [x] 1. 写真实 HTTP 的完整流程与越权失败测试，运行确认新路径尚不存在；新增 SupportRole、SupportActorResolver、SupportConfiguration 与 db/support-schema.sql，普通注册不接受角色。
- [x] 2. SupportTicketService 实现创建与重复重放、条件更新、同事务处理记录/事件；SupportTicketController 暴露接口并进行 Jakarta 参数校验。直接服务调用也校验字符串、角色、状态、确认及版本。
- [x] 3. 验证重复请求换内容 409、并发创建一个工单/事件、并发接单一个成功、审计插入失败回滚、非法状态及角色权限。新增 SupportTicketServiceTest 使用独立 H2 数据库。
- [x] 4. 分类管理与审计、管理员分配、服务器角色解析；SupportTicketHttpTest 验证 JWT、游客、伪造身份和角色、关闭评价。
- [x] 5. 更新 README、数据库说明、MODULE_ROADMAP、DEMO、RESUME_PROJECT、PROJECT_STATUS。保留旧入口并明确其仍是研究助手；新三端页面留给模块 5。
- [x] 6. mvn clean verify、差异检查、完整隐私检查。记录实际结果，使用 noreply 本地提交；远端隐私历史问题未解决时不发布。

## 固定决策

不引入 Redis、MQ、ORM 或数据库迁移新框架。不连接本机真实 MySQL，不在启动时写演示账户或默认密码。客服/管理员通过运维数据库配置，未来模块 5 的可重复演示账号仅在显式隔离环境中准备。订单关联在模块 2 新增，当前不处理订单关联，不假装完成订单权限校验。
