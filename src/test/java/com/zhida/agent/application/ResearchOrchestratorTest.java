package com.zhida.agent.application;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.zhida.agent.agent.planner.RuleBasedQuestionPlanner;
import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.common.config.ZhidaProperties;
import com.zhida.agent.observability.ToolTracePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.ObjectProvider;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchOrchestratorTest {
    @Test void totalTimeoutAppliesEvenWhenModelKeepsProducingTokens() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<ReactAgent> provider = mock(ObjectProvider.class);
        var agent = mock(ReactAgent.class);
        when(provider.getIfAvailable()).thenReturn(agent);
        when(agent.streamMessages(anyString(), any())).thenAnswer(invocation -> reactor.core.publisher.Flux.interval(java.time.Duration.ofSeconds(1))
                .map(tick -> new AssistantMessage("片段")));
        var properties = new ZhidaProperties();
        properties.getAi().setEnabled(true);
        properties.getExecution().setTimeoutSeconds(3);
        var orchestrator = new ResearchOrchestrator(new RuleBasedQuestionPlanner(), provider, properties, new ToolTracePublisher());
        StepVerifier.withVirtualTime(() -> orchestrator.stream(new ResearchRequest("timeout", "问题")))
                .thenAwait(java.time.Duration.ofSeconds(4))
                .thenConsumeWhile(event -> !event.type().equals("task.failed"))
                .expectNextMatches(event -> event.data() instanceof java.util.Map<?, ?> data && "TASK_TIMEOUT".equals(data.get("errorCode")))
                .verifyComplete();
    }

    @Test
    void realAgentAcceptsRestoredMessagesWithoutMemorySaver() {
        var model = mock(org.springframework.ai.chat.model.ChatModel.class);
        var response = new org.springframework.ai.chat.model.ChatResponse(List.of(
                new org.springframework.ai.chat.model.Generation(new AssistantMessage("你叫小周"))));
        when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(reactor.core.publisher.Flux.just(response));
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response);
        var agent = ReactAgent.builder().name("restore_test").model(model).instruction("回答用户问题").build();
        @SuppressWarnings("unchecked") ObjectProvider<ReactAgent> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agent);
        var properties = new ZhidaProperties();
        properties.getAi().setEnabled(true);
        var orchestrator = new ResearchOrchestrator(new RuleBasedQuestionPlanner(), provider, properties, new ToolTracePublisher());
        List<org.springframework.ai.chat.messages.Message> history = List.of(
                new org.springframework.ai.chat.messages.UserMessage("我叫小周"),
                new AssistantMessage("你好"), new org.springframework.ai.chat.messages.UserMessage("我叫什么？"));
        for (int i = 0; i < 2; i++) {
            var events = orchestrator.streamWithContext(new ResearchRequest("restored", "我叫什么？"), history)
                    .collectList().block(java.time.Duration.ofSeconds(10));
            org.assertj.core.api.Assertions.assertThat(events).extracting(AgentEvent::type)
                    .contains("task.completed").doesNotContain("task.failed");
        }
        var prompt = org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(2)).stream(prompt.capture());
        for (var sent : prompt.getAllValues()) {
            org.assertj.core.api.Assertions.assertThat(sent.getInstructions())
                    .filteredOn(m -> m.getMessageType() != org.springframework.ai.chat.messages.MessageType.SYSTEM)
                    .extracting(org.springframework.ai.chat.messages.Message::getText)
                    .containsExactly("我叫小周", "你好", "我叫什么？", "回答用户问题");
        }
    }

    @Test
    void shouldCompleteFullEventFlowInDemoMode() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReactAgent> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        ResearchOrchestrator orchestrator = new ResearchOrchestrator(
                new RuleBasedQuestionPlanner(),
                provider,
                new ZhidaProperties(),
                new ToolTracePublisher()
        );

        StepVerifier.create(orchestrator.stream(new ResearchRequest("demo", "解释一下 RocketMQ")))
                .expectNextMatches(event -> event.type().equals("task.started"))
                .expectNextMatches(event -> event.type().equals("plan.created"))
                .expectNextMatches(event -> event.type().equals("step.started"))
                .expectNextMatches(event -> event.type().equals("answer.started"))
                .expectNextMatches(event -> event.type().equals("answer.delta"))
                .expectNextMatches(event -> event.type().equals("step.completed"))
                .expectNextMatches(event -> event.type().equals("task.completed"))
                .verifyComplete();
    }

    @Test
    void shouldKeepEventIdsOrderedInRealAgentMode() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReactAgent> provider = mock(ObjectProvider.class);
        ReactAgent agent = mock(ReactAgent.class);
        when(provider.getIfAvailable()).thenReturn(agent);
        when(agent.streamMessages(anyString(), any()))
                .thenReturn(reactor.core.publisher.Flux.just(new AssistantMessage("回答")));

        ZhidaProperties properties = new ZhidaProperties();
        properties.getAi().setEnabled(true);
        ResearchOrchestrator orchestrator = new ResearchOrchestrator(
                new RuleBasedQuestionPlanner(),
                provider,
                properties,
                new ToolTracePublisher()
        );

        List<AgentEvent> events = orchestrator
                .stream(new ResearchRequest("conversation-1", "解释 Agent"))
                .collectList()
                .block();

        assertEquals("task.completed", events.get(events.size() - 1).type());
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1L, events.get(index).eventId());
        }
        assertTrue(events.stream().anyMatch(event -> event.type().equals("answer.delta")));
    }
}
