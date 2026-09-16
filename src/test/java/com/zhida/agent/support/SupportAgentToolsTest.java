package com.zhida.agent.support;

import static com.zhida.agent.support.ProductOrderRepository.PaymentStatus.PAID;
import static com.zhida.agent.support.ProductOrderRepository.ServiceStatus.NOT_ACTIVATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import com.zhida.agent.knowledge.KnowledgeBaseService;
import com.zhida.agent.observability.ObservableToolInterceptor;
import com.zhida.agent.observability.ToolTracePublisher;
import com.zhida.agent.support.SupportActorResolver.Actor;
import com.zhida.agent.tool.ResearchTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecifications;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 模块 4 工具边界：认证 owner 来自执行上下文，模型只能查数据和生成未确认草稿。 */
class SupportAgentToolsTest {
  HikariDataSource source;
  JdbcTemplate jdbc;
  SupportActorResolver actors;
  ProductOrderService orders;
  SupportTicketService tickets;
  ResearchTools tools;
  ObservableToolInterceptor interceptor;
  Actor user;
  Actor other;
  Actor admin;
  String categoryId;
  ProductOrderRepository.Order ownedOrder;
  ProductOrderRepository.Order foreignOrder;

  @BeforeEach
  void database() {
    source = new HikariDataSource();
    source.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
    source.setUsername("sa");
    new ResourceDatabasePopulator(
            new ClassPathResource("db/conversation-schema.sql"),
            new ClassPathResource("db/support-schema.sql"))
        .execute(source);
    jdbc = new JdbcTemplate(source);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    var ticketRepository = new SupportTicketRepository(jdbc, transaction);
    actors = new SupportActorResolver(jdbc);
    orders = new ProductOrderService(new ProductOrderRepository(jdbc, transaction), actors);
    tickets = new SupportTicketService(ticketRepository, actors, orders);
    user = account("agent_tool_user", SupportRole.USER);
    other = account("agent_tool_other", SupportRole.USER);
    admin = account("agent_tool_admin", SupportRole.ADMIN);
    categoryId = tickets.saveCategory(admin, null, "虚构订阅开通", true).id();
    String productId =
        orders.createProduct(admin, "AGENT-TOOL", "虚构 Agent 订阅", "隔离自动测试").id();
    ownedOrder = order(user, productId);
    foreignOrder = order(other, productId);
    tools =
        new ResearchTools(
            null,
            null,
            mock(KnowledgeBaseService.class),
            Optional.of(orders),
            Optional.of(tickets),
            Optional.of(actors));
    var traces = new ToolTracePublisher();
    traces.open("task", 20, ignored -> {});
    interceptor = new ObservableToolInterceptor(traces);
  }

  @AfterEach
  void close() {
    source.close();
  }

  @Test
  void toolsUseServerOwnerAndDraftDoesNotCreateTicket() {
    var listed = new AtomicReference<List<ProductOrderRepository.Order>>();
    var draft = new AtomicReference<SupportTicketService.Draft>();
    interceptor.execute(
        "task",
        "knowledge-scope",
        user.id(),
        request("support_orders"),
        () -> {
          listed.set(tools.supportOrders());
          draft.set(
              tools.supportTicketDraft(
                  "已付款但服务未开通",
                  "虚构订单付款后仍显示未开通。",
                  categoryId,
                  ownedOrder.id()));
          return "ok";
        });

    assertThat(listed.get()).extracting(ProductOrderRepository.Order::id).containsExactly(ownedOrder.id());
    assertThat(draft.get().confirmed()).isFalse();
    assertThat(draft.get().orderId()).isEqualTo(ownedOrder.id());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_ticket", Integer.class)).isZero();

    // 只有用户随后明确调用业务创建接口并提交 confirmed=true，草稿才会成为工单。
    var created =
        tickets.create(
            user,
            new SupportTicketService.Create(
                draft.get().requestId(),
                draft.get().title(),
                draft.get().description(),
                draft.get().categoryId(),
                draft.get().orderId(),
                true));
    assertThat(created.userId()).isEqualTo(user.id());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_ticket", Integer.class)).isOne();
  }

  @Test
  void foreignOrderIsRejectedAndNoMutationToolsAreRegistered() {
    assertThatThrownBy(
            () ->
                interceptor.execute(
                    "task",
                    "knowledge-scope",
                    user.id(),
                    request("support_ticket_draft"),
                    () ->
                        tools
                            .supportTicketDraft(
                                "越权草稿",
                                "不能关联其他用户的虚构订单。",
                                categoryId,
                                foreignOrder.id())
                            .toString()))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(404));

    var names =
        ToolSpecifications.toolSpecificationsFrom(tools).stream().map(spec -> spec.name()).toList();
    assertThat(names)
        .contains(
            "support_orders",
            "support_order",
            "support_tickets",
            "support_ticket",
            "support_categories",
            "support_ticket_draft")
        .noneMatch(
            name ->
                name.contains("create")
                    || name.contains("close")
                    || name.contains("refund")
                    || name.contains("payment")
                    || name.contains("activate"));
  }

  private ToolExecutionRequest request(String name) {
    return ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(name).arguments("{}").build();
  }

  private Actor account(String username, SupportRole role) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO user_account(id,username,password_hash,is_guest,created_at) VALUES"
            + " (?,?,?,false,CURRENT_TIMESTAMP)",
        id,
        username,
        "DISABLED");
    jdbc.update("INSERT INTO support_account_role(user_id,role) VALUES (?,?)", id, role.name());
    return actors.account(id);
  }

  private ProductOrderRepository.Order order(Actor owner, String productId) {
    return orders.createOrder(
        admin,
        new ProductOrderService.CreateOrder(
            UUID.randomUUID().toString(), owner.id(), productId, PAID, NOT_ACTIVATED));
  }
}
