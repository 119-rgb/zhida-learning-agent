package com.zhida.agent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhida.agent.application.AgentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class TaskRepositoryTest {
    @Test void quotaFailureRollsBackTaskAndUserMessage() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/conversation-schema.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var conversations = new ConversationRepository(jdbc, tx);
        var tasks = new TaskRepository(jdbc, tx, conversations, new ObjectMapper());
        tasks.configureLimits(1,1);
        conversations.create("alice", "quota", "question");
        tasks.begin("alice", "quota", "q1", "first");
        assertThatThrownBy(() -> tasks.begin("alice", "quota", "q1", "duplicate"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> tasks.begin("alice", "quota", "q2", "second"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(429));
        assertThat(tasks.list("alice", "quota")).hasSize(1);
        assertThat(conversations.messages("alice", "quota")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT used_count FROM daily_usage WHERE user_id='alice'", Integer.class)).isEqualTo(1);
        assertThat(tasks.recoverExpired(java.time.Instant.now().plusSeconds(1))).isEqualTo(1);
        assertThat(tasks.snapshot("alice", "q1").status()).isEqualTo("INTERRUPTED");
    }
    @Test void snapshotFindsOlderTasksAndRejectsOtherOwners() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/conversation-schema.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var conversations = new ConversationRepository(jdbc, tx);
        var tasks = new TaskRepository(jdbc, tx, conversations, new ObjectMapper());
        conversations.create("local-user", "old", "history");
        for (int i=0;i<105;i++) {
            tasks.begin("local-user", "old", "t"+i, "question");
            tasks.finish("local-user", "old", "t"+i, "COMPLETED", null, "answer");
        }
        assertThat(tasks.list("local-user", "old")).hasSize(100);
        assertThat(tasks.snapshot("local-user", "t0").status()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> tasks.snapshot("bob", "t0")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void recordsToolsAndEnforcesTerminalStateAndOwnership() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/conversation-schema.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var conversations = new ConversationRepository(jdbc, tx);
        var tasks = new TaskRepository(jdbc, tx, conversations, new ObjectMapper());
        conversations.create("alice", "a", "问题");
        tasks.begin("alice", "a", "task1", "问题");
        tasks.record("alice", "a", new AgentEvent(5, "task1", "tool.started", OffsetDateTime.now(), Map.of("toolName", "web_search")));
        tasks.record("alice", "a", new AgentEvent(6, "task1", "tool.failed", OffsetDateTime.now(), Map.of("toolName", "web_search", "error", true)));
        tasks.finish("alice", "a", "task1", "COMPLETED", null, "回答");
        tasks.finish("alice", "a", "task1", "CANCELLED", "LATE_CANCEL", "");
        tasks.finish("alice", "a", "task1", "COMPLETED", null, "重复回答");
        assertThat(tasks.list("alice", "a").get(0).status()).isEqualTo("COMPLETED");
        tasks.recordUsage("task1", UUID.randomUUID().toString(), "test-model", 100, 20, "onComplete");
        assertThat(tasks.usage("alice", "task1")).hasSize(1);
        assertThatThrownBy(() -> tasks.usage("bob", "task1")).isInstanceOf(ResponseStatusException.class);
        assertThat(conversations.messages("alice", "a")).hasSize(2);
        assertThat(tasks.events("alice", "a", "task1")).extracting(TaskRepository.Event::type)
                .containsExactly("tool.started", "tool.failed");
        assertThatThrownBy(() -> tasks.events("bob", "a", "task1")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tasks.finish("bob", "a", "task1", "FAILED", "BAD", "")).isInstanceOf(ResponseStatusException.class);
        tasks.begin("alice", "a", "task2", "取消测试");
        tasks.finish("alice", "a", "task2", "CANCELLED", "CLIENT_DISCONNECTED", "");
        assertThat(tasks.list("alice", "a")).filteredOn(t -> t.id().equals("task2"))
                .extracting(TaskRepository.Task::status).containsExactly("CANCELLED");
        assertThat(conversations.messages("alice", "a")).hasSize(3);
        conversations.deleteConversation("alice", "a");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_usage", Integer.class)).isZero();
    }

    @Test void recordsUsedMemoryWithoutKeepingDeletedContent() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/conversation-schema.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var conversations = new ConversationRepository(jdbc, tx);
        var tasks = new TaskRepository(jdbc, tx, conversations, new ObjectMapper());
        conversations.create("alice", "memory-audit", "记忆审计");
        var memory = conversations.saveMemory("alice", "我是 Java 初学者");

        tasks.begin("alice", "memory-audit", "memory-task", "请多举例", List.of(memory));

        assertThat(tasks.memoryUsage("alice", "memory-task"))
                .containsExactly(new TaskRepository.MemoryUsage(memory.id(), memory.content(), false));
        assertThatThrownBy(() -> tasks.memoryUsage("bob", "memory-task"))
                .isInstanceOf(ResponseStatusException.class);

        conversations.deleteMemory("alice", memory.id());
        assertThat(tasks.memoryUsage("alice", "memory-task"))
                .containsExactly(new TaskRepository.MemoryUsage(memory.id(), null, true));

        tasks.finish("alice", "memory-audit", "memory-task", "COMPLETED", null, "回答");
        conversations.deleteConversation("alice", "memory-audit");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_memory_usage", Integer.class)).isZero();
    }
}
