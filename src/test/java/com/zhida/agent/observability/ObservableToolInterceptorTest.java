package com.zhida.agent.observability;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservableToolInterceptorTest {

    @Test
    void shouldPublishStartedAndCompletedEvents() {
        ToolTracePublisher publisher = new ToolTracePublisher();
        ObservableToolInterceptor interceptor = new ObservableToolInterceptor(publisher);
        Flux<ToolTrace> traces = publisher.open("task-1");

        RunnableConfig config = mock(RunnableConfig.class);
        when(config.metadata(ToolTracePublisher.TASK_ID_METADATA_KEY)).thenReturn(Optional.of("task-1"));
        ToolCallExecutionContext executionContext = mock(ToolCallExecutionContext.class);
        when(executionContext.config()).thenReturn(config);

        ToolCallRequest request = mock(ToolCallRequest.class);
        when(request.getExecutionContext()).thenReturn(Optional.of(executionContext));
        when(request.getToolCallId()).thenReturn("call-1");
        when(request.getToolName()).thenReturn("current_date");
        when(request.getArguments()).thenReturn("{}");

        interceptor.interceptToolCall(
                request,
                ignored -> new ToolCallResponse("2026-09-09", "current_date", "call-1")
        );
        publisher.complete("task-1");

        StepVerifier.create(traces)
                .expectNextMatches(trace -> trace.type().equals("tool.started")
                        && trace.toolName().equals("current_date"))
                .expectNextMatches(trace -> trace.type().equals("tool.completed")
                        && trace.resultPreview().equals("2026-09-09")
                        && !trace.error())
                .verifyComplete();
    }
}
