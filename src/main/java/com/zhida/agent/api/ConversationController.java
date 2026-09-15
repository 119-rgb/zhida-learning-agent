package com.zhida.agent.api;

import com.zhida.agent.conversation.ConversationRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations")
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class ConversationController {
  private final ConversationRepository repository;
  private final com.zhida.agent.conversation.TaskRepository tasks;
  private final com.zhida.agent.auth.OwnerResolver owners;

  public ConversationController(
      ConversationRepository repository,
      com.zhida.agent.conversation.TaskRepository tasks,
      com.zhida.agent.auth.OwnerResolver owners) {
    this.repository = repository;
    this.tasks = tasks;
    this.owners = owners;
  }

  @GetMapping("/{id}/tasks")
  public List<com.zhida.agent.conversation.TaskRepository.Task> tasks(
      @PathVariable String id, java.security.Principal principal) {
    return tasks.list(owners.owner(principal), id);
  }

  @GetMapping("/{id}/tasks/{taskId}/events")
  public List<com.zhida.agent.conversation.TaskRepository.Event> events(
      @PathVariable String id, @PathVariable String taskId, java.security.Principal principal) {
    return tasks.events(owners.owner(principal), id, taskId);
  }

  @GetMapping
  public List<ConversationRepository.Conversation> list(java.security.Principal principal) {
    return repository.list(owners.owner(principal));
  }

  @GetMapping("/{id}")
  public List<ConversationRepository.Message> messages(
      @PathVariable String id, java.security.Principal principal) {
    return repository.messages(owners.owner(principal), id);
  }

  @PostMapping
  public java.util.Map<String, String> create(java.security.Principal principal) {
    String id = java.util.UUID.randomUUID().toString();
    repository.create(owners.owner(principal), id, "新会话");
    return java.util.Map.of("id", id);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id, java.security.Principal principal) {
    repository.deleteConversation(owners.owner(principal), id);
  }
}
