package com.zhida.agent.tool.search;

public record SearchResult(
        String title,
        String url,
        String snippet,
        String publishedAt,
        String provider
) {
}
