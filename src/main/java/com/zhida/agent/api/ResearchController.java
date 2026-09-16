package com.zhida.agent.api;

import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.ResearchOrchestrator;
import com.zhida.agent.application.ResearchSession;
import com.zhida.agent.conversation.ConversationHistory;
import com.zhida.agent.knowledge.KnowledgeBaseAccessService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/research")
public class ResearchController {

  private final ThreadPoolTaskExecutor executor;
  private final ResearchOrchestrator orchestrator;
  private final ObjectProvider<ConversationHistory> history;
  private final com.zhida.agent.auth.OwnerResolver owners;
  private final KnowledgeBaseAccessService knowledgeBaseAccess;

  @org.springframework.beans.factory.annotation.Value("${zhida.execution.timeout-seconds:90}")
  private long timeoutSeconds = 90;

  public ResearchController(
      ThreadPoolTaskExecutor researchExecutor,
      ResearchOrchestrator orchestrator,
      ObjectProvider<ConversationHistory> history,
      com.zhida.agent.auth.OwnerResolver owners,
      KnowledgeBaseAccessService knowledgeBaseAccess) {
    this.executor = researchExecutor;
    this.orchestrator = orchestrator;
    this.history = history;
    this.owners = owners;
    this.knowledgeBaseAccess = knowledgeBaseAccess;
  }

  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @Valid @RequestBody ResearchRequest request, java.security.Principal principal) {
    String owner = owners.owner(principal);
    knowledgeBaseAccess.resolve(owner, request.knowledgeBaseId());
    ConversationHistory persistence = history.getIfAvailable();
    ResearchSession session =
        persistence == null
            ? orchestrator.prepare(request, owner)
            : persistence.prepare(request, orchestrator, owner);
    // SSE 传输、取消与队列已满处理在共享组件中实现；本入口只决定身份、授权和研究模式。
    return ResearchSseStreamer.stream(session, executor, timeoutSeconds);
  }
}
