package com.zhida.agent.api;

import com.zhida.agent.conversation.ConversationHistory;
import com.zhida.agent.conversation.ConversationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.List;

@RestController
@RequestMapping("/api/v1/memories")
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class MemoryController {
    private final ConversationRepository repository;
    private final com.zhida.agent.auth.OwnerResolver owners;
    public MemoryController(ConversationRepository repository, com.zhida.agent.auth.OwnerResolver owners) { this.repository = repository; this.owners=owners; }
    public record MemoryRequest(@NotBlank @Size(max=500) String content) {}

    @GetMapping
    public Mono<List<ConversationRepository.Memory>> list(java.security.Principal principal) {
        return Mono.fromCallable(() -> repository.memories(owners.owner(principal))).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<ConversationRepository.Memory> save(@Valid @RequestBody MemoryRequest request, java.security.Principal principal) {
        return Mono.fromCallable(() -> repository.saveMemory(owners.owner(principal), request.content())).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable String id, java.security.Principal principal) {
        return Mono.<Void>fromRunnable(() -> repository.deleteMemory(owners.owner(principal), id)).subscribeOn(Schedulers.boundedElastic());
    }
    @PutMapping("/{id}") public Mono<Void> edit(@PathVariable String id,@Valid @RequestBody MemoryRequest request,java.security.Principal principal) {
        return Mono.<Void>fromRunnable(() -> repository.editMemory(owners.owner(principal),id,request.content())).subscribeOn(Schedulers.boundedElastic());
    }
    public record Settings(boolean enabled) {}
    @GetMapping("/settings") public Mono<Settings> settings(java.security.Principal principal) {
        return Mono.fromCallable(() -> new Settings(repository.memoryEnabled(owners.owner(principal)))).subscribeOn(Schedulers.boundedElastic());
    }
    @PutMapping("/settings") public Mono<Void> settings(@RequestBody Settings settings,java.security.Principal principal) {
        return Mono.<Void>fromRunnable(() -> repository.setMemoryEnabled(owners.owner(principal),settings.enabled())).subscribeOn(Schedulers.boundedElastic());
    }
}
