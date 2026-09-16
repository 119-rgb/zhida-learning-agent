package com.zhida.agent.knowledge;

import java.util.List;

/**
 * 知识检索结果。evidenceSufficient 和 nextAction 让页面及 Agent 明确区分“有出处的答案”
 * 与“资料不足”，避免把空结果包装成确定结论。
 */
public record KnowledgeSearchResponse(
        String query,
        String knowledgeBaseId,
        List<KnowledgeChunk> results,
        String message,
        boolean evidenceSufficient,
        String nextAction
) {
    /** 每个片段携带文件名、页码或片段编号，用户可以回到原文核对答案依据。 */
    public record KnowledgeChunk(
            String filename,
            Integer pageNumber,
            Integer chunkIndex,
            Double score,
            String content
    ) {
    }
}
