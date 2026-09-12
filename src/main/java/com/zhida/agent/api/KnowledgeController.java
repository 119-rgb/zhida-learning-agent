package com.zhida.agent.api;

import com.zhida.agent.auth.KnowledgeScope;
import com.zhida.agent.auth.OwnerResolver;
import com.zhida.agent.knowledge.KnowledgeBaseService;
import com.zhida.agent.knowledge.KnowledgeDocumentInfo;
import com.zhida.agent.knowledge.KnowledgeSearchResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final OwnerResolver owners;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService, OwnerResolver owners) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.owners = owners;
    }

    @PostMapping(path = "/{knowledgeBaseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<KnowledgeDocumentInfo>> upload(
            @PathVariable String knowledgeBaseId,
            @RequestPart("file") FilePart file,
            Principal principal
    ) {
        return Mono.fromCallable(knowledgeBaseService::createStagingFile)
                .flatMap(stagingFile -> saveAndSubmit(
                        KnowledgeScope.key(owners.owner(principal), knowledgeBaseId),
                        file,
                        stagingFile
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public Mono<List<KnowledgeDocumentInfo>> list(@PathVariable String knowledgeBaseId, Principal principal) {
        return Mono.fromCallable(() -> knowledgeBaseService.list(KnowledgeScope.key(owners.owner(principal), knowledgeBaseId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{knowledgeBaseId}/documents/{documentId}")
    public Mono<KnowledgeDocumentInfo> get(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId,
            Principal principal
    ) {
        return Mono.fromCallable(() -> knowledgeBaseService.get(
                        KnowledgeScope.key(owners.owner(principal), knowledgeBaseId),
                        documentId
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/retry")
    public Mono<ResponseEntity<KnowledgeDocumentInfo>> retry(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId,
            Principal principal
    ) {
        return Mono.fromCallable(() -> knowledgeBaseService.retry(
                        KnowledgeScope.key(owners.owner(principal), knowledgeBaseId),
                        documentId
                ))
                .map(info -> ResponseEntity.accepted().body(info))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{knowledgeBaseId}/search")
    public Mono<KnowledgeSearchResponse> search(
            @PathVariable String knowledgeBaseId,
            @RequestParam("q") String query,
            @RequestParam(value = "topK", required = false) Integer topK,
            Principal principal
    ) {
        return Mono.fromCallable(() -> knowledgeBaseService.search(
                        KnowledgeScope.key(owners.owner(principal), knowledgeBaseId),
                        query,
                        topK
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
    public Mono<Void> delete(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId,
            Principal principal
    ) {
        return Mono.<Void>fromRunnable(() -> knowledgeBaseService.delete(
                        KnowledgeScope.key(owners.owner(principal), knowledgeBaseId),
                        documentId
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<ResponseEntity<KnowledgeDocumentInfo>> saveAndSubmit(String knowledgeBaseId, FilePart file, Path stagingFile) {
        return file.transferTo(stagingFile)
                .then(Mono.fromCallable(() -> knowledgeBaseService.submit(
                        knowledgeBaseId,
                        file.filename(),
                        file.headers().getContentType(),
                        stagingFile
                )))
                .map(info -> ResponseEntity.accepted().body(info))
                .doFinally(signalType -> knowledgeBaseService.deleteStagingFile(stagingFile));
    }
}
