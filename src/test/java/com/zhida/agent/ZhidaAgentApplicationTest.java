package com.zhida.agent;

import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.AgentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "zhida.ai.enabled=false",
                "zhida.auth.enabled=false",
                "spring.ai.deepseek.api-key=demo-disabled",
                "zhida.search.tavily-api-key="
        }
)
@AutoConfigureWebTestClient
class ZhidaAgentApplicationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldStartApplicationAndExposeHealthEndpoint() {
        webTestClient.get()
                .uri("/api/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.application").isEqualTo("zhida-learning-agent")
                .jsonPath("$.aiEnabled").isEqualTo(false)
                .jsonPath("$.searchEnabled").isEqualTo(false);
    }

    @Test
    void shouldExposeEmptyKnowledgeBaseWithoutLoadingEmbeddingModel() {
        webTestClient.get()
                .uri("/api/v1/knowledge-bases/integration-test-empty/documents")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .json("[]");
    }

    @Test
    void shouldCompleteDemoResearchAsServerSentEvents() {
        FluxExchangeResult<AgentEvent> result = webTestClient.post()
                .uri("/api/v1/research/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new ResearchRequest("integration-test", "Spring AI vs LangChain4j"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(AgentEvent.class);

        StepVerifier.create(result.getResponseBody().map(AgentEvent::type))
                .recordWith(java.util.ArrayList::new)
                .thenConsumeWhile(type -> true)
                .consumeRecordedWith(types -> {
                    assertThat(types).contains(
                            "task.started",
                            "plan.created",
                            "answer.started",
                            "answer.delta",
                            "task.completed"
                    );
                    assertThat(types).doesNotContain("task.failed");
                })
                .verifyComplete();
    }
}
