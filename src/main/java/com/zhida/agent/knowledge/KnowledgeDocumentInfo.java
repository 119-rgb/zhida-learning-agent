package com.zhida.agent.knowledge;

import java.time.Instant;

public record KnowledgeDocumentInfo(
        String id,
        String knowledgeBaseId,
        String filename,
        String mediaType,
        long size,
        String sha256,
        int chunkCount,
        Instant uploadedAt,
        DocumentStatus status,
        String errorMessage,
        boolean duplicate
) {
}
