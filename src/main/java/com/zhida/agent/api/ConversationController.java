package com.zhida.agent.api;

import com.zhida.agent.conversation.ConversationHistory;
import com.zhida.agent.conversation.ConversationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations")
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class ConversationController {
    private final ConversationRepository repository;
    private final com.zhida.agent.conversation.TaskRepository tasks;
    private final com.zhida.agent.auth.OwnerResolver owners;
    public ConversationController(ConversationRepository repository, com.zhida.agent.conversation.TaskRepository tasks, com.zhida.agent.auth.OwnerResolver owners) {
        this.repository = repository;
        this.tasks = tasks;
        this.owners = owners;
    }

    @GetMapping("/{id}/tasks")
    public Mono<List<com.zhida.agent.conversation.TaskRepository.Task>> tasks(@PathVariable String id, java.security.Principal principal) {
        return Mono.fromCallable(() -> tasks.list(owners.owner(principal), id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/tasks/{taskId}/events")
    public Mono<List<com.zhida.agent.conversation.TaskRepository.Event>> events(@PathVariable String id, @PathVariable String taskId, java.security.Principal principal) {
        return Mono.fromCallable(() -> tasks.events(owners.owner(principal), id, taskId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<List<ConversationRepository.Conversation>> list(java.security.Principal principal) {
        return Mono.fromCallable(() -> repository.list(owners.owner(principal)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<List<ConversationRepository.Message>> messages(@PathVariable String id, java.security.Principal principal) {
        return Mono.fromCallable(() -> repository.messages(owners.owner(principal), id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<java.util.Map<String,String>> create(java.security.Principal principal) {
        return Mono.fromCallable(() -> {
            String id=java.util.UUID.randomUUID().toString();
            repository.create(owners.owner(principal),id,"新会话");
            return java.util.Map.of("id",id);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable String id, java.security.Principal principal) {
        return Mono.<Void>fromRunnable(() -> repository.deleteConversation(owners.owner(principal),id)).subscribeOn(Schedulers.boundedElastic());
    }
}
