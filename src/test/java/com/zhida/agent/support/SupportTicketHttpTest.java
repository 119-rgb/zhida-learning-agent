package com.zhida.agent.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zhida.support.enabled=true",
      "zhida.auth.enabled=true",
      "zhida.persistence.enabled=true",
      "zhida.persistence.url=jdbc:h2:mem:support-http;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "zhida.persistence.username=sa",
      "zhida.persistence.password=",
      "zhida.ai.enabled=false",
      "zhida.search.tavily-api-key=",
      "zhida.rag.embedding-base-url=",
      "zhida.rag.embedding-api-key=",
      "zhida.rag.embedding-model=",
      "zhida.rag.upload-dir=target/support-http/uploads",
      "zhida.rag.vector-store-file=target/support-http/vector.json"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportTicketHttpTest {
  private static final String FIXTURE_PASSWORD = UUID.randomUUID().toString();
  private static final String FIXTURE_JWT_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();

  @DynamicPropertySource
  static void isolatedSecrets(DynamicPropertyRegistry registry) {
    registry.add("zhida.auth.secret", () -> FIXTURE_JWT_SECRET);
  }

  @Autowired TestRestTemplate http;
  @Autowired HikariDataSource source;
  String user, other, agent, secondAgent, admin, category;
  JdbcTemplate jdbc;

  @BeforeAll
  void accounts() {
    jdbc = new JdbcTemplate(source);
    user = register("fixture_user");
    other = register("fixture_other");
    assertThat(call(HttpMethod.GET, "/me", user, null).getStatusCode().value()).isEqualTo(200);
    agent = register("fixture_agent");
    secondAgent = register("fixture_agent_two");
    admin = register("fixture_admin");
    setRole("fixture_agent", "CUSTOMER_SERVICE");
    setRole("fixture_agent_two", "CUSTOMER_SERVICE");
    setRole("fixture_admin", "ADMIN");
    var result =
        call(HttpMethod.POST, "/categories", admin, Map.of("name", "虚构产品开通", "enabled", true));
    assertThat(result.getStatusCode().value()).isEqualTo(201);
    category = (String) result.getBody().get("id");
  }

  String register(String username) {
    var result =
        http.postForEntity(
            "/api/v1/auth/register",
            Map.of("username", username, "password", FIXTURE_PASSWORD, "role", "ADMIN"),
            Map.class);
    assertThat(result.getStatusCode().value()).isEqualTo(200);
    return (String) result.getBody().get("accessToken");
  }

  void setRole(String name, String role) {
    jdbc.update(
        "INSERT INTO support_account_role(user_id,role) SELECT id,? FROM user_account WHERE"
            + " username=?",
        role,
        name);
  }

  ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
    var headers = new HttpHeaders();
    if (token != null) headers.setBearerAuth(token);
    return http.exchange(
        "/api/v1/support" + path, method, new HttpEntity<>(body, headers), Map.class);
  }

  Map create(String requestId) {
    var result =
        call(
            HttpMethod.POST,
            "/tickets",
            user,
            Map.of(
                "requestId",
                requestId,
                "title",
                "虚构订单付款后未开通",
                "description",
                "这是隔离测试数据，无真实支付。",
                "categoryId",
                category,
                "confirmed",
                true,
                "userId",
                "forged-owner",
                "role",
                "ADMIN"));
    assertThat(result.getStatusCode().value()).isEqualTo(201);
    return result.getBody();
  }

  String id(Map ticket) {
    return (String) ticket.get("id");
  }

  Map version(long value) {
    return Map.of("expectedVersion", value);
  }

  @Test
  void completeFlowUsesServerIdentityAndRequiresUserConfirmation() {
    assertThat(call(HttpMethod.GET, "/me", user, null).getBody().get("role")).isEqualTo("USER");
    Map ticket = create(UUID.randomUUID().toString());
    String id = id(ticket);
    assertThat(ticket.get("status")).isEqualTo("PENDING");
    assertThat(ticket.get("userId"))
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT id FROM user_account WHERE username='fixture_user'", String.class));
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets/" + id + "/comments",
                    user,
                    Map.of("expectedVersion", 0, "content", "补充虚构软件版本信息"))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            call(HttpMethod.POST, "/tickets/" + id + "/claim", agent, version(1))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets/" + id + "/replies",
                    agent,
                    Map.of("expectedVersion", 2, "content", "客服正在检查虚构服务状态"))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    var solution =
        call(
            HttpMethod.POST,
            "/tickets/" + id + "/solution",
            agent,
            Map.of("expectedVersion", 3, "content", "虚构方案：刷新订阅页面并核对账号"));
    assertThat(solution.getBody().get("status")).isEqualTo("AWAITING_CONFIRMATION");
    var reopened =
        call(
            HttpMethod.POST,
            "/tickets/" + id + "/reopen",
            user,
            Map.of("expectedVersion", 4, "content", "按方案操作后尚未解决"));
    assertThat(reopened.getBody().get("status")).isEqualTo("PROCESSING");
    call(
        HttpMethod.POST,
        "/tickets/" + id + "/solution",
        agent,
        Map.of("expectedVersion", 5, "content", "虚构方案：重新登录订阅账户"));
    var closed =
        call(
            HttpMethod.POST,
            "/tickets/" + id + "/confirm",
            user,
            Map.of("expectedVersion", 6, "rating", 5, "evaluation", "虚构评价：已解决"));
    assertThat(closed.getBody().get("status")).isEqualTo("CLOSED");
    assertThat(closed.getBody().get("rating")).isEqualTo(5);
    var detail = call(HttpMethod.GET, "/tickets/" + id, user, null);
    assertThat((java.util.List) detail.getBody().get("events")).hasSize(8);
    assertThat((java.util.List) detail.getBody().get("replies")).hasSize(5);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets/" + id + "/replies",
                    agent,
                    Map.of("expectedVersion", 7, "content", "不能修改关闭工单"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
  }

  @Test
  void crossUserAndRoleOperationsAreDenied() {
    String id = id(create(UUID.randomUUID().toString()));
    assertThat(call(HttpMethod.GET, "/tickets/" + id, other, null).getStatusCode().value())
        .isEqualTo(404);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets/" + id + "/comments",
                    other,
                    Map.of("expectedVersion", 0, "content", "越权补充"))
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            call(HttpMethod.POST, "/tickets/" + id + "/claim", user, version(0))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            call(HttpMethod.POST, "/categories", user, Map.of("name", "越权分类", "enabled", true))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(call(HttpMethod.GET, "/tickets?view=pending", user, null).getStatusCode().value())
        .isEqualTo(403);
    assertThat(call(HttpMethod.GET, "/tickets", null, null).getStatusCode().value()).isEqualTo(401);
    var guest = http.postForEntity("/api/v1/auth/guest", null, Map.class);
    assertThat(
            call(HttpMethod.GET, "/tickets", (String) guest.getBody().get("accessToken"), null)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    call(HttpMethod.POST, "/tickets/" + id + "/claim", agent, version(0));
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets/" + id + "/solution",
                    secondAgent,
                    Map.of("expectedVersion", 1, "content", "越权方案"))
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets/" + id + "/confirm",
                    agent,
                    Map.of("expectedVersion", 1, "rating", 5))
                .getStatusCode()
                .value())
        .isEqualTo(403);
  }

  @Test
  void duplicateReplayAndInvalidTransitions() {
    String request = UUID.randomUUID().toString();
    Map first = create(request);
    Map replay = create(request);
    assertThat(id(replay)).isEqualTo(id(first));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_ticket_event WHERE ticket_id=?",
                Integer.class,
                id(first)))
        .isEqualTo(1);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets",
                    user,
                    Map.of(
                        "requestId",
                        request,
                        "title",
                        "变更标题",
                        "description",
                        "相同幂等键不同内容",
                        "categoryId",
                        category,
                        "confirmed",
                        true))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets",
                    user,
                    Map.of(
                        "requestId",
                        UUID.randomUUID().toString(),
                        "title",
                        "未经确认",
                        "description",
                        "不能建单",
                        "categoryId",
                        category,
                        "confirmed",
                        false))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets/" + id(first) + "/confirm",
                    user,
                    Map.of("expectedVersion", 0, "rating", 4))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets/" + id(first) + "/solution",
                    agent,
                    Map.of("expectedVersion", 0, "content", "尚未接单"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    assertThat(
            call(HttpMethod.POST, "/tickets/" + id(first) + "/claim", agent, version(9))
                .getStatusCode()
                .value())
        .isEqualTo(409);
  }

  @Test
  void adminAssignsOnlyCustomerServiceAndAuditsCategoryChanges() {
    String id = id(create(UUID.randomUUID().toString()));
    String agentId =
        jdbc.queryForObject(
            "SELECT id FROM user_account WHERE username='fixture_agent'", String.class);
    var assigned =
        call(
            HttpMethod.POST,
            "/tickets/" + id + "/assign",
            admin,
            Map.of("expectedVersion", 0, "agentId", agentId));
    assertThat(assigned.getBody().get("status")).isEqualTo("PROCESSING");
    assertThat(assigned.getBody().get("assignedTo")).isEqualTo(agentId);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets/" + id + "/assign",
                    user,
                    Map.of("expectedVersion", 1, "agentId", agentId))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    String userId =
        jdbc.queryForObject(
            "SELECT id FROM user_account WHERE username='fixture_user'", String.class);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets/" + id + "/assign",
                    admin,
                    Map.of("expectedVersion", 1, "agentId", userId))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    var categoryResult =
        call(HttpMethod.POST, "/categories", admin, Map.of("name", "临时虚构分类", "enabled", true));
    String disabled = (String) categoryResult.getBody().get("id");
    assertThat(
            call(
                    HttpMethod.PUT,
                    "/categories/" + disabled,
                    admin,
                    Map.of("name", "临时虚构分类", "enabled", false))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_category_event WHERE category_id=?",
                Integer.class,
                disabled))
        .isEqualTo(2);
    assertThat(
            call(
                    HttpMethod.POST,
                    "/tickets",
                    user,
                    Map.of(
                        "requestId",
                        UUID.randomUUID().toString(),
                        "title",
                        "停用分类",
                        "description",
                        "不能创建",
                        "categoryId",
                        disabled,
                        "confirmed",
                        true))
                .getStatusCode()
                .value())
        .isEqualTo(400);
  }
}
