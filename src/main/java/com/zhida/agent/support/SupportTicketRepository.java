package com.zhida.agent.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

public class SupportTicketRepository {
  public enum Status {
    PENDING,
    PROCESSING,
    AWAITING_CONFIRMATION,
    CLOSED
  }

  public record Ticket(
      String id,
      String userId,
      String requestId,
      @com.fasterxml.jackson.annotation.JsonIgnore String requestHash,
      String title,
      String description,
      String categoryId,
      Status status,
      String assignedTo,
      String solution,
      Integer rating,
      String evaluation,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  public record Category(String id, String name, boolean enabled, long version) {}

  public record Reply(
      String id, long version, String actorId, String kind, String content, Instant createdAt) {}

  public record Event(
      long version,
      String action,
      String actorId,
      String actorRole,
      String fromStatus,
      String toStatus,
      String previousAssignee,
      String assignedTo,
      Instant createdAt) {}

  public record Detail(Ticket ticket, List<Reply> replies, List<Event> events) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final TransactionTemplate readTx;

  public SupportTicketRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
    readTx = new TransactionTemplate(java.util.Objects.requireNonNull(tx.getTransactionManager()));
    readTx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readTx.setReadOnly(true);
  }

  JdbcTemplate jdbc() {
    return jdbc;
  }

  TransactionTemplate transaction() {
    return tx;
  }

  TransactionTemplate readTransaction() {
    return readTx;
  }

  private static final RowMapper<Ticket> TICKET =
      (r, n) ->
          new Ticket(
              r.getString("id"),
              r.getString("user_id"),
              r.getString("request_id"),
              r.getString("request_hash"),
              r.getString("title"),
              r.getString("description"),
              r.getString("category_id"),
              Status.valueOf(r.getString("status")),
              r.getString("assigned_to"),
              r.getString("solution"),
              r.getObject("rating", Integer.class),
              r.getString("evaluation"),
              r.getLong("version"),
              r.getTimestamp("created_at").toInstant(),
              r.getTimestamp("updated_at").toInstant());

  Optional<Ticket> find(String id) {
    return jdbc.query("SELECT * FROM support_ticket WHERE id=?", TICKET, id).stream().findFirst();
  }

  Optional<Ticket> byRequest(String user, String request) {
    return jdbc
        .query(
            "SELECT * FROM support_ticket WHERE user_id=? AND request_id=?", TICKET, user, request)
        .stream()
        .findFirst();
  }

  List<Ticket> list(String predicate, Object... args) {
    return jdbc.query(
        "SELECT * FROM support_ticket WHERE "
            + predicate
            + " ORDER BY updated_at DESC,id LIMIT 100",
        TICKET,
        args);
  }

  Optional<Category> category(String id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM support_category WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (r, n) ->
                new Category(
                    r.getString("id"),
                    r.getString("name"),
                    r.getBoolean("enabled"),
                    r.getLong("version")),
            id)
        .stream()
        .findFirst();
  }

  List<Category> categories(boolean all) {
    return jdbc.query(
        "SELECT * FROM support_category" + (all ? "" : " WHERE enabled=true") + " ORDER BY name,id",
        (r, n) ->
            new Category(
                r.getString("id"),
                r.getString("name"),
                r.getBoolean("enabled"),
                r.getLong("version")));
  }

  void insert(Ticket t) {
    jdbc.update(
        """
INSERT INTO support_ticket(id,user_id,request_id,request_hash,title,description,category_id,status,version,created_at,updated_at)
VALUES (?,?,?,?,?,?,?,?,?,?,?)
""",
        t.id(),
        t.userId(),
        t.requestId(),
        t.requestHash(),
        t.title(),
        t.description(),
        t.categoryId(),
        t.status().name(),
        t.version(),
        Timestamp.from(t.createdAt()),
        Timestamp.from(t.updatedAt()));
  }

  int update(
      Ticket old,
      Status next,
      String assigned,
      String solution,
      Integer rating,
      String evaluation,
      Instant now) {
    return jdbc.update(
        """
UPDATE support_ticket SET status=?,assigned_to=?,solution=?,rating=?,evaluation=?,version=version+1,updated_at=?
WHERE id=? AND version=? AND status=? AND (assigned_to=? OR (assigned_to IS NULL AND ? IS NULL))
""",
        next.name(),
        assigned,
        solution,
        rating,
        evaluation,
        Timestamp.from(now),
        old.id(),
        old.version(),
        old.status().name(),
        old.assignedTo(),
        old.assignedTo());
  }

  void event(
      Ticket old,
      long version,
      String action,
      SupportActorResolver.Actor actor,
      Status next,
      String assigned,
      Instant now) {
    jdbc.update(
        """
INSERT INTO support_ticket_event(ticket_id,version,action,actor_id,actor_role,from_status,to_status,previous_assignee,assigned_to,created_at)
VALUES (?,?,?,?,?,?,?,?,?,?)
""",
        old.id(),
        version,
        action,
        actor.id(),
        actor.role().name(),
        version == 0 ? null : old.status().name(),
        next.name(),
        old.assignedTo(),
        assigned,
        Timestamp.from(now));
  }

  void reply(String ticket, long version, String actor, String kind, String content, Instant now) {
    jdbc.update(
        "INSERT INTO support_ticket_reply(id,ticket_id,version,actor_id,kind,content,created_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        java.util.UUID.randomUUID().toString(),
        ticket,
        version,
        actor,
        kind,
        content,
        Timestamp.from(now));
  }

  Detail detail(Ticket ticket) {
    return new Detail(
        ticket,
        jdbc.query(
            "SELECT * FROM support_ticket_reply WHERE ticket_id=? ORDER BY version",
            (r, n) ->
                new Reply(
                    r.getString("id"),
                    r.getLong("version"),
                    r.getString("actor_id"),
                    r.getString("kind"),
                    r.getString("content"),
                    r.getTimestamp("created_at").toInstant()),
            ticket.id()),
        jdbc.query(
            "SELECT * FROM support_ticket_event WHERE ticket_id=? ORDER BY version",
            (r, n) ->
                new Event(
                    r.getLong("version"),
                    r.getString("action"),
                    r.getString("actor_id"),
                    r.getString("actor_role"),
                    r.getString("from_status"),
                    r.getString("to_status"),
                    r.getString("previous_assignee"),
                    r.getString("assigned_to"),
                    r.getTimestamp("created_at").toInstant()),
            ticket.id()));
  }
}
