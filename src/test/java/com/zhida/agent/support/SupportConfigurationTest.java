package com.zhida.agent.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SupportConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              SupportConfiguration.class, SupportTicketController.class, IsolatedSource.class);

  @Configuration(proxyBeanMethods = false)
  static class IsolatedSource {
    @Bean
    HikariDataSource source() {
      return new HikariDataSource();
    }
  }

  @Test
  void disabledModuleDoesNotExposeBusinessBeans() {
    runner
        .withPropertyValues("zhida.support.enabled=false")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .doesNotHaveBean(SupportTicketController.class)
                  .doesNotHaveBean(SupportTicketService.class)
                  .doesNotHaveBean(ProductOrderService.class)
                  .doesNotHaveBean(ProductOrderRepository.class)
                  .doesNotHaveBean(SupportActorResolver.class);
            });
  }

  @Test
  void enabledModuleRejectsMissingAuthenticationOrPersistenceBeforeConnecting() {
    for (String[] flags : new String[][] {{"false", "true"}, {"true", "false"}}) {
      runner
          .withPropertyValues(
              "zhida.support.enabled=true",
              "zhida.auth.enabled=" + flags[0],
              "zhida.persistence.enabled=" + flags[1])
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("工单业务必须启用 JWT 和数据库");
              });
    }
  }

  @Test
  void upgradesModuleOneTicketTableWithOrderColumnAndForeignKey() throws Exception {
    HikariDataSource source = new HikariDataSource();
    source.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
    source.setUsername("sa");
    try {
      new ResourceDatabasePopulator(new ClassPathResource("db/conversation-schema.sql"))
          .execute(source);
      JdbcTemplate jdbc = new JdbcTemplate(source);
      // 还原模块 1 的旧表形状：字段完整但没有 order_id，随后走正式初始化代码升级。
      jdbc.execute(
          """
          CREATE TABLE support_ticket (
            id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36) NOT NULL,request_id VARCHAR(64) NOT NULL,
            request_hash VARCHAR(64) NOT NULL,title VARCHAR(120) NOT NULL,description TEXT NOT NULL,
            category_id VARCHAR(36) NOT NULL,status VARCHAR(24) NOT NULL,assigned_to VARCHAR(36),
            solution TEXT,rating INT,evaluation VARCHAR(1000),version BIGINT NOT NULL,
            created_at TIMESTAMP(6) NOT NULL,updated_at TIMESTAMP(6) NOT NULL
          )
          """);
      new SupportConfiguration().supportTicketRepository(source, true, true);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS"
                      + " WHERE TABLE_NAME='SUPPORT_TICKET' AND COLUMN_NAME='ORDER_ID'",
                  Integer.class))
          .isEqualTo(1);
      try (var connection = source.getConnection();
          var keys =
              connection
                  .getMetaData()
                  .getImportedKeys(connection.getCatalog(), null, "SUPPORT_TICKET")) {
        boolean found = false;
        while (keys.next())
          if ("ORDER_ID".equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))) found = true;
        assertThat(found).isTrue();
      }
    } finally {
      source.close();
    }
  }
}
