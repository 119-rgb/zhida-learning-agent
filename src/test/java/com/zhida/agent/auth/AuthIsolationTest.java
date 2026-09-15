package com.zhida.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhida.agent.ZhidaAgentApplicationTest.MvcClient;
import com.zhida.agent.api.KnowledgeCollectionController;
import com.zhida.agent.application.AgentEvent;
import com.zhida.agent.conversation.ConversationRepository;
import com.zhida.agent.knowledge.LocalVectorKnowledgeIndex;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zhida.auth.enabled=true",
      "zhida.auth.secret=test-only-jwt-secret-0123456789-0123456789",
      "zhida.ai.enabled=false",
      "zhida.persistence.enabled=true",
      "zhida.persistence.url=jdbc:h2:mem:authisolation;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "zhida.persistence.username=sa",
      "zhida.persistence.password=",
      "zhida.rag.upload-dir=target/auth-test/uploads",
      "zhida.rag.vector-store-file=target/auth-test/vector.json"
    })
class AuthIsolationTest {
  @Autowired TestRestTemplate http;
  MvcClient client;

  @BeforeEach
  void mvcClient() {
    client = new MvcClient(http);
  }

  @MockitoBean LocalVectorKnowledgeIndex index;

  private String register(String name) {
    return client
        .post()
        .uri("/api/v1/auth/register")
        .bodyValue(Map.of("username", name, "password", "test-password-123"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AuthController.Token.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  @Test
  void registerLoginRefreshAndGuestFlow() {
    String username = "login_user";
    String token = register(username);
    client
        .get()
        .uri("/api/v1/auth/me")
        .headers(h -> h.setBearerAuth(token))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.username")
        .isEqualTo(username)
        .jsonPath("$.guest")
        .isEqualTo(false);
    client
        .post()
        .uri("/api/v1/auth/register")
        .bodyValue(Map.of("username", username, "password", "test-password-123"))
        .exchange()
        .expectStatus()
        .isEqualTo(409);
    client
        .post()
        .uri("/api/v1/auth/login")
        .bodyValue(Map.of("username", username, "password", "wrong-password"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
    String loginToken =
        client
            .post()
            .uri("/api/v1/auth/login")
            .bodyValue(Map.of("username", username, "password", "test-password-123"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AuthController.Token.class)
            .returnResult()
            .getResponseBody()
            .accessToken();
    client
        .post()
        .uri("/api/v1/auth/refresh")
        .headers(h -> h.setBearerAuth(loginToken))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.expiresIn")
        .isEqualTo(3600);
    String guestToken =
        client
            .post()
            .uri("/api/v1/auth/guest")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AuthController.Token.class)
            .returnResult()
            .getResponseBody()
            .accessToken();
    client
        .get()
        .uri("/api/v1/auth/me")
        .headers(h -> h.setBearerAuth(guestToken))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.guest")
        .isEqualTo(true);
  }

  @Test
  void customKnowledgeBaseMustExistAndBelongToTheCurrentUser() {
    String alice = register("knowledge_alice");
    String bob = register("knowledge_bob");

    // 路径参数不能证明知识库真实存在；接受任意值会允许调用者制造无限个向量命名空间。
    client
        .get()
        .uri("/api/v1/knowledge-bases/not-registered/documents")
        .headers(headers -> headers.setBearerAuth(alice))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .post()
        .uri("/api/v1/research/stream")
        .headers(headers -> headers.setBearerAuth(alice))
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue(
            Map.of(
                "conversationId", "unknown-knowledge-base",
                "message", "查询不存在的知识库",
                "knowledgeBaseId", "not-registered"))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .get()
        .uri("/api/v1/conversations/unknown-knowledge-base")
        .headers(headers -> headers.setBearerAuth(alice))
        .exchange()
        .expectStatus()
        .isNotFound();

    var created =
        client
            .post()
            .uri("/api/v1/knowledge-bases")
            .headers(headers -> headers.setBearerAuth(alice))
            .bodyValue(Map.of("name", "Alice Notes"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(KnowledgeCollectionController.Base.class)
            .returnResult()
            .getResponseBody();

    assertThat(created).isNotNull();
    client
        .get()
        .uri("/api/v1/knowledge-bases/" + created.id() + "/documents")
        .headers(headers -> headers.setBearerAuth(alice))
        .exchange()
        .expectStatus()
        .isOk();
    client
        .get()
        .uri("/api/v1/knowledge-bases/" + created.id() + "/documents")
        .headers(headers -> headers.setBearerAuth(bob))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void twoUsersCannotReadOrModifyEachOthersData() {
    String a = register("alice"), b = register("bob");
    client.get().uri("/api/v1/conversations").exchange().expectStatus().isUnauthorized();
    client
        .get()
        .uri("/api/v1/conversations")
        .headers(h -> h.setBearerAuth(a + "invalid"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
    var events =
        client
            .post()
            .uri("/api/v1/research/stream")
            .headers(h -> h.setBearerAuth(a))
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(Map.of("conversationId", "alice-private", "message", "private question"))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(AgentEvent.class)
            .events();
    assertThat(events).extracting(AgentEvent::type).contains("task.completed");
    client
        .get()
        .uri("/api/v1/conversations/alice-private")
        .headers(h -> h.setBearerAuth(b))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .get()
        .uri("/api/v1/tasks/" + events.get(0).taskId())
        .headers(h -> h.setBearerAuth(b))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .get()
        .uri("/api/v1/tasks/" + events.get(0).taskId() + "/cost")
        .headers(h -> h.setBearerAuth(b))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .get()
        .uri("/api/v1/tasks/" + events.get(0).taskId() + "/memories")
        .headers(h -> h.setBearerAuth(b))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .delete()
        .uri("/api/v1/conversations/alice-private")
        .headers(h -> h.setBearerAuth(b))
        .exchange()
        .expectStatus()
        .isNotFound();
    var memory =
        client
            .post()
            .uri("/api/v1/memories")
            .headers(h -> h.setBearerAuth(a))
            .bodyValue(Map.of("content", "private memory"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ConversationRepository.Memory.class)
            .returnResult()
            .getResponseBody();
    client
        .get()
        .uri("/api/v1/memories")
        .headers(h -> h.setBearerAuth(b))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json("[]");
    client
        .delete()
        .uri("/api/v1/memories/" + memory.id())
        .headers(h -> h.setBearerAuth(b))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .put()
        .uri("/api/v1/memories/" + memory.id())
        .headers(h -> h.setBearerAuth(b))
        .bodyValue(Map.of("content", "override"))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .get()
        .uri("/api/v1/memories/settings")
        .headers(h -> h.setBearerAuth(a))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.enabled")
        .isEqualTo(false);
    client
        .put()
        .uri("/api/v1/memories/settings")
        .headers(h -> h.setBearerAuth(a))
        .bodyValue(Map.of("enabled", true))
        .exchange()
        .expectStatus()
        .isOk();
    var multipart = new LinkedMultiValueMap<String, Object>();
    multipart.add(
        "file",
        new ByteArrayResource("Alice private document".getBytes()) {
          @Override
          public String getFilename() {
            return "private.txt";
          }
        });
    var document =
        client
            .post()
            .uri("/api/v1/knowledge-bases/default/documents")
            .headers(h -> h.setBearerAuth(a))
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(multipart)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody(com.zhida.agent.knowledge.KnowledgeDocumentInfo.class)
            .returnResult()
            .getResponseBody();
    awaitDocumentReady(a, document.id());
    client
        .get()
        .uri("/api/v1/knowledge-bases/default/documents")
        .headers(h -> h.setBearerAuth(b))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json("[]");
    client
        .get()
        .uri("/api/v1/knowledge-bases/default/documents/" + document.id())
        .headers(h -> h.setBearerAuth(b))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .delete()
        .uri("/api/v1/knowledge-bases/default/documents/" + document.id())
        .headers(h -> h.setBearerAuth(b))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .delete()
        .uri("/api/v1/knowledge-bases/default/documents/" + document.id())
        .headers(h -> h.setBearerAuth(a))
        .exchange()
        .expectStatus()
        .isOk();
    client
        .delete()
        .uri("/api/v1/conversations/alice-private")
        .headers(h -> h.setBearerAuth(a))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private void awaitDocumentReady(String token, String documentId) {
    for (int attempt = 0; attempt < 80; attempt++) {
      var document =
          client
              .get()
              .uri("/api/v1/knowledge-bases/default/documents/" + documentId)
              .headers(headers -> headers.setBearerAuth(token))
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(com.zhida.agent.knowledge.KnowledgeDocumentInfo.class)
              .returnResult()
              .getResponseBody();
      if (document.status() == com.zhida.agent.knowledge.DocumentStatus.READY) {
        return;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("等待文档处理时被中断", exception);
      }
    }
    throw new AssertionError("文档未在预期时间内处理完成");
  }
}
