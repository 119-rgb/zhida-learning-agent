package com.zhida.agent.support;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 显式开启时准备一套可重复的虚构售后演示数据。默认关闭，避免在开发者已有数据库中静默造账号。
 * 演示账号使用保留用户名与确定 ID；若发现同名但非本配置创建的账号则立即停止，绝不接管或改角色。
 */
@Configuration
@ConditionalOnProperty(
    prefix = "zhida.support",
    name = {"enabled", "demo-data.enabled"},
    havingValue = "true")
public class SupportDemoDataConfiguration {

  public static final String USERNAME = "zhida_demo_user";
  public static final String AGENT_USERNAME = "zhida_demo_agent";
  public static final String ADMIN_USERNAME = "zhida_demo_admin";
  public static final String CATEGORY_NAME = "虚构订阅开通问题";
  public static final String PRODUCT_SKU = "DEMO-SUB-PRO";

  @Bean
  ApplicationRunner supportDemoDataInitializer(
      SupportTicketRepository repository,
      SupportActorResolver actors,
      SupportTicketService tickets,
      ProductOrderService orders,
      PasswordEncoder passwords,
      @Value("${zhida.support.demo-data.password:}") String password) {
    return args -> {
      validatePassword(password);
      JdbcTemplate jdbc = repository.jdbc();
      String userId = ensureAccount(jdbc, passwords, USERNAME, SupportRole.USER, password);
      ensureAccount(jdbc, passwords, AGENT_USERNAME, SupportRole.CUSTOMER_SERVICE, password);
      String adminId = ensureAccount(jdbc, passwords, ADMIN_USERNAME, SupportRole.ADMIN, password);

      SupportActorResolver.Actor admin = actors.account(adminId);
      String categoryId =
          repository.categories(true).stream()
              .filter(category -> CATEGORY_NAME.equals(category.name()))
              .findFirst()
              .map(
                  category -> {
                    if (!category.enabled())
                      throw new IllegalStateException("演示分类已存在但被停用，请改用独立演示数据库");
                    return category.id();
                  })
              .orElseGet(() -> tickets.saveCategory(admin, null, CATEGORY_NAME, true).id());

      ProductOrderRepository.Product product =
          orders.products(admin).stream()
              .filter(candidate -> PRODUCT_SKU.equals(candidate.sku()))
              .findFirst()
              .orElseGet(
                  () ->
                      orders.createProduct(
                          admin,
                          PRODUCT_SKU,
                          "虚构专业版订阅",
                          "仅用于知答售后平台演示，不代表真实商品或支付服务。"));

      // 固定 requestId 让重复启动重放同一订单；服务层仍会校验内容哈希与用户归属。
      orders.createOrder(
          admin,
          new ProductOrderService.CreateOrder(
              "demo-paid-not-activated-v1",
              userId,
              product.id(),
              ProductOrderRepository.PaymentStatus.PAID,
              ProductOrderRepository.ServiceStatus.NOT_ACTIVATED));
    };
  }

  private static String ensureAccount(
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      String username,
      SupportRole role,
      String password) {
    String id = deterministicId(username);
    List<String> existing =
        jdbc.query("SELECT id FROM user_account WHERE username=?", (rs, row) -> rs.getString(1), username);
    if (!existing.isEmpty() && !id.equals(existing.get(0))) {
      throw new IllegalStateException("演示用户名已被现有账号占用，请改用独立演示数据库: " + username);
    }
    if (existing.isEmpty()) {
      Integer idCount =
          jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE id=?", Integer.class, id);
      if (idCount != null && idCount > 0)
        throw new IllegalStateException("演示账号 ID 已被占用，请改用独立演示数据库");
      jdbc.update(
          "INSERT INTO user_account(id,username,password_hash,is_guest,created_at)"
              + " VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
          id,
          username,
          passwords.encode(password),
          false);
    }

    List<String> roles =
        jdbc.query(
            "SELECT role FROM support_account_role WHERE user_id=?",
            (rs, row) -> rs.getString(1),
            id);
    if (roles.isEmpty()) {
      jdbc.update(
          "INSERT INTO support_account_role(user_id,role) VALUES (?,?)", id, role.name());
    } else if (roles.stream().anyMatch(existingRole -> !role.name().equals(existingRole))) {
      // 只要存在与预期不符的角色行就停止：任何角色配置都可能来自人工授权，绝不能被演示数据覆盖。
      throw new IllegalStateException("演示账号角色已被修改，请改用独立演示数据库: " + username);
    }
    return id;
  }

  /**
   * 包级可见的账号准备入口，供自动测试直接验证三条安全停止分支。生产路径只通过
   * {@link #supportDemoDataInitializer} 调用它：先校验密码，再准备账号。
   *
   * <p>不变量：只 INSERT 新行，绝不 UPDATE 或 DELETE 已有账号与角色。发现同名不同 ID、
   * 演示 ID 被占用、或角色与预期不符时抛 {@link IllegalStateException} 让应用停止启动。
   */
  static String ensureDemoAccount(
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      String username,
      SupportRole role,
      String password) {
    return ensureAccount(jdbc, passwords, username, role, password);
  }

  private static String deterministicId(String username) {
    return UUID.nameUUIDFromBytes(("zhida-support-demo:" + username).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static void validatePassword(String password) {
    if (password == null || password.length() < 8 || password.length() > 64)
      throw new IllegalStateException(
          "开启演示数据时必须通过 ZHIDA_SUPPORT_DEMO_DATA_PASSWORD（zhida.support.demo-data.password）"
              + "提供 8 到 64 位临时密码");
  }
}
