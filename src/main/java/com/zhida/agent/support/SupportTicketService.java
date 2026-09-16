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
 * 工单业务入口：先检查真实角色与资源归属，再验证状态，最后在同一事务中写业务与审计。
 *
 * <p>Controller 的参数校验只是第一层；将来由其他 Java 服务调用时，也必须遵守这里的业务规则。
 */
public class SupportTicketService {
  private final SupportTicketRepository repository;
  private final SupportActorResolver actors;
  private final ProductOrderService productOrders;

  public SupportTicketService(
      SupportTicketRepository repository,
      SupportActorResolver actors,
      ProductOrderService productOrders) {
    this.repository = repository;
    this.actors = actors;
    this.productOrders = productOrders;
  }

  public record Create(
      String requestId,
      String title,
      String description,
      String categoryId,
      String orderId,
      Boolean confirmed) {
    // 保留模块 1 的无订单调用方式，旧客户端升级后仍可手动创建不关联订单的工单。
    public Create(
        String requestId,
        String title,
        String description,
        String categoryId,
        Boolean confirmed) {
      this(requestId, title, description, categoryId, null, confirmed);
    }
  }

  /**
   * AI 只能生成未确认草稿；confirmed 固定为 false，且此记录不会写库。requestId 由后端生成，
   * 用户确认后再原样提交给 create，届时分类状态、订单归属和全部业务规则会重新校验。
   */
  public record Draft(
      String requestId,
      String title,
      String description,
      String categoryId,
      String categoryName,
      String orderId,
      boolean confirmed,
      String nextAction) {}

  private Actor refresh(Actor supplied) {
    // Actor 中的 role 不作为授权依据；重新查库，防止伪造角色或继续使用已经撤销的角色。
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
            // 与创建工单共用分类行锁，避免检查“启用”之后分类又被并发停用。
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
            // 分类修改与分类审计共用当前事务，不能修改成功但丢失操作记录。
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
    // 创建是用户确认后的业务接口；缺失/false 均拒绝，不能把待确认草稿直接作为工单保存。
    if (request == null || !Boolean.TRUE.equals(request.confirmed()))
      throw error(HttpStatus.BAD_REQUEST, "必须明确确认创建工单");
    String key = text(request.requestId(), 64, "requestId");
    String title = text(request.title(), 120, "标题");
    String description = text(request.description(), 4000, "问题描述");
    String category = text(request.categoryId(), 36, "分类");
    String orderId =
        request.orderId() == null || request.orderId().isBlank()
            ? null
            : text(request.orderId(), 36, "订单");
    // 关联订单属于工单内容的一部分；同一 requestId 不能在重试时悄悄换成另一张订单。
    String hash = hash(title, description, category, orderId == null ? "" : orderId);
    // 先查用于快速重放；最终防重复仍依靠数据库 (user_id, request_id) 唯一约束。
    // 在检查分类启用前重放，使已经创建的请求不会因分类后来停用而失去幂等性。
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
            if (orderId != null) productOrders.ownedOrder(actor.id(), orderId);
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
                    orderId,
                    PENDING,
                    null,
                    null,
                    null,
                    null,
                    0,
                    now,
                    now);
            // 创建记录和版本 0 的 CREATED 事件一起提交；审计失败时工单也回滚。
            repository.insert(ticket);
            repository.event(ticket, 0, "CREATED", actor, PENDING, null, now);
            return ticket;
          });
    } catch (DuplicateKeyException e) {
      // 并发插入的失败事务已退出并回滚，此时才读取赢家已提交的工单，避免在失败事务中继续查询。
      return repository
          .byRequest(actor.id(), key)
          .map(t -> replay(t, hash))
          .orElseThrow(() -> conflict("创建冲突，请重试"));
    }
  }

  public Draft draft(
      Actor supplied, String titleValue, String descriptionValue, String categoryId, String orderIdValue) {
    Actor actor = refresh(supplied);
    role(actor, USER);
    String title = text(titleValue, 120, "标题");
    String description = text(descriptionValue, 4000, "问题描述");
    String category = text(categoryId, 36, "分类");
    Category chosen =
        repository.category(category, false).orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "分类不存在"));
    if (!chosen.enabled()) throw error(HttpStatus.BAD_REQUEST, "分类已停用");
    String orderId =
        orderIdValue == null || orderIdValue.isBlank()
            ? null
            : productOrders.myOrder(actor, orderIdValue).id();
    return new Draft(
        UUID.randomUUID().toString(),
        title,
        description,
        chosen.id(),
        chosen.name(),
        orderId,
        false,
        "请用户核对草稿并明确确认后，再调用工单创建接口。");
  }

  /**
   * 按 requestId 查询本人已创建的工单，供页面在确认建单前检测重复提交。
   *
   * <p>只读接口，因此不要求 expectedVersion；查询始终带 user_id 条件，其他用户的工单即使
   * requestId 相同也查不到。返回空表示该 requestId 尚未建单，可以继续确认创建。
   */
  public Optional<Ticket> createdBy(Actor supplied, String requestIdValue) {
    Actor actor = refresh(supplied);
    role(actor, USER);
    String key = text(requestIdValue, 64, "requestId");
    return repository.byRequest(actor.id(), key);
  }

  private Ticket replay(Ticket ticket, String hash) {
    // 相同 key 只允许重放相同规范化内容；不能用同一个 key 悄悄替换问题描述。
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
    // 本调用链没有外层事务；独立 RR 事务让本体、回复、事件属于同一快照，避免混合新旧版本。
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
    // 无权限与不存在统一返回 404，避免跨账号探测工单是否存在。
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
    // 第一道门是操作角色；第二道门 accessible 检查“这个角色能否操作这张工单”。
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
          // 状态转换集中在一个入口；不存在绕过用户确认直接关闭的客服/管理员操作。
          switch (action) {
            case "COMMENT" -> {} // 补充不改变状态，但仍增加版本并记录事件。
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
                // 提交方案不代表问题已解决，后续必须由工单发起人确认。
                next = AWAITING_CONFIRMATION;
                solution = body;
              }
            }
            case "REOPEN" -> {
              // 用户未解决时退回原客服，保留此前方案及回复作为处理历史。
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
          // 前面的 SELECT 只是判断依据；UPDATE 中的版本/状态/处理人条件才是并发胜负的裁决。
          if (repository.update(old, next, assigned, solution, rating, review, now) != 1)
            throw conflict("工单被其他操作修改，请刷新后重试");
          // 三次写共用同一事务。任一事件/回复插入失败，工单状态和版本也不会留下部分变更。
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
      // TransactionTemplate 已完成回滚；不自动重做业务，让客户端刷新版本后明确重试。
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
      // 长度前缀避免不同字段组合拼成相同字符串；调用者先 trim，确保幂等比较稳定。
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
