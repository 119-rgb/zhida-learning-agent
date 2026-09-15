package com.zhida.agent.api;

import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.ResearchOrchestrator;
import com.zhida.agent.application.ResearchSession;
import com.zhida.agent.conversation.ConversationHistory;
import com.zhida.agent.knowledge.KnowledgeBaseAccessService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
    SseEmitter emitter = new SseEmitter(timeoutSeconds * 1_000L + 5_000L);
    AtomicBoolean closed = new AtomicBoolean();
    Runnable cancel =
        () -> {
          if (closed.compareAndSet(false, true)) session.cancel();
        };
    emitter.onTimeout(cancel);
    emitter.onError(error -> cancel.run());
    emitter.onCompletion(cancel);
    try {
      executor.execute(
          new ResearchExecutorConfiguration.CancellableWork(
              cancel,
              () -> {
                try {
                  session.execute(
                      event -> {
                        if (closed.get()) throw new IllegalStateException("SSE connection closed");
                        try {
                          emitter.send(
                              SseEmitter.event()
                                  .id(Long.toString(event.eventId()))
                                  .name(event.type())
                                  .data(event));
                        } catch (IOException | IllegalStateException error) {
                          cancel.run();
                          throw new IllegalStateException("SSE send failed", error);
                        }
                      });
                  if (closed.compareAndSet(false, true)) emitter.complete();
                } catch (Exception error) {
                  cancel.run();
                  emitter.completeWithError(error);
                }
              }));
    } catch (org.springframework.core.task.TaskRejectedException error) {
      cancel.run();
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Research queue full", error);
    }
    return emitter;
  }
}
