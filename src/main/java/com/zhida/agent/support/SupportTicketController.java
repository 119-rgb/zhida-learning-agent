package com.zhida.agent.support;

import com.zhida.agent.support.SupportTicketRepository.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/support")
@ConditionalOnProperty(name = "zhida.support.enabled", havingValue = "true")
public class SupportTicketController {
  private final SupportTicketService service;
  private final SupportActorResolver actors;

  public SupportTicketController(SupportTicketService service, SupportActorResolver actors) {
    this.service = service;
    this.actors = actors;
  }

  public record CreateRequest(
      @NotBlank @Size(max = 64) String requestId,
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 4000) String description,
      @NotBlank @Size(max = 36) String categoryId,
      @NotNull @AssertTrue Boolean confirmed) {}

  public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

  public record ContentRequest(
      @NotNull @PositiveOrZero Long expectedVersion, @NotBlank @Size(max = 4000) String content) {}

  public record ConfirmRequest(
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotNull @Min(1) @Max(5) Integer rating,
      @Size(max = 1000) String evaluation) {}

  public record AssignRequest(
      @NotNull @PositiveOrZero Long expectedVersion, @NotBlank @Size(max = 36) String agentId) {}

  public record CategoryRequest(@NotBlank @Size(max = 80) String name, @NotNull Boolean enabled) {}

  @GetMapping("/me")
  public SupportActorResolver.Actor me(Principal principal) {
    return actors.resolve(principal);
  }

  @GetMapping("/categories")
  public List<Category> categories(Principal p) {
    return service.categories(actors.resolve(p));
  }

  @PostMapping("/categories")
  @ResponseStatus(HttpStatus.CREATED)
  public Category category(Principal p, @Valid @RequestBody CategoryRequest r) {
    return service.saveCategory(actors.resolve(p), null, r.name(), r.enabled());
  }

  @PutMapping("/categories/{id}")
  public Category category(
      Principal p, @PathVariable String id, @Valid @RequestBody CategoryRequest r) {
    return service.saveCategory(actors.resolve(p), id, r.name(), r.enabled());
  }

  @PostMapping("/tickets")
  @ResponseStatus(HttpStatus.CREATED)
  public Ticket create(Principal p, @Valid @RequestBody CreateRequest r) {
    return service.create(
        actors.resolve(p),
        new SupportTicketService.Create(
            r.requestId(), r.title(), r.description(), r.categoryId(), r.confirmed()));
  }

  @GetMapping("/tickets")
  public List<Ticket> list(Principal p, @RequestParam(defaultValue = "mine") String view) {
    return service.list(actors.resolve(p), view);
  }

  @GetMapping("/tickets/{id}")
  public Detail detail(Principal p, @PathVariable String id) {
    return service.detail(actors.resolve(p), id);
  }

  @PostMapping("/tickets/{id}/comments")
  public Ticket comment(
      Principal p, @PathVariable String id, @Valid @RequestBody ContentRequest r) {
    return service.comment(actors.resolve(p), id, r.expectedVersion(), r.content());
  }

  @PostMapping("/tickets/{id}/claim")
  public Ticket claim(Principal p, @PathVariable String id, @Valid @RequestBody VersionRequest r) {
    return service.claim(actors.resolve(p), id, r.expectedVersion());
  }

  @PostMapping("/tickets/{id}/replies")
  public Ticket reply(Principal p, @PathVariable String id, @Valid @RequestBody ContentRequest r) {
    return service.reply(actors.resolve(p), id, r.expectedVersion(), r.content());
  }

  @PostMapping("/tickets/{id}/solution")
  public Ticket solution(
      Principal p, @PathVariable String id, @Valid @RequestBody ContentRequest r) {
    return service.solution(actors.resolve(p), id, r.expectedVersion(), r.content());
  }

  @PostMapping("/tickets/{id}/reopen")
  public Ticket reopen(Principal p, @PathVariable String id, @Valid @RequestBody ContentRequest r) {
    return service.reopen(actors.resolve(p), id, r.expectedVersion(), r.content());
  }

  @PostMapping("/tickets/{id}/confirm")
  public Ticket confirm(
      Principal p, @PathVariable String id, @Valid @RequestBody ConfirmRequest r) {
    return service.confirm(actors.resolve(p), id, r.expectedVersion(), r.rating(), r.evaluation());
  }

  @PostMapping("/tickets/{id}/assign")
  public Ticket assign(Principal p, @PathVariable String id, @Valid @RequestBody AssignRequest r) {
    return service.assign(actors.resolve(p), id, r.expectedVersion(), r.agentId());
  }
}
