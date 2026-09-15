package com.zhida.agent.knowledge;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhida.agent.common.config.ZhidaProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class LocalVectorKnowledgeIndex {
  private final ZhidaProperties.Rag properties;
  private final ObjectMapper mapper = new ObjectMapper();
  private EmbeddingModel model;
  private InMemoryEmbeddingStore<TextSegment> vectorStore;

  @org.springframework.beans.factory.annotation.Autowired
  public LocalVectorKnowledgeIndex(ZhidaProperties properties) {
    this.properties = properties.getRag();
  }

  /** Package-private injection keeps deterministic fake embeddings in tests only. */
  LocalVectorKnowledgeIndex(ZhidaProperties properties, EmbeddingModel model) {
    this(properties);
    this.model = Objects.requireNonNull(model);
  }

  public synchronized boolean requiresReindex() {
    if (!Files.isRegularFile(file())) return true;
    try {
      return !identity().equals(mapper.readValue(file().toFile(), Snapshot.class).identity());
    } catch (Exception error) {
      return true;
    }
  }

  public synchronized void add(List<KnowledgeChunk> chunks) {
    if (chunks.isEmpty()) return;
    InMemoryEmbeddingStore<TextSegment> current = store();
    List<TextSegment> segments =
        chunks.stream()
            .map(
                chunk -> {
                  Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata());
                  metadata.put("chunkId", chunk.id());
                  return TextSegment.from(chunk.content(), Metadata.from(metadata));
                })
            .toList();
    List<Embedding> embeddings = model().embedAll(segments).content();
    if (embeddings.size() != chunks.size())
      throw new IllegalStateException("Embedding 服务返回的向量数量不正确");
    InMemoryEmbeddingStore<TextSegment> next =
        InMemoryEmbeddingStore.fromJson(current.serializeToJson());
    for (int i = 0; i < chunks.size(); i++)
      next.add(chunks.get(i).id(), embeddings.get(i), segments.get(i));
    save(next);
    vectorStore = next;
  }

  public synchronized List<KnowledgeChunk> search(String knowledgeBaseId, String query, int topK) {
    InMemoryEmbeddingStore<TextSegment> current = store();
    return current
        .search(
            EmbeddingSearchRequest.builder()
                // LangChain4j relevance = (cosine + 1) / 2; preserve the API's cosine 0.30
                // threshold.
                .queryEmbedding(model().embed(query).content())
                .maxResults(topK)
                .minScore(0.65)
                .filter(metadataKey("knowledgeBaseId").isEqualTo(knowledgeBaseId))
                .build())
        .matches()
        .stream()
        .map(
            match ->
                new KnowledgeChunk(
                    match.embeddingId(),
                    match.embedded().text(),
                    match.embedded().metadata().toMap(),
                    2 * match.score() - 1))
        .toList();
  }

  public synchronized void delete(List<String> ids) {
    if (ids.isEmpty() || (!Files.isRegularFile(file()) && vectorStore == null)) return;
    // A stale provider index cannot contain usable current vectors; preserve it for diagnosis.
    if (requiresReindex()) return;
    InMemoryEmbeddingStore<TextSegment> next =
        InMemoryEmbeddingStore.fromJson(store().serializeToJson());
    next.removeAll(ids);
    save(next);
    vectorStore = next;
  }

  private EmbeddingModel model() {
    if (model == null) {
      if (blank(properties.getEmbeddingBaseUrl())
          || blank(properties.getEmbeddingApiKey())
          || blank(properties.getEmbeddingModel()))
        throw new IllegalStateException(
            "请配置独立的 EMBEDDING_BASE_URL、EMBEDDING_API_KEY 和 EMBEDDING_MODEL 后重试知识库索引");
      model =
          OpenAiEmbeddingModel.builder()
              .baseUrl(properties.getEmbeddingBaseUrl().trim())
              .apiKey(properties.getEmbeddingApiKey().trim())
              .modelName(properties.getEmbeddingModel().trim())
              .timeout(Duration.ofSeconds(60))
              .maxRetries(1)
              .build();
    }
    return model;
  }

  private InMemoryEmbeddingStore<TextSegment> store() {
    if (vectorStore != null) return vectorStore;
    if (!Files.isRegularFile(file())) return vectorStore = new InMemoryEmbeddingStore<>();
    if (requiresReindex()) return vectorStore = new InMemoryEmbeddingStore<>();
    try {
      Snapshot snapshot = mapper.readValue(file().toFile(), Snapshot.class);
      if (!identity().equals(snapshot.identity())) {
        // Reindex into a clean store. The old file is copied before replacement by save().
        return vectorStore = new InMemoryEmbeddingStore<>();
      }
      return vectorStore = InMemoryEmbeddingStore.fromJson(snapshot.store());
    } catch (Exception error) {
      throw new IllegalStateException("向量索引读取失败，请保留原文件并重新索引", error);
    }
  }

  private void save(InMemoryEmbeddingStore<TextSegment> store) {
    Path temporary = null;
    try {
      Files.createDirectories(file().getParent());
      if (Files.isRegularFile(file()) && requiresReindex()) {
        Path backup =
            file()
                .resolveSibling(file().getFileName() + ".previous-" + java.util.UUID.randomUUID());
        Files.copy(file(), backup);
      }
      temporary = Files.createTempFile(file().getParent(), "vector-", ".tmp");
      mapper.writeValue(temporary.toFile(), new Snapshot(identity(), store.serializeToJson()));
      try {
        Files.move(
            temporary, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException error) {
        Files.move(temporary, file(), StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception error) {
      throw new IllegalStateException("向量索引保存失败", error);
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (Exception ignored) {
        }
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private String identity() {
    String endpoint =
        Objects.toString(properties.getEmbeddingBaseUrl(), "").trim().replaceAll("/+$", "");
    try {
      String fingerprint =
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(endpoint.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return "langchain4j-v1:"
          + Objects.toString(properties.getEmbeddingModel(), "").trim()
          + ":"
          + fingerprint;
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private Path file() {
    return Path.of(properties.getVectorStoreFile()).toAbsolutePath().normalize();
  }

  private record Snapshot(String identity, String store) {}
}
