package com.zhida.agent.conversation;

import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.AgentEvent;
import com.zhida.agent.application.ResearchOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class ConversationHistory {
    // Used only when authentication is explicitly disabled.
    public static final String LOCAL_OWNER = "local-user";
    private final ConversationRepository repository;
    private final TaskRepository tasks;
    private final ConversationSummaryService summaryService;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, Sinks.One<Void>> cancellations = new ConcurrentHashMap<>();

    public boolean cancel(String owner, String taskId) {
        var task = tasks.snapshot(owner, taskId);
        if (!task.status().equals("RUNNING")) return false;
        var signal = cancellations.get(taskId);
        if (signal == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务正在恢复，请稍后刷新状态");
        return signal.tryEmitEmpty().isSuccess();
    }

    @Autowired
    public ConversationHistory(
            ConversationRepository repository,
            TaskRepository tasks,
            @Nullable ConversationSummaryService summaryService
    ) {
        this.repository = repository;
        this.tasks = tasks;
        this.summaryService = summaryService;
    }

    ConversationHistory(ConversationRepository repository, TaskRepository tasks) {
        this(repository, tasks, null);
    }

    public Flux<AgentEvent> stream(ResearchRequest request, ResearchOrchestrator orchestrator) {
        return stream(request, orchestrator, LOCAL_OWNER);
    }

    public Flux<AgentEvent> stream(ResearchRequest request, ResearchOrchestrator orchestrator, String owner) {
        return Flux.defer(() -> {
            String id = request.conversationId() == null || request.conversationId().isBlank()
                    ? UUID.randomUUID().toString() : request.conversationId().trim();
            if (!running.add(id)) return Flux.error(new ResponseStatusException(HttpStatus.CONFLICT, "这个会话正在回答，请稍后再试"));
            return Flux.defer(() -> {
                repository.ensureConversation(owner, id, request.message());
                var restored = ConversationContext.restore(repository.recentMessages(owner, id), request.message());
                var summarized = summaryService == null
                        ? restored
                        : ConversationContext.withSummary(restored, summaryService.current(owner, id).orElse(null));
                var activeMemories = repository.activeMemories(owner);
                var context = ConversationContext.withMemories(summarized, activeMemories);
                String taskId = request.requestId() == null ? UUID.randomUUID().toString() : request.requestId();
                StringBuilder answer = new StringBuilder();
                var cancellation = Sinks.<Void>one();
                return Flux.usingWhen(Mono.fromCallable(() -> {
                    tasks.begin(owner, id, taskId, request.message(), activeMemories);
                    cancellations.put(taskId, cancellation);
                    return taskId;
                }), ignored -> orchestrator.streamWithContext(new ResearchRequest(id, request.message(), request.requestId(), request.knowledgeBaseId()), context, taskId, owner)
                        .takeUntilOther(cancellation.asMono())
                        .publishOn(Schedulers.boundedElastic())
                        .doOnNext(event -> {
                            tasks.record(owner, id, event);
                            if (event.type().equals("answer.delta") && event.data() instanceof Map<?, ?> data) {
                                Object content = data.get("content");
                                if (content instanceof String text) answer.append(text);
                            }
                            if (event.type().equals("task.completed")) {
                                tasks.finish(owner, id, taskId, "COMPLETED", null, answer.toString());
                                if (summaryService != null) {
                                    summaryService.schedule(owner, id);
                                }
                            }
                            if (event.type().equals("task.failed")) {
                                String code = event.data() instanceof Map<?, ?> data ? String.valueOf(data.get("errorCode")) : "AGENT_EXECUTION_FAILED";
                                tasks.finish(owner, id, taskId, "FAILED", code, "");
                            }
                        }),
                        ignored -> cleanup(owner, id, taskId,
                                cancellation.scan(reactor.core.Scannable.Attr.TERMINATED) ? "CANCELLED" : "INTERRUPTED",
                                cancellation.scan(reactor.core.Scannable.Attr.TERMINATED) ? "USER_CANCELLED" : "STREAM_INCOMPLETE"),
                        (ignored, error) -> cleanup(owner, id, taskId, "FAILED", "EXECUTION_OR_STORAGE_FAILED"),
                        ignored -> cleanup(owner, id, taskId, "CANCELLED", "CLIENT_DISCONNECTED"))
                        .doFinally(signal -> cancellations.remove(taskId, cancellation));
            }).doFinally(signal -> running.remove(id));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> cleanup(String owner, String conversationId, String taskId, String state, String code) {
        return Mono.<Void>fromRunnable(() -> tasks.finish(owner, conversationId, taskId, state, code, ""))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
