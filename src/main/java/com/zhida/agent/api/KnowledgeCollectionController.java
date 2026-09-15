package com.zhida.agent.api;

import com.zhida.agent.auth.OwnerResolver;
import com.zhida.agent.knowledge.KnowledgeBaseRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class KnowledgeCollectionController {
  private final KnowledgeBaseRepository repository;
  private final OwnerResolver owners;

  public KnowledgeCollectionController(KnowledgeBaseRepository repository, OwnerResolver owners) {
    this.repository = repository;
    this.owners = owners;
  }

  public record Base(String id, String name) {}

  public record Create(@NotBlank @Size(max = 100) String name) {}

  @GetMapping
  public List<Base> list(Principal principal) {
    List<Base> list = new ArrayList<>();
    list.add(new Base("default", "默认知识库"));
    repository.list(owners.owner(principal)).stream()
        .map(base -> new Base(base.id(), base.name()))
        .forEach(list::add);
    return list;
  }

  @PostMapping
  public Base create(@Valid @RequestBody Create request, Principal principal) {
    var created = repository.create(owners.owner(principal), request.name());
    return new Base(created.id(), created.name());
  }
}
