package com.zhida.agent.tool.web;

public record WebPageContent(
        String title,
        String url,
        String content,
        boolean truncated
) {
}
