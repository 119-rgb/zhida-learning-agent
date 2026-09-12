package com.zhida.agent.api;

import com.zhida.agent.auth.OwnerResolver;
import com.zhida.agent.conversation.ConversationHistory;
import com.zhida.agent.conversation.TaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class TaskController {

    private final TaskRepository repository;
    private final OwnerResolver owners;
    private final ConversationHistory history;

    public TaskController(
            TaskRepository repository,
            OwnerResolver owners,
            ConversationHistory history
    ) {
        this.repository = repository;
        this.owners = owners;
        this.history = history;
    }

    @PostMapping("/{id}/cancel")
    public Mono<Map<String, Boolean>> cancel(@PathVariable String id, Principal principal) {
        return Mono.fromCallable(() -> Map.of("accepted", history.cancel(owners.owner(principal), id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<TaskRepository.Task> snapshot(@PathVariable String id, Principal principal) {
        return Mono.fromCallable(() -> repository.snapshot(owners.owner(principal), id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/usage")
    public Mono<List<TaskRepository.Usage>> usage(@PathVariable String id, Principal principal) {
        return Mono.fromCallable(() -> repository.usage(owners.owner(principal), id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/memories")
    public Mono<List<TaskRepository.MemoryUsage>> memories(@PathVariable String id, Principal principal) {
        return Mono.fromCallable(() -> repository.memoryUsage(owners.owner(principal), id))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
