package com.zhida.agent.api;

import com.zhida.agent.auth.OwnerResolver;
import com.zhida.agent.knowledge.KnowledgeBaseAccessService;
import com.zhida.agent.knowledge.KnowledgeBaseService;
import com.zhida.agent.knowledge.KnowledgeDocumentInfo;
import com.zhida.agent.knowledge.KnowledgeSearchResponse;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeController {

  private final KnowledgeBaseService knowledgeBaseService;
  private final KnowledgeBaseAccessService access;
  private final OwnerResolver owners;

  public KnowledgeController(
      KnowledgeBaseService knowledgeBaseService,
      KnowledgeBaseAccessService access,
      OwnerResolver owners) {
    this.knowledgeBaseService = knowledgeBaseService;
    this.access = access;
    this.owners = owners;
  }

  @PostMapping(
      path = "/{knowledgeBaseId}/documents",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<KnowledgeDocumentInfo> upload(
      @PathVariable String knowledgeBaseId,
      @RequestPart("file") MultipartFile file,
      Principal principal)
      throws java.io.IOException {
    String logicalId = access.logicalId(knowledgeBaseId);
    // 先完成权限校验再创建暂存文件，拒绝请求不会产生磁盘副作用。
    String resolved = access.resolveForWrite(owners.owner(principal), logicalId);
    Path stagingFile = knowledgeBaseService.createStagingFile();
    try {
      file.transferTo(stagingFile);
      return ResponseEntity.accepted()
          .body(
              externalize(
                  knowledgeBaseService.submit(
                      resolved,
                      file.getOriginalFilename(),
                      file.getContentType() == null
                          ? null
                          : MediaType.parseMediaType(file.getContentType()),
                      stagingFile),
                  logicalId));
    } finally {
      knowledgeBaseService.deleteStagingFile(stagingFile);
    }
  }

  @GetMapping("/{knowledgeBaseId}/documents")
  public List<KnowledgeDocumentInfo> list(
      @PathVariable String knowledgeBaseId, Principal principal) {
    String logicalId = access.logicalId(knowledgeBaseId);
    return knowledgeBaseService
        .list(access.resolve(owners.owner(principal), logicalId))
        .stream()
        .map(document -> externalize(document, logicalId))
        .toList();
  }

  @GetMapping("/{knowledgeBaseId}/documents/{documentId}")
  public KnowledgeDocumentInfo get(
      @PathVariable String knowledgeBaseId, @PathVariable String documentId, Principal principal) {
    String logicalId = access.logicalId(knowledgeBaseId);
    return externalize(
        knowledgeBaseService.get(
            access.resolve(owners.owner(principal), logicalId), documentId),
        logicalId);
  }

  @PostMapping("/{knowledgeBaseId}/documents/{documentId}/retry")
  public ResponseEntity<KnowledgeDocumentInfo> retry(
      @PathVariable String knowledgeBaseId, @PathVariable String documentId, Principal principal) {
    String logicalId = access.logicalId(knowledgeBaseId);
    return ResponseEntity.accepted()
        .body(
            externalize(
                knowledgeBaseService.retry(
                    access.resolveForWrite(owners.owner(principal), logicalId), documentId),
                logicalId));
  }

  @GetMapping("/{knowledgeBaseId}/search")
  public KnowledgeSearchResponse search(
      @PathVariable String knowledgeBaseId,
      @RequestParam("q") String query,
      @RequestParam(value = "topK", required = false) Integer topK,
      Principal principal) {
    String logicalId = access.logicalId(knowledgeBaseId);
    KnowledgeSearchResponse result =
        knowledgeBaseService.search(
            access.resolve(owners.owner(principal), logicalId), query, topK);
    return new KnowledgeSearchResponse(
        result.query(),
        logicalId,
        result.results(),
        result.message(),
        result.evidenceSufficient(),
        result.nextAction());
  }

  @DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
  public void delete(
      @PathVariable String knowledgeBaseId, @PathVariable String documentId, Principal principal) {
    String logicalId = access.logicalId(knowledgeBaseId);
    knowledgeBaseService.delete(
        access.resolveForWrite(owners.owner(principal), logicalId), documentId);
  }

  /** 内部命名空间只用于授权和向量过滤，HTTP 响应始终返回用户请求的逻辑知识库 ID。 */
  private KnowledgeDocumentInfo externalize(
      KnowledgeDocumentInfo document, String knowledgeBaseId) {
    return new KnowledgeDocumentInfo(
        document.id(),
        knowledgeBaseId,
        document.filename(),
        document.mediaType(),
        document.size(),
        document.sha256(),
        document.chunkCount(),
        document.uploadedAt(),
        document.status(),
        document.errorMessage(),
        document.duplicate());
  }
}
