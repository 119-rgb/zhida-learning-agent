package com.zhida.agent.support;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 售后模块显式启用后，复用已有连接池、创建 JDBC 事务模板，独立初始化新增业务表。 */
@Configuration
@ConditionalOnProperty(name = "zhida.support.enabled", havingValue = "true")
public class SupportConfiguration {
  @Bean
  SupportTicketRepository supportTicketRepository(
      HikariDataSource source,
      @Value("${zhida.auth.enabled:false}") boolean auth,
      @Value("${zhida.persistence.enabled:false}") boolean persistence) {
    // 原研究助手允许本地匿名演示，售后工单不能复用该身份或退化为内存业务。
    if (!auth || !persistence) throw new IllegalStateException("工单业务必须启用 JWT 和数据库");
    new ResourceDatabasePopulator(new ClassPathResource("db/support-schema.sql")).execute(source);
    var jdbc = new JdbcTemplate(source);
    // CREATE TABLE IF NOT EXISTS 不会给模块 1 的旧表补列，因此启动时按元数据执行一次向后兼容升级。
    ensureColumn(source, jdbc, "support_ticket", "order_id", "VARCHAR(36)");
    ensureForeignKey(source, jdbc);
    // 按查询中的归属/状态及排序字段建索引；只补不存在的索引，不自动改写已有同名索引。
    for (String[] index :
        new String[][] {
          {"idx_support_user", "support_ticket", "user_id,updated_at,id"},
          {"idx_support_queue", "support_ticket", "status,updated_at,id"},
          {"idx_support_agent", "support_ticket", "assigned_to,updated_at,id"},
          {"idx_support_ticket_order", "support_ticket", "order_id"},
          {"idx_support_order_user", "support_order", "user_id,created_at,id"},
          {"idx_support_order_product", "support_order", "product_id,created_at,id"}
        }) {
      ensureIndex(source, jdbc, index);
    }
    return new SupportTicketRepository(
        jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)));
  }

  private void ensureColumn(
      HikariDataSource source, JdbcTemplate jdbc, String table, String column, String definition) {
    try (var connection = source.getConnection()) {
      boolean exists = false;
      for (String candidate : new String[] {table, table.toUpperCase(java.util.Locale.ROOT)}) {
        try (var rows =
            connection
                .getMetaData()
                .getColumns(connection.getCatalog(), null, candidate, column.toUpperCase(java.util.Locale.ROOT))) {
          if (rows.next()) exists = true;
        }
        try (var rows =
            connection.getMetaData().getColumns(connection.getCatalog(), null, candidate, column)) {
          if (rows.next()) exists = true;
        }
      }
      if (!exists) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("工单字段升级失败", e);
    }
  }

  private void ensureForeignKey(HikariDataSource source, JdbcTemplate jdbc) {
    // 新库由 DDL 直接建约束；旧模块 1 数据库补列后再补外键，已有工单的 NULL 值可安全迁移。
    try (var connection = source.getConnection()) {
      boolean exists = false;
      for (String table : new String[] {"support_ticket", "SUPPORT_TICKET"}) {
        try (var rows = connection.getMetaData().getImportedKeys(connection.getCatalog(), null, table)) {
          while (rows.next())
            if ("order_id".equalsIgnoreCase(rows.getString("FKCOLUMN_NAME"))) exists = true;
        }
      }
      if (!exists)
        jdbc.execute(
            "ALTER TABLE support_ticket ADD CONSTRAINT fk_support_ticket_order"
                + " FOREIGN KEY (order_id) REFERENCES support_order(id)");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("工单订单外键升级失败", e);
    }
  }

  private void ensureIndex(HikariDataSource source, JdbcTemplate jdbc, String[] index) {
    // 元数据匹配兼容 H2 大写表名。仅支持单实例初始化，多实例 DDL 迁移需另行管理。
    try (var connection = source.getConnection()) {
      boolean exists = false;
      for (String table : new String[] {index[1], index[1].toUpperCase(java.util.Locale.ROOT)}) {
        try (var rows =
            connection
                .getMetaData()
                .getIndexInfo(connection.getCatalog(), null, table, false, false)) {
          while (rows.next())
            if (index[0].equalsIgnoreCase(rows.getString("INDEX_NAME"))) exists = true;
        }
      }
      if (!exists)
        jdbc.execute("CREATE INDEX " + index[0] + " ON " + index[1] + " (" + index[2] + ")");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("工单索引初始化失败", e);
    }
  }

  @Bean
  SupportActorResolver supportActorResolver(SupportTicketRepository repository) {
    return new SupportActorResolver(repository.jdbc());
  }

  @Bean
  ProductOrderRepository productOrderRepository(SupportTicketRepository tickets) {
    // 两个业务仓储复用同一连接池和事务管理器，工单创建可在一个事务中校验订单归属。
    return new ProductOrderRepository(tickets.jdbc(), tickets.transaction());
  }

  @Bean
  ProductOrderService productOrderService(
      ProductOrderRepository repository, SupportActorResolver actors) {
    return new ProductOrderService(repository, actors);
  }

  @Bean
  SupportTicketService supportTicketService(
      SupportTicketRepository repository,
      SupportActorResolver actors,
      ProductOrderService productOrders) {
    return new SupportTicketService(repository, actors, productOrders);
  }
}
