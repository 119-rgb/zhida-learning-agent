package com.zhida.agent.observability;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ToolTracePublisher {

    public static final String TASK_ID_METADATA_KEY = "zhida.taskId";

    private final ConcurrentMap<String, Sinks.Many<ToolTrace>> channels = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, java.util.concurrent.atomic.AtomicInteger> budgets = new ConcurrentHashMap<>();

    public Flux<ToolTrace> open(String taskId) {
        return open(taskId, 8);
    }

    public Flux<ToolTrace> open(String taskId, int maxCalls) {
        Sinks.Many<ToolTrace> channel = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<ToolTrace> existing = channels.putIfAbsent(taskId, channel);
        if (existing != null) {
            throw new IllegalStateException("工具事件通道已经存在: " + taskId);
        }
        budgets.put(taskId, new java.util.concurrent.atomic.AtomicInteger(maxCalls));
        return channel.asFlux();
    }

    public void acquireToolCall(String taskId) {
        if (taskId == null) return;
        var remaining = budgets.get(taskId);
        if (remaining == null) throw new java.util.concurrent.CancellationException("任务已结束");
        if (remaining.getAndDecrement() <= 0) {
            var channel = channels.get(taskId);
            if (channel != null) channel.tryEmitError(new ToolBudgetExceededException());
            throw new ToolBudgetExceededException();
        }
    }

    public static class ToolBudgetExceededException extends RuntimeException {
        public ToolBudgetExceededException() { super("已达到工具调用次数上限"); }
    }

    public void publish(String taskId, ToolTrace trace) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        Sinks.Many<ToolTrace> channel = channels.get(taskId);
        if (channel != null) {
            channel.tryEmitNext(trace);
        }
    }

    public void complete(String taskId) {
        Sinks.Many<ToolTrace> channel = channels.get(taskId);
        if (channel != null) {
            channel.tryEmitComplete();
        }
    }

    public void remove(String taskId) {
        budgets.remove(taskId);
        channels.remove(taskId);
    }
}
