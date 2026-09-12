package com.zhida.agent.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All reads and writes require a server-resolved owner, never a request body user id. */
public class ConversationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ConversationRepository(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public record Conversation(String id, String title, Instant updatedAt) {}
    public record Message(String id, String role, String content, Instant createdAt) {}
    public record Memory(String id, String content, Instant createdAt) {}
    public record ConversationSummary(String content, Instant coveredAt, Instant updatedAt) {}

    public Optional<ConversationSummary> summary(String owner, String conversationId) {
        requireOwner(owner, conversationId);
        return jdbc.query("""
                        SELECT s.content,s.covered_at,s.updated_at
                        FROM conversation_summary s
                        JOIN conversation c ON c.id=s.conversation_id
                        WHERE s.conversation_id=? AND c.user_id=?
                        """,
                (rs, row) -> new ConversationSummary(
                        rs.getString(1),
                        rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3).toInstant()
                ),
                conversationId,
                owner
        ).stream().findFirst();
    }

    public void saveSummary(String owner, String conversationId, String content, Instant coveredAt) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("对话摘要不能为空");
        }
        requireOwner(owner, conversationId);
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp covered = Timestamp.from(coveredAt);
        jdbc.update("""
                        INSERT INTO conversation_summary(conversation_id,content,covered_at,updated_at)
                        VALUES (?,?,?,?)
                        ON DUPLICATE KEY UPDATE content=?,covered_at=?,updated_at=?
                        """,
                conversationId, content.trim(), covered, now,
                content.trim(), covered, now
        );
    }

    public List<Memory> memories(String owner) {
        return jdbc.query("SELECT id,content,created_at FROM user_memory WHERE user_id=? ORDER BY created_at DESC,id DESC",
                (rs, row) -> new Memory(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), owner);
    }

    public Memory saveMemory(String owner, String content) {
        if (content == null || content.isBlank() || content.length() > 500) throw new IllegalArgumentException("记忆需要 1 至 500 字符");
        Memory memory = new Memory(UUID.randomUUID().toString(), content.trim(), Instant.now());
        jdbc.update("INSERT INTO user_memory(id,user_id,content,created_at) VALUES (?,?,?,?)",
                memory.id(), owner, memory.content(), Timestamp.from(memory.createdAt()));
        return memory;
    }

    public void deleteMemory(String owner, String id) {
        if (jdbc.update("DELETE FROM user_memory WHERE id=? AND user_id=?", id, owner) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆不存在");
        }
    }
    public void editMemory(String owner,String id,String content) {
        if (jdbc.update("UPDATE user_memory SET content=? WHERE id=? AND user_id=?",content.trim(),id,owner)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记忆不存在");
    }
    public boolean memoryEnabled(String owner) {
        return jdbc.queryForList("SELECT enabled FROM memory_settings WHERE user_id=?",Boolean.class,owner).stream().findFirst().orElse(false);
    }
    public List<Memory> activeMemories(String owner) { return memoryEnabled(owner)?memories(owner):List.of(); }
    public void setMemoryEnabled(String owner,boolean enabled) {
        jdbc.update("INSERT INTO memory_settings(user_id,enabled) VALUES (?,?) ON DUPLICATE KEY UPDATE enabled=?",owner,enabled,enabled);
    }
    public void deleteConversation(String owner,String id) {
        transaction.executeWithoutResult(status -> {
            requireOwner(owner,id);
            jdbc.queryForObject("SELECT id FROM conversation WHERE id=? AND user_id=? FOR UPDATE",String.class,id,owner);
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM research_task WHERE conversation_id=? AND status='RUNNING'",Integer.class,id);
            if (count != null && count>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"请先停止正在执行的任务");
            jdbc.update("DELETE FROM research_task_event WHERE task_id IN (SELECT id FROM research_task WHERE conversation_id=?)",id);
            jdbc.update("DELETE FROM research_task WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM conversation_summary WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM chat_message WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM conversation WHERE id=? AND user_id=?",id,owner);
        });
    }

    public List<Conversation> list(String owner) {
        return jdbc.query("SELECT id,title,updated_at FROM conversation WHERE user_id=? ORDER BY updated_at DESC,id LIMIT 100",
                (rs, row) -> new Conversation(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), owner);
    }

    public void create(String owner, String id, String title) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO conversation(id,user_id,title,created_at,updated_at) VALUES (?,?,?,?,?)",
                id, owner, title.substring(0, Math.min(title.length(), 200)), now, now);
    }

    public void ensureConversation(String owner, String id, String title) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM conversation WHERE id=?", Integer.class, id);
        if (count != null && count > 0) requireOwner(owner, id);
        else create(owner, id, title);
    }

    public List<Message> messages(String owner, String id) {
        requireOwner(owner, id);
        return jdbc.query("SELECT m.id,m.role,m.content,m.created_at FROM chat_message m JOIN conversation c ON c.id=m.conversation_id WHERE c.id=? AND c.user_id=? ORDER BY m.created_at,m.id",
                (rs, row) -> new Message(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()), id, owner);
    }

    public void append(String owner, String id, String role, String content) {
        if (!List.of("user", "assistant").contains(role)) throw new IllegalArgumentException("Invalid message role");
        transaction.executeWithoutResult(status -> {
            requireOwner(owner, id);
            // Serialize writes and keep chronological order even when the clock has coarse precision.
            Timestamp previous = jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id=? AND user_id=? FOR UPDATE",
                    Timestamp.class, id, owner);
            Instant instant = Instant.now();
            if (previous != null && !instant.isAfter(previous.toInstant().plusNanos(1000))) {
                instant = previous.toInstant().plusNanos(1000);
            }
            Timestamp now = Timestamp.from(instant);
            jdbc.update("INSERT INTO chat_message(id,conversation_id,role,content,created_at) VALUES (?,?,?,?,?)",
                    UUID.randomUUID().toString(), id, role, content, now);
            jdbc.update("UPDATE conversation SET updated_at=? WHERE id=? AND user_id=?", now, id, owner);
        });
    }

    public void requireOwner(String owner, String id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM conversation WHERE id=? AND user_id=?", Integer.class, id, owner);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
    }

    public List<Message> recentMessages(String owner, String id) {
        requireOwner(owner, id);
        List<Message> messages = jdbc.query("SELECT m.id,m.role,m.content,m.created_at FROM chat_message m JOIN conversation c ON c.id=m.conversation_id WHERE c.id=? AND c.user_id=? ORDER BY m.created_at DESC,m.id DESC LIMIT 40",
                (rs, row) -> new Message(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()), id, owner);
        java.util.Collections.reverse(messages);
        return messages;
    }
}
