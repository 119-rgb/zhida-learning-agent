package com.zhida.agent.support;

import static com.zhida.agent.support.ProductOrderRepository.PaymentStatus.PAID;
import static com.zhida.agent.support.ProductOrderRepository.ServiceStatus.ACTIVATED;
import static com.zhida.agent.support.SupportRole.ADMIN;
import static com.zhida.agent.support.SupportRole.USER;

import com.zhida.agent.support.ProductOrderRepository.*;
import com.zhida.agent.support.SupportActorResolver.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 产品与模拟订单业务层。只有管理员可以录入虚构数据，用户查询始终绑定认证账号。
 *
 * <p>本服务没有既有订单的付款、退款或开通状态更新方法。模块 4 注册 Agent 工具时仍须只选择
 * myOrders/myOrder 等查询方法，不能把管理员造数方法注册给模型。
 */
public class ProductOrderService {
  public record CreateOrder(
      String requestId,
      String userId,
      String productId,
      PaymentStatus paymentStatus,
      ServiceStatus serviceStatus) {}

  private final ProductOrderRepository repository;
  private final SupportActorResolver actors;

  public ProductOrderService(ProductOrderRepository repository, SupportActorResolver actors) {
    this.repository = repository;
    this.actors = actors;
  }

  public List<Product> products(Actor supplied) {
    Actor actor = refresh(supplied);
    return repository.products(actor.role() == ADMIN);
  }

  public Product createProduct(
      Actor supplied, String skuValue, String nameValue, String descriptionValue) {
    Actor actor = refresh(supplied);
    requireRole(actor, ADMIN);
    String sku = text(skuValue, 32, "产品 SKU").toUpperCase(Locale.ROOT);
    if (!sku.matches("[A-Z0-9][A-Z0-9_-]{1,31}"))
      throw error(HttpStatus.BAD_REQUEST, "产品 SKU 只能包含大写字母、数字、下划线和连字符");
    String name = text(nameValue, 120, "产品名称");
    String description = text(descriptionValue, 1000, "产品说明");
    Instant now = Instant.now();
    Product product =
        new Product(UUID.randomUUID().toString(), sku, name, description, true, true, 0, now, now);
    try {
      return repository
          .transaction()
          .execute(
              status -> {
                repository.insertProduct(product, actor.id());
                return product;
              });
    } catch (DuplicateKeyException duplicate) {
      throw conflict("产品 SKU 已存在");
    }
  }

  public Order createOrder(Actor supplied, CreateOrder request) {
    Actor actor = refresh(supplied);
    requireRole(actor, ADMIN);
    if (request == null || request.paymentStatus() == null || request.serviceStatus() == null)
      throw error(HttpStatus.BAD_REQUEST, "订单状态不能为空");
    String requestId = text(request.requestId(), 64, "requestId");
    String ownerId = text(request.userId(), 36, "用户账号");
    String productId = text(request.productId(), 36, "产品");
    validateState(request.paymentStatus(), request.serviceStatus());
    String requestHash =
        hash(
            ownerId,
            productId,
            request.paymentStatus().name(),
            request.serviceStatus().name());
    var existing = repository.byRequest(actor.id(), requestId);
    if (existing.isPresent()) return replay(existing.get(), requestHash);

    try {
      return repository
          .transaction()
          .execute(
              status -> {
                Actor owner;
                try {
                  owner = actors.account(ownerId);
                } catch (ResponseStatusException invalidAccount) {
                  throw error(HttpStatus.BAD_REQUEST, "目标用户账号无效");
                }
                if (owner.role() != USER)
                  throw error(HttpStatus.BAD_REQUEST, "模拟订单只能分配给普通用户");
                Product product =
                    repository
                        .product(productId)
                        .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "产品不存在"));
                if (!product.enabled()) throw error(HttpStatus.BAD_REQUEST, "产品已停用");
                Instant now = Instant.now();
                Order order =
                    new Order(
                        UUID.randomUUID().toString(),
                        demoOrderNo(),
                        owner.id(),
                        product.id(),
                        product.name(),
                        request.paymentStatus(),
                        request.serviceStatus(),
                        true,
                        request.paymentStatus() == PAID ? now : null,
                        request.serviceStatus() == ACTIVATED ? now : null,
                        now,
                        actor.id(),
                        requestId,
                        requestHash);
                // 订单本体与 CREATED 审计共用事务；事件失败时订单不会部分落库。
                repository.insertOrder(order);
                return order;
              });
    } catch (DuplicateKeyException duplicate) {
      // 唯一约束裁决并发重试，失败事务回滚后再读取已提交赢家。
      return repository
          .byRequest(actor.id(), requestId)
          .map(order -> replay(order, requestHash))
          .orElseThrow(() -> conflict("订单创建冲突，请重试"));
    }
  }

  public List<Order> myOrders(Actor supplied) {
    Actor actor = refresh(supplied);
    requireRole(actor, USER);
    return repository.orders(actor.id());
  }

  public Order myOrder(Actor supplied, String orderId) {
    Actor actor = refresh(supplied);
    requireRole(actor, USER);
    return ownedOrder(actor.id(), orderId);
  }

  /**
   * 管理员核对演示数据用：返回全部模拟订单。
   *
   * <p>这是唯一不带 owner 条件的订单查询，角色校验必须在服务层完成，不能依赖页面隐藏入口；
   * 普通用户与客服调用都会得到 403。
   */
  public List<Order> allOrders(Actor supplied) {
    Actor actor = refresh(supplied);
    requireRole(actor, ADMIN);
    return repository.allOrders();
  }

  /** 工单创建事务调用此方法；404 同时表示不存在或不归属，防止探测其他用户订单。 */
  Order ownedOrder(String ownerId, String orderId) {
    String id = text(orderId, 36, "订单");
    return repository.ownedOrder(ownerId, id).orElseThrow(ProductOrderService::missing);
  }

  private Actor refresh(Actor supplied) {
    if (supplied == null) throw error(HttpStatus.UNAUTHORIZED, "请先登录");
    return actors.account(supplied.id());
  }

  private void requireRole(Actor actor, SupportRole required) {
    if (actor.role() != required) throw error(HttpStatus.FORBIDDEN, "当前角色不能执行此操作");
  }

  private void validateState(PaymentStatus payment, ServiceStatus service) {
    // 待付款不可能已经开通；已付款则可表示“未开通”和“已开通”两个演示场景。
    if (payment != PAID && service == ACTIVATED)
      throw error(HttpStatus.BAD_REQUEST, "待付款订单不能标记为已开通");
  }

  private Order replay(Order order, String requestHash) {
    if (!order.requestHash().equals(requestHash)) throw conflict("requestId 已用于不同订单内容");
    return order;
  }

  private static String demoOrderNo() {
    return "DEMO-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
  }

  private static String text(String input, int max, String field) {
    if (input == null || input.isBlank() || input.length() > max)
      throw error(HttpStatus.BAD_REQUEST, field + "不能为空且最多" + max + "字符");
    return input.trim();
  }

  private static String hash(String... values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String value : values)
        digest.update((value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
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
    return error(HttpStatus.NOT_FOUND, "订单或资源不存在");
  }
}
