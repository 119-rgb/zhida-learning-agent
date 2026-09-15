package com.zhida.agent.support;

import static com.zhida.agent.support.SupportRole.*;
import static com.zhida.agent.support.SupportTicketRepository.Status.*;

import com.zhida.agent.support.SupportActorResolver.Actor;
import com.zhida.agent.support.SupportTicketRepository.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authorizes and validates even direct service calls; all ticket changes and events share a
 * transaction.
 */
public class SupportTicketService {
  private final SupportTicketRepository repository;
  private final SupportActorResolver actors;

  public SupportTicketService(SupportTicketRepository repository, SupportActorResolver actors) {
    this.repository = repository;
    this.actors = actors;
  }

  public record Create(
      String requestId, String title, String description, String categoryId, Boolean confirmed) {}

  private Actor refresh(Actor supplied) {
    if (supplied == null) throw error(HttpStatus.UNAUTHORIZED, "请先登录");
    return actors.account(supplied.id());
  }

  private void role(Actor actor, SupportRole required) {
    if (actor.role() != required) throw error(HttpStatus.FORBIDDEN, "当前角色不能执行此操作");
  }

  public List<Category> categories(Actor supplied) {
    Actor actor = refresh(supplied);
    return repository.categories(actor.role() == ADMIN);
  }

  public Category saveCategory(Actor supplied, String id, String name, boolean enabled) {
    Actor actor = refresh(supplied);
    role(actor, ADMIN);
    String normalized = text(name, 80, "分类名称");
    try {
      return write(
          status -> {
            Category old =
                id == null ? null : repository.category(id, true).orElseThrow(() -> missing());
            String categoryId = old == null ? UUID.randomUUID().toString() : old.id();
            long version = old == null ? 0 : old.version() + 1;
            if (old == null)
              repository
                  .jdbc()
                  .update(
                      "INSERT INTO support_category(id,name,enabled,version) VALUES (?,?,?,?)",
                      categoryId,
                      normalized,
                      enabled,
                      version);
            else
              repository
                  .jdbc()
                  .update(
                      "UPDATE support_category SET name=?,enabled=?,version=? WHERE id=?",
                      normalized,
                      enabled,
                      version,
                      categoryId);
            repository
                .jdbc()
                .update(
                    """
INSERT INTO support_category_event(id,category_id,version,actor_id,old_name,new_name,old_enabled,new_enabled,created_at)
VALUES (?,?,?,?,?,?,?,?,?)
""",
                    UUID.randomUUID().toString(),
                    categoryId,
                    version,
                    actor.id(),
                    old == null ? null : old.name(),
                    normalized,
                    old == null ? null : old.enabled(),
                    enabled,
                    Timestamp.from(Instant.now()));
            return new Category(categoryId, normalized, enabled, version);
          });
    } catch (DuplicateKeyException e) {
      throw conflict("分类名称已存在");
    }
  }

  public Ticket create(Actor supplied, Create request) {
    Actor actor = refresh(supplied);
    role(actor, USER);
    if (request == null || !Boolean.TRUE.equals(request.confirmed()))
      throw error(HttpStatus.BAD_REQUEST, "必须明确确认创建工单");
    String key = text(request.requestId(), 64, "requestId");
    String title = text(request.title(), 120, "标题");
    String description = text(request.description(), 4000, "问题描述");
    String category = text(request.categoryId(), 36, "分类");
    String hash = hash(title, description, category);
    var existing = repository.byRequest(actor.id(), key);
    if (existing.isPresent()) return replay(existing.get(), hash);
    try {
      return write(
          status -> {
            Category chosen =
                repository
                    .category(category, true)
                    .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "分类不存在"));
            if (!chosen.enabled()) throw error(HttpStatus.BAD_REQUEST, "分类已停用");
            Instant now = Instant.now();
            Ticket ticket =
                new Ticket(
                    UUID.randomUUID().toString(),
                    actor.id(),
                    key,
                    hash,
                    title,
                    description,
                    category,
                    PENDING,
                    null,
                    null,
                    null,
                    null,
                    0,
                    now,
                    now);
            repository.insert(ticket);
            repository.event(ticket, 0, "CREATED", actor, PENDING, null, now);
            return ticket;
          });
    } catch (DuplicateKeyException e) {
      // The losing insert transaction has rolled back; read the committed winner in a fresh
      // transaction.
      return repository
          .byRequest(actor.id(), key)
          .map(t -> replay(t, hash))
          .orElseThrow(() -> conflict("创建冲突，请重试"));
    }
  }

  private Ticket replay(Ticket ticket, String hash) {
    if (!ticket.requestHash().equals(hash)) throw conflict("requestId 已用于不同内容");
    return ticket;
  }

  public List<Ticket> list(Actor supplied, String view) {
    Actor actor = refresh(supplied);
    return switch (view) {
      case "mine" ->
          actor.role() == CUSTOMER_SERVICE
              ? repository.list("assigned_to=?", actor.id())
              : repository.list("user_id=?", actor.id());
      case "pending" -> {
        role(actor, CUSTOMER_SERVICE);
        yield repository.list("status='PENDING'");
      }
      case "all" -> {
        role(actor, ADMIN);
        yield repository.list("1=1");
      }
      default -> throw error(HttpStatus.BAD_REQUEST, "未知列表类型");
    };
  }

  public Detail detail(Actor supplied, String id) {
    Actor actor = refresh(supplied);
    // A transaction gives the ticket version and its replies/events one repeatable-read snapshot.
    return repository.readTransaction().execute(status -> repository.detail(accessible(actor, id)));
  }

  private Ticket accessible(Actor actor, String id) {
    Ticket ticket = repository.find(id).orElseThrow(() -> missing());
    boolean allowed =
        switch (actor.role()) {
          case USER -> actor.id().equals(ticket.userId());
          case CUSTOMER_SERVICE ->
              ticket.status() == PENDING || actor.id().equals(ticket.assignedTo());
          case ADMIN -> true;
        };
    if (!allowed) throw missing();
    return ticket;
  }

  public Ticket comment(Actor actor, String id, Long version, String content) {
    return change(actor, id, version, "COMMENT", content, null, null, null);
  }

  public Ticket claim(Actor actor, String id, Long version) {
    return change(actor, id, version, "CLAIM", null, null, null, null);
  }

  public Ticket reply(Actor actor, String id, Long version, String content) {
    return change(actor, id, version, "REPLY", content, null, null, null);
  }

  public Ticket solution(Actor actor, String id, Long version, String content) {
    return change(actor, id, version, "SOLUTION", content, null, null, null);
  }

  public Ticket reopen(Actor actor, String id, Long version, String content) {
    return change(actor, id, version, "REOPEN", content, null, null, null);
  }

  public Ticket confirm(Actor actor, String id, Long version, Integer rating, String evaluation) {
    return change(actor, id, version, "CONFIRM", null, null, rating, evaluation);
  }

  public Ticket assign(Actor actor, String id, Long version, String agentId) {
    return change(actor, id, version, "ASSIGN", null, agentId, null, null);
  }

  private Ticket change(
      Actor supplied,
      String id,
      Long expected,
      String action,
      String content,
      String agentId,
      Integer score,
      String evaluation) {
    Actor actor = refresh(supplied);
    SupportRole required =
        switch (action) {
          case "COMMENT", "REOPEN", "CONFIRM" -> USER;
          case "ASSIGN" -> ADMIN;
          default -> CUSTOMER_SERVICE;
        };
    role(actor, required);
    if (expected == null || expected < 0)
      throw error(HttpStatus.BAD_REQUEST, "expectedVersion 必须为非负数");
    String body = content == null ? null : text(content, 4000, "处理内容");
    if (Set.of("COMMENT", "REPLY", "SOLUTION", "REOPEN").contains(action) && body == null)
      throw error(HttpStatus.BAD_REQUEST, "处理内容不能为空");
    if (action.equals("CONFIRM") && (score == null || score < 1 || score > 5))
      throw error(HttpStatus.BAD_REQUEST, "评价分数必须为1到5");
    if (evaluation != null && evaluation.length() > 1000)
      throw error(HttpStatus.BAD_REQUEST, "评价最多1000字符");
    return write(
        status -> {
          Ticket old = accessible(actor, id);
          if (old.version() != expected) throw conflict("工单已变化，请刷新后重试");
          if (old.status() == CLOSED) throw conflict("工单已关闭");
          Status next = old.status();
          String assigned = old.assignedTo();
          String solution = old.solution();
          Integer rating = old.rating();
          String review = old.evaluation();
          switch (action) {
            case "COMMENT" -> {}
            case "CLAIM" -> {
              requireState(old, PENDING);
              if (assigned != null) throw conflict("工单已被接单");
              next = PROCESSING;
              assigned = actor.id();
            }
            case "REPLY", "SOLUTION" -> {
              requireState(old, PROCESSING);
              if (!actor.id().equals(assigned)) throw missing();
              if (action.equals("SOLUTION")) {
                next = AWAITING_CONFIRMATION;
                solution = body;
              }
            }
            case "REOPEN" -> {
              requireState(old, AWAITING_CONFIRMATION);
              next = PROCESSING;
            }
            case "CONFIRM" -> {
              requireState(old, AWAITING_CONFIRMATION);
              next = CLOSED;
              rating = score;
              review = evaluation == null ? null : evaluation.trim();
            }
            case "ASSIGN" -> {
              if (old.status() != PENDING && old.status() != PROCESSING)
                throw conflict("当前状态不能分配客服");
              String target = text(agentId, 36, "客服账号");
              Actor agent;
              try {
                agent = actors.account(target);
              } catch (ResponseStatusException e) {
                throw error(HttpStatus.BAD_REQUEST, "客服账号无效");
              }
              if (agent.role() != CUSTOMER_SERVICE) throw error(HttpStatus.BAD_REQUEST, "只能分配给客服");
              if (target.equals(assigned)) throw conflict("工单已分配给该客服");
              assigned = target;
              next = PROCESSING;
            }
            default -> throw new IllegalArgumentException("未知工单操作");
          }
          Instant now = Instant.now();
          if (repository.update(old, next, assigned, solution, rating, review, now) != 1)
            throw conflict("工单被其他操作修改，请刷新后重试");
          repository.event(old, old.version() + 1, action, actor, next, assigned, now);
          if (body != null) repository.reply(id, old.version() + 1, actor.id(), action, body, now);
          return repository.find(id).orElseThrow(() -> missing());
        });
  }

  private void requireState(Ticket ticket, Status expected) {
    if (ticket.status() != expected) throw conflict("当前状态不能执行此操作");
  }

  private <T> T write(
      java.util.function.Function<org.springframework.transaction.TransactionStatus, T> work) {
    try {
      return repository.transaction().execute(work::apply);
    } catch (org.springframework.dao.ConcurrencyFailureException error) {
      throw conflict("数据库并发冲突，请刷新后重试");
    }
  }

  private static String text(String input, int max, String field) {
    if (input == null || input.isBlank() || input.length() > max)
      throw error(HttpStatus.BAD_REQUEST, field + "不能为空且最多" + max + "字符");
    return input.trim();
  }

  private static String hash(String... values) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      for (String value : values)
        digest.update(
            (value.length() + ":" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static ResponseStatusException error(HttpStatus status, String message) {
    return new ResponseStatusException(status, message);
  }

  private static ResponseStatusException conflict(String message) {
    return error(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException missing() {
    return error(HttpStatus.NOT_FOUND, "工单或资源不存在");
  }
}
