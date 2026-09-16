package com.zhida.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhida.agent.common.config.ZhidaProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

class KnowledgeBaseServiceTest {

  @TempDir Path directory;

  @Test
  void processesUploadedDocumentInBackground() throws Exception {
    LocalVectorKnowledgeIndex index = mock(LocalVectorKnowledgeIndex.class);
    QueuedExecutor executor = new QueuedExecutor();
    KnowledgeBaseService service = service(index, executor);

    KnowledgeDocumentInfo submitted =
        service.submit("default", "notes.txt", MediaType.TEXT_PLAIN, textFile("异步文档解析测试内容"));

    assertThat(submitted.status()).isEqualTo(DocumentStatus.PROCESSING);
    assertThat(service.get("default", submitted.id()).status())
        .isEqualTo(DocumentStatus.PROCESSING);

    executor.runNext();

    KnowledgeDocumentInfo ready = service.get("default", submitted.id());
    assertThat(ready.status()).isEqualTo(DocumentStatus.READY);
    assertThat(ready.chunkCount()).isPositive();
    verify(index).add(anyList());
  }

  @Test
  void failedDocumentCanBeRetried() throws Exception {
    LocalVectorKnowledgeIndex index = mock(LocalVectorKnowledgeIndex.class);
    doThrow(new IllegalStateException("embedding unavailable"))
        .doNothing()
        .when(index)
        .add(anyList());
    QueuedExecutor executor = new QueuedExecutor();
    KnowledgeBaseService service = service(index, executor);
    KnowledgeDocumentInfo submitted =
        service.submit("default", "retry.md", MediaType.TEXT_MARKDOWN, textFile("可以重试的文档"));

    executor.runNext();
    KnowledgeDocumentInfo failed = service.get("default", submitted.id());
    assertThat(failed.status()).isEqualTo(DocumentStatus.FAILED);
    assertThat(failed.errorMessage()).isNotBlank();

    KnowledgeDocumentInfo retrying = service.retry("default", submitted.id());
    assertThat(retrying.status()).isEqualTo(DocumentStatus.PROCESSING);
    executor.runNext();

    assertThat(service.get("default", submitted.id()).status()).isEqualTo(DocumentStatus.READY);
  }

  @Test
  void insufficientEvidenceIsExplicitAndSuggestsManualTicketCreation() {
    KnowledgeBaseService service = service(mock(LocalVectorKnowledgeIndex.class), new QueuedExecutor());

    KnowledgeSearchResponse response =
        service.search(KnowledgeBaseAccessService.PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID, "付款后没有开通", 5);

    assertThat(response.results()).isEmpty();
    assertThat(response.evidenceSufficient()).isFalse();
    assertThat(response.message()).contains("检索依据不足");
    assertThat(response.nextAction()).contains("创建售后工单");
  }

  @Test
  void marksInterruptedProcessingAsFailedAfterRestart() throws Exception {
    LocalVectorKnowledgeIndex index = mock(LocalVectorKnowledgeIndex.class);
    KnowledgeBaseService first = service(index, new QueuedExecutor());
    KnowledgeDocumentInfo submitted =
        first.submit("default", "restart.txt", MediaType.TEXT_PLAIN, textFile("重启恢复测试"));

    KnowledgeBaseService restarted = service(index, new QueuedExecutor());
    KnowledgeDocumentInfo recovered = restarted.get("default", submitted.id());

    assertThat(recovered.status()).isEqualTo(DocumentStatus.FAILED);
    assertThat(recovered.errorMessage()).contains("服务重启");
  }

  @Test
  void failedPhysicalDeletionRemainsRetryable() throws Exception {
    LocalVectorKnowledgeIndex index = mock(LocalVectorKnowledgeIndex.class);
    QueuedExecutor executor = new QueuedExecutor();
    KnowledgeBaseService service = service(index, executor);
    KnowledgeDocumentInfo submitted =
        service.submit(
            "default", "delete-retry.txt", MediaType.TEXT_PLAIN, textFile("删除失败后应当可以重试"));
    executor.runNext();

    doThrow(new IllegalStateException("vector store unavailable"))
        .doNothing()
        .when(index)
        .delete(anyList());

    assertThatThrownBy(() -> service.delete("default", submitted.id()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vector store unavailable");
    assertThat(service.get("default", submitted.id()).status()).isEqualTo(DocumentStatus.DELETING);

    service.delete("default", submitted.id());

    assertThat(service.list("default")).isEmpty();
  }

  @Test
  void resumesDeletingDocumentAfterRestart() throws Exception {
    LocalVectorKnowledgeIndex index = mock(LocalVectorKnowledgeIndex.class);
    QueuedExecutor firstExecutor = new QueuedExecutor();
    KnowledgeBaseService first = service(index, firstExecutor);
    KnowledgeDocumentInfo submitted =
        first.submit("default", "delete-restart.txt", MediaType.TEXT_PLAIN, textFile("重启后继续完成删除"));
    firstExecutor.runNext();

    doThrow(new IllegalStateException("temporary delete failure"))
        .doNothing()
        .when(index)
        .delete(anyList());
    assertThatThrownBy(() -> first.delete("default", submitted.id()))
        .isInstanceOf(IllegalStateException.class);

    QueuedExecutor restartExecutor = new QueuedExecutor();
    KnowledgeBaseService restarted = service(index, restartExecutor);
    assertThat(restarted.get("default", submitted.id()).status())
        .isEqualTo(DocumentStatus.DELETING);

    restartExecutor.runNext();

    assertThat(restarted.list("default")).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void parsesRealPdfAndKeepsItSearchableAfterRestart() throws Exception {
    LocalVectorKnowledgeIndex index = mock(LocalVectorKnowledgeIndex.class);
    QueuedExecutor executor = new QueuedExecutor();
    KnowledgeBaseService service = service(index, executor);
    Path pdf = pdfFixture();

    KnowledgeDocumentInfo submitted =
        service.submit("default", "zhida-rag-e2e.pdf", MediaType.APPLICATION_PDF, pdf);
    executor.runNext();

    ArgumentCaptor<List<KnowledgeChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
    verify(index).add(chunksCaptor.capture());
    KnowledgeChunk matchingChunk =
        chunksCaptor.getValue().stream()
            .filter(chunk -> chunk.getText().contains("ZHIDA-PDF-4729"))
            .findFirst()
            .orElseThrow();

    assertThat(matchingChunk.getMetadata())
        .containsEntry("knowledgeBaseId", "default")
        .containsEntry("documentId", submitted.id())
        .containsEntry("filename", "zhida-rag-e2e.pdf")
        .containsKeys("pageNumber", "chunkIndex");

    when(index.search("default", "ZHIDA-PDF-4729", 5)).thenReturn(List.of(matchingChunk));
    KnowledgeSearchResponse beforeRestart = service.search("default", "ZHIDA-PDF-4729", 5);
    assertThat(beforeRestart.evidenceSufficient()).isTrue();
    assertThat(beforeRestart.nextAction()).isNull();
    assertThat(beforeRestart.results())
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.filename()).isEqualTo("zhida-rag-e2e.pdf");
              assertThat(result.pageNumber()).isPositive();
              String normalizedContent = result.content().replaceAll("\\s+", " ").trim();
              assertThat(normalizedContent)
                  .contains("The cobalt library beacon is numbered ZHIDA-PDF-4729.");
            });

    KnowledgeBaseService restarted = service(index, new QueuedExecutor());
    KnowledgeDocumentInfo restored = restarted.get("default", submitted.id());
    assertThat(restored.status()).isEqualTo(DocumentStatus.READY);
    assertThat(restored.chunkCount()).isEqualTo(chunksCaptor.getValue().size());
    assertThat(restarted.search("default", "ZHIDA-PDF-4729", 5).results()).hasSize(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void pdfBoxParsesEachPageWithoutLosingPageNumbers() throws Exception {
    Path pdfFile = directory.resolve("two-pages.pdf");
    try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
      for (int pageNumber = 1; pageNumber <= 2; pageNumber++) {
        var page = new org.apache.pdfbox.pdmodel.PDPage();
        pdf.addPage(page);
        try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
          content.beginText();
          content.setFont(
              new org.apache.pdfbox.pdmodel.font.PDType1Font(
                  org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
              12);
          content.newLineAtOffset(50, 700);
          content.showText("Distinct page marker " + pageNumber);
          content.endText();
        }
      }
      pdf.save(pdfFile.toFile());
    }
    LocalVectorKnowledgeIndex index = mock(LocalVectorKnowledgeIndex.class);
    QueuedExecutor executor = new QueuedExecutor();
    KnowledgeBaseService service = service(index, executor);
    service.submit("default", "two-pages.pdf", MediaType.APPLICATION_PDF, pdfFile);
    executor.runNext();
    ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
    verify(index).add(captor.capture());
    assertThat(captor.getValue()).hasSize(2);
    for (int i = 0; i < 2; i++) {
      assertThat(captor.getValue().get(i).content()).contains("Distinct page marker " + (i + 1));
      assertThat(captor.getValue().get(i).metadata())
          .containsEntry("pageNumber", i + 1)
          .containsEntry("chunkIndex", i);
    }
  }

  @Test
  void migrationMarksReadyDocumentsRetryableAndPreservesOriginals() throws Exception {
    LocalVectorKnowledgeIndex index = mock(LocalVectorKnowledgeIndex.class);
    QueuedExecutor firstExecutor = new QueuedExecutor();
    KnowledgeBaseService first = service(index, firstExecutor);
    KnowledgeDocumentInfo submitted =
        first.submit(
            "default", "migration.txt", MediaType.TEXT_PLAIN, textFile("original content"));
    firstExecutor.runNext();
    Path original;
    try (var files = Files.walk(directory.resolve("uploads"))) {
      original =
          files
              .filter(path -> path.getFileName().toString().equals("migration.txt"))
              .findFirst()
              .orElseThrow();
    }
    when(index.requiresReindex()).thenReturn(true);
    QueuedExecutor restartExecutor = new QueuedExecutor();
    KnowledgeBaseService restarted = service(index, restartExecutor);
    assertThat(restarted.get("default", submitted.id()).status()).isEqualTo(DocumentStatus.FAILED);
    assertThat(restarted.get("default", submitted.id()).errorMessage()).contains("重新索引");
    assertThat(Files.readString(original)).isEqualTo("original content");
    restarted.retry("default", submitted.id());
    restartExecutor.runNext();
    assertThat(restarted.get("default", submitted.id()).status()).isEqualTo(DocumentStatus.READY);
  }

  private KnowledgeBaseService service(LocalVectorKnowledgeIndex index, Executor executor) {
    ZhidaProperties properties = new ZhidaProperties();
    properties.getRag().setUploadDir(directory.resolve("uploads").toString());
    properties.getRag().setVectorStoreFile(directory.resolve("vector/vector.json").toString());
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    KnowledgeBaseService service =
        new KnowledgeBaseService(properties, new DocumentChunker(), index, mapper, executor);
    service.loadCatalog();
    return service;
  }

  private Path textFile(String content) throws Exception {
    Path file = Files.createTempFile(directory, "upload-", ".tmp");
    return Files.writeString(file, content);
  }

  private Path pdfFixture() throws Exception {
    Path file = Files.createTempFile(directory, "upload-", ".pdf");
    try (InputStream input = new ClassPathResource("fixtures/zhida-rag-e2e.pdf").getInputStream()) {
      Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
    }
    return file;
  }

  private static final class QueuedExecutor implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.addLast(command);
    }

    void runNext() {
      tasks.removeFirst().run();
    }
  }
}
