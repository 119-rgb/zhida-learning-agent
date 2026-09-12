# 部署与验收

当前适合单实例学习演示；尚未完成公网生产验收。2026-09-11 检查时本机仍未安装 Docker，因此容器配置已完成代码与静态检查，但尚未实际构建、启动。

## 本地启动

使用 JDK 17 和 Maven，执行 `mvn test`、`mvn package`，然后 `java -jar target/zhida-learning-agent-0.1.0-SNAPSHOT.jar --server.address=127.0.0.1`。

普通 Java 启动不会自动读取 `.env`。需通过进程环境变量配置；`.env.example` 是变量清单，不要提交真实密钥。

认证模式要求 `ZHIDA_PERSISTENCE_ENABLED=true`、`ZHIDA_AUTH_ENABLED=true`、`DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 和随机 `ZHIDA_JWT_SECRET`（至少 32 字节）。默认认证关闭，禁止把该模式直接暴露公网。真实模型另需 `ZHIDA_AI_ENABLED=true` 和 DeepSeek/Tavily 密钥。长对话摘要默认开启，会把较早完整问答发送给 DeepSeek；如部署方不允许该用途，设置 `ZHIDA_SUMMARY_ENABLED=false`。

## Docker 模板

先复制环境变量模板，在本地私密 `.env` 中设置数据库密码、随机 JWT secret 及可选 AI 密钥：

```powershell
Copy-Item .env.example .env
# 使用文本编辑器填写 .env，不要把密钥粘贴到命令历史或提交到仓库。
.\deploy\preflight.ps1
docker compose up -d --build
docker compose ps
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

部署前检查脚本只验证变量是否存在和 JWT secret 长度，不输出具体内容；还会检查 Docker 服务并执行 `docker compose config --quiet`。默认入口只绑定 `127.0.0.1:8080`，MySQL 不映射主机端口。MySQL 健康后才启动应用，应用健康后才启动 Nginx；Java 容器以非 root 用户运行，退出时预留 30 秒处理终止信号。首次 PDF 入库会把本地 Embedding 模型下载到持久卷，时间取决于网络。

HTTPS 模板为 `compose.https.yaml`，要求支持 `!override` 的 Compose 2.24.4+。自行准备域名和证书文件 `deploy/certs/fullchain.pem`、`deploy/certs/privkey.pem` 后执行：

```powershell
.\deploy\preflight.ps1 -Https
docker compose -f compose.yaml -f compose.https.yaml up -d --build
```

脚本会检查 Compose 版本和两个证书文件。Nginx 已关闭代理缓冲与请求缓冲，避免 SSE 被攒成整段返回，并把上传大小限制为 20MB。不要上传私钥，证书目录已忽略。这里没有购买服务器、域名或执行公网部署。

备份时同时备份 MySQL 数据、上传文档、文档目录索引与向量文件；恢复前停止写入，避免目录与向量数据不一致。不要使用 `docker compose down -v`，它会删除持久卷。

## 上线前剩余门槛

- 安装 Docker Desktop 后，真实验证首次构建、模型下载、容器健康检查、SSE 长连接和持久卷重启恢复。
- 准备真实域名和证书后，验证 HTTPS 跳转、证书链与公网访问。
- 当前请求限流使用进程内计数，不能跨实例共享；反向代理后客户端可能共享代理 IP 的额度。未信任外部 X-Forwarded-For，避免用户伪造 IP 绕过限制。
- 网页工具拒绝已知内网地址、重定向和超大响应，但 DNS 校验与实际连接间仍有重绑定风险。公网需配置网络出口隔离，禁止容器访问内网及云元数据地址；当前代码不能替代出口防火墙。
- 本地 JSON 向量存储只支持单实例。文档与向量文件尚无跨文件事务，应测试失败重试和备份恢复。
- JWT 注销目前仅清理浏览器令牌，不撤销已签发令牌；需补齐撤销、密码找回和账号管理才适合正式账户服务。
- 已有服务商 Token 用量记录、长对话摘要和异步文档处理；还需费用换算与告警，以及完整端到端演示验收。未返回统计和保存失败的调用不能用于精确对账。
