package com.zhida.agent.support;

import com.zhida.agent.support.ProductOrderRepository.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 模块 2 HTTP 入口；仅暴露产品/订单创建和本人查询，不暴露支付、退款或开通状态修改。 */
@RestController
@RequestMapping("/api/v1/support")
@ConditionalOnProperty(name = "zhida.support.enabled", havingValue = "true")
public class SupportOrderController {
  public record ProductRequest(
      @NotBlank @Size(max = 32) String sku,
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Size(max = 1000) String description) {}

  // userId 仅存在于管理员造数接口；普通用户查询身份仍只取服务端 Principal。
  public record OrderRequest(
      @NotBlank @Size(max = 64) String requestId,
      @NotBlank @Size(max = 36) String userId,
      @NotBlank @Size(max = 36) String productId,
      @NotNull PaymentStatus paymentStatus,
      @NotNull ServiceStatus serviceStatus) {}

  private final ProductOrderService service;
  private final SupportActorResolver actors;

  public SupportOrderController(ProductOrderService service, SupportActorResolver actors) {
    this.service = service;
    this.actors = actors;
  }

  @GetMapping("/products")
  public List<Product> products(Principal principal) {
    return service.products(actors.resolve(principal));
  }

  @PostMapping("/products")
  @ResponseStatus(HttpStatus.CREATED)
  public Product createProduct(Principal principal, @Valid @RequestBody ProductRequest request) {
    return service.createProduct(
        actors.resolve(principal), request.sku(), request.name(), request.description());
  }

  @GetMapping("/orders")
  public List<Order> myOrders(Principal principal) {
    return service.myOrders(actors.resolve(principal));
  }

  @GetMapping("/orders/{id}")
  public Order myOrder(Principal principal, @PathVariable String id) {
    return service.myOrder(actors.resolve(principal), id);
  }

  @PostMapping("/admin/orders")
  @ResponseStatus(HttpStatus.CREATED)
  public Order createOrder(Principal principal, @Valid @RequestBody OrderRequest request) {
    return service.createOrder(
        actors.resolve(principal),
        new ProductOrderService.CreateOrder(
            request.requestId(),
            request.userId(),
            request.productId(),
            request.paymentStatus(),
            request.serviceStatus()));
  }
}
