package com.zhida.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhida.agent.ZhidaAgentApplicationTest.MvcClient;
import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.AgentEvent;
import com.zhida.agent.conversation.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zhida.ai.enabled=false",
      "zhida.auth.enabled=false",
      "zhida.persistence.enabled=false",
      "zhida.rag.upload-dir=target/smoke-test/uploads",
      "zhida.rag.vector-store-file=target/smoke-test/vector.json",
      "zhida.rag.embedding-base-url=",
      "zhida.rag.embedding-api-key=",
      "zhida.rag.embedding-model=",
      "zhida.search.tavily-api-key="
    })
public class ZhidaAgentApplicationTest {

  @Autowired private TestRestTemplate http;
  private MvcClient webTestClient;

  @BeforeEach
  void mvcClient() {
    webTestClient = new MvcClient(http);
  }

  @Autowired private ApplicationContext applicationContext;

  @Test
  void shouldNotCreatePersistenceBeansForTheDefaultSmokeTest() {
    assertThat(applicationContext.getBeansOfType(TaskRepository.class)).isEmpty();
  }

  @Test
  void shouldStartApplicationAndExposeHealthEndpoint() {
    webTestClient
        .get()
        .uri("/api/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP")
        .jsonPath("$.application")
        .isEqualTo("zhida-learning-agent")
        .jsonPath("$.aiEnabled")
        .isEqualTo(false)
        .jsonPath("$.searchEnabled")
        .isEqualTo(false);
  }

  @Test
  void shouldExposeEmptyKnowledgeBaseWithoutLoadingEmbeddingModel() {
    webTestClient
        .get()
        .uri("/api/v1/knowledge-bases/integration-test-empty/documents")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json("[]");
  }

  @Test
  void shouldCompleteDemoResearchAsServerSentEvents() {
    MvcClient.Result<AgentEvent> result =
        webTestClient
            .post()
            .uri("/api/v1/research/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(new ResearchRequest("integration-test", "Spring AI vs LangChain4j"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(AgentEvent.class);

    assertThat(result.events())
        .extracting(AgentEvent::type)
        .contains(
            "task.started", "plan.created", "answer.started", "answer.delta", "task.completed")
        .doesNotContain("task.failed");
  }

  @Test
  void fastSseResponsesRetainSecurityHeadersAndDoNotLoseConnection() {
    for (int i = 0; i < 10; i++) {
      var response =
          http.postForEntity(
              "/api/v1/research/stream",
              new ResearchRequest("fast-sse-" + i, "演示问题"),
              String.class);
      assertThat(response.getStatusCode().value()).isEqualTo(200);
      assertThat(response.getBody())
          .contains("event:task.completed")
          .doesNotContain("event:task.failed");
      assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void rejectedExecutorCancelsPreparedSessionBeforeReturningHttpError() {
    var executor =
        org.mockito.Mockito.mock(
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
    var orchestrator =
        org.mockito.Mockito.mock(com.zhida.agent.application.ResearchOrchestrator.class);
    var history = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    var owners = org.mockito.Mockito.mock(com.zhida.agent.auth.OwnerResolver.class);
    var access =
        org.mockito.Mockito.mock(com.zhida.agent.knowledge.KnowledgeBaseAccessService.class);
    var session = org.mockito.Mockito.mock(com.zhida.agent.application.ResearchSession.class);
    var request = new ResearchRequest("queue-rejection", "question");
    org.mockito.Mockito.when(owners.owner(null)).thenReturn("local");
    org.mockito.Mockito.when(orchestrator.prepare(request, "local")).thenReturn(session);
    org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("full"))
        .when(executor)
        .execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    var controller =
        new com.zhida.agent.api.ResearchController(executor, orchestrator, history, owners, access);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.stream(request, null))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(
                        ((org.springframework.web.server.ResponseStatusException) error)
                            .getStatusCode()
                            .value())
                    .isEqualTo(429));
    org.mockito.Mockito.verify(session).cancel();
    org.mockito.Mockito.verify(session, org.mockito.Mockito.never())
        .execute(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void servletRateFilterPreservesSeparateAuthAndApiPostLimits() throws Exception {
    var filter = new com.zhida.agent.auth.RequestRateFilter();
    var count = new java.util.concurrent.atomic.AtomicInteger();
    jakarta.servlet.FilterChain chain = (request, response) -> count.incrementAndGet();
    for (int attempt = 0; attempt < 11; attempt++) {
      var request =
          new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/auth/login");
      request.setRemoteAddr("192.0.2.1");
      var response = new org.springframework.mock.web.MockHttpServletResponse();
      filter.doFilter(request, response, chain);
      assertThat(response.getStatus()).isEqualTo(attempt < 10 ? 200 : 429);
      if (attempt == 10) assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }
    for (int attempt = 0; attempt < 31; attempt++) {
      var api =
          new org.springframework.mock.web.MockHttpServletRequest(
              "POST", "/api/v1/research/stream");
      api.setRemoteAddr("192.0.2.1");
      var response = new org.springframework.mock.web.MockHttpServletResponse();
      filter.doFilter(api, response, chain);
      assertThat(response.getStatus()).isEqualTo(attempt < 30 ? 200 : 429);
    }
    var get =
        new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/conversations");
    get.setRemoteAddr("192.0.2.1");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    filter.doFilter(get, response, chain);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(count.get()).isEqualTo(41);
  }

  /** Small synchronous HTTP assertion helper shared by the MVC integration tests. */
  public static class MvcClient {
    private final TestRestTemplate http;

    public MvcClient(TestRestTemplate http) {
      this.http = http;
      var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
      factory.setConnectTimeout(5_000);
      factory.setReadTimeout(30_000);
      http.getRestTemplate().setRequestFactory(factory);
    }

    public Request get() {
      return new Request(org.springframework.http.HttpMethod.GET);
    }

    public Request post() {
      return new Request(org.springframework.http.HttpMethod.POST);
    }

    public Request put() {
      return new Request(org.springframework.http.HttpMethod.PUT);
    }

    public Request delete() {
      return new Request(org.springframework.http.HttpMethod.DELETE);
    }

    public class Request {
      private final org.springframework.http.HttpMethod method;
      private String uri;
      private Object body;
      private final org.springframework.http.HttpHeaders headers =
          new org.springframework.http.HttpHeaders();

      Request(org.springframework.http.HttpMethod method) {
        this.method = method;
        headers.setContentType(MediaType.APPLICATION_JSON);
      }

      public Request uri(String uri) {
        this.uri = uri;
        return this;
      }

      public Request bodyValue(Object body) {
        this.body = body;
        return this;
      }

      public Request headers(
          java.util.function.Consumer<org.springframework.http.HttpHeaders> action) {
        action.accept(headers);
        return this;
      }

      public Request contentType(MediaType type) {
        headers.setContentType(type);
        return this;
      }

      public Request accept(MediaType type) {
        headers.setAccept(java.util.List.of(type));
        return this;
      }

      public Response exchange() {
        return new Response(
            http.exchange(
                uri,
                method,
                new org.springframework.http.HttpEntity<>(body, headers),
                String.class));
      }
    }

    public static class Response {
      private final org.springframework.http.ResponseEntity<String> response;
      private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
          new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

      Response(org.springframework.http.ResponseEntity<String> response) {
        this.response = response;
      }

      public Response expectStatus() {
        return this;
      }

      public Response isOk() {
        return isEqualTo(200);
      }

      public Response isAccepted() {
        return isEqualTo(202);
      }

      public Response isUnauthorized() {
        return isEqualTo(401);
      }

      public Response isNotFound() {
        return isEqualTo(404);
      }

      public Response isBadRequest() {
        return isEqualTo(400);
      }

      public Response isEqualTo(int status) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        return this;
      }

      public Response expectHeader() {
        return this;
      }

      public Response contentTypeCompatibleWith(MediaType type) {
        assertThat(response.getHeaders().getContentType().isCompatibleWith(type)).isTrue();
        return this;
      }

      public Response expectBody() {
        return this;
      }

      public Response json(String expected) {
        try {
          assertThat(JSON.readTree(response.getBody())).isEqualTo(JSON.readTree(expected));
        } catch (java.io.IOException error) {
          throw new AssertionError(error);
        }
        return this;
      }

      public PathAssertion jsonPath(String path) {
        return new PathAssertion(this, path);
      }

      public <T> Result<T> expectBody(Class<T> type) {
        return new Result<>(decode(type));
      }

      public <T> ListAssertion<T> expectBodyList(Class<T> type) {
        try {
          return new ListAssertion<>(
              JSON.readValue(
                  response.getBody(),
                  JSON.getTypeFactory().constructCollectionType(java.util.List.class, type)));
        } catch (java.io.IOException error) {
          throw new AssertionError(error);
        }
      }

      private <T> T decode(Class<T> type) {
        try {
          return JSON.readValue(response.getBody(), type);
        } catch (java.io.IOException error) {
          throw new AssertionError(error);
        }
      }

      public <T> Result<T> returnResult(Class<T> type) {
        java.util.List<T> events = new java.util.ArrayList<>();
        for (String frame : response.getBody().split("\r?\n\r?\n")) {
          String id = null, name = null, data = null;
          for (String line : frame.split("\r?\n")) {
            if (line.startsWith("id:")) id = line.substring(3).strip();
            if (line.startsWith("event:")) name = line.substring(6).strip();
            if (line.startsWith("data:")) data = line.substring(5).strip();
          }
          if (data == null) continue;
          try {
            T event = JSON.readValue(data, type);
            if (event instanceof AgentEvent agent) {
              assertThat(id).isEqualTo(Long.toString(agent.eventId()));
              assertThat(name).isEqualTo(agent.type());
            }
            events.add(event);
          } catch (java.io.IOException error) {
            throw new AssertionError(error);
          }
        }
        assertThat(events).isNotEmpty();
        return new Result<>(events);
      }
    }

    public static class PathAssertion {
      private final Response response;
      private final String path;

      PathAssertion(Response response, String path) {
        this.response = response;
        this.path = path;
      }

      public Response isEqualTo(Object expected) {
        Object actual = com.jayway.jsonpath.JsonPath.read(response.response.getBody(), path);
        assertThat(actual).isEqualTo(expected);
        return response;
      }

      public Response doesNotExist() {
        try {
          assertThat((Object) com.jayway.jsonpath.JsonPath.read(response.response.getBody(), path))
              .isNull();
        } catch (com.jayway.jsonpath.PathNotFoundException ignored) {
        }
        return response;
      }
    }

    public static class ListAssertion<T> {
      private final java.util.List<T> list;

      ListAssertion(java.util.List<T> list) {
        this.list = list;
      }

      public ListAssertion<T> hasSize(int size) {
        assertThat(list).hasSize(size);
        return this;
      }

      public void value(java.util.function.Consumer<java.util.List<T>> assertion) {
        assertion.accept(list);
      }
    }

    public static class Result<T> {
      private T body;
      private java.util.List<T> events;

      Result(T body) {
        this.body = body;
      }

      Result(java.util.List<T> events) {
        this.events = events;
      }

      public Result<T> returnResult() {
        return this;
      }

      public T getResponseBody() {
        return body;
      }

      public java.util.List<T> events() {
        return events;
      }
    }
  }
}
