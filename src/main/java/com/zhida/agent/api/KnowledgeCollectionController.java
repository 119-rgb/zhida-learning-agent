package com.zhida.agent.api;

import com.zhida.agent.auth.OwnerResolver;
import com.zhida.agent.knowledge.KnowledgeBaseAccessService;
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
  private final KnowledgeBaseAccessService access;
  private final OwnerResolver owners;

  public KnowledgeCollectionController(
      KnowledgeBaseRepository repository,
      KnowledgeBaseAccessService access,
      OwnerResolver owners) {
    this.repository = repository;
    this.access = access;
    this.owners = owners;
  }

  /** visibility/writable 供页面说明访问边界；服务端接口仍独立执行权限校验。 */
  public record Base(String id, String name, String visibility, boolean writable) {}

  public record Create(@NotBlank @Size(max = 100) String name) {}

  @GetMapping
  public List<Base> list(Principal principal) {
    String owner = owners.owner(principal);
    List<Base> list = new ArrayList<>();
    list.add(new Base("default", "我的默认知识库", "PRIVATE", true));
    if (access.canReadPublic(owner)) {
      list.add(
          new Base(
              KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID,
              "公共产品售后知识库",
              "PUBLIC",
              access.canMaintainPublic(owner)));
    }
    repository.list(owner).stream()
        .map(base -> new Base(base.id(), base.name(), "PRIVATE", true))
        .forEach(list::add);
    return list;
  }

  @PostMapping
  public Base create(@Valid @RequestBody Create request, Principal principal) {
    var created = repository.create(owners.owner(principal), request.name());
    return new Base(created.id(), created.name(), "PRIVATE", true);
  }
}
