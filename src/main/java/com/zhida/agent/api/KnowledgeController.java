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
    String resolved = access.resolve(owners.owner(principal), knowledgeBaseId);
    Path stagingFile = knowledgeBaseService.createStagingFile();
    try {
      file.transferTo(stagingFile);
      return ResponseEntity.accepted()
          .body(
              knowledgeBaseService.submit(
                  resolved,
                  file.getOriginalFilename(),
                  file.getContentType() == null
                      ? null
                      : MediaType.parseMediaType(file.getContentType()),
                  stagingFile));
    } finally {
      knowledgeBaseService.deleteStagingFile(stagingFile);
    }
  }

  @GetMapping("/{knowledgeBaseId}/documents")
  public List<KnowledgeDocumentInfo> list(
      @PathVariable String knowledgeBaseId, Principal principal) {
    return knowledgeBaseService.list(access.resolve(owners.owner(principal), knowledgeBaseId));
  }

  @GetMapping("/{knowledgeBaseId}/documents/{documentId}")
  public KnowledgeDocumentInfo get(
      @PathVariable String knowledgeBaseId, @PathVariable String documentId, Principal principal) {
    return knowledgeBaseService.get(
        access.resolve(owners.owner(principal), knowledgeBaseId), documentId);
  }

  @PostMapping("/{knowledgeBaseId}/documents/{documentId}/retry")
  public ResponseEntity<KnowledgeDocumentInfo> retry(
      @PathVariable String knowledgeBaseId, @PathVariable String documentId, Principal principal) {
    return ResponseEntity.accepted()
        .body(
            knowledgeBaseService.retry(
                access.resolve(owners.owner(principal), knowledgeBaseId), documentId));
  }

  @GetMapping("/{knowledgeBaseId}/search")
  public KnowledgeSearchResponse search(
      @PathVariable String knowledgeBaseId,
      @RequestParam("q") String query,
      @RequestParam(value = "topK", required = false) Integer topK,
      Principal principal) {
    return knowledgeBaseService.search(
        access.resolve(owners.owner(principal), knowledgeBaseId), query, topK);
  }

  @DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
  public void delete(
      @PathVariable String knowledgeBaseId, @PathVariable String documentId, Principal principal) {
    knowledgeBaseService.delete(
        access.resolve(owners.owner(principal), knowledgeBaseId), documentId);
  }
}
