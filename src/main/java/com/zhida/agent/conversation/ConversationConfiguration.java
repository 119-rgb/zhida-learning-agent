package com.zhida.agent.conversation;

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
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class ConversationConfiguration {
    @Bean
    TaskRepository taskRepository(HikariDataSource source, ConversationRepository conversations,
                                  com.fasterxml.jackson.databind.ObjectMapper mapper,
                                  @Value("${zhida.execution.daily-limit:100}") int daily,
                                  @Value("${zhida.execution.guest-daily-limit:5}") int guest) {
        var repository = new TaskRepository(new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)), conversations, mapper);
        repository.configureLimits(daily,guest);
        return repository;
    }
    @Bean(destroyMethod = "close")
    HikariDataSource conversationDataSource(
            @Value("${zhida.persistence.url}") String url,
            @Value("${zhida.persistence.username}") String username,
            @Value("${zhida.persistence.password}") String password) {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        source.setMaximumPoolSize(5);
        source.setConnectionTimeout(5000);
        new ResourceDatabasePopulator(new ClassPathResource("db/conversation-schema.sql")).execute(source);
        return source;
    }

    @Bean
    ConversationRepository conversationRepository(HikariDataSource source) {
        return new ConversationRepository(new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }
}
