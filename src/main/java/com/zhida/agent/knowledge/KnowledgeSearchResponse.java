package com.zhida.agent.knowledge;

import java.util.List;

public record KnowledgeSearchResponse(
        String query,
        String knowledgeBaseId,
        List<KnowledgeChunk> results,
        String message
) {
    public record KnowledgeChunk(
            String filename,
            Integer pageNumber,
            Integer chunkIndex,
            Double score,
            String content
    ) {
    }
}
