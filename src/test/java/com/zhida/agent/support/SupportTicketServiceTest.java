package com.zhida.agent.support;

import static com.zhida.agent.support.SupportTicketRepository.Status.*;
import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import com.zhida.agent.support.SupportActorResolver.Actor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.h2.api.Trigger;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class SupportTicketServiceTest {
  HikariDataSource source;
  JdbcTemplate jdbc;
  SupportTicketService service;
  Actor user, other, agent, second, admin;
  String category;

  @BeforeEach
  void database() {
    source = new HikariDataSource();
    source.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
    source.setUsername("sa");
    source.setMaximumPoolSize(5);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/conversation-schema.sql"),
            new ClassPathResource("db/support-schema.sql"))
        .execute(source);
    jdbc = new JdbcTemplate(source);
    var repository =
        new SupportTicketRepository(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)));
    var actors = new SupportActorResolver(jdbc);
    service = ticketService(repository, actors);
    user = account("fixture_user", SupportRole.USER, actors);
    other = account("fixture_other", SupportRole.USER, actors);
    agent = account("fixture_agent", SupportRole.CUSTOMER_SERVICE, actors);
    second = account("fixture_second", SupportRole.CUSTOMER_SERVICE, actors);
    admin = account("fixture_admin", SupportRole.ADMIN, actors);
    category = service.saveCategory(admin, null, "虚构订阅开通", true).id();
  }

  @AfterEach
  void close() {
    source.close();
  }

  Actor account(String name, SupportRole role, SupportActorResolver resolver) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO user_account(id,username,password_hash,is_guest,created_at) VALUES"
            + " (?,?,?,false,CURRENT_TIMESTAMP)",
        id,
        name,
        "DISABLED");
    jdbc.update("INSERT INTO support_account_role(user_id,role) VALUES (?,?)", id, role.name());
    return resolver.account(id);
  }

  SupportTicketService.Create request(String key) {
    return new SupportTicketService.Create(key, "虚构订单已付款未开通", "这是测试订单，不连接支付。", category, true);
  }

  SupportTicketRepository.Ticket create() {
    return service.create(user, request(UUID.randomUUID().toString()));
  }

  int count(String table, String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE ticket_id=?", Integer.class, id);
  }

  List<Object> race(Supplier<?> a, Supplier<?> b) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<Object>>();
      for (Supplier<?> action : List.of(a, b))
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!go.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("fixture start timeout");
                  try {
                    return action.get();
                  } catch (ResponseStatusException error) {
                    return error;
                  }
                }));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      return List.of(
          futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentCreationHasOneTicketAndOneAuditEvent() throws Exception {
    for (int i = 0; i < 5; i++) {
      String key = UUID.randomUUID().toString();
      var results =
          race(() -> service.create(user, request(key)), () -> service.create(user, request(key)));
      assertThat(results).allMatch(r -> r instanceof SupportTicketRepository.Ticket);
      var first = (SupportTicketRepository.Ticket) results.get(0);
      var second = (SupportTicketRepository.Ticket) results.get(1);
      assertThat(second.id()).isEqualTo(first.id());
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM support_ticket WHERE user_id=? AND request_id=?",
                  Integer.class,
                  user.id(),
                  key))
          .isEqualTo(1);
      assertThat(count("support_ticket_event", first.id())).isEqualTo(1);
    }
  }

  @Test
  void twoCustomerServiceAgentsCannotBothClaim() throws Exception {
    for (int i = 0; i < 5; i++) {
      String id = create().id();
      var results = race(() -> service.claim(agent, id, 0L), () -> service.claim(second, id, 0L));
      assertThat(results.stream().filter(r -> r instanceof SupportTicketRepository.Ticket).count())
          .isEqualTo(1);
      var rejected =
          (ResponseStatusException)
              results.stream()
                  .filter(r -> r instanceof ResponseStatusException)
                  .findFirst()
                  .orElseThrow();
      assertThat(rejected.getStatusCode().value()).isIn(404, 409);
      var detail = service.detail(user, id);
      assertThat(detail.ticket().version()).isEqualTo(1);
      assertThat(detail.ticket().status()).isEqualTo(PROCESSING);
      assertThat(detail.events()).hasSize(2);
      assertThat(detail.events().get(1).actorId()).isEqualTo(detail.ticket().assignedTo());
    }
  }

  public static class RejectAudit implements Trigger {
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
      throw new SQLException("fixture audit failure");
    }
  }

  @Test
  void serializationFailureIsReturnedAsConflictRatherThanServerError() throws Exception {
    String id = create().id();
    CyclicBarrier snapshots = new CyclicBarrier(2);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    var repository =
        new SupportTicketRepository(jdbc, tx) {
          @Override
          Optional<SupportTicketRepository.Ticket> find(String ticketId) {
            var result = super.find(ticketId);
            if (result.orElseThrow().version() == 0) {
              try {
                snapshots.await(5, TimeUnit.SECONDS);
              } catch (Exception error) {
                throw new AssertionError(error);
              }
            }
            return result;
          }
        };
    var isolated = ticketService(repository, new SupportActorResolver(jdbc));
    var results = race(() -> isolated.claim(agent, id, 0L), () -> isolated.claim(second, id, 0L));
    assertThat(results.stream().filter(r -> r instanceof SupportTicketRepository.Ticket).count())
        .isEqualTo(1);
    var rejected =
        (ResponseStatusException)
            results.stream()
                .filter(r -> r instanceof ResponseStatusException)
                .findFirst()
                .orElseThrow();
    assertThat(rejected.getStatusCode().value()).isEqualTo(409);
    assertThat(service.detail(user, id).events()).hasSize(2);
  }

  @Test
  void auditFailureRollsBackTicketAndReplyTogether() {
    String id = create().id();
    service.claim(agent, id, 0L);
    jdbc.execute(
        "CREATE TRIGGER fixture_reject_audit BEFORE INSERT ON support_ticket_event FOR EACH ROW"
            + " CALL 'com.zhida.agent.support.SupportTicketServiceTest$RejectAudit'");
    assertThatThrownBy(() -> service.reply(agent, id, 1L, "不能部分提交的回复"))
        .isInstanceOf(DataAccessException.class);
    var detail = service.detail(user, id);
    assertThat(detail.ticket().version()).isEqualTo(1);
    assertThat(detail.ticket().status()).isEqualTo(PROCESSING);
    assertThat(detail.events()).hasSize(2);
    assertThat(detail.replies()).isEmpty();
    jdbc.execute("DROP TRIGGER fixture_reject_audit");
    service.reply(agent, id, 1L, "恢复审计后正常提交");
    assertThat(service.detail(user, id).replies()).hasSize(1);
  }

  @Test
  void creationAuditFailureLeavesNoTicketAndCanRetry() {
    var request = request(UUID.randomUUID().toString());
    jdbc.execute(
        "CREATE TRIGGER fixture_reject_audit BEFORE INSERT ON support_ticket_event FOR EACH ROW"
            + " CALL 'com.zhida.agent.support.SupportTicketServiceTest$RejectAudit'");
    assertThatThrownBy(() -> service.create(user, request)).isInstanceOf(DataAccessException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_ticket", Integer.class)).isZero();
    jdbc.execute("DROP TRIGGER fixture_reject_audit");
    assertThat(service.create(user, request).version()).isZero();
  }

  @Test
  void duplicateKeysArePerUserAndChangedContentIsRejected() {
    String key = UUID.randomUUID().toString();
    var first = service.create(user, request(key));
    assertThat(service.create(user, request(key)).id()).isEqualTo(first.id());
    assertThat(service.create(other, request(key)).id()).isNotEqualTo(first.id());
    assertStatus(
        409,
        () ->
            service.create(
                user, new SupportTicketService.Create(key, "不同标题", "不同内容", category, true)));
    assertStatus(
        400,
        () ->
            service.create(
                user,
                new SupportTicketService.Create("not-confirmed", "标题", "描述", category, false)));
  }

  @Test
  void forgedRoleAndRevokedRoleNeverAuthorizeServiceCalls() {
    String id = create().id();
    Actor forged = new Actor(user.id(), SupportRole.ADMIN);
    assertStatus(403, () -> service.assign(forged, id, 0L, agent.id()));
    assertStatus(
        403, () -> service.claim(new Actor(user.id(), SupportRole.CUSTOMER_SERVICE), id, 0L));
    jdbc.update("UPDATE support_account_role SET role='USER' WHERE user_id=?", agent.id());
    assertStatus(403, () -> service.claim(agent, id, 0L));
  }

  @Test
  void assignmentAndSolutionRequireLegalStatesAndCorrectOwners() {
    String id = create().id();
    assertStatus(404, () -> service.detail(other, id));
    assertStatus(409, () -> service.reopen(user, id, 0L, "尚无解决方案"));
    assertStatus(409, () -> service.solution(agent, id, 0L, "尚未接单"));
    service.assign(admin, id, 0L, agent.id());
    service.assign(admin, id, 1L, second.id());
    assertStatus(404, () -> service.reply(agent, id, 2L, "旧客服不能回复"));
    service.solution(second, id, 2L, "虚构解决方案");
    assertStatus(409, () -> service.assign(admin, id, 3L, agent.id()));
    assertStatus(403, () -> service.confirm(admin, id, 3L, 5, "管理员不能替用户确认"));
    assertStatus(400, () -> service.confirm(user, id, 3L, 6, "无效评价"));
    service.confirm(user, id, 3L, 5, "虚构满意评价");
    assertStatus(409, () -> service.comment(user, id, 4L, "关闭后不能补充"));
    assertStatus(409, () -> service.reopen(user, id, 4L, "关闭后不能退回"));
    assertStatus(409, () -> service.assign(admin, id, 4L, agent.id()));
    var detail = service.detail(user, id);
    assertThat(detail.events()).hasSize(5);
    for (int i = 0; i < detail.events().size(); i++)
      assertThat(detail.events().get(i).version()).isEqualTo(i);
    assertThat(detail.events().get(4).actorRole()).isEqualTo("USER");
  }

  @Test
  void disabledCategoryStillAllowsHistoryAndExactReplay() {
    var request = request(UUID.randomUUID().toString());
    var first = service.create(user, request);
    service.saveCategory(admin, category, "虚构订阅开通", false);
    assertThat(service.detail(user, first.id()).ticket().categoryId()).isEqualTo(category);
    assertThat(service.create(user, request).id()).isEqualTo(first.id());
    assertThat(service.categories(user)).isEmpty();
    assertThat(service.categories(admin)).hasSize(1);
    assertStatus(400, () -> service.create(user, request(UUID.randomUUID().toString())));
  }

  @Test
  void categoryAuditFailureRollsBackCategory() {
    jdbc.execute(
        "CREATE TRIGGER fixture_reject_category BEFORE INSERT ON support_category_event FOR EACH"
            + " ROW CALL 'com.zhida.agent.support.SupportTicketServiceTest$RejectAudit'");
    assertThatThrownBy(() -> service.saveCategory(admin, category, "不能部分更新", false))
        .isInstanceOf(DataAccessException.class);
    assertThat(service.categories(user))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.name()).isEqualTo("虚构订阅开通");
              assertThat(c.version()).isZero();
            });
  }

  @Test
  void detailIsOneConsistentSnapshotDuringConcurrentChange() throws Exception {
    String id = create().id();
    CountDownLatch read = new CountDownLatch(1), release = new CountDownLatch(1);
    JdbcTemplate paused =
        new JdbcTemplate(source) {
          @Override
          public <T> List<T> query(
              String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
            List<T> rows = super.query(sql, mapper, args);
            if (sql.equals("SELECT * FROM support_ticket WHERE id=?")) {
              read.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new AssertionError("fixture snapshot timeout");
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
              }
            }
            return rows;
          }
        };
    var snapshotRepository =
        new SupportTicketRepository(
            paused, new TransactionTemplate(new DataSourceTransactionManager(source)));
    var snapshotService = ticketService(snapshotRepository, new SupportActorResolver(paused));
    var worker = Executors.newSingleThreadExecutor();
    try {
      var future = worker.submit(() -> snapshotService.detail(user, id));
      assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
      service.claim(agent, id, 0L);
      release.countDown();
      var detail = future.get(5, TimeUnit.SECONDS);
      assertThat(detail.ticket().version()).isZero();
      assertThat(detail.events()).hasSize(1);
      assertThat(detail.events().get(0).toStatus()).isEqualTo(detail.ticket().status().name());
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }

  static void assertStatus(int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
  }

  /** 单元测试与生产配置使用相同的订单归属服务，避免工单测试绕过模块 2 的校验路径。 */
  SupportTicketService ticketService(
      SupportTicketRepository tickets, SupportActorResolver actorResolver) {
    var orders = new ProductOrderRepository(tickets.jdbc(), tickets.transaction());
    return new SupportTicketService(
        tickets, actorResolver, new ProductOrderService(orders, actorResolver));
  }
}
