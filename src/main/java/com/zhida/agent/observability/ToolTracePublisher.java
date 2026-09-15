package com.zhida.agent.observability;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class ToolTracePublisher {
  public static final String TASK_ID_METADATA_KEY = "zhida.taskId";

  private record Channel(AtomicInteger remaining, Consumer<ToolTrace> sink) {}

  private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

  public void open(String taskId, int maxCalls, Consumer<ToolTrace> sink) {
    if (channels.putIfAbsent(taskId, new Channel(new AtomicInteger(maxCalls), sink)) != null)
      throw new IllegalStateException("工具事件通道已经存在: " + taskId);
  }

  public void acquireToolCall(String taskId) {
    if (taskId == null) return;
    var channel = channels.get(taskId);
    if (channel == null) throw new java.util.concurrent.CancellationException("任务已结束");
    if (channel.remaining.getAndDecrement() <= 0) throw new ToolBudgetExceededException();
  }

  public void publish(String taskId, ToolTrace trace) {
    if (taskId == null) return;
    var channel = channels.get(taskId);
    if (channel != null) channel.sink.accept(trace);
  }

  public void complete(String taskId) {
    remove(taskId);
  }

  public void remove(String taskId) {
    channels.remove(taskId);
  }

  public static class ToolBudgetExceededException extends RuntimeException {
    public ToolBudgetExceededException() {
      super("已达到工具调用次数上限");
    }
  }
}
