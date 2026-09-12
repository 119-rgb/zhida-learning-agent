package com.zhida.agent.tool;

import com.zhida.agent.tool.search.SearchProvider;
import com.zhida.agent.tool.search.SearchResponse;
import com.zhida.agent.tool.web.SafeWebReader;
import com.zhida.agent.tool.web.WebPageContent;
import com.zhida.agent.knowledge.KnowledgeBaseService;
import com.zhida.agent.knowledge.KnowledgeSearchResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class ResearchTools {

    private final SearchProvider searchProvider;
    private final SafeWebReader webReader;
    private final KnowledgeBaseService knowledgeBaseService;

    public ResearchTools(SearchProvider searchProvider, SafeWebReader webReader, KnowledgeBaseService knowledgeBaseService) {
        this.searchProvider = searchProvider;
        this.webReader = webReader;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Tool(name = "current_date", description = "获取当前日期。当问题涉及今天、当前或相对日期时使用。")
    public String currentDate() {
        return LocalDate.now().toString();
    }

    @Tool(name = "web_search", description = "搜索公开互联网资料。当问题需要最新信息、事实验证、方案比较或来源引用时使用。")
    public SearchResponse searchWeb(
            @ToolParam(description = "简洁、明确的搜索关键词") String query,
            @ToolParam(description = "返回结果数量，取值 1 到 5", required = false) Integer maxResults
    ) {
        return searchProvider.search(query, maxResults == null ? 5 : maxResults);
    }

    @Tool(name = "read_web_page", description = "读取一个公开 HTTP/HTTPS 网页的正文。应先搜索，再选择可信来源读取。")
    public WebPageContent readWebPage(
            @ToolParam(description = "要读取的完整公开网页 URL") String url
    ) {
        return webReader.read(url);
    }

    @Tool(name = "knowledge_search", description = "检索用户已经上传的本地 PDF、TXT 或 Markdown 文档。回答与文档有关的问题时优先使用。")
    public KnowledgeSearchResponse searchKnowledge(
            @ToolParam(description = "要在文档中查找的问题或关键词") String query,
            @ToolParam(description = "返回片段数量，取值 1 到 8", required = false) Integer topK
    ) {
        return knowledgeBaseService.search(com.zhida.agent.auth.KnowledgeScope.current(), query, topK);
    }
}
