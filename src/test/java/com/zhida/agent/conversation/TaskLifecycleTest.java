package com.zhida.agent.conversation;

import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.application.ResearchOrchestrator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.time.Duration;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskLifecycleTest {
    @Test void explicitCancellationCompletesStreamAndSavesState() {
        var repository = mock(ConversationRepository.class);
        when(repository.recentMessages(anyString(), anyString())).thenReturn(List.of());
        var tasks = mock(TaskRepository.class);
        when(tasks.snapshot("alice", "task-cancel")).thenReturn(new TaskRepository.Task("task-cancel", "question", "RUNNING", null, java.time.Instant.now(), null));
        var orchestrator = mock(ResearchOrchestrator.class);
        when(orchestrator.streamWithContext(any(), anyList(), anyString(), anyString())).thenReturn(Flux.never());
        var history = new ConversationHistory(repository, tasks);
        StepVerifier.create(history.stream(new ResearchRequest("c", "question", "task-cancel"), orchestrator, "alice"))
                .then(() -> {
                    verify(orchestrator, timeout(3000)).streamWithContext(any(), anyList(), eq("task-cancel"), eq("alice"));
                    org.assertj.core.api.Assertions.assertThat(history.cancel("alice", "task-cancel")).isTrue();
                }).verifyComplete();
        verify(tasks).finish("alice", "c", "task-cancel", "CANCELLED", "USER_CANCELLED", "");
    }
    @Test void cancellationIsSavedAndLockIsReleased() {
        var repository = mock(ConversationRepository.class);
        when(repository.recentMessages(anyString(), anyString())).thenReturn(List.of());
        var tasks = mock(TaskRepository.class);
        var orchestrator = mock(ResearchOrchestrator.class);
        when(orchestrator.streamWithContext(any(), anyList(), anyString(), anyString())).thenReturn(Flux.never());
        var history = new ConversationHistory(repository, tasks);
        StepVerifier.create(history.stream(new ResearchRequest("c", "问题"), orchestrator))
                .thenAwait(Duration.ofMillis(200)).thenCancel().verify(Duration.ofSeconds(5));
        verify(tasks, timeout(3000)).finish(eq("local-user"), eq("c"), anyString(), eq("CANCELLED"), eq("CLIENT_DISCONNECTED"), eq(""));
    }

    @Test void unexpectedStreamFailureIsSaved() {
        var repository = mock(ConversationRepository.class);
        when(repository.recentMessages(anyString(), anyString())).thenReturn(List.of());
        var tasks = mock(TaskRepository.class);
        var orchestrator = mock(ResearchOrchestrator.class);
        when(orchestrator.streamWithContext(any(), anyList(), anyString(), anyString())).thenReturn(Flux.error(new IllegalStateException("failure")));
        StepVerifier.create(new ConversationHistory(repository, tasks).stream(new ResearchRequest("c", "问题"), orchestrator))
                .expectError(IllegalStateException.class).verify(Duration.ofSeconds(5));
        verify(tasks).finish(eq("local-user"), eq("c"), anyString(), eq("FAILED"), eq("EXECUTION_OR_STORAGE_FAILED"), eq(""));
    }
}
