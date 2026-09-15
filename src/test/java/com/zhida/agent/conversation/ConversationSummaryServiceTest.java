package com.zhida.agent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationSummaryServiceTest {

  @TempDir Path directory;

  @Test
  void summarizesOnlyOlderExchangesAndUpdatesIncrementally() {
    ConversationRepository repository = openRepository();
    repository.create("alice", "conversation", "长对话");
    appendExchanges(repository, 0, 12);

    var prompts = new ArrayList<String>();
    ConversationSummarizer summarizer =
        prompt -> {
          prompts.add(prompt);
          return "摘要版本" + prompts.size();
        };
    var service = new ConversationSummaryService(repository, summarizer, Runnable::run);

    service.schedule("alice", "conversation");

    assertThat(prompts).hasSize(1);
    assertThat(prompts.get(0)).contains("问题0", "问题1").doesNotContain("问题2");
    assertThat(service.current("alice", "conversation")).contains("摘要版本1");

    appendExchanges(repository, 12, 1);
    service.schedule("alice", "conversation");

    assertThat(prompts).hasSize(2);
    assertThat(prompts.get(1)).contains("摘要版本1", "问题2").doesNotContain("问题3");
    assertThat(service.current("alice", "conversation")).contains("摘要版本2");

    var context =
        ConversationContext.withSummary(
            ConversationContext.restore(repository.recentMessages("alice", "conversation"), "继续"),
            service.current("alice", "conversation").orElse(null));
    assertThat(context).hasSize(22);
    assertThat(((dev.langchain4j.data.message.UserMessage) context.get(0)).singleText())
        .contains("摘要版本2");
    assertThat(((dev.langchain4j.data.message.UserMessage) context.get(1)).singleText())
        .isEqualTo("问题3");
    assertThatThrownBy(() -> service.current("bob", "conversation"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void summaryFailureDoesNotBreakConversationHistory() {
    ConversationRepository repository = openRepository();
    repository.create("alice", "conversation", "失败回退");
    appendExchanges(repository, 0, 11);
    var service =
        new ConversationSummaryService(
            repository,
            prompt -> {
              throw new IllegalStateException("model unavailable");
            },
            Runnable::run);

    service.schedule("alice", "conversation");

    assertThat(service.current("alice", "conversation")).isEmpty();
    assertThat(
            ConversationContext.restore(repository.recentMessages("alice", "conversation"), "继续"))
        .hasSize(21);
  }

  @Test
  void oversizedExchangeKeepsItsBeginningAndEnd() {
    ConversationRepository repository = openRepository();
    repository.create("alice", "conversation", "超长对话");
    repository.append("alice", "conversation", "user", "超长问题开头");
    repository.append("alice", "conversation", "assistant", "回答开头" + "中".repeat(20_000) + "回答结尾");
    appendExchanges(repository, 1, 10);
    var prompts = new ArrayList<String>();
    var service =
        new ConversationSummaryService(
            repository,
            prompt -> {
              prompts.add(prompt);
              return "已压缩";
            },
            Runnable::run);

    service.schedule("alice", "conversation");

    assertThat(prompts).singleElement().asString().contains("超长问题开头", "回答开头", "中间内容过长，已省略", "回答结尾");
  }

  private ConversationRepository openRepository() {
    String database = directory.resolve("summary").toString().replace('\\', '/');
    var source = new DriverManagerDataSource("jdbc:h2:file:" + database + ";MODE=MySQL", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("db/conversation-schema.sql"))
        .execute(source);
    return new ConversationRepository(
        new JdbcTemplate(source),
        new TransactionTemplate(new DataSourceTransactionManager(source)));
  }

  private void appendExchanges(ConversationRepository repository, int start, int count) {
    for (int index = start; index < start + count; index++) {
      repository.append("alice", "conversation", "user", "问题" + index);
      repository.append("alice", "conversation", "assistant", "回答" + index);
    }
  }
}
