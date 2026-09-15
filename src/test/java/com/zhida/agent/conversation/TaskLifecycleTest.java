package com.zhida.agent.conversation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class TaskLifecycleTest {
  private final ConversationRepository repository = mock(ConversationRepository.class);
  private final TaskRepository tasks = mock(TaskRepository.class);
  private final ResearchOrchestrator orchestrator = mock(ResearchOrchestrator.class);
  private final ResearchSession core = mock(ResearchSession.class);
  private final ConversationHistory history = new ConversationHistory(repository, tasks);

  TaskLifecycleTest() {
    when(repository.recentMessages(anyString(), anyString())).thenReturn(List.of());
    when(repository.activeMemories(anyString())).thenReturn(List.of());
    when(orchestrator.prepareWithContext(any(), anyList(), anyString(), anyString()))
        .thenReturn(core);
  }

  private ResearchSession prepare() {
    return history.prepare(
        new ResearchRequest("c", "question", "task-cancel"), orchestrator, "alice");
  }

  @Test
  void cancelledOldWorkerCannotReleaseNewSessionLock() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              release.await(3, java.util.concurrent.TimeUnit.SECONDS);
              return null;
            })
        .when(core)
        .execute(any());
    var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    ResearchSession next = null;
    try {
      var old = prepare();
      var exiting = worker.submit(() -> old.execute(event -> {}));
      assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      old.cancel();
      next = history.prepare(new ResearchRequest("c", "next", "next-task"), orchestrator, "alice");
      release.countDown();
      exiting.get(2, java.util.concurrent.TimeUnit.SECONDS);
      assertThatThrownBy(
              () ->
                  history.prepare(
                      new ResearchRequest("c", "third", "third-task"), orchestrator, "alice"))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
          .satisfies(
              error ->
                  assertThat(
                          ((org.springframework.web.server.ResponseStatusException) error)
                              .getStatusCode()
                              .value())
                      .isEqualTo(409));
    } finally {
      release.countDown();
      if (next != null) next.cancel();
      worker.shutdownNow();
    }
  }

  @Test
  void explicitCancellationBeforeExecutionSavesStateAndReleasesLock() {
    when(tasks.snapshot("alice", "task-cancel"))
        .thenReturn(
            new TaskRepository.Task(
                "task-cancel", "question", "RUNNING", null, java.time.Instant.now(), null));
    var session = prepare();
    verify(tasks).begin(eq("alice"), eq("c"), eq("task-cancel"), eq("question"), anyList());
    assertThat(history.cancel("alice", "task-cancel")).isTrue();
    session.execute(
        e -> {
          throw new AssertionError("cancelled event");
        });
    verify(core, never()).execute(any());
    verify(tasks).finish("alice", "c", "task-cancel", "CANCELLED", "USER_CANCELLED", "");
    prepare().cancel();
  }

  @Test
  void disconnectBeforeExecutorStartsAlsoCleansUp() {
    var session = prepare();
    assertThat(session.cancel()).isTrue();
    assertThat(session.cancel()).isFalse();
    verify(tasks).finish("alice", "c", "task-cancel", "CANCELLED", "CLIENT_DISCONNECTED", "");
    prepare().cancel();
  }

  @Test
  void terminalCommitPrecedesCompletionDelivery() {
    doAnswer(
            invocation -> {
              java.util.function.Consumer<AgentEvent> sink = invocation.getArgument(0);
              sink.accept(
                  new AgentEvent(
                      1,
                      "task-cancel",
                      "answer.delta",
                      OffsetDateTime.now(),
                      Map.of("content", "完整答案")));
              sink.accept(
                  new AgentEvent(
                      2, "task-cancel", "task.completed", OffsetDateTime.now(), Map.of()));
              return null;
            })
        .when(core)
        .execute(any());
    var events = new ArrayList<AgentEvent>();
    prepare()
        .execute(
            event -> {
              if (event.type().equals("task.completed"))
                verify(tasks).finish("alice", "c", "task-cancel", "COMPLETED", null, "完整答案");
              events.add(event);
            });
    assertThat(events)
        .extracting(AgentEvent::type)
        .containsExactly("answer.delta", "task.completed");
    verify(tasks, never())
        .finish(anyString(), anyString(), anyString(), eq("INTERRUPTED"), any(), anyString());
  }

  @Test
  void failedStorageCannotPublishSuccessfulCompletion() {
    doThrow(new IllegalStateException("database unavailable"))
        .when(tasks)
        .finish(eq("alice"), eq("c"), eq("task-cancel"), eq("COMPLETED"), isNull(), anyString());
    doAnswer(
            invocation -> {
              java.util.function.Consumer<AgentEvent> sink = invocation.getArgument(0);
              sink.accept(
                  new AgentEvent(
                      1, "task-cancel", "task.completed", OffsetDateTime.now(), Map.of()));
              return null;
            })
        .when(core)
        .execute(any());
    var events = new ArrayList<AgentEvent>();
    assertThatThrownBy(() -> prepare().execute(events::add))
        .isInstanceOf(IllegalStateException.class);
    assertThat(events).isEmpty();
    verify(tasks).finish("alice", "c", "task-cancel", "FAILED", "EXECUTION_OR_STORAGE_FAILED", "");
    prepare().cancel();
  }

  @Test
  void unexpectedExecutionFailureIsSaved() {
    doThrow(new IllegalStateException("failure")).when(core).execute(any());
    assertThatThrownBy(() -> prepare().execute(e -> {})).isInstanceOf(IllegalStateException.class);
    verify(tasks).finish("alice", "c", "task-cancel", "FAILED", "EXECUTION_OR_STORAGE_FAILED", "");
  }

  @Test
  void preparationFailureReleasesLockAndDoesNotStartTask() {
    when(orchestrator.prepareWithContext(any(), anyList(), anyString(), anyString()))
        .thenThrow(new IllegalArgumentException("invalid knowledge base"));
    assertThatThrownBy(this::prepare).isInstanceOf(IllegalArgumentException.class);
    verify(tasks, never()).begin(anyString(), anyString(), anyString(), anyString(), anyList());
    doReturn(core)
        .when(orchestrator)
        .prepareWithContext(any(), anyList(), anyString(), anyString());
    prepare().cancel();
  }
}
