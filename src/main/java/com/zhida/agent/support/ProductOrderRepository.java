package com.zhida.agent.support;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** 产品与模拟订单的数据访问层；查询 SQL 固定携带归属条件，写入由业务事务统一包裹。 */
public class ProductOrderRepository {
  public enum PaymentStatus {
    PENDING_PAYMENT,
    PAID
  }

  public enum ServiceStatus {
    NOT_ACTIVATED,
    ACTIVATED
  }

  public record Product(
      String id,
      String sku,
      String name,
      String description,
      boolean simulated,
      boolean enabled,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  public record Order(
      String id,
      String orderNo,
      String userId,
      String productId,
      String productName,
      PaymentStatus paymentStatus,
      ServiceStatus serviceStatus,
      boolean simulated,
      Instant paidAt,
      Instant activatedAt,
      Instant createdAt,
      @JsonIgnore String createdBy,
      @JsonIgnore String requestId,
      @JsonIgnore String requestHash) {}

  private static final RowMapper<Product> PRODUCT =
      (row, number) ->
          new Product(
              row.getString("id"),
              row.getString("sku"),
              row.getString("name"),
              row.getString("description"),
              row.getBoolean("simulated"),
              row.getBoolean("enabled"),
              row.getLong("version"),
              row.getTimestamp("created_at").toInstant(),
              row.getTimestamp("updated_at").toInstant());

  private static final RowMapper<Order> ORDER =
      (row, number) ->
          new Order(
              row.getString("id"),
              row.getString("order_no"),
              row.getString("user_id"),
              row.getString("product_id"),
              row.getString("product_name"),
              PaymentStatus.valueOf(row.getString("payment_status")),
              ServiceStatus.valueOf(row.getString("service_status")),
              row.getBoolean("simulated"),
              instant(row.getTimestamp("paid_at")),
              instant(row.getTimestamp("activated_at")),
              row.getTimestamp("created_at").toInstant(),
              row.getString("created_by"),
              row.getString("request_id"),
              row.getString("request_hash"));

  private static final String ORDER_SELECT =
      """
      SELECT o.*,p.name AS product_name FROM support_order o
      JOIN support_product p ON p.id=o.product_id
      """;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public ProductOrderRepository(JdbcTemplate jdbc, TransactionTemplate transaction) {
    this.jdbc = jdbc;
    this.transaction = transaction;
  }

  TransactionTemplate transaction() {
    return transaction;
  }

  List<Product> products(boolean includeDisabled) {
    return jdbc.query(
        "SELECT * FROM support_product"
            + (includeDisabled ? "" : " WHERE enabled=true")
            + " ORDER BY name,id",
        PRODUCT);
  }

  Optional<Product> product(String id) {
    return jdbc.query("SELECT * FROM support_product WHERE id=?", PRODUCT, id).stream().findFirst();
  }

  void insertProduct(Product product, String actorId) {
    jdbc.update(
        """
        INSERT INTO support_product(id,sku,name,description,simulated,enabled,version,created_at,updated_at)
        VALUES (?,?,?,?,?,?,?,?,?)
        """,
        product.id(),
        product.sku(),
        product.name(),
        product.description(),
        true,
        product.enabled(),
        product.version(),
        Timestamp.from(product.createdAt()),
        Timestamp.from(product.updatedAt()));
    // 产品及其创建事件在同一事务中提交，避免管理员看到没有来源记录的产品。
    jdbc.update(
        "INSERT INTO support_product_event(product_id,version,action,actor_id,created_at) VALUES (?,?,?,?,?)",
        product.id(),
        0,
        "CREATED",
        actorId,
        Timestamp.from(product.createdAt()));
  }

  Optional<Order> order(String id) {
    return jdbc.query(ORDER_SELECT + " WHERE o.id=?", ORDER, id).stream().findFirst();
  }

  Optional<Order> ownedOrder(String ownerId, String id) {
    // owner 条件进入 SQL；不能先按 id 读取后再依靠页面隐藏，避免跨用户数据泄露。
    return jdbc.query(ORDER_SELECT + " WHERE o.id=? AND o.user_id=?", ORDER, id, ownerId).stream()
        .findFirst();
  }

  List<Order> orders(String ownerId) {
    return jdbc.query(
        ORDER_SELECT + " WHERE o.user_id=? ORDER BY o.created_at DESC,o.id LIMIT 100",
        ORDER,
        ownerId);
  }

  /**
   * 管理端演示数据视图：返回全部模拟订单，仅用于管理员核对演示数据是否就绪。
   *
   * <p>调用方必须先确认管理员角色。这里没有 owner 条件，因此绝不能用于普通用户查询路径；
   * 普通用户只能通过 {@link #orders(String)} 读取本人订单。
   */
  List<Order> allOrders() {
    return jdbc.query(ORDER_SELECT + " ORDER BY o.created_at DESC,o.id LIMIT 100", ORDER);
  }

  Optional<Order> byRequest(String actorId, String requestId) {
    return jdbc
        .query(
            ORDER_SELECT + " WHERE o.created_by=? AND o.request_id=?", ORDER, actorId, requestId)
        .stream()
        .findFirst();
  }

  void insertOrder(Order order) {
    jdbc.update(
        """
        INSERT INTO support_order(
          id,order_no,user_id,product_id,payment_status,service_status,simulated,
          created_by,request_id,request_hash,paid_at,activated_at,created_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        order.id(),
        order.orderNo(),
        order.userId(),
        order.productId(),
        order.paymentStatus().name(),
        order.serviceStatus().name(),
        true,
        order.createdBy(),
        order.requestId(),
        order.requestHash(),
        timestamp(order.paidAt()),
        timestamp(order.activatedAt()),
        Timestamp.from(order.createdAt()));
    // 当前模块只创建初始模拟状态，不提供支付或开通状态更新事件。
    jdbc.update(
        """
        INSERT INTO support_order_event(
          order_id,version,action,actor_id,payment_status,service_status,created_at
        ) VALUES (?,?,?,?,?,?,?)
        """,
        order.id(),
        0,
        "CREATED",
        order.createdBy(),
        order.paymentStatus().name(),
        order.serviceStatus().name(),
        Timestamp.from(order.createdAt()));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
