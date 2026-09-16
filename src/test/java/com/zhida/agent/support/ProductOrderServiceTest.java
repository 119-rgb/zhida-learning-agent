package com.zhida.agent.support;

import static com.zhida.agent.support.ProductOrderRepository.PaymentStatus.*;
import static com.zhida.agent.support.ProductOrderRepository.ServiceStatus.*;
import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import com.zhida.agent.support.SupportActorResolver.Actor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.h2.api.Trigger;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 模块 2 的事务与并发测试；HTTP 测试之外直接观察数据库，证明没有部分提交。 */
class ProductOrderServiceTest {
  HikariDataSource source;
  JdbcTemplate jdbc;
  ProductOrderService service;
  Actor user;
  Actor other;
  Actor admin;
  String productId;

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
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    SupportActorResolver actors = new SupportActorResolver(jdbc);
    service = new ProductOrderService(new ProductOrderRepository(jdbc, transaction), actors);
    user = account("order_unit_user", SupportRole.USER, actors);
    other = account("order_unit_other", SupportRole.USER, actors);
    admin = account("order_unit_admin", SupportRole.ADMIN, actors);
    productId = service.createProduct(admin, "UNIT-SUB", "虚构单元测试订阅", "无真实交易").id();
  }

  @AfterEach
  void close() {
    source.close();
  }

  @Test
  void concurrentAdminRetryCreatesOneOrderAndOneEvent() throws Exception {
    String requestId = UUID.randomUUID().toString();
    var request = new ProductOrderService.CreateOrder(requestId, user.id(), productId, PAID, NOT_ACTIVATED);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<ProductOrderRepository.Order>> futures =
          List.of(
              workers.submit(() -> createAfterBarrier(request, ready, start)),
              workers.submit(() -> createAfterBarrier(request, ready, start)));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var first = futures.get(0).get(10, TimeUnit.SECONDS);
      var second = futures.get(1).get(10, TimeUnit.SECONDS);
      assertThat(second.id()).isEqualTo(first.id());
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM support_order WHERE created_by=? AND request_id=?",
                  Integer.class,
                  admin.id(),
                  requestId))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM support_order_event WHERE order_id=?",
                  Integer.class,
                  first.id()))
          .isEqualTo(1);
    } finally {
      start.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void orderAuditFailureRollsBackOrderAndRetrySucceeds() {
    String requestId = UUID.randomUUID().toString();
    var request = new ProductOrderService.CreateOrder(requestId, user.id(), productId, PAID, ACTIVATED);
    jdbc.execute(
        "CREATE TRIGGER fixture_reject_order_audit BEFORE INSERT ON support_order_event FOR EACH ROW"
            + " CALL 'com.zhida.agent.support.ProductOrderServiceTest$RejectAudit'");
    assertThatThrownBy(() -> service.createOrder(admin, request)).isInstanceOf(DataAccessException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_order", Integer.class)).isZero();
    jdbc.execute("DROP TRIGGER fixture_reject_order_audit");
    assertThat(service.createOrder(admin, request).serviceStatus()).isEqualTo(ACTIVATED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_order_event", Integer.class)).isOne();
  }

  @Test
  void productAuditFailureRollsBackProduct() {
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM support_product", Integer.class);
    jdbc.execute(
        "CREATE TRIGGER fixture_reject_product_audit BEFORE INSERT ON support_product_event FOR EACH ROW"
            + " CALL 'com.zhida.agent.support.ProductOrderServiceTest$RejectAudit'");
    assertThatThrownBy(
            () -> service.createProduct(admin, "ROLLBACK-SUB", "不应留下的虚构产品", "审计失败"))
        .isInstanceOf(DataAccessException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_product", Integer.class))
        .isEqualTo(before);
  }

  @Test
  void ownershipAndStateRulesAreEnforcedInsideService() {
    var order =
        service.createOrder(
            admin,
            new ProductOrderService.CreateOrder(
                UUID.randomUUID().toString(), user.id(), productId, PAID, NOT_ACTIVATED));
    assertThat(service.myOrder(user, order.id()).userId()).isEqualTo(user.id());
    assertStatus(404, () -> service.myOrder(other, order.id()));
    assertStatus(
        400,
        () ->
            service.createOrder(
                admin,
                new ProductOrderService.CreateOrder(
                    UUID.randomUUID().toString(),
                    user.id(),
                    productId,
                    PENDING_PAYMENT,
                    ACTIVATED)));
    assertStatus(403, () -> service.myOrders(admin));
    assertStatus(
        403,
        () ->
            service.createOrder(
                new Actor(user.id(), SupportRole.ADMIN),
                new ProductOrderService.CreateOrder(
                    UUID.randomUUID().toString(), user.id(), productId, PAID, ACTIVATED)));
  }

  private ProductOrderRepository.Order createAfterBarrier(
      ProductOrderService.CreateOrder request, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture start timeout");
    return service.createOrder(admin, request);
  }

  private Actor account(String username, SupportRole role, SupportActorResolver actors) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO user_account(id,username,password_hash,is_guest,created_at) VALUES"
            + " (?,?,?,false,CURRENT_TIMESTAMP)",
        id,
        username,
        "DISABLED");
    jdbc.update("INSERT INTO support_account_role(user_id,role) VALUES (?,?)", id, role.name());
    return actors.account(id);
  }

  static void assertStatus(int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
  }

  public static class RejectAudit implements Trigger {
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
      throw new SQLException("fixture order audit failure");
    }
  }
}
