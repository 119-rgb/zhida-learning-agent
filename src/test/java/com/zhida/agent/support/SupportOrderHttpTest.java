package com.zhida.agent.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 模块 2 的真实 HTTP/JWT 验收：重点验证订单归属，而不是只验证 Controller 是否返回数据。
 * 所有账号、产品和订单都是随机生成的隔离测试数据，不连接支付或开通系统。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zhida.support.enabled=true",
      "zhida.auth.enabled=true",
      "zhida.persistence.enabled=true",
      "zhida.persistence.url=jdbc:h2:mem:support-order-http;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "zhida.persistence.username=sa",
      "zhida.persistence.password=",
      "zhida.ai.enabled=false",
      "zhida.search.tavily-api-key=",
      "zhida.rag.embedding-base-url=",
      "zhida.rag.embedding-api-key=",
      "zhida.rag.embedding-model=",
      "zhida.rag.upload-dir=target/support-order-http/uploads",
      "zhida.rag.vector-store-file=target/support-order-http/vector.json"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportOrderHttpTest {
  private static final String FIXTURE_PASSWORD = UUID.randomUUID().toString();
  private static final String FIXTURE_JWT_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();

  @DynamicPropertySource
  static void isolatedSecrets(DynamicPropertyRegistry registry) {
    registry.add("zhida.auth.secret", () -> FIXTURE_JWT_SECRET);
  }

  @Autowired TestRestTemplate http;
  @Autowired HikariDataSource source;
  JdbcTemplate jdbc;
  String user;
  String other;
  String agent;
  String admin;
  String userId;
  String otherId;
  String categoryId;

  @BeforeAll
  void accounts() {
    jdbc = new JdbcTemplate(source);
    user = register("order_fixture_user");
    other = register("order_fixture_other");
    agent = register("order_fixture_agent");
    admin = register("order_fixture_admin");
    setRole("order_fixture_agent", "CUSTOMER_SERVICE");
    setRole("order_fixture_admin", "ADMIN");
    userId = accountId("order_fixture_user");
    otherId = accountId("order_fixture_other");
    categoryId =
        (String)
            body(
                    call(
                        HttpMethod.POST,
                        "/categories",
                        admin,
                        Map.of("name", "虚构订单问题", "enabled", true)),
                    201)
                .get("id");
  }

  @Test
  void exposesThreeReadOnlyDemoStatesAndIsolatesOwners() {
    Map<String, Object> product = product("SUB-" + UUID.randomUUID(), "虚构协作订阅");
    Map<String, Object> pending =
        order(userId, product, "PENDING_PAYMENT", "NOT_ACTIVATED", UUID.randomUUID().toString());
    Map<String, Object> paid =
        order(userId, product, "PAID", "NOT_ACTIVATED", UUID.randomUUID().toString());
    Map<String, Object> active =
        order(userId, product, "PAID", "ACTIVATED", UUID.randomUUID().toString());

    List<Map<String, Object>> mine = list(value(call(HttpMethod.GET, "/orders", user, null), 200));
    assertThat(mine).extracting(row -> row.get("id")).contains(pending.get("id"), paid.get("id"), active.get("id"));
    assertThat(mine).allSatisfy(row -> assertThat(row.get("simulated")).isEqualTo(true));
    assertThat(list(value(call(HttpMethod.GET, "/orders", other, null), 200)))
        .extracting(row -> row.get("id"))
        .doesNotContain(paid.get("id"));
    assertThat(call(HttpMethod.GET, "/orders/" + paid.get("id"), other, null).getStatusCode().value())
        .isEqualTo(404);
    assertThat(body(call(HttpMethod.GET, "/orders/" + paid.get("id"), user, null), 200).get("serviceStatus"))
        .isEqualTo("NOT_ACTIVATED");

    assertThat(call(HttpMethod.POST, "/products", user, Map.of("sku", "NO", "name", "越权产品", "description", "测试")).getStatusCode().value())
        .isEqualTo(403);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/admin/orders",
                    admin,
                    Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "userId", userId,
                        "productId", product.get("id"),
                        "paymentStatus", "PENDING_PAYMENT",
                        "serviceStatus", "ACTIVATED"))
                .getStatusCode()
                .value())
        .isEqualTo(400);
  }

  @Test
  void orderProvisioningIsIdempotentAuditedAndRestrictedToUsers() {
    Map<String, Object> product = product("IDEM-" + UUID.randomUUID(), "虚构幂等订阅");
    String requestId = UUID.randomUUID().toString();
    Map<String, Object> first = order(userId, product, "PAID", "NOT_ACTIVATED", requestId);
    Map<String, Object> replay = order(userId, product, "PAID", "NOT_ACTIVATED", requestId);
    assertThat(replay.get("id")).isEqualTo(first.get("id"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_order_event WHERE order_id=?",
                Integer.class,
                first.get("id")))
        .isEqualTo(1);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/admin/orders",
                    admin,
                    Map.of(
                        "requestId", requestId,
                        "userId", userId,
                        "productId", product.get("id"),
                        "paymentStatus", "PAID",
                        "serviceStatus", "ACTIVATED"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/admin/orders",
                    user,
                    Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "userId", userId,
                        "productId", product.get("id"),
                        "paymentStatus", "PAID",
                        "serviceStatus", "ACTIVATED"))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/admin/orders",
                    agent,
                    Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "userId", userId,
                        "productId", product.get("id"),
                        "paymentStatus", "PAID",
                        "serviceStatus", "ACTIVATED"))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(call(HttpMethod.GET, "/orders", agent, null).getStatusCode().value()).isEqualTo(403);
    ResponseEntity<Map> guest = http.postForEntity("/api/v1/auth/guest", null, Map.class);
    assertThat(
            call(
                    HttpMethod.GET,
                    "/orders",
                    (String) guest.getBody().get("accessToken"),
                    null)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/admin/orders",
                    admin,
                    Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "userId", accountId("order_fixture_agent"),
                        "productId", product.get("id"),
                        "paymentStatus", "PAID",
                        "serviceStatus", "ACTIVATED"))
                .getStatusCode()
                .value())
        .isEqualTo(400);
  }

  @Test
  void ticketCanReferenceOnlyTheAuthenticatedUsersOrder() {
    Map<String, Object> product = product("LINK-" + UUID.randomUUID(), "虚构工单关联订阅");
    Map<String, Object> owned =
        order(userId, product, "PAID", "NOT_ACTIVATED", UUID.randomUUID().toString());
    Map<String, Object> foreign =
        order(otherId, product, "PAID", "NOT_ACTIVATED", UUID.randomUUID().toString());
    String requestId = UUID.randomUUID().toString();
    Map<String, Object> ticket = body(createTicket(requestId, (String) owned.get("id")), 201);
    assertThat(ticket.get("orderId")).isEqualTo(owned.get("id"));
    assertThat(createTicket(UUID.randomUUID().toString(), (String) foreign.get("id")).getStatusCode().value())
        .isEqualTo(404);

    Map<String, Object> second =
        order(userId, product, "PAID", "ACTIVATED", UUID.randomUUID().toString());
    assertThat(createTicket(requestId, (String) second.get("id")).getStatusCode().value())
        .isEqualTo(409);
  }

  @Test
  void publicOrderApiHasNoPaymentOrActivationMutation() {
    Map<String, Object> product = product("READ-" + UUID.randomUUID(), "虚构只读订阅");
    Map<String, Object> order =
        order(userId, product, "PAID", "NOT_ACTIVATED", UUID.randomUUID().toString());
    String id = (String) order.get("id");
    assertThat(call(HttpMethod.PUT, "/orders/" + id, user, Map.of("serviceStatus", "ACTIVATED")).getStatusCode().value())
        .isEqualTo(405);
    assertThat(call(HttpMethod.POST, "/orders/" + id + "/activate", user, Map.of()).getStatusCode().value())
        .isEqualTo(404);
  }

  private String register(String username) {
    ResponseEntity<Map> response =
        http.postForEntity(
            "/api/v1/auth/register",
            Map.of("username", username, "password", FIXTURE_PASSWORD),
            Map.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    return (String) response.getBody().get("accessToken");
  }

  private void setRole(String username, String role) {
    jdbc.update(
        "INSERT INTO support_account_role(user_id,role) SELECT id,? FROM user_account WHERE username=?",
        role,
        username);
  }

  private String accountId(String username) {
    return jdbc.queryForObject("SELECT id FROM user_account WHERE username=?", String.class, username);
  }

  private Map<String, Object> product(String sku, String name) {
    String fixtureSku = sku.replace("-", "");
    fixtureSku = fixtureSku.substring(0, Math.min(fixtureSku.length(), 32));
    Map<String, Object> created = body(
        call(
            HttpMethod.POST,
            "/products",
            admin,
            Map.of("sku", fixtureSku, "name", name, "description", "仅用于自动测试的虚构产品")),
        201);
    assertThat(created.get("simulated")).isEqualTo(true);
    return created;
  }

  private Map<String, Object> order(
      String owner,
      Map<String, Object> product,
      String payment,
      String service,
      String requestId) {
    return body(
        call(
            HttpMethod.POST,
            "/admin/orders",
            admin,
            Map.of(
                "requestId", requestId,
                "userId", owner,
                "productId", product.get("id"),
                "paymentStatus", payment,
                "serviceStatus", service)),
        201);
  }

  private ResponseEntity<Object> createTicket(String requestId, String orderId) {
    return call(
        HttpMethod.POST,
        "/tickets",
        user,
        Map.of(
            "requestId", requestId,
            "title", "虚构订单付款后未开通",
            "description", "该问题仅关联隔离环境中的模拟订单。",
            "categoryId", categoryId,
            "orderId", orderId,
            "confirmed", true));
  }

  private ResponseEntity<Object> call(HttpMethod method, String path, String token, Object request) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) headers.setBearerAuth(token);
    return http.exchange(
        "/api/v1/support" + path,
        method,
        new HttpEntity<>(request, headers),
        Object.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> body(ResponseEntity<Object> response, int status) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    return (Map<String, Object>) response.getBody();
  }

  private Object value(ResponseEntity<Object> response, int status) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    return response.getBody();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(Object body) {
    return (List<Map<String, Object>>) body;
  }
}
