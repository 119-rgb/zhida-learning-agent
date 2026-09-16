package com.zhida.agent.tool;

import com.zhida.agent.knowledge.KnowledgeBaseService;
import com.zhida.agent.knowledge.KnowledgeSearchResponse;
import com.zhida.agent.support.ProductOrderRepository.Order;
import com.zhida.agent.support.ProductOrderService;
import com.zhida.agent.support.SupportActorResolver;
import com.zhida.agent.support.SupportTicketRepository.Category;
import com.zhida.agent.support.SupportTicketRepository.Detail;
import com.zhida.agent.support.SupportTicketRepository.Ticket;
import com.zhida.agent.support.SupportTicketService;
import com.zhida.agent.support.SupportToolContext;
import com.zhida.agent.tool.search.SearchProvider;
import com.zhida.agent.tool.search.SearchResponse;
import com.zhida.agent.tool.web.SafeWebReader;
import com.zhida.agent.tool.web.WebPageContent;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ResearchTools {

  private final SearchProvider searchProvider;
  private final SafeWebReader webReader;
  private final KnowledgeBaseService knowledgeBaseService;
  private final ProductOrderService productOrders;
  private final SupportTicketService supportTickets;
  private final SupportActorResolver supportActors;

  public ResearchTools(
      SearchProvider searchProvider,
      SafeWebReader webReader,
      KnowledgeBaseService knowledgeBaseService) {
    this(searchProvider, webReader, knowledgeBaseService, Optional.empty(), Optional.empty(), Optional.empty());
  }

  @Autowired
  public ResearchTools(
      SearchProvider searchProvider,
      SafeWebReader webReader,
      KnowledgeBaseService knowledgeBaseService,
      Optional<ProductOrderService> productOrders,
      Optional<SupportTicketService> supportTickets,
      Optional<SupportActorResolver> supportActors) {
    this.searchProvider = searchProvider;
    this.webReader = webReader;
    this.knowledgeBaseService = knowledgeBaseService;
    this.productOrders = productOrders.orElse(null);
    this.supportTickets = supportTickets.orElse(null);
    this.supportActors = supportActors.orElse(null);
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

  @Tool(name = "support_orders", value = "查询当前认证普通用户自己的模拟订单列表。不能查询其他用户。")
  public List<Order> supportOrders() {
    requireSupport();
    return productOrders.myOrders(actor());
  }

  @Tool(name = "support_order", value = "按订单 ID 查询当前认证普通用户自己的模拟订单。")
  public Order supportOrder(@P(value = "要查询的订单 ID") String orderId) {
    requireSupport();
    return productOrders.myOrder(actor(), orderId);
  }

  @Tool(name = "support_tickets", value = "查询当前认证账号自己的工单列表；普通用户只能看到本人创建的工单。")
  public List<Ticket> supportTickets() {
    requireSupport();
    return supportTickets.list(actor(), "mine");
  }

  @Tool(name = "support_ticket", value = "查询当前认证账号有权查看的一张工单及回复、状态记录。")
  public Detail supportTicket(@P(value = "要查询的工单 ID") String ticketId) {
    requireSupport();
    return supportTickets.detail(actor(), ticketId);
  }

  @Tool(name = "support_categories", value = "查询当前认证账号可用于新工单的售后分类。")
  public List<Category> supportCategories() {
    requireSupport();
    return supportTickets.categories(actor()).stream().filter(Category::enabled).toList();
  }

  @Tool(
      name = "support_ticket_draft",
      value =
          "生成未确认的售后工单草稿，返回标题、描述、分类、关联订单和后端 requestId。"
              + "此工具绝不创建工单；必须展示草稿并等待用户明确确认。")
  public SupportTicketService.Draft supportTicketDraft(
      @P(value = "简洁的工单标题，最多 120 字") String title,
      @P(value = "根据用户原话整理的问题描述，最多 4000 字") String description,
      @P(value = "从 support_categories 查询得到的分类 ID") String categoryId,
      @P(value = "用户本人订单 ID；问题与订单无关时可不填", required = false) String orderId) {
    requireSupport();
    return supportTickets.draft(actor(), title, description, categoryId, orderId);
  }

  private SupportActorResolver.Actor actor() {
    return supportActors.account(SupportToolContext.currentOwner());
  }

  private void requireSupport() {
    if (productOrders == null || supportTickets == null || supportActors == null)
      throw new IllegalStateException("售后工具未启用");
  }
}
