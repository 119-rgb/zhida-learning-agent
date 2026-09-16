package com.zhida.agent.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhida.agent.common.config.ZhidaProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeBaseService {

  private static final String DEFAULT_KNOWLEDGE_BASE = "default";
  private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

  private final ZhidaProperties.Rag properties;
  private final DocumentChunker chunker;
  private final LocalVectorKnowledgeIndex index;
  private final ObjectMapper objectMapper;
  private final Executor executor;
  private final Map<String, CatalogEntry> catalog = new LinkedHashMap<>();

  public KnowledgeBaseService(
      ZhidaProperties properties,
      DocumentChunker chunker,
      LocalVectorKnowledgeIndex index,
      ObjectMapper objectMapper,
      @Qualifier("knowledgeExecutor") Executor executor) {
    this.properties = properties.getRag();
    this.chunker = chunker;
    this.index = index;
    this.objectMapper = objectMapper;
    this.executor = executor;
  }

  @PostConstruct
  synchronized void loadCatalog() {
    Path path = catalogFile();
    if (!Files.isRegularFile(path)) {
      return;
    }
    try {
      List<CatalogEntry> entries = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
      boolean recovered = false;
      List<String> pendingDeletions = new ArrayList<>();
      for (CatalogEntry rawEntry : entries) {
        CatalogEntry entry = rawEntry.normalized();
        if (entry.status() == DocumentStatus.READY && index.requiresReindex()) {
          entry = entry.failed("向量索引已迁移或模型已变化，请重试以重新索引；原文件已保留。");
          recovered = true;
        } else if (entry.status() == DocumentStatus.PROCESSING) {
          entry = entry.failed("服务重启导致处理终止，请重试。");
          recovered = true;
        } else if (entry.status() == DocumentStatus.DELETING) {
          /*
           * DELETING 表示已经持久化的删除意图，不是普通失败状态。上一个进程
           * 可能只删除了部分物理资源就退出；清理步骤全部按幂等方式实现，因此
           * 服务启动后重复执行不会要求文件或向量仍然存在。
           */
          pendingDeletions.add(entry.id());
        }
        catalog.put(entry.id(), entry);
      }
      if (recovered) {
        saveCatalog();
      }
      pendingDeletions.forEach(this::scheduleDeletion);
    } catch (IOException exception) {
      throw new IllegalStateException("知识库目录读取失败：" + path, exception);
    }
  }

  public Path createStagingFile() {
    try {
      Path temporaryDirectory = uploadRoot().resolve(".tmp");
      Files.createDirectories(temporaryDirectory);
      return Files.createTempFile(temporaryDirectory, "upload-", ".tmp");
    } catch (IOException exception) {
      throw new IllegalStateException("无法创建上传临时文件", exception);
    }
  }

  public void deleteStagingFile(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // 临时文件清理失败不覆盖主要业务结果。
    }
  }

  public KnowledgeDocumentInfo submit(
      String knowledgeBaseId, String originalFilename, MediaType mediaType, Path stagingFile) {
    validateKnowledgeBaseId(knowledgeBaseId);
    String filename = safeFilename(originalFilename);
    validateExtension(filename);

    try {
      long size = Files.size(stagingFile);
      if (size == 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件不能为空");
      }
      if (size > properties.getMaxFileSize()) {
        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件不能超过 20MB");
      }

      String sha256 = sha256(stagingFile);
      CatalogEntry entry;
      synchronized (this) {
        CatalogEntry duplicate = findDuplicate(knowledgeBaseId, sha256);
        if (duplicate != null) {
          return duplicate.toInfo(true);
        }
        ensureDocumentCapacity(knowledgeBaseId);
        entry =
            storePendingDocument(knowledgeBaseId, filename, mediaType, stagingFile, size, sha256);
      }
      schedule(entry.id());
      return entry.toInfo(false);
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new IllegalStateException("文档保存失败", exception);
    }
  }

  public synchronized KnowledgeDocumentInfo get(String knowledgeBaseId, String documentId) {
    return requireEntry(knowledgeBaseId, documentId).toInfo(false);
  }

  public KnowledgeDocumentInfo retry(String knowledgeBaseId, String documentId) {
    CatalogEntry processing;
    synchronized (this) {
      CatalogEntry entry = requireEntry(knowledgeBaseId, documentId);
      if (entry.status() == DocumentStatus.PROCESSING) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "文档正在处理中");
      }
      if (entry.status() == DocumentStatus.READY) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "文档已经处理完成");
      }
      if (entry.status() == DocumentStatus.DELETING) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "文档正在删除中");
      }
      processing = entry.processing();
      catalog.put(documentId, processing);
      saveCatalog();
    }
    schedule(documentId);
    return processing.toInfo(false);
  }

  private CatalogEntry findDuplicate(String knowledgeBaseId, String sha256) {
    return catalog.values().stream()
        .filter(
            entry ->
                entry.knowledgeBaseId().equals(knowledgeBaseId) && entry.sha256().equals(sha256))
        .findFirst()
        .orElse(null);
  }

  private void ensureDocumentCapacity(String knowledgeBaseId) {
    long count =
        catalog.values().stream()
            .filter(entry -> entry.knowledgeBaseId().equals(knowledgeBaseId))
            .count();
    if (count >= properties.getMaxDocumentsPerKnowledgeBase()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前知识库最多上传 20 份文档");
    }
  }

  private CatalogEntry storePendingDocument(
      String knowledgeBaseId,
      String filename,
      MediaType mediaType,
      Path stagingFile,
      long size,
      String sha256)
      throws IOException {
    String documentId = UUID.randomUUID().toString();
    Path documentDirectory = uploadRoot().resolve(documentId).normalize();
    Path storedFile = documentDirectory.resolve(filename).normalize();
    if (!documentDirectory.startsWith(uploadRoot()) || !storedFile.startsWith(documentDirectory)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不合法");
    }
    Files.createDirectories(documentDirectory);
    Files.move(stagingFile, storedFile, StandardCopyOption.REPLACE_EXISTING);

    CatalogEntry entry =
        new CatalogEntry(
            documentId,
            knowledgeBaseId,
            filename,
            mediaType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : mediaType.toString(),
            size,
            sha256,
            0,
            Instant.now(),
            storedFile.toString(),
            List.of(),
            DocumentStatus.PROCESSING,
            null);
    catalog.put(documentId, entry);
    try {
      saveCatalog();
    } catch (RuntimeException exception) {
      catalog.remove(documentId);
      try {
        Files.deleteIfExists(storedFile);
        Files.deleteIfExists(documentDirectory);
      } catch (IOException cleanupError) {
        exception.addSuppressed(cleanupError);
      }
      throw exception;
    }
    return entry;
  }

  private void schedule(String documentId) {
    try {
      executor.execute(() -> process(documentId));
    } catch (RejectedExecutionException exception) {
      markFailed(documentId, "处理队列已满，请稍后重试。");
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "处理队列已满，请稍后重试");
    }
  }

  private void process(String documentId) {
    CatalogEntry entry;
    synchronized (this) {
      entry = catalog.get(documentId);
      if (entry == null || entry.status() != DocumentStatus.PROCESSING) {
        return;
      }
    }

    List<String> chunkIds = List.of();
    try {
      List<KnowledgeChunk> chunks =
          parseAndChunk(
              entry.knowledgeBaseId(), entry.id(), entry.filename(), Path.of(entry.storedPath()));
      if (chunks.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "没有从文件中解析到可索引的文字");
      }
      chunkIds = chunks.stream().map(KnowledgeChunk::getId).toList();
      index.add(chunks);
      markReady(documentId, chunkIds);
    } catch (Exception exception) {
      if (!chunkIds.isEmpty()) {
        try {
          index.delete(chunkIds);
        } catch (RuntimeException cleanupError) {
          log.warn(
              "Failed to clean partial vector index for document {}", documentId, cleanupError);
        }
      }
      markFailed(documentId, failureMessage(exception));
      log.warn("Document processing failed for {}", documentId, exception);
    }
  }

  private synchronized void markReady(String documentId, List<String> chunkIds) {
    CatalogEntry entry = catalog.get(documentId);
    if (entry == null || entry.status() != DocumentStatus.PROCESSING) {
      return;
    }
    catalog.put(documentId, entry.ready(chunkIds));
    saveCatalog();
  }

  private synchronized void markFailed(String documentId, String message) {
    CatalogEntry entry = catalog.get(documentId);
    if (entry == null) {
      return;
    }
    catalog.put(documentId, entry.failed(message));
    saveCatalog();
  }

  private String failureMessage(Exception exception) {
    if (exception instanceof ResponseStatusException response
        && StringUtils.hasText(response.getReason())) {
      return response.getReason();
    }
    if (exception instanceof IllegalStateException && exception.getMessage() != null) {
      return exception.getMessage();
    }
    return "文档处理失败，请重试。";
  }

  private CatalogEntry requireEntry(String knowledgeBaseId, String documentId) {
    validateKnowledgeBaseId(knowledgeBaseId);
    CatalogEntry entry = catalog.get(documentId);
    if (entry == null || !entry.knowledgeBaseId().equals(knowledgeBaseId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
    }
    return entry;
  }

  public synchronized List<KnowledgeDocumentInfo> list(String knowledgeBaseId) {
    validateKnowledgeBaseId(knowledgeBaseId);
    return catalog.values().stream()
        .filter(entry -> entry.knowledgeBaseId().equals(knowledgeBaseId))
        .sorted(Comparator.comparing(CatalogEntry::uploadedAt).reversed())
        .map(entry -> entry.toInfo(false))
        .toList();
  }

  public void delete(String knowledgeBaseId, String documentId) {
    validateKnowledgeBaseId(knowledgeBaseId);
    synchronized (this) {
      CatalogEntry entry = requireEntry(knowledgeBaseId, documentId);
      if (entry.status() == DocumentStatus.PROCESSING) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "文档正在处理中，暂时不能删除");
      }
      if (entry.status() != DocumentStatus.DELETING) {
        CatalogEntry deleting = entry.deleting();
        catalog.put(documentId, deleting);
        try {
          /*
           * 在第一次破坏性操作前先持久化删除意图。即使进程随后退出，启动恢复
           * 也能区分“尚未完成删除”和“仍可使用的 READY 文档”，避免把已经丢失
           * 向量或原文件的记录错误展示为可用状态。
           */
          saveCatalog();
        } catch (RuntimeException error) {
          catalog.put(documentId, entry);
          throw error;
        }
      }
    }

    completeDeletion(documentId);
  }

  private void completeDeletion(String documentId) {
    CatalogEntry entry;
    synchronized (this) {
      entry = catalog.get(documentId);
      if (entry == null) {
        return;
      }
      if (entry.status() != DocumentStatus.DELETING) {
        throw new IllegalStateException("文档尚未进入删除状态");
      }
    }

    Path stored = Path.of(entry.storedPath()).toAbsolutePath().normalize();
    Path expected = uploadRoot().resolve(documentId).normalize();
    if (!expected.startsWith(uploadRoot()) || !stored.startsWith(expected)) {
      throw new IllegalStateException("文档路径校验失败");
    }

    /*
     * 所有物理清理步骤都必须幂等。重试发生时，向量或文件可能已经被上一次操作删除；
     * 这种“目标已经不存在”的情况仍应视为成功，不能让文档永久卡在 DELETING。
     */
    index.delete(entry.chunkIds());
    try {
      Files.deleteIfExists(stored);
      Files.deleteIfExists(expected);
    } catch (IOException error) {
      throw new IllegalStateException("原文件删除失败，请重试", error);
    }

    synchronized (this) {
      CatalogEntry current = catalog.get(documentId);
      if (current == null) {
        return;
      }
      catalog.remove(documentId);
      try {
        saveCatalog();
      } catch (RuntimeException error) {
        /*
         * 此时物理资源可能已经不存在。恢复 DELETING 而不是 READY，既能真实表达
         * 当前状态，也让下一次重试只需幂等确认资源已删除并完成目录更新。
         */
        catalog.put(documentId, current);
        throw error;
      }
    }
  }

  private void scheduleDeletion(String documentId) {
    try {
      executor.execute(
          () -> {
            try {
              completeDeletion(documentId);
            } catch (RuntimeException error) {
              // 保留 DELETING；用户再次调用删除接口或下次重启时仍可继续清理。
              log.warn("Document deletion recovery failed for {}", documentId, error);
            }
          });
    } catch (RejectedExecutionException error) {
      // 队列暂时满不能抹掉已经持久化的删除意图，后续仍可通过重试恢复。
      log.warn("Document deletion recovery queue is full for {}", documentId, error);
    }
  }

  public KnowledgeSearchResponse search(
      String knowledgeBaseId, String query, Integer requestedTopK) {
    validateKnowledgeBaseId(knowledgeBaseId);
    if (!StringUtils.hasText(query)) {
      throw new IllegalArgumentException("检索问题不能为空");
    }
    boolean hasReadyDocuments;
    boolean hasProcessingDocuments;
    synchronized (this) {
      hasReadyDocuments =
          catalog.values().stream()
              .anyMatch(
                  entry ->
                      entry.knowledgeBaseId().equals(knowledgeBaseId)
                          && entry.status() == DocumentStatus.READY);
      hasProcessingDocuments =
          catalog.values().stream()
              .anyMatch(
                  entry ->
                      entry.knowledgeBaseId().equals(knowledgeBaseId)
                          && entry.status() == DocumentStatus.PROCESSING);
    }
    if (!hasReadyDocuments) {
      String message =
          hasProcessingDocuments
              ? "检索依据不足：文档仍在处理中，请稍后再试。"
              : "检索依据不足：知识库中还没有可用文档。";
      String nextAction =
          hasProcessingDocuments
              ? "请稍后重新检索；如问题紧急，可创建售后工单。"
              : "请补充产品手册、开通说明或售后规则，或创建售后工单。";
      return new KnowledgeSearchResponse(
          query, knowledgeBaseId, List.of(), message, false, nextAction);
    }

    int topK =
        requestedTopK == null ? properties.getTopK() : Math.max(1, Math.min(requestedTopK, 8));
    List<KnowledgeSearchResponse.KnowledgeChunk> results =
        index.search(knowledgeBaseId, query, topK).stream()
            .map(
                document ->
                    new KnowledgeSearchResponse.KnowledgeChunk(
                        valueAsString(document.getMetadata().get("filename")),
                        valueAsInteger(document.getMetadata().get("pageNumber")),
                        valueAsInteger(document.getMetadata().get("chunkIndex")),
                        document.getScore(),
                        document.getText()))
            .toList();
    boolean sufficient = !results.isEmpty();
    String message =
        sufficient
            ? "已找到 " + results.size() + " 个可核对的相关片段。"
            : "检索依据不足：没有找到相关度足够高的文档片段。";
    String nextAction =
        sufficient ? null : "请补充订单现象和错误信息，或创建售后工单。";
    return new KnowledgeSearchResponse(
        query, knowledgeBaseId, results, message, sufficient, nextAction);
  }

  public String defaultKnowledgeBaseId() {
    return DEFAULT_KNOWLEDGE_BASE;
  }

  private List<KnowledgeChunk> parseAndChunk(
      String knowledgeBaseId, String documentId, String filename, Path storedFile)
      throws IOException {
    String extension = extension(filename);
    List<KnowledgeChunk> sourceDocuments = new ArrayList<>();
    if ("pdf".equals(extension)) {
      try (PDDocument pdf = Loader.loadPDF(storedFile.toFile())) {
        PDFTextStripper stripper = new PDFTextStripper();
        for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
          stripper.setStartPage(page);
          stripper.setEndPage(page);
          sourceDocuments.add(
              new KnowledgeChunk(
                  "page:" + page, stripper.getText(pdf), Map.of("pageNumber", page)));
        }
      }
    } else {
      sourceDocuments.add(
          new KnowledgeChunk(
              "source", Files.readString(storedFile, StandardCharsets.UTF_8), Map.of()));
    }

    List<KnowledgeChunk> chunks = new ArrayList<>();
    int globalChunkIndex = 0;
    for (int sourceIndex = 0; sourceIndex < sourceDocuments.size(); sourceIndex++) {
      KnowledgeChunk source = sourceDocuments.get(sourceIndex);
      List<String> texts =
          chunker.split(source.getText(), properties.getChunkSize(), properties.getChunkOverlap());
      for (String text : texts) {
        Map<String, Object> metadata = new LinkedHashMap<>(source.getMetadata());
        metadata.put("knowledgeBaseId", knowledgeBaseId);
        metadata.put("documentId", documentId);
        metadata.put("filename", filename);
        metadata.put("chunkIndex", globalChunkIndex);
        String chunkId = documentId + ":" + globalChunkIndex;
        chunks.add(new KnowledgeChunk(chunkId, text, metadata));
        globalChunkIndex++;
      }
    }
    return chunks;
  }

  private synchronized void saveCatalog() {
    Path path = catalogFile();
    try {
      Files.createDirectories(path.getParent());
      Path temporary = Files.createTempFile(path.getParent(), "catalog-", ".tmp");
      try {
        objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValue(temporary.toFile(), new ArrayList<>(catalog.values()));
        replaceCatalogFile(temporary, path);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("知识库目录保存失败", exception);
    }
  }

  private void replaceCatalogFile(Path temporary, Path target) throws IOException {
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private Path uploadRoot() {
    return Path.of(properties.getUploadDir()).toAbsolutePath().normalize();
  }

  private Path catalogFile() {
    Path vectorFile = Path.of(properties.getVectorStoreFile()).toAbsolutePath().normalize();
    return vectorFile.resolveSibling("catalog.json");
  }

  private String safeFilename(String originalFilename) {
    if (!StringUtils.hasText(originalFilename)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不能为空");
    }
    String normalized = originalFilename.replace('\\', '/');
    String filename = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
    if (!StringUtils.hasText(filename) || filename.length() > 180) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不合法或过长");
    }
    return filename.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_");
  }

  private void validateExtension(String filename) {
    String extension = extension(filename);
    if (!List.of("pdf", "txt", "md", "markdown").contains(extension)) {
      throw new ResponseStatusException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅支持 PDF、TXT 和 Markdown 文件");
    }
  }

  private String extension(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private void validateKnowledgeBaseId(String knowledgeBaseId) {
    if (knowledgeBaseId == null || !knowledgeBaseId.matches("[A-Za-z0-9_-]{1,100}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库 ID 只能包含字母、数字、下划线和连字符");
    }
  }

  private String sha256(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var inputStream = Files.newInputStream(path)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
    }
  }

  private String valueAsString(Object value) {
    return value == null ? null : value.toString();
  }

  private Integer valueAsInteger(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value.toString());
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private record CatalogEntry(
      String id,
      String knowledgeBaseId,
      String filename,
      String mediaType,
      long size,
      String sha256,
      int chunkCount,
      Instant uploadedAt,
      String storedPath,
      List<String> chunkIds,
      DocumentStatus status,
      String errorMessage) {
    CatalogEntry normalized() {
      return new CatalogEntry(
          id,
          knowledgeBaseId,
          filename,
          mediaType,
          size,
          sha256,
          chunkCount,
          uploadedAt,
          storedPath,
          chunkIds == null ? List.of() : List.copyOf(chunkIds),
          status == null ? DocumentStatus.READY : status,
          errorMessage);
    }

    CatalogEntry processing() {
      return withState(0, List.of(), DocumentStatus.PROCESSING, null);
    }

    CatalogEntry ready(List<String> ids) {
      return withState(ids.size(), List.copyOf(ids), DocumentStatus.READY, null);
    }

    CatalogEntry failed(String message) {
      return withState(0, List.of(), DocumentStatus.FAILED, message);
    }

    CatalogEntry deleting() {
      return withState(chunkCount, chunkIds, DocumentStatus.DELETING, null);
    }

    private CatalogEntry withState(
        int chunks, List<String> ids, DocumentStatus nextStatus, String message) {
      return new CatalogEntry(
          id,
          knowledgeBaseId,
          filename,
          mediaType,
          size,
          sha256,
          chunks,
          uploadedAt,
          storedPath,
          ids,
          nextStatus,
          message);
    }

    KnowledgeDocumentInfo toInfo(boolean duplicate) {
      return new KnowledgeDocumentInfo(
          id,
          knowledgeBaseId,
          filename,
          mediaType,
          size,
          sha256,
          chunkCount,
          uploadedAt,
          status,
          errorMessage,
          duplicate);
    }
  }
}
