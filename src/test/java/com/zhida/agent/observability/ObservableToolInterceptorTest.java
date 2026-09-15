package com.zhida.agent.observability;

import static org.assertj.core.api.Assertions.*;

import com.zhida.agent.auth.KnowledgeScope;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ObservableToolInterceptorTest {
  @Test
  void publishesOrderedTracesAndRestoresKnowledgeScope() {
    var publisher = new ToolTracePublisher();
    var events = new ArrayList<ToolTrace>();
    publisher.open("t", 2, events::add);
    var interceptor = new ObservableToolInterceptor(publisher);
    var request =
        ToolExecutionRequest.builder()
            .id("call")
            .name("knowledge_search")
            .arguments("{\"query\":\"test\"}")
            .build();
    assertThat(
            interceptor.execute(
                "t",
                "alice-base",
                request,
                () -> {
                  assertThat(KnowledgeScope.current()).isEqualTo("alice-base");
                  return "result";
                }))
        .isEqualTo("result");
    assertThat(events)
        .extracting(ToolTrace::type)
        .containsExactly("tool.started", "tool.completed");
    assertThat(events.get(1).error()).isFalse();
    assertThatThrownBy(KnowledgeScope::current).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failuresPublishErrorAndClearScope() {
    var publisher = new ToolTracePublisher();
    var events = new ArrayList<ToolTrace>();
    publisher.open("t", 1, events::add);
    var request =
        ToolExecutionRequest.builder().id("call").name("web_search").arguments("{}").build();
    assertThatThrownBy(
            () ->
                new ObservableToolInterceptor(publisher)
                    .execute(
                        "t",
                        "scope",
                        request,
                        () -> {
                          throw new IllegalStateException("failure");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(events.get(1).error()).isTrue();
    assertThatThrownBy(KnowledgeScope::current).isInstanceOf(IllegalStateException.class);
  }
}
