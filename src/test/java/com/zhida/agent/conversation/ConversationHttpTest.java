package com.zhida.agent.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhida.agent.ZhidaAgentApplicationTest.MvcClient;
import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.AgentEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zhida.ai.enabled=false",
      "zhida.auth.enabled=false",
      "zhida.persistence.enabled=true",
      "zhida.persistence.url=jdbc:h2:mem:historyhttp;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "zhida.persistence.username=sa",
      "zhida.persistence.password=",
      "zhida.rag.upload-dir=target/conversation-http/uploads",
      "zhida.rag.vector-store-file=target/conversation-http/vector.json",
      "zhida.rag.embedding-base-url=",
      "zhida.rag.embedding-api-key=",
      "zhida.rag.embedding-model=",
      "zhida.cost.warning-usd=0.75"
    })
class ConversationHttpTest {
  @Autowired TestRestTemplate http;
  MvcClient client;

  @BeforeEach
  void mvcClient() {
    client = new MvcClient(http);
  }

  @Autowired TaskRepository tasks;
  @Autowired JdbcTemplate jdbc;

  @Test
  void duplicateRequestCannotCreateAnotherTaskOrMessage() {
    String requestId = java.util.UUID.randomUUID().toString();
    var request = new ResearchRequest("idempotency-test", "幂等请求", requestId);
    client
        .post()
        .uri("/api/v1/research/stream")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(AgentEvent.class)
        .events();
    client
        .post()
        .uri("/api/v1/research/stream")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isEqualTo(409);
    client
        .get()
        .uri("/api/v1/conversations/idempotency-test/tasks")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBodyList(TaskRepository.Task.class)
        .hasSize(1);
    client
        .get()
        .uri("/api/v1/conversations/idempotency-test")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBodyList(ConversationRepository.Message.class)
        .hasSize(2);
  }

  @Test
  void userCanSaveAndDeleteMemoryAndBlankContentIsRejected() {
    var result =
        client
            .post()
            .uri("/api/v1/memories")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("content", "用小白语言回答"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ConversationRepository.Memory.class)
            .returnResult()
            .getResponseBody();
    assertThat(result).isNotNull();
    client
        .get()
        .uri("/api/v1/memories")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].content")
        .isEqualTo("用小白语言回答");
    client.delete().uri("/api/v1/memories/" + result.id()).exchange().expectStatus().isOk();
    client.get().uri("/api/v1/memories").exchange().expectStatus().isOk().expectBody().json("[]");
    client
        .post()
        .uri("/api/v1/memories")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(java.util.Map.of("content", " "))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void streamPersistsMessagesAndExposesHistory() {
    var events =
        client
            .post()
            .uri("/api/v1/research/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(new ResearchRequest("saved-demo", "解释 RAG"))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(AgentEvent.class)
            .events();
    assertThat(events)
        .extracting(AgentEvent::type)
        .contains("task.completed")
        .doesNotContain("task.failed");
    client
        .get()
        .uri("/api/v1/conversations")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBodyList(ConversationRepository.Conversation.class)
        .value(
            list ->
                assertThat(list)
                    .extracting(ConversationRepository.Conversation::id)
                    .contains("saved-demo"));
    client
        .get()
        .uri("/api/v1/conversations/saved-demo")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].role")
        .isEqualTo("user")
        .jsonPath("$[1].role")
        .isEqualTo("assistant");
    client.get().uri("/api/v1/conversations/missing").exchange().expectStatus().isNotFound();
    client
        .get()
        .uri("/api/v1/conversations/saved-demo/tasks")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].status")
        .isEqualTo("COMPLETED");
    String taskId = events.get(0).taskId();
    client
        .get()
        .uri("/api/v1/conversations/saved-demo/tasks/" + taskId + "/events")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].type")
        .isEqualTo("plan.created");
    client
        .get()
        .uri("/api/v1/conversations/other/tasks/" + taskId + "/events")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void taskHistoryShowsWhichEnabledMemoriesWereUsed() {
    var memory =
        client
            .post()
            .uri("/api/v1/memories")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("content", "我是 Java 初学者"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ConversationRepository.Memory.class)
            .returnResult()
            .getResponseBody();
    assertThat(memory).isNotNull();
    client
        .put()
        .uri("/api/v1/memories/settings")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(java.util.Map.of("enabled", true))
        .exchange()
        .expectStatus()
        .isOk();

    var events =
        client
            .post()
            .uri("/api/v1/research/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(new ResearchRequest("memory-usage-http", "请解释依赖注入"))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(AgentEvent.class)
            .events();
    String taskId = events.get(0).taskId();

    client
        .get()
        .uri("/api/v1/tasks/" + taskId + "/memories")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].memoryId")
        .isEqualTo(memory.id())
        .jsonPath("$[0].content")
        .isEqualTo("我是 Java 初学者")
        .jsonPath("$[0].deleted")
        .isEqualTo(false);

    client.delete().uri("/api/v1/memories/" + memory.id()).exchange().expectStatus().isOk();
    client
        .get()
        .uri("/api/v1/tasks/" + taskId + "/memories")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].memoryId")
        .isEqualTo(memory.id())
        .jsonPath("$[0].content")
        .doesNotExist()
        .jsonPath("$[0].deleted")
        .isEqualTo(true);
  }

  @Test
  void taskCostEndpointReturnsAnUpperEstimateAndWarning() {
    var events =
        client
            .post()
            .uri("/api/v1/research/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(new ResearchRequest("cost-http", "解释向量检索"))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(AgentEvent.class)
            .events();
    String taskId = events.get(0).taskId();
    tasks.recordUsage(
        taskId, UUID.randomUUID().toString(), "deepseek-flash", 1_000_000, 1_000_000, "onComplete");
    jdbc.update(
        "UPDATE model_usage SET created_at=? WHERE task_id=?",
        Timestamp.from(Instant.parse("2026-09-13T12:00:00Z")),
        taskId);

    client
        .get()
        .uri("/api/v1/tasks/" + taskId + "/cost")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.estimatedMaxCostUsd")
        .isEqualTo(0.75)
        .jsonPath("$.complete")
        .isEqualTo(true)
        .jsonPath("$.pricedCalls")
        .isEqualTo(1)
        .jsonPath("$.totalCalls")
        .isEqualTo(1)
        .jsonPath("$.warning")
        .isEqualTo(true)
        .jsonPath("$.basis")
        .isEqualTo("cache_miss_upper_bound");
  }
}
