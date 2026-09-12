package com.zhida.agent.tool.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhida.agent.common.config.ZhidaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class TavilySearchProvider implements SearchProvider {

    private static final String SEARCH_URL = "https://api.tavily.com/search";

    private final ZhidaProperties properties;
    private final RestClient restClient;

    @Autowired
    public TavilySearchProvider(ZhidaProperties properties) {
        this(properties, com.zhida.agent.tool.HttpSupport.client());
    }

    TavilySearchProvider(ZhidaProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public SearchResponse search(String query, int maxResults) {
        String apiKey = properties.getSearch().getTavilyApiKey();
        if (!StringUtils.hasText(apiKey)) {
            return new SearchResponse(query, List.of(),
                    "联网搜索未启用：请设置 TAVILY_API_KEY 环境变量。不要根据不存在的搜索结果编造答案。");
        }

        int safeLimit = Math.max(1, Math.min(maxResults, properties.getSearch().getMaxResults()));
        TavilyResponse response = com.zhida.agent.tool.HttpSupport.retry(() -> restClient.post()
                .uri(SEARCH_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(Map.of(
                        "query", query,
                        "search_depth", "basic",
                        "max_results", safeLimit,
                        "include_answer", false,
                        "include_raw_content", false
                ))
                .retrieve()
                .body(TavilyResponse.class));

        if (response == null || response.results() == null) {
            return new SearchResponse(query, List.of(), "搜索服务没有返回结果。");
        }

        List<SearchResult> results = response.results().stream()
                .limit(safeLimit)
                .map(item -> new SearchResult(
                        item.title(),
                        item.url(),
                        item.content(),
                        item.publishedDate(),
                        "tavily"
                ))
                .toList();

        return new SearchResponse(query, results, results.isEmpty() ? "没有找到匹配结果。" : "搜索成功。");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResponse(List<TavilyResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResult(
            String title,
            String url,
            String content,
            @JsonProperty("published_date") String publishedDate
    ) {
    }
}
