package com.zhida.agent.conversation;

import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class ConversationHistory {
  public static final String LOCAL_OWNER = "local-user";
  private final ConversationRepository repository;
  private final TaskRepository tasks;
  private final ConversationSummaryService summaryService;
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private final Map<String, PersistedSession> cancellations = new ConcurrentHashMap<>();

  @Autowired
  public ConversationHistory(
      ConversationRepository repository,
      TaskRepository tasks,
      @Nullable ConversationSummaryService summaryService) {
    this.repository = repository;
    this.tasks = tasks;
    this.summaryService = summaryService;
  }

  ConversationHistory(ConversationRepository repository, TaskRepository tasks) {
    this(repository, tasks, null);
  }

  public boolean cancel(String owner, String taskId) {
    var task = tasks.snapshot(owner, taskId);
    if (!task.status().equals("RUNNING")) return false;
    var session = cancellations.get(taskId);
    if (session == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务正在恢复，请稍后刷新状态");
    return session.cancelWithCode("USER_CANCELLED");
  }

  public ResearchSession prepare(
      ResearchRequest request, ResearchOrchestrator orchestrator, String owner) {
    String id =
        request.conversationId() == null || request.conversationId().isBlank()
            ? UUID.randomUUID().toString()
            : request.conversationId().trim();
    String key = owner + ":" + id;
    if (!running.add(key)) throw new ResponseStatusException(HttpStatus.CONFLICT, "这个会话正在回答，请稍后再试");
    ResearchSession core = null;
    try {
      repository.ensureConversation(owner, id, request.message());
      var restored =
          ConversationContext.restore(repository.recentMessages(owner, id), request.message());
      var summarized =
          summaryService == null
              ? restored
              : ConversationContext.withSummary(
                  restored, summaryService.current(owner, id).orElse(null));
      var memories = repository.activeMemories(owner);
      var context = ConversationContext.withMemories(summarized, memories);
      String taskId =
          request.requestId() == null ? UUID.randomUUID().toString() : request.requestId();
      // Knowledge ownership and model input validation precede quota/user-message insertion.
      core =
          orchestrator.prepareWithContext(
              new ResearchRequest(
                  id, request.message(), request.requestId(), request.knowledgeBaseId()),
              context,
              taskId,
              owner);
      tasks.begin(owner, id, taskId, request.message(), memories);
      var session = new PersistedSession(core, owner, id, taskId, key);
      cancellations.put(taskId, session);
      return session;
    } catch (RuntimeException error) {
      if (core != null) core.cancel();
      running.remove(key);
      throw error;
    }
  }

  private final class PersistedSession implements ResearchSession {
    private final ResearchSession core;
    private final String owner, id, taskId, key;
    private final StringBuilder answer = new StringBuilder();
    private boolean terminal, started;
    private final java.util.concurrent.atomic.AtomicBoolean released =
        new java.util.concurrent.atomic.AtomicBoolean();

    private PersistedSession(
        ResearchSession core, String owner, String id, String taskId, String key) {
      this.core = core;
      this.owner = owner;
      this.id = id;
      this.taskId = taskId;
      this.key = key;
    }

    private void release() {
      if (released.compareAndSet(false, true)) {
        running.remove(key);
        cancellations.remove(taskId, this);
      }
    }

    @Override
    public boolean cancel() {
      return cancelWithCode("CLIENT_DISCONNECTED");
    }

    boolean cancelWithCode(String code) {
      try {
        synchronized (this) {
          if (terminal) return false;
          terminal = true;
          tasks.finish(owner, id, taskId, "CANCELLED", code, "");
        }
        return true;
      } finally {
        if (terminal) {
          core.cancel();
          release();
        }
      }
    }

    @Override
    public void execute(Consumer<AgentEvent> sink) {
      synchronized (this) {
        if (terminal || started) return;
        started = true;
      }
      try {
        core.execute(
            event -> {
              synchronized (this) {
                if (terminal) return;
                tasks.record(owner, id, event);
                if (event.type().equals("answer.delta")
                    && event.data() instanceof Map<?, ?> data
                    && data.get("content") instanceof String text) answer.append(text);
                if (event.type().equals("task.completed")) {
                  tasks.finish(owner, id, taskId, "COMPLETED", null, answer.toString());
                  terminal = true;
                } else if (event.type().equals("task.failed")) {
                  String code =
                      event.data() instanceof Map<?, ?> data
                          ? String.valueOf(data.get("errorCode"))
                          : "AGENT_EXECUTION_FAILED";
                  tasks.finish(owner, id, taskId, "FAILED", code, "");
                  terminal = true;
                }
                sink.accept(event);
              }
              if (event.type().equals("task.completed") && summaryService != null)
                summaryService.schedule(owner, id);
            });
      } catch (RuntimeException error) {
        synchronized (this) {
          if (!terminal) {
            tasks.finish(owner, id, taskId, "FAILED", "EXECUTION_OR_STORAGE_FAILED", "");
            terminal = true;
          }
        }
        throw error;
      } finally {
        try {
          synchronized (this) {
            if (!terminal) {
              tasks.finish(owner, id, taskId, "INTERRUPTED", "STREAM_INCOMPLETE", "");
              terminal = true;
            }
          }
        } finally {
          core.cancel();
          release();
        }
      }
    }
  }
}
