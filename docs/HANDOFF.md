# 当前接力交接单（2026-09-17，DSH 接手后）

本文件只记录当前可复现状态。以工作区文件与重新执行的测试为准，不根据旧聊天或旧阶段结论猜测。

## 接力状态

- 当前分支：`codex/after-sales-tickets`。已提交：`0b383e1`（模块 4）→ `a09c939`（模块 5 页面）→ `113974b`（模块 5 文档）。
- 工作区改动（模块 4 审查修复 + 模块 5 收尾 + 本轮审查修复）已由 DSH 提交并推送，见本轮提交。
- 未重写历史、未 force push、未推送 `archive/*`。

## 本轮做完的事（按顺序）

0. **新增助手页「生成工单草稿」入口**：真实模型很少主动调用草稿工具（实测 3 轮 10 次工具调用全为查询类），用户因此难以走到建单流程。新增 `POST /api/v1/support/ticket-drafts`（`draftFromPage`），与模型工具复用同一个 `draft` 业务方法，标题由服务端从描述派生；页面用服务端返回的分类/订单下拉组装描述，确认后仍走原建单接口。同一轮修掉三个真实缺陷：`.draft-compose { display: grid }` 覆盖 `hidden` 使「收起」无效并遮挡页面、`confirmDraft` 非 204 会继续提交、造草稿表单异步加载未完成即提交导致空描述。
0b. **知识库文档状态自动刷新**：文档处于处理中/删除中时页面轮询文档接口并只重绘文档区（不整页重载），完成后停止；总时长上限 2 分钟；切路由与登出清除定时器。
0c. **内置离线向量模型**：`EMBEDDING_MODEL=local` 使用打包在依赖里的 `all-MiniLM-L6-v2`（384 维），无需第三方 Key、不联网；索引身份区分离线/外部模式，切换会要求重建。同时把 `ZHIDA_CHUNK_SIZE` 等分块参数外部化（此前硬编码导致设了无效），并实证小分块显著提升检索命中。
1. **修掉长草稿无法确认**：`support.css` 的 `.auth-dialog.wide` 只有宽度限制，描述接近 4000 字时确认按钮落在视口外且弹窗不可滚动。加 `max-height: calc(100dvh - 48px)` + `overflow-y: auto`。
2. **定位并修复「详情渲染过期状态」**：`start()` 与 `hashchange` 并发调用 `dispatch()`，先发起后返回的那次用过期数据覆盖新渲染，表现为工单已到「待用户确认」页面仍显示「待受理」、操作按钮只剩「补充说明」，用户无法确认或退回。
3. **加固渲染竞态防护**：审查指出原先只在 `loadCounts()` 后复检序号，各 `render*` 在自己 await 之后写 `innerHTML` 仍会覆盖。现在视图根元素记录渲染序号，所有 await 后的写入都过 `isCurrentRender()`。
4. **修掉功能回归**：售后助手「手动建单」按钮原先放在 `#assistantStatus` 内，而该节点用 `textContent` 更新，发送第一条消息后按钮即被删除。已拆成独立 span；脚本改为发送后再次断言。
5. **修掉脚本自身的坑**：① 用页面文案判定状态会匹配历史事件（功能坏了也判通过）→ 改为轮询后端状态；② `page.goto()` 到只差 hash 的同文档 URL 不重载 → 改整页 `reload`；③ 密码环境变量名与实际属性不一致 → 统一为 `ZHIDA_SUPPORT_DEMO_DATA_PASSWORD`；④ 回放自造 SSE 无法覆盖后端投影 → 增加真实接口 SSE 契约探针；⑤ 偶发渲染抖动 → `actOnTicket` 先确认后端状态再重试按钮。
6. **后端健壮性**：`emitSupportUiResult` 同时捕获 `JsonProcessingException` 与 `RuntimeException`（仅记录工具名与异常类型），补测试证明投影失败时任务仍完成；演示数据角色检查改为任一角色行不符即停止，并补 3 条安全停止负向用例；`pendingDraft` 改为快照使用并在登出时清空。

## 本机真实环境启动命令（已实测）

真实模型 + 离线向量 + 真实 MySQL（端口 8081）：

```powershell
cd "E:\learning path xx\zhida-learning-agent"
$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')  # 只引用，不写文件
$env:ZHIDA_AI_ENABLED="true"
$env:DEEPSEEK_CHAT_MODEL="deepseek-flash"   # 官方当前模型名；deepseek-chat 已失效
$env:EMBEDDING_MODEL="local"                # 内置离线向量模型，不需要第三方 Key
$env:ZHIDA_TASK_TIMEOUT_SECONDS="120"
$env:ZHIDA_CHUNK_SIZE="300"; $env:ZHIDA_CHUNK_OVERLAP="60"   # 小分块显著提升检索命中，见 README
$env:ZHIDA_SUPPORT_ENABLED="true"; $env:ZHIDA_AUTH_ENABLED="true"; $env:ZHIDA_PERSISTENCE_ENABLED="true"
$env:DB_URL="jdbc:mysql://127.0.0.1:3306/zhida_agent?characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
$env:DB_USERNAME="root"; $env:DB_PASSWORD="<本机口令>"
$env:ZHIDA_JWT_SECRET="<至少32字节>"
java -jar tmp/dsh-emb-final/zhida-learning-agent-0.1.0-SNAPSHOT.jar --server.port=8081
```

- `GET /api/health` 应返回 `aiEnabled=true`、`embeddingConfigured=true`、`embeddingMode=local`、`supportEnabled=true`。
- 演示账号口令为 `Demo-pass-2026`；公共库 `product-support` 已有一份虚构手册（`READY`）。
- 可执行 JAR 约 231MB（含离线向量模型），`tmp/` 已被 gitignore，不进版本库。

## 已执行验收（本轮实际执行）

- `mvn -o clean verify '-Dzhida.build.directory=tmp/dsh-emb-final'`：**138 项、0 失败、0 错误、1 项真实 MySQL 测试跳过**，可执行 JAR（231MB，含离线向量模型）生成成功。
- `node scripts/verify-support-workbench.cjs`：**连续多次 PASS**（含主动造草稿、取消后不建单、长草稿确认、客服处理、用户退回、关闭评价、手动建单、文档状态自动刷新、管理端公共知识库）。连续第 3 次起会触发应用自带每 IP 限流（429），脚本已把该情况标注为「限流」而不是功能失败。
- **真实模型 + 真实 MySQL + 离线向量**实测：售后助手 5 次工具调用全部成功、任务 COMPLETED；回答正确引用本人订单号与知识库片段；上传虚构手册后 2 秒内 READY 并返回可核对出处。
- `node --check`：`app.js`、`support.js`、`scripts/verify-support-workbench.cjs` 全部通过；`git diff --check` 无空白错误。
- 隐私：待推送文件与远端全部可达历史均无命中；提交身份为已核实的 noreply。
- **隔离实例的正确启动方式（本轮踩过坑，务必照此执行）**：
  1. `mvn -o -q -DskipTests process-resources`（或任何会复制资源的构建）——**改完 `src/main/resources/static/*` 必须重建，否则服务端仍返回旧文件**；
  2. classpath 用 `"target/classes;" + (Get-Content tmp/cp-full.txt -Raw).Trim()`，其中 `tmp/cp-full.txt` 由 `mvn -o dependency:build-classpath "-Dmdep.outputFile=tmp/cp-full.txt" "-Dmdep.includeScope=test"` 生成（H2 在 test scope，`java -jar` 的可执行包不含 H2，`mvn spring-boot:run` 也不含）；
  3. 环境变量：`ZHIDA_SUPPORT_ENABLED=true`、`ZHIDA_SUPPORT_DEMO_DATA_ENABLED=true`、`ZHIDA_SUPPORT_DEMO_DATA_PASSWORD=<临时值>`、`ZHIDA_AUTH_ENABLED=true`、`ZHIDA_PERSISTENCE_ENABLED=true`、`DB_URL=jdbc:h2:mem:<名>;MODE=MySQL;DB_CLOSE_DELAY=-1`、`DB_USERNAME=sa`、空 `DB_PASSWORD`、`ZHIDA_AI_ENABLED=false`，端口 `18080`；
  4. **改完资源必须重启进程**（Spring 不会热加载 classpath 内的静态资源）。

## 未执行验收

- 真实 MySQL 上的**页面**闭环、真实模型驱动的**页面**闭环（脚本用构造 SSE 事件）。
- 跨浏览器（Firefox/Safari）、移动端真机、无障碍审计、Docker。
- 页面用例纳入 `mvn test`；真实 Embedding 服务商（本项目只用过内置离线模型）。

## 独立审查状态

- 模块 1–3：已完成独立审查与复查。
- 模块 4：由未参与实现的审查者完成只读 review，问题已修复并复查。
- 模块 5（含竞态、回归与脚本修复）：已完成独立审查与修复，记录见 `docs/reviews/2026-09-17-module-5.md`。
- **本轮新增改动尚未独立审查**：页面主动造草稿入口、文档状态自动刷新、内置离线向量模型与分块参数外部化、会话过期恢复（助手流 401）。按 AGENTS.md「写的人不自审」，需要由未参与实现的审查者补做。其中「助手流 401」已用反向验证（临时移除修复后用例必定失败）确认测试有效。

## 下一位 Agent 的建议顺序

1. 读 `AGENTS.md`、本文件与 `docs/reviews/2026-09-17-module-5.md`，确认当前基线。
2. 为本轮三块新增改动安排独立审查（离线向量模式切换与索引身份最值得看）。
3. 剩余 P3 清理（无用样式与死节点、陈旧文案、被拒路由历史记录）可独立成一个小任务。
4. 发布前仍按 AGENTS.md 过一遍隐私门；禁止推送 `archive/*`，禁止 force push。

## 待办（非阻断）

- 知识库相似度是绝对门槛，无关查询可能擦线命中（实测无关问题得 0.371 > 0.30），相对分数过滤未实现。
- 调整分块参数不会自动重建索引，需要重新上传或点「重试」。
- 售后助手只覆盖本人订单与工单；客服/管理员的 AI 辅助未实现。
