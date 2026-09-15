package com.zhida.agent.observability;

import com.zhida.agent.auth.KnowledgeScope;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ObservableToolInterceptor {
  private final ToolTracePublisher publisher;

  public ObservableToolInterceptor(ToolTracePublisher publisher) {
    this.publisher = publisher;
  }

  public String execute(
      String taskId, String scope, ToolExecutionRequest request, Supplier<String> action) {
    publisher.acquireToolCall(taskId);
    String arguments = abbreviate(request.arguments(), 1000);
    publisher.publish(taskId, ToolTrace.started(request.id(), request.name(), arguments));
    long started = System.nanoTime();
    try {
      KnowledgeScope.set(scope);
      String result = action.get();
      publisher.publish(
          taskId,
          ToolTrace.completed(
              request.id(),
              request.name(),
              arguments,
              abbreviate(result, 800),
              (System.nanoTime() - started) / 1_000_000,
              false));
      return result;
    } catch (RuntimeException error) {
      publisher.publish(
          taskId,
          ToolTrace.completed(
              request.id(),
              request.name(),
              arguments,
              abbreviate(error.getMessage(), 800),
              (System.nanoTime() - started) / 1_000_000,
              true));
      throw error;
    } finally {
      KnowledgeScope.clear();
    }
  }

  private String abbreviate(String value, int length) {
    if (value == null) return "";
    String normalized = value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= length ? normalized : normalized.substring(0, length) + "…";
  }
}
