package com.zhida.agent.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
