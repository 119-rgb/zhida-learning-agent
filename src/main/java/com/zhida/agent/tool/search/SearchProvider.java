package com.zhida.agent.tool.search;

public interface SearchProvider {

    SearchResponse search(String query, int maxResults);
}
