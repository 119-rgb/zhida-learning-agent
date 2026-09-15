package com.zhida.agent.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zhida.agent.agent.planner.RuleBasedQuestionPlanner;
import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.common.config.ZhidaProperties;
import com.zhida.agent.knowledge.KnowledgeBaseAccessService;
import com.zhida.agent.observability.*;
import com.zhida.agent.tool.ResearchTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ResearchOrchestratorTest {
  @Test
  void completesWhenProviderOnlyReturnsFinalMessage() {
    StreamingChatModel model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onCompleteResponse(
                ChatResponse.builder().aiMessage(AiMessage.from("完整回答")).build());
          }
        };
    var core = orchestrator(model, 3);
    var events = new ArrayList<AgentEvent>();
    try {
      core.prepare(new ResearchRequest("final-only", "问题"), "local-user").execute(events::add);
      assertThat(
              events.stream()
                  .filter(e -> e.type().equals("answer.delta"))
                  .map(e -> (String) ((Map<?, ?>) e.data()).get("content")))
          .containsExactly("完整回答");
      assertThat(events)
          .extracting(AgentEvent::type)
          .contains("task.completed")
          .doesNotContain("task.failed");
    } finally {
      core.close();
    }
  }

  @Test
  void queueDelayConsumesTaskBudgetBeforeModelCall() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    StreamingChatModel model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            calls.incrementAndGet();
          }
        };
    var core = orchestrator(model, 1);
    try {
      var session = core.prepare(new ResearchRequest("queued", "问题"), "local-user");
      Thread.sleep(1100);
      var events = new ArrayList<AgentEvent>();
      session.execute(events::add);
      assertThat(calls).hasValue(0);
      assertThat(events)
          .filteredOn(e -> e.type().equals("task.failed"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(((Map<?, ?>) event.data()).get("errorCode"))
                      .isEqualTo("TASK_TIMEOUT"));
      assertThat(events).extracting(AgentEvent::type).doesNotContain("task.completed");
    } finally {
      core.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void oldCancelledSessionCannotUnlockNewConversation() throws Exception {
    var accountingEntered = new CountDownLatch(1);
    var accountingRelease = new CountDownLatch(1);
    var providerStarted = new CountDownLatch(1);
    var repository = mock(com.zhida.agent.conversation.TaskRepository.class);
    doAnswer(
            invocation -> {
              accountingEntered.countDown();
              accountingRelease.await(3, TimeUnit.SECONDS);
              return null;
            })
        .when(repository)
        .recordUsage(
            anyString(),
            anyString(),
            anyString(),
            nullable(Integer.class),
            nullable(Integer.class),
            anyString());
    ObjectProvider<com.zhida.agent.conversation.TaskRepository> repositories =
        mock(ObjectProvider.class);
    when(repositories.getIfAvailable()).thenReturn(repository);
    StreamingChatModel model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            providerStarted.countDown();
          }
        };
    ObjectProvider<StreamingChatModel> models = mock(ObjectProvider.class);
    when(models.getIfAvailable()).thenReturn(model);
    var properties = new ZhidaProperties();
    properties.getAi().setEnabled(true);
    var traces = new ToolTracePublisher();
    var core =
        new ResearchOrchestrator(
            new RuleBasedQuestionPlanner(),
            models,
            properties,
            traces,
            new KnowledgeBaseAccessService(Optional.empty()),
            null,
            new ObservableToolInterceptor(traces),
            new ModelUsageInterceptor(repositories));
    var worker = Executors.newSingleThreadExecutor();
    ResearchSession next = null;
    try {
      var old = core.prepare(new ResearchRequest("race", "A"), "local-user");
      var exiting = worker.submit(() -> old.execute(event -> {}));
      assertThat(providerStarted.await(2, TimeUnit.SECONDS)).isTrue();
      old.cancel();
      assertThat(accountingEntered.await(2, TimeUnit.SECONDS)).isTrue();
      next = core.prepare(new ResearchRequest("race", "B"), "local-user");
      accountingRelease.countDown();
      exiting.get(2, TimeUnit.SECONDS);
      assertThatThrownBy(() -> core.prepare(new ResearchRequest("race", "C"), "local-user"))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
          .satisfies(
              error ->
                  assertThat(
                          ((org.springframework.web.server.ResponseStatusException) error)
                              .getStatusCode()
                              .value())
                      .isEqualTo(409));
    } finally {
      accountingRelease.countDown();
      if (next != null) next.cancel();
      worker.shutdownNow();
      core.close();
    }
  }

  @SuppressWarnings("unchecked")
  private ResearchOrchestrator orchestrator(StreamingChatModel model, int timeout) {
    return orchestrator(model, timeout, null, 30);
  }

  @SuppressWarnings("unchecked")
  private ResearchOrchestrator orchestrator(
      StreamingChatModel model, int timeout, ResearchTools tools, int maxTools) {
    ObjectProvider<StreamingChatModel> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(model);
    var properties = new ZhidaProperties();
    properties.getAi().setEnabled(model != null);
    properties.getExecution().setTimeoutSeconds(timeout);
    properties.getExecution().setMaxToolCalls(maxTools);
    var traces = new ToolTracePublisher();
    return new ResearchOrchestrator(
        new RuleBasedQuestionPlanner(),
        provider,
        properties,
        traces,
        new KnowledgeBaseAccessService(Optional.empty()),
        tools,
        new ObservableToolInterceptor(traces),
        new ModelUsageInterceptor(mock(ObjectProvider.class)));
  }

  @Test
  void demoCompletesWithOrderedEvents() {
    var events = new ArrayList<AgentEvent>();
    var core = orchestrator(null, 3);
    try {
      core.prepare(new ResearchRequest("demo", "解释 Agent"), "local-user").execute(events::add);
      assertThat(events)
          .extracting(AgentEvent::type)
          .containsExactly(
              "task.started",
              "plan.created",
              "step.started",
              "answer.started",
              "answer.delta",
              "step.completed",
              "task.completed");
      for (int i = 0; i < events.size(); i++) assertThat(events.get(i).eventId()).isEqualTo(i + 1);
    } finally {
      core.close();
    }
  }

  @Test
  void cancellationBeforeExecutionSuppressesEventsAndReleasesConversation() {
    var events = new ArrayList<AgentEvent>();
    var core = orchestrator(null, 3);
    try {
      var session = core.prepare(new ResearchRequest("demo", "问题"), "local-user");
      assertThat(session.cancel()).isTrue();
      session.execute(events::add);
      assertThat(events).isEmpty();
      assertThat(session.cancel()).isFalse();
      core.prepare(new ResearchRequest("demo", "继续"), "local-user").cancel();
    } finally {
      core.close();
    }
  }

  @Test
  void streamsProviderDeltasAndPreservesLowPrivilegeHistory() {
    var requests = new ArrayList<ChatRequest>();
    StreamingChatModel model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(request);
            handler.onPartialResponse("你叫");
            handler.onPartialResponse(" ");
            handler.onPartialResponse("小周");
            handler.onCompleteResponse(
                ChatResponse.builder().aiMessage(AiMessage.from("你叫小周")).build());
          }
        };
    var events = new ArrayList<AgentEvent>();
    var core = orchestrator(model, 3);
    try {
      core.prepareWithContext(
              new ResearchRequest("c", "我叫什么？"),
              List.of(UserMessage.from("我叫小周"), AiMessage.from("你好"), UserMessage.from("我叫什么？")),
              "t",
              "local-user")
          .execute(events::add);
      assertThat(events)
          .extracting(AgentEvent::type)
          .contains("task.completed")
          .doesNotContain("task.failed");
      assertThat(
              events.stream()
                  .filter(e -> e.type().equals("answer.delta"))
                  .map(e -> (String) ((Map<?, ?>) e.data()).get("content")))
          .containsExactly("你叫", " ", "小周");
      assertThat(requests.get(0).messages()).hasSize(4);
      assertThat(requests.get(0).messages().get(1)).isEqualTo(UserMessage.from("我叫小周"));
    } finally {
      core.close();
    }
  }

  @Test
  void timeoutStopsContinuouslyStreamingProvider() {
    var producer = Executors.newSingleThreadScheduledExecutor();
    ResearchOrchestrator core = null;
    try {
      StreamingChatModel model =
          new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
              producer.scheduleAtFixedRate(
                  () -> handler.onPartialResponse("字"), 0, 20, TimeUnit.MILLISECONDS);
            }
          };
      var events = new ArrayList<AgentEvent>();
      long start = System.nanoTime();
      core = orchestrator(model, 1);
      core.prepare(new ResearchRequest("c", "问题"), "local-user").execute(events::add);
      assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(3);
      assertThat(events).extracting(AgentEvent::type).doesNotContain("task.completed");
      assertThat(((Map<?, ?>) events.get(events.size() - 1).data()).get("errorCode"))
          .isEqualTo("TASK_TIMEOUT");
    } finally {
      producer.shutdownNow();
      if (core != null) core.close();
    }
  }

  @Test
  void toolLoopStreamsAfterToolAndEnforcesBudget() {
    var tools = new ResearchTools(null, null, null);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    StreamingChatModel model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            calls.incrementAndGet();
            handler.onCompleteResponse(
                ChatResponse.builder()
                    .aiMessage(
                        AiMessage.from(
                            ToolExecutionRequest.builder()
                                .id("call" + calls.get())
                                .name("current_date")
                                .arguments("{}")
                                .build()))
                    .build());
          }
        };
    var events = new ArrayList<AgentEvent>();
    var core = orchestrator(model, 3, tools, 1);
    try {
      core.prepare(new ResearchRequest("c", "今天？"), "local-user").execute(events::add);
      assertThat(events)
          .extracting(AgentEvent::type)
          .containsSubsequence("tool.started", "tool.completed", "task.failed")
          .doesNotContain("task.completed");
      assertThat(((Map<?, ?>) events.get(events.size() - 1).data()).get("errorCode"))
          .isEqualTo("TOOL_BUDGET_EXCEEDED");
      assertThat(calls).hasValue(2);
    } finally {
      core.close();
    }
  }

  @Test
  void noDatabaseRetainsBoundedCompletedExchanges() {
    var requests = new ArrayList<ChatRequest>();
    StreamingChatModel model =
        new StreamingChatModel() {
          @Override
          public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(request);
            handler.onPartialResponse("回答");
            handler.onCompleteResponse(
                ChatResponse.builder().aiMessage(AiMessage.from("回答")).build());
          }
        };
    var core = orchestrator(model, 3);
    try {
      for (int i = 0; i < 12; i++)
        core.prepare(new ResearchRequest("c", "问题" + i), "local-user").execute(e -> {});
      assertThat(requests.get(11).messages()).hasSize(22);
      assertThat(requests.get(11).messages().get(1)).isEqualTo(UserMessage.from("问题1"));
    } finally {
      core.close();
    }
  }
}
