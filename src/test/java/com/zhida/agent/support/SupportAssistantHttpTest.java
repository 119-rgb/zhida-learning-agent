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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 模块 4 的 HTTP 边界：售后助手入口只接受正式账号，且 Demo 模式（未开启真实模型）也能完成一次
 * 会话而不会创建工单。真实模型、真实 SSE 浏览器行为在其它验收记录中单独标注。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zhida.support.enabled=true",
      "zhida.auth.enabled=true",
      "zhida.persistence.enabled=true",
      "zhida.persistence.url=jdbc:h2:mem:support-assistant-http;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "zhida.persistence.username=sa",
      "zhida.persistence.password=",
      "zhida.ai.enabled=false",
      "zhida.search.tavily-api-key=",
      "zhida.rag.embedding-base-url=",
      "zhida.rag.embedding-api-key=",
      "zhida.rag.embedding-model=",
      "zhida.rag.upload-dir=target/support-assistant-http/uploads",
      "zhida.rag.vector-store-file=target/support-assistant-http/vector.json"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportAssistantHttpTest {
  private static final String FIXTURE_PASSWORD = UUID.randomUUID().toString();
  private static final String FIXTURE_JWT_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();

  @DynamicPropertySource
  static void isolatedSecrets(DynamicPropertyRegistry registry) {
    registry.add("zhida.auth.secret", () -> FIXTURE_JWT_SECRET);
  }

  @Autowired TestRestTemplate http;
  @Autowired HikariDataSource source;

  /** 与 SupportTicketHttpTest 相同的理由：HTTP 用例共用限流窗口，测试前清空计数。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  com.zhida.agent.auth.RequestRateFilter rateFilter;

  @org.junit.jupiter.api.BeforeEach
  void resetRateLimitWindow() {
    if (rateFilter != null) rateFilter.resetForTests();
  }

  String user;
  JdbcTemplate jdbc;

  @BeforeAll
  void accounts() {
    jdbc = new JdbcTemplate(source);
    user = register("assistant_fixture_user");
  }

  @Test
  void unauthenticatedAndGuestCallersAreRejected() {
    assertThat(stream(null).getStatusCode().value()).isEqualTo(401);

    String guest =
        (String)
            http.postForEntity("/api/v1/auth/guest", null, Map.class)
                .getBody()
                .get("accessToken");
    assertThat(stream(guest).getStatusCode().value()).isEqualTo(403);
  }

  @Test
  void authenticatedUserGetsSupportSessionWithoutCreatingTicket() {
    int before = ticketCount();
    ResponseEntity<String> response = stream(user);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType())
        .isNotNull()
        .satisfies(type -> assertThat(type.toString()).contains(MediaType.TEXT_EVENT_STREAM_VALUE));
    // Demo 模式不调用模型，但事件序列与真实模式一致：任务开始、计划、回答、完成。
    assertThat(response.getBody())
        .contains("event:task.started")
        .contains("event:plan.created")
        .contains("event:answer.started")
        .contains("event:answer.delta")
        .contains("event:task.completed")
        .doesNotContain("event:task.failed")
        .doesNotContain("tool.started");
    // 助手入口本身没有建单路径：一次会话结束后工单数量不变。
    assertThat(ticketCount()).isEqualTo(before);
  }

  private ResponseEntity<String> stream(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) headers.setBearerAuth(token);
    return http.exchange(
        "/api/v1/support/assistant/stream",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("message", "我的订单已经付款，但服务没有开通"), headers),
        String.class);
  }

  private int ticketCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM support_ticket", Integer.class);
  }

  private String register(String username) {
    var result =
        http.postForEntity(
            "/api/v1/auth/register",
            Map.of("username", username, "password", FIXTURE_PASSWORD),
            Map.class);
    assertThat(result.getStatusCode().value()).isEqualTo(200);
    return (String) result.getBody().get("accessToken");
  }
}
