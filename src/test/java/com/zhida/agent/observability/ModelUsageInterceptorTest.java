package com.zhida.agent.observability;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zhida.agent.conversation.TaskRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ModelUsageInterceptorTest {
  @Test
  @SuppressWarnings("unchecked")
  void savesUsageExactlyOnceForCompletedCall() {
    var repository = mock(TaskRepository.class);
    ObjectProvider<TaskRepository> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(repository);
    var invocation = new ModelUsageInterceptor(provider).begin("task");
    var response =
        ChatResponse.builder()
            .aiMessage(AiMessage.from("answer"))
            .modelName("deepseek-chat")
            .tokenUsage(new TokenUsage(10, 20))
            .build();
    invocation.finish(response, "onComplete");
    invocation.finish(null, "cancel");
    verify(repository, times(1))
        .recordUsage(
            eq("task"), anyString(), eq("deepseek-chat"), eq(10), eq(20), eq("onComplete"));
    verifyNoMoreInteractions(repository);
  }

  @Test
  @SuppressWarnings("unchecked")
  void missingFailedAndCancelledUsageRemainUnknown() {
    var repository = mock(TaskRepository.class);
    ObjectProvider<TaskRepository> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(repository);
    var usage = new ModelUsageInterceptor(provider);
    for (String outcome : new String[] {"onComplete", "onError", "cancel"}) {
      var call = usage.begin("task");
      call.finish(null, outcome);
      call.finish(null, outcome);
    }
    verify(repository, times(3))
        .recordUsage(eq("task"), anyString(), eq("unknown"), isNull(), isNull(), anyString());
  }
}
