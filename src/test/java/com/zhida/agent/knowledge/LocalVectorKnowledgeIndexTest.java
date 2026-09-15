package com.zhida.agent.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.zhida.agent.common.config.ZhidaProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalVectorKnowledgeIndexTest {
  @TempDir Path directory;

  @Test
  void springCanConstructProductionIndexWithoutDownloadingModels() {
    try (var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context.registerBean(ZhidaProperties.class, () -> config("one"));
      context.register(LocalVectorKnowledgeIndex.class);
      context.refresh();
      assertThat(context.getBean(LocalVectorKnowledgeIndex.class)).isNotNull();
    }
  }

  @Test
  void excludesOrthogonalVectorsAndReturnsCosineSimilarity() {
    EmbeddingModel model =
        new EmbeddingModel() {
          @Override
          public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(
                segments.stream()
                    .map(
                        segment ->
                            Embedding.from(
                                segment.text().equals("orthogonal")
                                    ? new float[] {0, 1, 0}
                                    : segment.text().equals("partial")
                                        ? new float[] {0.6f, 0.8f, 0}
                                        : new float[] {1, 0, 0}))
                    .toList());
          }
        };
    LocalVectorKnowledgeIndex index = new LocalVectorKnowledgeIndex(config("one"), model);
    index.add(List.of(chunk("zero", "orthogonal", "a"), chunk("cosine", "partial", "a")));
    assertThat(index.search("a", "query", 5))
        .singleElement()
        .satisfies(
            hit -> {
              assertThat(hit.id()).isEqualTo("cosine");
              assertThat(hit.score()).isCloseTo(0.6, within(0.0001));
            });
  }

  @Test
  void persistsIdsMetadataAndNamespaceAndDeletesAfterRestart() {
    ZhidaProperties config = config("one");
    LocalVectorKnowledgeIndex first = new LocalVectorKnowledgeIndex(config, fake());
    first.add(List.of(chunk("a:0", "alpha", "a"), chunk("b:0", "alpha", "b")));
    LocalVectorKnowledgeIndex restarted = new LocalVectorKnowledgeIndex(config, fake());
    assertThat(restarted.requiresReindex()).isFalse();
    assertThat(restarted.search("a", "alpha", 5))
        .singleElement()
        .satisfies(
            hit -> {
              assertThat(hit.id()).isEqualTo("a:0");
              assertThat(hit.metadata())
                  .containsEntry("pageNumber", 2)
                  .containsEntry("filename", "source.pdf");
              assertThat(hit.score()).isGreaterThan(0.9);
            });
    restarted.delete(List.of("a:0"));
    LocalVectorKnowledgeIndex deleted = new LocalVectorKnowledgeIndex(config, fake());
    assertThat(deleted.search("a", "alpha", 5)).isEmpty();
    assertThat(deleted.search("b", "alpha", 5)).hasSize(1);
  }

  @Test
  void modelChangesRequireCleanReindexAndPreservePreviousFile() throws Exception {
    LocalVectorKnowledgeIndex first = new LocalVectorKnowledgeIndex(config("one"), fake());
    first.add(List.of(chunk("old", "alpha", "a")));
    byte[] original = Files.readAllBytes(directory.resolve("index.json"));
    LocalVectorKnowledgeIndex changed = new LocalVectorKnowledgeIndex(config("two"), fake());
    assertThat(changed.requiresReindex()).isTrue();
    changed.add(List.of(chunk("new", "alpha", "a")));
    assertThat(changed.search("a", "alpha", 5))
        .extracting(KnowledgeChunk::id)
        .containsExactly("new");
    try (var files = Files.list(directory)) {
      Path backup =
          files
              .filter(path -> path.getFileName().toString().startsWith("index.json.previous-"))
              .findFirst()
              .orElseThrow();
      assertThat(Files.readAllBytes(backup)).isEqualTo(original);
    }
  }

  @Test
  void endpointChangesRequireReindexButTrailingSlashDoesNot() {
    ZhidaProperties original = config("one");
    new LocalVectorKnowledgeIndex(original, fake()).add(List.of(chunk("old", "alpha", "a")));
    ZhidaProperties slash = config("one");
    slash.getRag().setEmbeddingBaseUrl("http://test.invalid/v1/");
    assertThat(new LocalVectorKnowledgeIndex(slash, fake()).requiresReindex()).isFalse();
    slash.getRag().setEmbeddingBaseUrl("http://another.invalid/v1");
    assertThat(new LocalVectorKnowledgeIndex(slash, fake()).requiresReindex()).isTrue();
  }

  @Test
  void oldSpringAiFileIsNeverLoadedAsCurrentModel() throws Exception {
    Files.writeString(directory.resolve("index.json"), "{\"old-bge-document\":{}}");
    LocalVectorKnowledgeIndex index = new LocalVectorKnowledgeIndex(config("one"), fake());
    assertThat(index.requiresReindex()).isTrue();
    index.add(List.of(chunk("new", "alpha", "a")));
    assertThat(index.requiresReindex()).isFalse();
  }

  @Test
  void startsWithoutEmbeddingConfigButIndexingFailsUsefully() {
    ZhidaProperties properties = new ZhidaProperties();
    properties.getRag().setVectorStoreFile(directory.resolve("empty.json").toString());
    LocalVectorKnowledgeIndex index = new LocalVectorKnowledgeIndex(properties);
    index.delete(List.of("missing"));
    assertThatThrownBy(() -> index.add(List.of(chunk("one", "alpha", "a"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EMBEDDING_BASE_URL");
    assertThat(directory.resolve("empty.json")).doesNotExist();
  }

  private ZhidaProperties config(String model) {
    ZhidaProperties config = new ZhidaProperties();
    config.getRag().setEmbeddingBaseUrl("http://test.invalid/v1");
    config.getRag().setEmbeddingModel(model);
    config.getRag().setVectorStoreFile(directory.resolve("index.json").toString());
    return config;
  }

  private KnowledgeChunk chunk(String id, String text, String namespace) {
    return new KnowledgeChunk(
        id, text, Map.of("knowledgeBaseId", namespace, "filename", "source.pdf", "pageNumber", 2));
  }

  private EmbeddingModel fake() {
    return new EmbeddingModel() {
      @Override
      public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return Response.from(
            segments.stream().map(segment -> Embedding.from(new float[] {1, 0, 0})).toList());
      }
    };
  }
}
