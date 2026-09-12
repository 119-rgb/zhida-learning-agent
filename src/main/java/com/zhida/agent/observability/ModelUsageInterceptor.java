package com.zhida.agent.observability;

import com.alibaba.cloud.ai.graph.agent.interceptor.*;
import com.zhida.agent.conversation.TaskRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Records provider usage once per model invocation, never sums streaming chunks. */
@Component
public class ModelUsageInterceptor extends ModelInterceptor {
    private final ObjectProvider<TaskRepository> repositories;
    public ModelUsageInterceptor(ObjectProvider<TaskRepository> repositories) { this.repositories = repositories; }
    @Override public String getName() { return "zhida_model_usage"; }
    @Override public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String taskId = (String) request.getContext().get(ToolTracePublisher.TASK_ID_METADATA_KEY);
        var repository = repositories.getIfAvailable();
        if (taskId == null || repository == null) return handler.call(request);
        String callId = UUID.randomUUID().toString();
        ModelResponse response = handler.call(request);
        if (response.getMessage() instanceof Flux<?> source) {
            return ModelResponse.of(Flux.defer(() -> {
                var latest = new AtomicReference<ChatResponse>();
                var saved = new java.util.concurrent.atomic.AtomicBoolean();
                return source.cast(ChatResponse.class).publishOn(Schedulers.boundedElastic())
                        .doOnNext(chunk -> {
                            if (chunk.getMetadata().getUsage().getTotalTokens() > 0) latest.set(chunk);
                        })
                        .doOnComplete(() -> { if (saved.compareAndSet(false,true)) save(repository,taskId,callId,latest.get(),"onComplete"); })
                        .doOnError(error -> { if (saved.compareAndSet(false,true)) save(repository,taskId,callId,latest.get(),"onError"); })
                        .doFinally(signal -> { if (saved.compareAndSet(false,true)) save(repository, taskId, callId, latest.get(), signal.toString()); });
            }));
        }
        save(repository, taskId, callId, response.getChatResponse(), "onComplete");
        return response;
    }
    private void save(TaskRepository repository, String task, String call, ChatResponse response, String outcome) {
        var usage = response == null || response.getMetadata().getUsage().getTotalTokens() == 0 ? null : response.getMetadata().getUsage();
        try {
            repository.recordUsage(task, call, response == null ? "unknown" : response.getMetadata().getModel(),
                    usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens(), outcome);
        } catch (org.springframework.dao.DataAccessException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Model usage could not be saved for task {}",task);
        }
    }
}
