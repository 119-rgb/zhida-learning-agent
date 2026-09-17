package com.zhida.agent.support;

import com.zhida.agent.support.SupportTicketRepository.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** HTTP 参数校验和认证身份适配；状态、归属和事务规则统一交给业务服务。 */
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

  // 不定义 userId/owner/role 字段；orderId 只是待校验资源，不能决定工单 owner 或操作者权限。
  public record CreateRequest(
      @NotBlank @Size(max = 64) String requestId,
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 4000) String description,
      @NotBlank @Size(max = 36) String categoryId,
      @Size(max = 36) String orderId,
      @NotNull @AssertTrue Boolean confirmed) {}

  // 客户端读取工单后提交当时的版本；版本已变化时后端拒绝，客户端应刷新而非盲目重试。
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
            r.requestId(),
            r.title(),
            r.description(),
            r.categoryId(),
            r.orderId(),
            r.confirmed()));
  }

  /**
   * 管理员读取账号目录：分配工单需要客服列表，演示造数需要普通用户列表。
   * 只返回 ID、用户名和角色，不返回密码哈希；非管理员调用返回 403。
   */
  @GetMapping("/accounts")
  public List<SupportActorResolver.AccountSummary> accounts(
      Principal p, @RequestParam String role) {
    return service.accounts(actors.resolve(p), role);
  }

  @GetMapping("/tickets")
  public List<Ticket> list(Principal p, @RequestParam(defaultValue = "mine") String view) {
    return service.list(actors.resolve(p), view);
  }

  /**
   * 页面主动生成工单草稿。模型是否调用草稿工具不可靠，因此提供等价的服务端入口：
   * 页面在售后助手里填好分类、关联订单和问题描述后直接请求这里。
   *
   * <p>与模型工具走同一个 {@code draft} 业务方法，因此同样只产生未写入数据库的草稿，
   * 也返回后端生成的 requestId；真正的建单仍然是用户在页面确认后调用 {@code POST /tickets}。
   */
  @PostMapping("/ticket-drafts")
  @ResponseStatus(HttpStatus.CREATED)
  public SupportTicketService.Draft draft(Principal p, @Valid @RequestBody DraftRequest r) {
    return service.draftFromPage(
        actors.resolve(p), r.description(), r.categoryId(), r.orderId());
  }

  public record DraftRequest(
      @NotBlank @Size(max = 4000) String description,
      @NotBlank @Size(max = 36) String categoryId,
      @Size(max = 36) String orderId) {}

  /**
   * 按草稿的 requestId 检查是否已经建单。返回 204 表示尚未创建，页面可以继续提交；
   * 返回 200 与工单体表示该 requestId 已使用，页面应直接展示原工单而不是再次提交。
   * 该查询只作用于当前登录用户，其他用户使用同一 requestId 不会互相影响。
   *
   * <p>注意：这里显式映射 200/204，不使用 {@code ResponseEntity.of(Optional)}——后者在
   * Optional 为空时返回 404，会把“尚未建单”与“资源不存在”混为一谈。
   */
  @GetMapping("/ticket-drafts/{requestId}")
  public org.springframework.http.ResponseEntity<Ticket> createdBy(
      Principal p, @PathVariable String requestId) {
    return service
        .createdBy(actors.resolve(p), requestId)
        .map(org.springframework.http.ResponseEntity::ok)
        .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
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
