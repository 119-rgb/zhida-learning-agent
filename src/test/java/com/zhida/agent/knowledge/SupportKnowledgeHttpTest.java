package com.zhida.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;

/** 模块 3 的 HTTP/JWT 验收：公共售后资料可共享，但维护权限只属于管理员。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zhida.support.enabled=true",
      "zhida.auth.enabled=true",
      "zhida.persistence.enabled=true",
      "zhida.persistence.url=jdbc:h2:mem:support-knowledge-http;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "zhida.persistence.username=sa",
      "zhida.persistence.password=",
      "zhida.ai.enabled=false",
      "zhida.search.tavily-api-key=",
      "zhida.rag.embedding-base-url=",
      "zhida.rag.embedding-api-key=",
      "zhida.rag.embedding-model="
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportKnowledgeHttpTest {
  private static final String FIXTURE_PASSWORD = UUID.randomUUID().toString();
  private static final String FIXTURE_JWT_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
  private static final String FIXTURE_STORAGE =
      "target/support-knowledge-http-" + UUID.randomUUID();

  @DynamicPropertySource
  static void isolatedSecrets(DynamicPropertyRegistry registry) {
    registry.add("zhida.auth.secret", () -> FIXTURE_JWT_SECRET);
    registry.add("zhida.rag.upload-dir", () -> FIXTURE_STORAGE + "/uploads");
    registry.add("zhida.rag.vector-store-file", () -> FIXTURE_STORAGE + "/vector.json");
  }

  @Autowired TestRestTemplate http;
  @Autowired HikariDataSource source;
  @Autowired KnowledgeBaseService knowledgeBaseService;
  @MockitoBean LocalVectorKnowledgeIndex index;

  JdbcTemplate jdbc;
  String user;
  String agent;
  String admin;

  @BeforeAll
  void accounts() {
    jdbc = new JdbcTemplate(source);
    user = register("knowledge_fixture_user");
    agent = register("knowledge_fixture_agent");
    admin = register("knowledge_fixture_admin");
    setRole("knowledge_fixture_agent", "CUSTOMER_SERVICE");
    setRole("knowledge_fixture_admin", "ADMIN");
  }

  @Test
  void publicKnowledgeBaseIsVisibleAndEmptySearchGuidesTicketCreation() {
    List<Map<String, Object>> userBases = list(call(HttpMethod.GET, "", user, null), 200);
    assertThat(userBases)
        .anySatisfy(
            base -> {
              assertThat(base.get("id"))
                  .isEqualTo(KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID);
              assertThat(base.get("visibility")).isEqualTo("PUBLIC");
              assertThat(base.get("writable")).isEqualTo(false);
            });

    List<Map<String, Object>> adminBases = list(call(HttpMethod.GET, "", admin, null), 200);
    assertThat(adminBases)
        .filteredOn(
            base ->
                KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID.equals(base.get("id")))
        .singleElement()
        .satisfies(base -> assertThat(base.get("writable")).isEqualTo(true));

    Map<String, Object> search =
        body(
            call(
                HttpMethod.GET,
                "/" + KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID
                    + "/search?q=%E4%BB%98%E6%AC%BE%E5%90%8E%E6%9C%AA%E5%BC%80%E9%80%9A",
                user,
                null),
            200);
    assertThat(search.get("evidenceSufficient")).isEqualTo(false);
    assertThat(search.get("knowledgeBaseId"))
        .isEqualTo(KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID);
    assertThat(search.get("message").toString()).contains("检索依据不足");
    assertThat(search.get("nextAction").toString()).contains("创建售后工单");

    String guest =
        (String)
            http.postForEntity("/api/v1/auth/guest", null, Map.class)
                .getBody()
                .get("accessToken");
    assertThat(
            call(
                    HttpMethod.GET,
                    "/" + KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID
                        + "/documents",
                    guest,
                    null)
                .getStatusCode()
                .value())
        .isEqualTo(403);
  }

  @Test
  void onlyAdminCanMaintainPublicKnowledgeWhileAllAccountsCanReadIt() {
    String publicDocuments =
        "/" + KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID + "/documents";
    assertThat(upload(publicDocuments, user).getStatusCode().value()).isEqualTo(403);
    assertThat(upload(publicDocuments, agent).getStatusCode().value()).isEqualTo(403);

    Map<String, Object> uploaded = body(upload(publicDocuments, admin), 202);
    assertThat(uploaded.get("knowledgeBaseId"))
        .isEqualTo(KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID);
    assertThat(uploaded.get("filename")).isEqualTo("activation-guide.md");

    verify(index, timeout(2000))
        .add(
            argThat(
                chunks ->
                    chunks.stream()
                        .allMatch(
                            chunk -> {
                              String namespace =
                                  chunk.getMetadata().get("knowledgeBaseId").toString();
                              return namespace.length() == 64
                                  && !namespace.equals(
                                      KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID);
                            })));

    assertThat(list(call(HttpMethod.GET, publicDocuments, user, null), 200))
        .extracting(row -> row.get("id"))
        .contains(uploaded.get("id"));
    assertThat(list(call(HttpMethod.GET, publicDocuments, agent, null), 200))
        .extracting(row -> row.get("id"))
        .contains(uploaded.get("id"));
    assertThat(
            call(
                    HttpMethod.DELETE,
                    publicDocuments + "/" + uploaded.get("id"),
                    user,
                    null)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    awaitStatus(publicDocuments + "/" + uploaded.get("id"), admin, "READY");
    assertThat(
            call(
                    HttpMethod.DELETE,
                    publicDocuments + "/" + uploaded.get("id"),
                    admin,
                    null)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(list(call(HttpMethod.GET, publicDocuments, user, null), 200))
        .extracting(row -> row.get("id"))
        .doesNotContain(uploaded.get("id"));
  }

  @Test
  void administratorStillCannotCrossPrivateKnowledgeBoundaries() {
    Map<String, Object> privateBase =
        body(call(HttpMethod.POST, "", user, Map.of("name", "用户私人售后记录")), 200);

    assertThat(
            call(
                    HttpMethod.GET,
                    "/" + privateBase.get("id") + "/documents",
                    admin,
                    null)
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  @Test
  void legacyLocalNamespaceCannotBecomePublicAfterUpgrade() throws Exception {
    var staging = knowledgeBaseService.createStagingFile();
    KnowledgeDocumentInfo legacy;
    try {
      Files.writeString(staging, "旧本地私人资料，不得升级为公共内容。", StandardCharsets.UTF_8);
      // 模拟旧版本本地模式：外部 ID 会原样写入目录和向量命名空间。
      legacy =
          knowledgeBaseService.submit(
              KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID,
              "legacy-private.txt",
              MediaType.TEXT_PLAIN,
              staging);
    } finally {
      knowledgeBaseService.deleteStagingFile(staging);
    }

    List<Map<String, Object>> publicDocuments =
        list(
            call(
                HttpMethod.GET,
                "/" + KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID + "/documents",
                user,
                null),
            200);
    assertThat(publicDocuments).extracting(row -> row.get("id")).doesNotContain(legacy.id());
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

  private ResponseEntity<Object> upload(String path, String token) {
    var multipart = new LinkedMultiValueMap<String, Object>();
    multipart.add(
        "file",
        new ByteArrayResource(
            "# 虚构产品开通说明\n付款成功后服务应进入已开通状态。"
                .getBytes(StandardCharsets.UTF_8)) {
          @Override
          public String getFilename() {
            return "activation-guide.md";
          }
        });
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return http.exchange(
        "/api/v1/knowledge-bases" + path,
        HttpMethod.POST,
        new HttpEntity<>(multipart, headers),
        Object.class);
  }

  private ResponseEntity<Object> call(HttpMethod method, String path, String token, Object request) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) headers.setBearerAuth(token);
    return http.exchange(
        "/api/v1/knowledge-bases" + path,
        method,
        new HttpEntity<>(request, headers),
        Object.class);
  }

  private void awaitStatus(String path, String token, String expected) {
    for (int attempt = 0; attempt < 100; attempt++) {
      Map<String, Object> document = body(call(HttpMethod.GET, path, token, null), 200);
      if (expected.equals(document.get("status"))) return;
      try {
        Thread.sleep(20);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("等待文档状态时线程被中断", exception);
      }
    }
    throw new AssertionError("文档未在预期时间内进入状态：" + expected);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> body(ResponseEntity<Object> response, int status) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    return (Map<String, Object>) response.getBody();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(ResponseEntity<Object> response, int status) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    return (List<Map<String, Object>>) response.getBody();
  }
}
