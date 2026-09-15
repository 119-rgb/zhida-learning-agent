package com.zhida.agent.conversation;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ConversationRepositoryTest {
  @TempDir Path directory;

  private ConversationRepository open() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:file:"
                + directory.resolve("history").toString().replace('\\', '/')
                + ";MODE=MySQL",
            "sa",
            "");
    new ResourceDatabasePopulator(new ClassPathResource("db/conversation-schema.sql"))
        .execute(source);
    return new ConversationRepository(
        new JdbcTemplate(source),
        new TransactionTemplate(new DataSourceTransactionManager(source)));
  }

  @Test
  void persistsAcrossReopenedConnectionsAndFiltersOwners() {
    var first = open();
    first.create("alice", "a", "RAG 是什么");
    first.append("alice", "a", "user", "RAG 是什么");
    first.append("alice", "a", "assistant", "先检索资料，再生成回答。");
    var reopened = open();
    assertThat(reopened.messages("alice", "a"))
        .extracting(ConversationRepository.Message::role)
        .containsExactly("user", "assistant");
    assertThat(reopened.list("bob")).isEmpty();
    assertThatThrownBy(() -> reopened.messages("bob", "a"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> reopened.append("bob", "a", "user", "越权"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> reopened.ensureConversation("bob", "a", "占用"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(reopened.messages("alice", "a")).hasSize(2);
    assertThatThrownBy(() -> reopened.recentMessages("bob", "a"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(ConversationContext.restore(reopened.recentMessages("alice", "a"), "继续解释"))
        .extracting(
            m ->
                m instanceof dev.langchain4j.data.message.UserMessage u
                    ? u.singleText()
                    : ((dev.langchain4j.data.message.AiMessage) m).text())
        .containsExactly("RAG 是什么", "先检索资料，再生成回答。", "继续解释");
    var memory = reopened.saveMemory("alice", "请多举例子");
    assertThat(reopened.memories("bob")).isEmpty();
    assertThatThrownBy(() -> reopened.deleteMemory("bob", memory.id()))
        .isInstanceOf(ResponseStatusException.class);
    var question = ConversationContext.restore(List.of(), "新对话");
    assertThat(ConversationContext.withMemories(question, reopened.memories("alice"))).hasSize(2);
    reopened.deleteMemory("alice", memory.id());
    assertThat(ConversationContext.withMemories(question, reopened.memories("alice"))).hasSize(1);
  }
}
