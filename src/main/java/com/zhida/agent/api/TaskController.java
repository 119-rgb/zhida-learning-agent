package com.zhida.agent.api;

import com.zhida.agent.auth.OwnerResolver;
import com.zhida.agent.conversation.ConversationHistory;
import com.zhida.agent.conversation.TaskRepository;
import com.zhida.agent.observability.ModelCostService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class TaskController {

  private final TaskRepository repository;
  private final OwnerResolver owners;
  private final ConversationHistory history;
  private final ModelCostService costs;

  public TaskController(
      TaskRepository repository,
      OwnerResolver owners,
      ConversationHistory history,
      ModelCostService costs) {
    this.repository = repository;
    this.owners = owners;
    this.history = history;
    this.costs = costs;
  }

  @PostMapping("/{id}/cancel")
  public Map<String, Boolean> cancel(@PathVariable String id, Principal principal) {
    return Map.of("accepted", history.cancel(owners.owner(principal), id));
  }

  @GetMapping("/{id}")
  public TaskRepository.Task snapshot(@PathVariable String id, Principal principal) {
    return repository.snapshot(owners.owner(principal), id);
  }

  @GetMapping("/{id}/usage")
  public List<TaskRepository.Usage> usage(@PathVariable String id, Principal principal) {
    return repository.usage(owners.owner(principal), id);
  }

  @GetMapping("/{id}/cost")
  public ModelCostService.CostEstimate cost(@PathVariable String id, Principal principal) {
    return costs.estimate(
        repository.usage(owners.owner(principal), id).stream()
            .map(
                usage ->
                    new ModelCostService.UsageSample(
                        usage.model(),
                        usage.promptTokens(),
                        usage.completionTokens(),
                        usage.recordedAt()))
            .toList());
  }

  @GetMapping("/{id}/memories")
  public List<TaskRepository.MemoryUsage> memories(@PathVariable String id, Principal principal) {
    return repository.memoryUsage(owners.owner(principal), id);
  }
}
