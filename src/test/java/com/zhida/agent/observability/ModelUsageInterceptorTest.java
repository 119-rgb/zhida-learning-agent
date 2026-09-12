package com.zhida.agent.observability;

import com.alibaba.cloud.ai.graph.agent.interceptor.*;
import com.zhida.agent.conversation.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.util.Map;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelUsageInterceptorTest {
    @Test void recordsCumulativeStreamingUsageOnce() {
        TaskRepository repository = mock(TaskRepository.class);
        ObjectProvider<TaskRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        var request = ModelRequest.builder().context(Map.of(ToolTracePublisher.TASK_ID_METADATA_KEY,"task")).build();
        var chunk = new ChatResponse(List.of(), ChatResponseMetadata.builder().model("test-model").usage(new DefaultUsage(100,20)).build());
        var result = new ModelUsageInterceptor(provider).interceptModel(request, ignored -> ModelResponse.of(Flux.just(chunk,chunk)));
        StepVerifier.create((Flux<?>)result.getMessage()).expectNextCount(2).verifyComplete();
        verify(repository,times(1)).recordUsage(eq("task"),anyString(),eq("test-model"),eq(100),eq(20),eq("onComplete"));
    }
    @Test void missingUsageIsUnknownRatherThanZero() {
        TaskRepository repository = mock(TaskRepository.class);
        ObjectProvider<TaskRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        var request = ModelRequest.builder().context(Map.of(ToolTracePublisher.TASK_ID_METADATA_KEY,"task")).build();
        var result = new ModelUsageInterceptor(provider).interceptModel(request, ignored -> ModelResponse.of(Flux.just(new ChatResponse(List.of()))));
        StepVerifier.create((Flux<?>)result.getMessage()).expectNextCount(1).verifyComplete();
        verify(repository).recordUsage(eq("task"),anyString(),eq("unknown"),isNull(),isNull(),eq("onComplete"));
    }
}
