package com.zhida.agent.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in check: creates missing project tables; test data writes roll back. */
@EnabledIfEnvironmentVariable(named = "ZHIDA_MYSQL_TEST", matches = "true")
class MySqlConversationTest {
  @Test
  void restoresOrderedContextFromMySql() {
    var source =
        new DriverManagerDataSource(
            System.getenv("DB_URL"), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource("db/conversation-schema.sql"))
        .execute(source);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    var jdbc = new JdbcTemplate(source);
    transaction.executeWithoutResult(
        status -> {
          status.setRollbackOnly();
          var repository = new ConversationRepository(jdbc, transaction);
          String id = UUID.randomUUID().toString();
          repository.create("context-test", id, "上下文测试");
          repository.append("context-test", id, "user", "我的学习目标是 Java");
          repository.append("context-test", id, "assistant", "好的，我们学习 Java");
          var rebuilt = new ConversationRepository(jdbc, transaction);
          assertThat(
                  ConversationContext.restore(rebuilt.recentMessages("context-test", id), "我想学什么？"))
              .extracting(
                  m ->
                      m instanceof dev.langchain4j.data.message.UserMessage u
                          ? u.singleText()
                          : ((dev.langchain4j.data.message.AiMessage) m).text())
              .containsExactly("我的学习目标是 Java", "好的，我们学习 Java", "我想学什么？");
          var tasks =
              new TaskRepository(
                  jdbc, transaction, rebuilt, new com.fasterxml.jackson.databind.ObjectMapper());
          String taskId = UUID.randomUUID().toString();
          tasks.begin("context-test", id, taskId, "继续");
          tasks.record(
              "context-test",
              id,
              new com.zhida.agent.application.AgentEvent(
                  1,
                  taskId,
                  "tool.completed",
                  java.time.OffsetDateTime.now(),
                  java.util.Map.of("toolName", "current_date")));
          tasks.finish("context-test", id, taskId, "COMPLETED", null, "继续学习");
          assertThat(tasks.list("context-test", id).get(0).status()).isEqualTo("COMPLETED");
          assertThat(tasks.events("context-test", id, taskId)).hasSize(1);
          var memory = rebuilt.saveMemory("context-test", "喜欢举例");
          assertThat(rebuilt.memories("context-test")).hasSize(1);
          rebuilt.deleteMemory("context-test", memory.id());
          assertThat(rebuilt.memories("context-test")).isEmpty();
        });
  }
}
