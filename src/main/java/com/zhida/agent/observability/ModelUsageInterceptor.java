package com.zhida.agent.observability;

import com.zhida.agent.conversation.TaskRepository;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** One terminal accounting record per provider call, including missing usage and cancellation. */
@Component
public class ModelUsageInterceptor {
  private final ObjectProvider<TaskRepository> repositories;

  public ModelUsageInterceptor(ObjectProvider<TaskRepository> repositories) {
    this.repositories = repositories;
  }

  public Invocation begin(String taskId) {
    return new Invocation(taskId);
  }

  public final class Invocation {
    private final String taskId, callId = UUID.randomUUID().toString();
    private final AtomicBoolean saved = new AtomicBoolean();

    private Invocation(String taskId) {
      this.taskId = taskId;
    }

    public void finish(ChatResponse response, String outcome) {
      if (!saved.compareAndSet(false, true)) return;
      var repository = repositories.getIfAvailable();
      if (repository == null || taskId == null) return;
      var usage = response == null ? null : response.tokenUsage();
      try {
        repository.recordUsage(
            taskId,
            callId,
            response == null ? "unknown" : response.modelName(),
            usage == null ? null : usage.inputTokenCount(),
            usage == null ? null : usage.outputTokenCount(),
            outcome);
      } catch (org.springframework.dao.DataAccessException error) {
        org.slf4j.LoggerFactory.getLogger(ModelUsageInterceptor.class)
            .warn("Model usage could not be saved for task {}", taskId);
      }
    }
  }
}
