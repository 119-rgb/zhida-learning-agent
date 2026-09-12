package com.zhida.agent.observability;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ObservableToolInterceptor extends ToolInterceptor {

    private static final int MAX_ARGUMENT_LENGTH = 1_000;
    private static final int MAX_RESULT_PREVIEW_LENGTH = 800;

    private final ToolTracePublisher publisher;

    public ObservableToolInterceptor(ToolTracePublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public String getName() {
        return "zhida_tool_observability";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String taskId = request.getExecutionContext()
                .flatMap(context -> context.config().metadata(ToolTracePublisher.TASK_ID_METADATA_KEY))
                .map(String::valueOf)
                .orElse(null);

        String arguments = abbreviate(request.getArguments(), MAX_ARGUMENT_LENGTH);
        publisher.acquireToolCall(taskId);
        publisher.publish(taskId, ToolTrace.started(request.getToolCallId(), request.getToolName(), arguments));
        Instant startedAt = Instant.now();

        try {
            request.getExecutionContext().flatMap(context -> context.config().metadata("zhida.knowledgeBase"))
                    .ifPresent(key -> com.zhida.agent.auth.KnowledgeScope.set(String.valueOf(key)));
            ToolCallResponse response = handler.call(request);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            publisher.publish(taskId, ToolTrace.completed(
                    request.getToolCallId(),
                    request.getToolName(),
                    arguments,
                    abbreviate(response.getResult(), MAX_RESULT_PREVIEW_LENGTH),
                    durationMs,
                    response.isError()
            ));
            return response;
        }
        catch (RuntimeException error) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            publisher.publish(taskId, ToolTrace.completed(
                    request.getToolCallId(),
                    request.getToolName(),
                    arguments,
                    abbreviate(error.getMessage(), MAX_RESULT_PREVIEW_LENGTH),
                    durationMs,
                    true
            ));
            throw error;
        } finally {
            com.zhida.agent.auth.KnowledgeScope.clear();
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }
}
