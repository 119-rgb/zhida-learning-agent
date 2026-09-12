package com.zhida.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResearchRequest(
        @Size(max = 100) String conversationId,
        @NotBlank @Size(max = 8_000) String message,
        @jakarta.validation.constraints.Pattern(regexp = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}") String requestId,
        @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{1,50}") String knowledgeBaseId
) {
    public ResearchRequest(String conversationId, String message) { this(conversationId, message, null, null); }
    public ResearchRequest(String conversationId, String message, String requestId) { this(conversationId, message, requestId, null); }
}
