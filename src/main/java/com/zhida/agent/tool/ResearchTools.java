package com.zhida.agent.tool;

import com.zhida.agent.knowledge.KnowledgeBaseService;
import com.zhida.agent.knowledge.KnowledgeSearchResponse;
import com.zhida.agent.tool.search.SearchProvider;
import com.zhida.agent.tool.search.SearchResponse;
import com.zhida.agent.tool.web.SafeWebReader;
import com.zhida.agent.tool.web.WebPageContent;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class ResearchTools {

  private final SearchProvider searchProvider;
  private final SafeWebReader webReader;
  private final KnowledgeBaseService knowledgeBaseService;

  public ResearchTools(
      SearchProvider searchProvider,
      SafeWebReader webReader,
      KnowledgeBaseService knowledgeBaseService) {
    this.searchProvider = searchProvider;
    this.webReader = webReader;
    this.knowledgeBaseService = knowledgeBaseService;
  }

  @Tool(name = "current_date", value = "获取当前日期。当问题涉及今天、当前或相对日期时使用。")
  public String currentDate() {
    return LocalDate.now().toString();
  }

  @Tool(name = "web_search", value = "搜索公开互联网资料。当问题需要最新信息、事实验证、方案比较或来源引用时使用。")
  public SearchResponse searchWeb(
      @P(value = "简洁、明确的搜索关键词") String query,
      @P(value = "返回结果数量，取值 1 到 5", required = false) Integer maxResults) {
    return searchProvider.search(query, maxResults == null ? 5 : maxResults);
  }

  @Tool(name = "read_web_page", value = "读取一个公开 HTTP/HTTPS 网页的正文。应先搜索，再选择可信来源读取。")
  public WebPageContent readWebPage(@P(value = "要读取的完整公开网页 URL") String url) {
    return webReader.read(url);
  }

  @Tool(
      name = "knowledge_search",
      value =
          "检索当前已授权的公共产品售后知识库或用户私人 PDF、TXT、Markdown 文档。"
              + "结果包含文件名、页码或片段编号及证据充分性；回答售后资料问题时优先使用。")
  public KnowledgeSearchResponse searchKnowledge(
      @P(value = "要在文档中查找的问题或关键词") String query,
      @P(value = "返回片段数量，取值 1 到 8", required = false) Integer topK) {
    return knowledgeBaseService.search(com.zhida.agent.auth.KnowledgeScope.current(), query, topK);
  }
}
