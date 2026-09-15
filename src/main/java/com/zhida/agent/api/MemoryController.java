package com.zhida.agent.api;

import com.zhida.agent.conversation.ConversationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/memories")
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class MemoryController {
  private final ConversationRepository repository;
  private final com.zhida.agent.auth.OwnerResolver owners;

  public MemoryController(
      ConversationRepository repository, com.zhida.agent.auth.OwnerResolver owners) {
    this.repository = repository;
    this.owners = owners;
  }

  public record MemoryRequest(@NotBlank @Size(max = 500) String content) {}

  @GetMapping
  public List<ConversationRepository.Memory> list(java.security.Principal principal) {
    return repository.memories(owners.owner(principal));
  }

  @PostMapping
  public ConversationRepository.Memory save(
      @Valid @RequestBody MemoryRequest request, java.security.Principal principal) {
    return repository.saveMemory(owners.owner(principal), request.content());
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id, java.security.Principal principal) {
    repository.deleteMemory(owners.owner(principal), id);
  }

  @PutMapping("/{id}")
  public void edit(
      @PathVariable String id,
      @Valid @RequestBody MemoryRequest request,
      java.security.Principal principal) {
    repository.editMemory(owners.owner(principal), id, request.content());
  }

  public record Settings(boolean enabled) {}

  @GetMapping("/settings")
  public Settings settings(java.security.Principal principal) {
    return new Settings(repository.memoryEnabled(owners.owner(principal)));
  }

  @PutMapping("/settings")
  public void settings(@RequestBody Settings settings, java.security.Principal principal) {
    repository.setMemoryEnabled(owners.owner(principal), settings.enabled());
  }
}
