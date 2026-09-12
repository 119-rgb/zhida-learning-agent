package com.zhida.agent.tool.search;

import java.util.List;

public record SearchResponse(
        String query,
        List<SearchResult> results,
        String message
) {
}
