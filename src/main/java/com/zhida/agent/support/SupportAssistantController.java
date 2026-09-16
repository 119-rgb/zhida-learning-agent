package com.zhida.agent.support;

import com.zhida.agent.api.ResearchSseStreamer;
import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.AgentMode;
import com.zhida.agent.application.ResearchOrchestrator;
import com.zhida.agent.application.ResearchSession;
import com.zhida.agent.conversation.ConversationHistory;
import com.zhida.agent.knowledge.KnowledgeBaseAccessService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 售后助手入口。它复用研究助手已经验证过的 SSE、任务持久化、取消、超时和工具预算能力，
 * 只把服务端模式固定为 {@link AgentMode#SUPPORT}，从而绑定售后系统指令与只读工具集。
 *
 * <p>安全边界：
 *
 * <ul>
 *   <li>身份取自 Spring Security 的已验证 Principal；请求体没有 userId/owner 字段，
 *       模型也无法通过工具参数指定身份。
 *   <li>模式由本入口写死，客户端不能在请求中声明模式，因此无法选择更宽松的研究助手工具集。
 *   <li>本接口没有任何创建工单的路径：模型最多返回草稿，建单必须由用户在页面上确认后
 *       调用 {@code POST /api/v1/support/tickets}（confirmed=true）完成。
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/support/assistant")
@ConditionalOnProperty(name = "zhida.support.enabled", havingValue = "true")
public class SupportAssistantController {

  private final ThreadPoolTaskExecutor executor;
  private final ResearchOrchestrator orchestrator;
  private final ObjectProvider<ConversationHistory> history;
  private final com.zhida.agent.auth.OwnerResolver owners;
  private final KnowledgeBaseAccessService knowledgeBaseAccess;
  private final SupportActorResolver actors;

  @Value("${zhida.execution.timeout-seconds:90}")
  private long timeoutSeconds = 90;

  public SupportAssistantController(
      ThreadPoolTaskExecutor researchExecutor,
      ResearchOrchestrator orchestrator,
      ObjectProvider<ConversationHistory> history,
      com.zhida.agent.auth.OwnerResolver owners,
      KnowledgeBaseAccessService knowledgeBaseAccess,
      SupportActorResolver actors) {
    this.executor = researchExecutor;
    this.orchestrator = orchestrator;
    this.history = history;
    this.owners = owners;
    this.knowledgeBaseAccess = knowledgeBaseAccess;
    this.actors = actors;
  }

  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@Valid @RequestBody ResearchRequest request, Principal principal) {
    // 未登录在这里被拒绝；认证上下文是唯一的身份来源。
    String owner = owners.owner(principal);
    // 售后助手只对正式账号开放：游客即使持有有效 JWT 也不能进入售后业务。
    actors.account(owner);
    // 先解析知识库授权，未授权命名空间在进入模型之前失败，避免模型影响授权判断。
    knowledgeBaseAccess.resolve(owner, request.knowledgeBaseId());
    ConversationHistory persistence = history.getIfAvailable();
    ResearchSession session =
        persistence == null
            ? orchestrator.prepare(request, owner, AgentMode.SUPPORT)
            : persistence.prepare(request, orchestrator, owner, AgentMode.SUPPORT);
    // 与研究助手共用同一套 SSE、取消、超时和队列保护实现。
    return ResearchSseStreamer.stream(session, executor, timeoutSeconds);
  }
}
