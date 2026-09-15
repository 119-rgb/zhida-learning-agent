package com.zhida.agent.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhida.agent.application.AgentEvent;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

public class TaskRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ConversationRepository conversations;
    private final ObjectMapper mapper;
    private int dailyLimit=100;
    private int guestLimit=5;
    public void configureLimits(int daily,int guest) {
        if(daily<1 || guest<1) throw new IllegalArgumentException("额度必须为正数");
        this.dailyLimit=daily;this.guestLimit=guest;
    }

    public TaskRepository(JdbcTemplate jdbc, TransactionTemplate transaction,
                          ConversationRepository conversations, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.conversations = conversations;
        this.mapper = mapper;
    }

    public record Task(String id, String question, String status, String errorCode, Instant startedAt, Instant finishedAt) {}
    public record Event(long eventId, String type, JsonNode data, Instant createdAt) {}
    public record Usage(String model, Integer promptTokens, Integer completionTokens, String outcome, Instant recordedAt) {}
    public record MemoryUsage(String memoryId, String content, boolean deleted) {}

    public List<MemoryUsage> memoryUsage(String owner, String taskId) {
        snapshot(owner, taskId);
        return jdbc.query("""
                        SELECT tm.memory_id,memory.content
                        FROM task_memory_usage tm
                        LEFT JOIN user_memory memory
                          ON memory.id=tm.memory_id AND memory.user_id=?
                        WHERE tm.task_id=?
                        ORDER BY tm.created_at,tm.memory_id
                        """,
                (rs, row) -> {
                    String content = rs.getString(2);
                    return new MemoryUsage(rs.getString(1), content, content == null);
                },
                owner,
                taskId
        );
    }

    public void recordUsage(String taskId, String callId, String model, Integer input, Integer output, String outcome) {
        jdbc.update("INSERT INTO model_usage(id,task_id,model_name,prompt_tokens,completion_tokens,outcome,created_at) VALUES (?,?,?,?,?,?,?)",
                callId, taskId, model == null || model.isBlank() ? "unknown" : model, input, output, outcome, Timestamp.from(Instant.now()));
    }

    public List<Usage> usage(String owner, String taskId) {
        snapshot(owner, taskId);
        return jdbc.query("SELECT model_name,prompt_tokens,completion_tokens,outcome,created_at FROM model_usage WHERE task_id=? ORDER BY created_at,id",
                (rs,row) -> new Usage(
                        rs.getString(1),
                        rs.getObject(2,Integer.class),
                        rs.getObject(3,Integer.class),
                        rs.getString(4),
                        rs.getTimestamp(5).toInstant()
                ),taskId);
    }

    public void begin(String owner, String conversationId, String taskId, String question) {
        begin(owner, conversationId, taskId, question, List.of());
    }

    public void begin(
            String owner,
            String conversationId,
            String taskId,
            String question,
            List<ConversationRepository.Memory> memories
    ) {
        try {
            transaction.executeWithoutResult(status -> {
                conversations.requireOwner(owner, conversationId);
                jdbc.queryForObject("SELECT id FROM conversation WHERE id=? AND user_id=? FOR UPDATE", String.class, conversationId, owner);
                jdbc.update("INSERT INTO research_task(id,conversation_id,question,status,started_at) VALUES (?,?,?,'RUNNING',?)",
                        taskId, conversationId, question, Timestamp.from(Instant.now()));
                if (!owner.equals("local-user")) {
                    var guests = jdbc.queryForList("SELECT is_guest FROM user_account WHERE id=?", Boolean.class, owner);
                    int limit = !guests.isEmpty() && guests.get(0) ? guestLimit : dailyLimit;
                    var origins = jdbc.queryForList("SELECT origin_hash FROM guest_origin WHERE user_id=?", String.class, owner);
                    String quotaOwner = origins.isEmpty() ? owner : "guest-ip:" + origins.get(0);
                    java.sql.Date today = java.sql.Date.valueOf(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
                    jdbc.update("INSERT INTO daily_usage(user_id,usage_date,used_count) VALUES (?,?,0) ON DUPLICATE KEY UPDATE used_count=used_count", quotaOwner, today);
                    if (jdbc.update("UPDATE daily_usage SET used_count=used_count+1 WHERE user_id=? AND usage_date=? AND used_count<?", quotaOwner, today, limit) == 0) {
                        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "今日任务额度已用完");
                    }
                }
                conversations.append(owner, conversationId, "user", question);
                Timestamp recordedAt = Timestamp.from(Instant.now());
                for (var memory : memories) {
                    int inserted = jdbc.update("""
                                    INSERT INTO task_memory_usage(task_id,memory_id,created_at)
                                    SELECT ?,id,? FROM user_memory WHERE id=? AND user_id=?
                                    """,
                            taskId, recordedAt, memory.id(), owner
                    );
                    if (inserted != 1) {
                        throw new IllegalStateException("无法记录本次使用的记忆");
                    }
                }
            });
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求已经提交，请查看历史记录；重新提问请使用新的 requestId");
        }
    }

    public void record(String owner, String conversationId, AgentEvent event) {
        if (!(event.type().startsWith("tool.") || event.type().equals("plan.created"))) return;
        requireTask(owner, conversationId, event.taskId());
        try {
            String json = mapper.writeValueAsString(event.data());
            jdbc.update("INSERT INTO research_task_event(task_id,event_id,event_type,data_json,created_at) VALUES (?,?,?,?,?)",
                    event.taskId(), event.eventId(), event.type(), json, Timestamp.from(event.timestamp().toInstant()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("无法保存执行记录", error);
        }
    }

    public void finish(String owner, String conversationId, String taskId, String state, String errorCode, String answer) {
        if (!List.of("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED").contains(state)) throw new IllegalArgumentException("Invalid terminal state");
        transaction.executeWithoutResult(status -> {
            requireTask(owner, conversationId, taskId);
            int updated = jdbc.update("UPDATE research_task SET status=?,error_code=?,finished_at=? WHERE id=? AND status='RUNNING'",
                    state, errorCode, Timestamp.from(Instant.now()), taskId);
            if (updated == 1 && state.equals("COMPLETED") && !answer.isBlank()) {
                conversations.append(owner, conversationId, "assistant", answer);
            }
        });
    }

    public List<Task> list(String owner, String conversationId) {
        conversations.requireOwner(owner, conversationId);
        return jdbc.query("SELECT t.id,t.question,t.status,t.error_code,t.started_at,t.finished_at FROM research_task t JOIN conversation c ON t.conversation_id=c.id WHERE c.id=? AND c.user_id=? ORDER BY t.started_at DESC,t.id DESC LIMIT 100",
                (rs, row) -> new Task(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()), conversationId, owner);
    }

    public List<Event> events(String owner, String conversationId, String taskId) {
        requireTask(owner, conversationId, taskId);
        return jdbc.query("SELECT event_id,event_type,data_json,created_at FROM research_task_event WHERE task_id=? ORDER BY event_id",
                (rs, row) -> {
                    try { return new Event(rs.getLong(1), rs.getString(2), mapper.readTree(rs.getString(3)), rs.getTimestamp(4).toInstant()); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("执行记录格式异常", error); }
                }, taskId);
    }

    private void requireTask(String owner, String conversationId, String taskId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM research_task t JOIN conversation c ON c.id=t.conversation_id WHERE t.id=? AND c.id=? AND c.user_id=?",
                Integer.class, taskId, conversationId, owner);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
    }
    public int recoverExpired(Instant cutoff) {
        return jdbc.update("UPDATE research_task SET status='INTERRUPTED',error_code='PROCESS_INTERRUPTED',finished_at=? WHERE status='RUNNING' AND started_at<?",
                Timestamp.from(Instant.now()),Timestamp.from(cutoff));
    }
    public Task snapshot(String owner,String taskId) {
        var matches=jdbc.query("SELECT t.id,t.question,t.status,t.error_code,t.started_at,t.finished_at FROM research_task t JOIN conversation c ON c.id=t.conversation_id WHERE t.id=? AND c.user_id=?",
                (rs,row) -> new Task(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getTimestamp(5).toInstant(),rs.getTimestamp(6)==null?null:rs.getTimestamp(6).toInstant()),taskId,owner);
        if(matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不存在");
        return matches.get(0);
    }
}
