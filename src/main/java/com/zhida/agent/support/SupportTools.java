package com.zhida.agent.support;

import com.zhida.agent.knowledge.KnowledgeBaseService;
import com.zhida.agent.knowledge.KnowledgeSearchResponse;
import com.zhida.agent.support.ProductOrderRepository.Order;
import com.zhida.agent.support.SupportTicketRepository.Category;
import com.zhida.agent.support.SupportTicketRepository.Detail;
import com.zhida.agent.support.SupportTicketRepository.Ticket;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 售后 Agent 专用的工具集。它与研究助手的 {@link com.zhida.agent.tool.ResearchTools} 分开，
 * 因为售后工具必须满足三条与通用助手不同的约束：
 *
 * <ol>
 *   <li><b>没有写操作。</b>这里只注册只读查询和一个“生成未确认草稿”的方法。没有创建工单、
 *       接单、回复、提交方案、关闭、退款、改订单状态的方法，模型因此不可能通过工具改变业务数据。
 *   <li><b>没有身份参数。</b>所有工具都不接受 userId/owner/role。操作者身份由
 *       {@link SupportToolContext} 从服务端认证上下文读取，模型无法伪造或越权查询他人数据。
 *   <li><b>没有联网工具。</b>售后回答只允许基于本系统订单、工单和已授权知识库，避免把互联网
 *       内容当作产品规则，也避免把用户问题外发到搜索服务。
 * </ol>
 *
 * <p>工具返回的业务对象在序列化后作为工具结果回传给模型，模型只是这些数据的读者；任何权限
 * 判断都在工具方法内部重新执行，不依赖模型是否“愿意遵守”系统指令。
 */
@Component
@ConditionalOnProperty(name = "zhida.support.enabled", havingValue = "true")
public class SupportTools {

  private final ProductOrderService productOrders;
  private final SupportTicketService supportTickets;
  private final SupportActorResolver actors;
  private final KnowledgeBaseService knowledgeBaseService;

  public SupportTools(
      ProductOrderService productOrders,
      SupportTicketService supportTickets,
      SupportActorResolver actors,
      KnowledgeBaseService knowledgeBaseService) {
    this.productOrders = productOrders;
    this.supportTickets = supportTickets;
    this.actors = actors;
    this.knowledgeBaseService = knowledgeBaseService;
  }

  @Tool(name = "current_date", value = "获取当前日期。问题涉及今天、当前或相对时间时使用。")
  public String currentDate() {
    return LocalDate.now().toString();
  }

  @Tool(
      name = "knowledge_search",
      value =
          "检索当前账号已授权的产品售后知识库和本人私人文档。结果包含文件名、PDF 页码或片段编号"
              + "以及证据是否充分；回答产品规则、开通方式、故障处理时必须先调用它。")
  public KnowledgeSearchResponse searchKnowledge(
      @P(value = "要在文档中查找的问题或关键词") String query,
      @P(value = "返回片段数量，取值 1 到 8", required = false) Integer topK) {
    // 命名空间由服务端授权上下文提供；模型只能选择检索词，不能选择别人的知识库。
    return knowledgeBaseService.search(
        com.zhida.agent.auth.KnowledgeScope.current(), query, topK);
  }

  @Tool(name = "support_orders", value = "查询当前登录用户本人的模拟订单列表，不能查询其他用户。")
  public List<Order> myOrders() {
    return productOrders.myOrders(actor());
  }

  @Tool(name = "support_order", value = "按订单 ID 查询当前登录用户本人的模拟订单；他人订单一律查不到。")
  public Order myOrder(@P(value = "用户本人的模拟订单 ID") String orderId) {
    return productOrders.myOrder(actor(), orderId);
  }

  @Tool(name = "support_tickets", value = "查询当前登录用户本人的工单列表和处理进度。")
  public List<Ticket> myTickets() {
    return supportTickets.list(actor(), "mine");
  }

  @Tool(name = "support_ticket", value = "查询当前账号有权查看的一张工单及其回复和处理记录。")
  public Detail ticketDetail(@P(value = "要查看的工单 ID") String ticketId) {
    return supportTickets.detail(actor(), ticketId);
  }

  @Tool(name = "support_categories", value = "查询可用于新工单的售后分类，分类 ID 必须取自本工具。")
  public List<Category> categories() {
    return supportTickets.categories(actor()).stream().filter(Category::enabled).toList();
  }

  @Tool(
      name = "support_ticket_draft",
      value =
          "生成一张待用户确认的工单草稿，返回标题、问题描述、分类、关联订单和后端生成的 requestId。"
              + "本工具不创建工单；必须把草稿展示给用户，等用户明确确认后由页面调用工单创建接口。")
  public SupportTicketService.Draft ticketDraft(
      @P(value = "简洁的工单标题，最多 120 字") String title,
      @P(value = "根据用户原话整理的问题描述，最多 4000 字") String description,
      @P(value = "取自 support_categories 的分类 ID") String categoryId,
      @P(value = "用户本人订单 ID；问题与订单无关时可不填", required = false) String orderId) {
    return supportTickets.draft(actor(), title, description, categoryId, orderId);
  }

  /** 身份只能来自服务端工具上下文；工具调用线程由 Orchestrator 在认证通过后写入。 */
  private SupportActorResolver.Actor actor() {
    return actors.account(SupportToolContext.currentOwner());
  }
}
