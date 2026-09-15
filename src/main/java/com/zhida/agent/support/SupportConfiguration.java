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

@Configuration
@ConditionalOnProperty(name = "zhida.support.enabled", havingValue = "true")
public class SupportConfiguration {
  @Bean
  SupportTicketRepository supportTicketRepository(
      HikariDataSource source,
      @Value("${zhida.auth.enabled:false}") boolean auth,
      @Value("${zhida.persistence.enabled:false}") boolean persistence) {
    if (!auth || !persistence) throw new IllegalStateException("工单业务必须启用 JWT 和数据库");
    new ResourceDatabasePopulator(new ClassPathResource("db/support-schema.sql")).execute(source);
    var jdbc = new JdbcTemplate(source);
    // Index discovery makes additive startup idempotent on both MySQL and H2.
    for (String[] index :
        new String[][] {
          {"idx_support_user", "support_ticket", "user_id,updated_at,id"},
          {"idx_support_queue", "support_ticket", "status,updated_at,id"},
          {"idx_support_agent", "support_ticket", "assigned_to,updated_at,id"}
        }) {
      ensureIndex(source, jdbc, index);
    }
    return new SupportTicketRepository(
        jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)));
  }

  private void ensureIndex(HikariDataSource source, JdbcTemplate jdbc, String[] index) {
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
  SupportTicketService supportTicketService(
      SupportTicketRepository repository, SupportActorResolver actors) {
    return new SupportTicketService(repository, actors);
  }
}
