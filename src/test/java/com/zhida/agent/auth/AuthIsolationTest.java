package com.zhida.agent.auth;

import com.zhida.agent.application.AgentEvent;
import com.zhida.agent.conversation.ConversationRepository;
import com.zhida.agent.knowledge.LocalVectorKnowledgeIndex;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.core.io.ByteArrayResource;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "zhida.auth.enabled=true","zhida.auth.secret=test-only-jwt-secret-0123456789-0123456789",
        "zhida.ai.enabled=false","spring.ai.deepseek.api-key=demo-disabled","zhida.persistence.enabled=true",
        "zhida.persistence.url=jdbc:h2:mem:authisolation;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "zhida.persistence.username=sa","zhida.persistence.password=",
        "zhida.rag.upload-dir=target/auth-test/uploads","zhida.rag.vector-store-file=target/auth-test/vector.json"
})
@AutoConfigureWebTestClient
class AuthIsolationTest {
    @Autowired WebTestClient client;
    @MockitoBean LocalVectorKnowledgeIndex index;
    private String register(String name) {
        return client.post().uri("/api/v1/auth/register").bodyValue(Map.of("username",name,"password","test-password-123"))
                .exchange().expectStatus().isOk().expectBody(AuthController.Token.class).returnResult().getResponseBody().accessToken();
    }
    @Test void registerLoginRefreshAndGuestFlow() {
        String username="login_user";
        String token=register(username);
        client.get().uri("/api/v1/auth/me").headers(h->h.setBearerAuth(token)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.username").isEqualTo(username).jsonPath("$.guest").isEqualTo(false);
        client.post().uri("/api/v1/auth/register").bodyValue(Map.of("username",username,"password","test-password-123"))
                .exchange().expectStatus().isEqualTo(409);
        client.post().uri("/api/v1/auth/login").bodyValue(Map.of("username",username,"password","wrong-password"))
                .exchange().expectStatus().isUnauthorized();
        String loginToken=client.post().uri("/api/v1/auth/login").bodyValue(Map.of("username",username,"password","test-password-123"))
                .exchange().expectStatus().isOk().expectBody(AuthController.Token.class).returnResult().getResponseBody().accessToken();
        client.post().uri("/api/v1/auth/refresh").headers(h->h.setBearerAuth(loginToken)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.expiresIn").isEqualTo(3600);
        String guestToken=client.post().uri("/api/v1/auth/guest").exchange().expectStatus().isOk()
                .expectBody(AuthController.Token.class).returnResult().getResponseBody().accessToken();
        client.get().uri("/api/v1/auth/me").headers(h->h.setBearerAuth(guestToken)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.guest").isEqualTo(true);
    }
    @Test void twoUsersCannotReadOrModifyEachOthersData() {
        String a=register("alice"),b=register("bob");
        client.get().uri("/api/v1/conversations").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/conversations").headers(h->h.setBearerAuth(a+"invalid")).exchange().expectStatus().isUnauthorized();
        var events=client.post().uri("/api/v1/research/stream").headers(h->h.setBearerAuth(a))
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(Map.of("conversationId","alice-private","message","private question"))
                .exchange().expectStatus().isOk().returnResult(AgentEvent.class).getResponseBody().collectList().block(java.time.Duration.ofSeconds(10));
        assertThat(events).extracting(AgentEvent::type).contains("task.completed");
        client.get().uri("/api/v1/conversations/alice-private").headers(h->h.setBearerAuth(b)).exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/tasks/"+events.get(0).taskId()).headers(h->h.setBearerAuth(b)).exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/tasks/"+events.get(0).taskId()+"/memories").headers(h->h.setBearerAuth(b)).exchange().expectStatus().isNotFound();
        client.delete().uri("/api/v1/conversations/alice-private").headers(h->h.setBearerAuth(b)).exchange().expectStatus().isNotFound();
        var memory=client.post().uri("/api/v1/memories").headers(h->h.setBearerAuth(a)).bodyValue(Map.of("content","private memory"))
                .exchange().expectStatus().isOk().expectBody(ConversationRepository.Memory.class).returnResult().getResponseBody();
        client.get().uri("/api/v1/memories").headers(h->h.setBearerAuth(b)).exchange().expectStatus().isOk().expectBody().json("[]");
        client.delete().uri("/api/v1/memories/"+memory.id()).headers(h->h.setBearerAuth(b)).exchange().expectStatus().isNotFound();
        client.put().uri("/api/v1/memories/"+memory.id()).headers(h->h.setBearerAuth(b)).bodyValue(Map.of("content","override"))
                .exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/memories/settings").headers(h->h.setBearerAuth(a)).exchange().expectStatus().isOk().expectBody().jsonPath("$.enabled").isEqualTo(false);
        client.put().uri("/api/v1/memories/settings").headers(h->h.setBearerAuth(a)).bodyValue(Map.of("enabled",true)).exchange().expectStatus().isOk();
        var multipart=new MultipartBodyBuilder();
        multipart.part("file",new ByteArrayResource("Alice private document".getBytes()) { @Override public String getFilename(){return "private.txt";} });
        var document=client.post().uri("/api/v1/knowledge-bases/default/documents").headers(h->h.setBearerAuth(a))
                .contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(multipart.build()).exchange().expectStatus().isAccepted()
                .expectBody(com.zhida.agent.knowledge.KnowledgeDocumentInfo.class).returnResult().getResponseBody();
        awaitDocumentReady(a, document.id());
        client.get().uri("/api/v1/knowledge-bases/default/documents").headers(h->h.setBearerAuth(b)).exchange().expectStatus().isOk().expectBody().json("[]");
        client.get().uri("/api/v1/knowledge-bases/default/documents/"+document.id()).headers(h->h.setBearerAuth(b)).exchange().expectStatus().isNotFound();
        client.delete().uri("/api/v1/knowledge-bases/default/documents/"+document.id()).headers(h->h.setBearerAuth(b)).exchange().expectStatus().isNotFound();
        client.delete().uri("/api/v1/knowledge-bases/default/documents/"+document.id()).headers(h->h.setBearerAuth(a)).exchange().expectStatus().isOk();
        client.delete().uri("/api/v1/conversations/alice-private").headers(h->h.setBearerAuth(a)).exchange().expectStatus().isOk();
    }

    private void awaitDocumentReady(String token, String documentId) {
        for (int attempt = 0; attempt < 80; attempt++) {
            var document = client.get().uri("/api/v1/knowledge-bases/default/documents/" + documentId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .exchange().expectStatus().isOk()
                    .expectBody(com.zhida.agent.knowledge.KnowledgeDocumentInfo.class)
                    .returnResult().getResponseBody();
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
