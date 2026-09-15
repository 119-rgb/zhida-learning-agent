package com.zhida.agent.conversation;

import dev.langchain4j.model.chat.ChatModel;
import java.util.concurrent.Executor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "zhida",
    name = {"ai.enabled", "persistence.enabled", "conversation-summary.enabled"},
    havingValue = "true")
public class ConversationSummaryConfiguration {

  @Bean(name = "conversationSummaryExecutor")
  Executor conversationSummaryExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("conversation-summary-");
    executor.initialize();
    return executor;
  }

  @Bean
  ConversationSummarizer conversationSummarizer(ChatModel chatModel) {
    return prompt -> {
      var response = chatModel.chat(prompt);
      if (response == null || response.isBlank()) {
        throw new IllegalStateException("摘要模型没有返回内容");
      }
      return response;
    };
  }
}
