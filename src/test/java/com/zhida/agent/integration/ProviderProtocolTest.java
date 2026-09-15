package com.zhida.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

/** Runs the real application and SDK against a local HTTP protocol fixture, never a supplier. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "zhida.ai.enabled=true",
      "zhida.ai.api-key=local-contract-key",
      "zhida.ai.model=contract-model",
      "zhida.persistence.enabled=false",
      "zhida.auth.enabled=false",
      "zhida.conversation-summary.enabled=false",
      "zhida.search.tavily-api-key=",
      "zhida.rag.embedding-api-key=local-embedding-key",
      "zhida.rag.embedding-model=contract-vector",
      "zhida.execution.timeout-seconds=5"
    })
class ProviderProtocolTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<JsonNode> CHAT_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<JsonNode> EMBEDDING_REQUESTS = new CopyOnWriteArrayList<>();
  private static final Path DIRECTORY;
  private static final HttpServer SUPPLIER;

  static {
    try {
      DIRECTORY = Files.createTempDirectory("zhida-provider-contract-");
      SUPPLIER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      SUPPLIER.createContext("/v1/chat/completions", ProviderProtocolTest::chat);
      SUPPLIER.createContext("/v1/embeddings", ProviderProtocolTest::embeddings);
      SUPPLIER.setExecutor(
          Executors.newCachedThreadPool(
              task -> {
                Thread thread = new Thread(task, "local-provider-fixture");
                thread.setDaemon(true);
                return thread;
              }));
      SUPPLIER.start();
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String url = "http://127.0.0.1:" + SUPPLIER.getAddress().getPort() + "/v1";
    registry.add("zhida.ai.base-url", () -> url);
    registry.add("zhida.rag.embedding-base-url", () -> url);
    registry.add("zhida.rag.upload-dir", () -> DIRECTORY.resolve("uploads").toString());
    registry.add("zhida.rag.vector-store-file", () -> DIRECTORY.resolve("vectors.json").toString());
  }

  @AfterAll
  static void stop() {
    SUPPLIER.stop(0);
  }

  @Autowired TestRestTemplate http;

  @Test
  void shouldStreamThroughRealSdkAndExecuteToolBeforeAnswer() throws Exception {
    ResponseEntity<String> response =
        http.postForEntity(
            "/api/v1/research/stream",
            Map.of("conversationId", "protocol-chat", "message", "请查询今天的日期"),
            String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isNotNull();
    assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .isTrue();
    String frames = response.getBody();
    assertThat(frames)
        .contains("event:tool.started", "event:tool.completed", "event:task.completed")
        .doesNotContain("event:task.failed");
    List<String> deltas = new ArrayList<>();
    long expected = 1;
    for (String frame : frames.replace("\r\n", "\n").split("\n\n")) {
      if (frame.isBlank()) continue;
      String data = null;
      for (String line : frame.split("\n")) {
        if (line.startsWith("id:"))
          assertThat(Long.parseLong(line.substring(3).trim())).isEqualTo(expected++);
        if (line.startsWith("data:")) data = line.substring(5).trim();
      }
      if (data == null) continue;
      JsonNode event = JSON.readTree(data);
      if (event.path("type").asText().equals("answer.delta"))
        deltas.add(event.path("data").path("content").asText());
    }
    assertThat(deltas).containsExactly("日期已查", "询完成。");
    assertThat(CHAT_REQUESTS).hasSize(2);
    assertThat(CHAT_REQUESTS.get(0).path("model").asText()).isEqualTo("contract-model");
    assertThat(CHAT_REQUESTS.get(0).path("tools").toString())
        .contains("current_date", "knowledge_search");
    List<JsonNode> messages = new ArrayList<>();
    CHAT_REQUESTS.get(1).path("messages").forEach(messages::add);
    assertThat(messages)
        .anySatisfy(
            message -> {
              assertThat(message.path("role").asText()).isEqualTo("tool");
              assertThat(message.path("tool_call_id").asText()).isEqualTo("call-date");
              assertThat(message.path("content").asText()).matches(".*\\d{4}-\\d{2}-\\d{2}.*");
            });
  }

  @Test
  void shouldUploadIndexAndSearchUsingRemoteEmbeddingProtocol() throws Exception {
    ByteArrayResource content =
        new ByteArrayResource("ZHIDA-REMOTE-1937 是协议验收编号。".getBytes(StandardCharsets.UTF_8)) {
          @Override
          public String getFilename() {
            return "remote-contract.txt";
          }
        };
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", content);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<JsonNode> uploaded =
        http.postForEntity(
            "/api/v1/knowledge-bases/default/documents",
            new HttpEntity<>(parts, headers),
            JsonNode.class);
    assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    String id = uploaded.getBody().path("id").asText();
    assertThat(id).isNotBlank();
    JsonNode status = null;
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      status = http.getForObject("/api/v1/knowledge-bases/default/documents/" + id, JsonNode.class);
      if (!status.path("status").asText().equals("PROCESSING")) break;
      Thread.sleep(20);
    }
    assertThat(status.path("status").asText()).isEqualTo("READY");
    ResponseEntity<JsonNode> search =
        http.getForEntity(
            "/api/v1/knowledge-bases/default/search?q=ZHIDA-REMOTE-1937&topK=5", JsonNode.class);
    assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(search.getBody().toString()).contains("ZHIDA-REMOTE-1937", "remote-contract.txt");
    assertThat(EMBEDDING_REQUESTS).hasSizeGreaterThanOrEqualTo(2);
    assertThat(EMBEDDING_REQUESTS)
        .allSatisfy(
            request -> assertThat(request.path("model").asText()).isEqualTo("contract-vector"));
    assertThat(Files.readString(DIRECTORY.resolve("vectors.json")))
        .contains("contract-vector")
        .doesNotContain("local-embedding-key");
  }

  private static void chat(HttpExchange exchange) throws IOException {
    JsonNode request = JSON.readTree(exchange.getRequestBody());
    CHAT_REQUESTS.add(request);
    if (!"Bearer local-contract-key"
        .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      exchange.sendResponseHeaders(401, -1);
      exchange.close();
      return;
    }
    boolean hasResult = false;
    for (JsonNode message : request.path("messages"))
      if (message.path("role").asText().equals("tool")) hasResult = true;
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    if (!hasResult) {
      sse(
          exchange,
          "{\"id\":\"chat-contract\",\"object\":\"chat.completion.chunk\",\"model\":\"contract-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call-date\",\"type\":\"function\",\"function\":{\"name\":\"current_date\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}");
      sse(
          exchange,
          "{\"id\":\"chat-contract\",\"model\":\"contract-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
    } else {
      for (String token : List.of("日期已查", "询完成。")) {
        sse(
            exchange,
            JSON.writeValueAsString(
                Map.of(
                    "id",
                    "chat-contract",
                    "model",
                    "contract-model",
                    "choices",
                    List.of(Map.of("index", 0, "delta", Map.of("content", token))))));
      }
      sse(
          exchange,
          "{\"id\":\"chat-contract\",\"model\":\"contract-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
    }
    sse(
        exchange,
        "{\"id\":\"chat-contract\",\"model\":\"contract-model\",\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":2,\"total_tokens\":13}}");
    sse(exchange, "[DONE]");
    exchange.close();
  }

  private static void embeddings(HttpExchange exchange) throws IOException {
    JsonNode request = JSON.readTree(exchange.getRequestBody());
    EMBEDDING_REQUESTS.add(request);
    if (!"Bearer local-embedding-key"
        .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      exchange.sendResponseHeaders(401, -1);
      exchange.close();
      return;
    }
    List<Map<String, Object>> vectors = new ArrayList<>();
    JsonNode input = request.path("input");
    int count = input.isArray() ? input.size() : 1;
    for (int i = 0; i < count; i++)
      vectors.add(Map.of("object", "embedding", "index", i, "embedding", List.of(1.0, 0.0, 0.0)));
    byte[] body =
        JSON.writeValueAsBytes(
            Map.of(
                "object",
                "list",
                "model",
                "contract-vector",
                "data",
                vectors,
                "usage",
                Map.of("prompt_tokens", 1, "total_tokens", 1)));
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static void sse(HttpExchange exchange, String data) throws IOException {
    exchange.getResponseBody().write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    exchange.getResponseBody().flush();
  }
}
