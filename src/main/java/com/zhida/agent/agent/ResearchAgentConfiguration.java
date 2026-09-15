package com.zhida.agent.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(prefix = "zhida.ai", name = "enabled", havingValue = "true")
public class ResearchAgentConfiguration {
  private final String key;

  public ResearchAgentConfiguration(@Value("${zhida.ai.api-key:}") String key) {
    this.key = key;
  }

  @PostConstruct
  void checkApiKeyConfigured() {
    if (!StringUtils.hasText(key) || "demo-disabled".equals(key))
      throw new IllegalStateException("已开启真实 Agent 模式，但没有配置有效的 DEEPSEEK_API_KEY。");
  }

  @Bean
  StreamingChatModel researchStreamingChatModel(
      @Value("${zhida.ai.base-url:https://api.deepseek.com/v1}") String base,
      @Value("${zhida.ai.model:deepseek-flash}") String model,
      @Value("${zhida.execution.timeout-seconds:90}") long seconds) {
    return OpenAiStreamingChatModel.builder()
        .baseUrl(base)
        .apiKey(key)
        .modelName(model)
        .timeout(Duration.ofSeconds(seconds))
        .build();
  }

  @Bean
  ChatModel researchChatModel(
      @Value("${zhida.ai.base-url:https://api.deepseek.com/v1}") String base,
      @Value("${zhida.ai.model:deepseek-flash}") String model) {
    return OpenAiChatModel.builder()
        .baseUrl(base)
        .apiKey(key)
        .modelName(model)
        .timeout(Duration.ofSeconds(60))
        .maxRetries(0)
        .build();
  }

  public static final String INSTRUCTION =
      """
      你是“知答”，一个通用学习与研究 Agent。

      你的目标是理解用户真正要解决的问题，补充容易遗漏的角度，并给出清晰、可靠、可执行的答案。

      执行规则：
      1. 涉及最新信息、事实核验或方案比较时，优先调用 web_search，再选择可信网页调用 read_web_page。
      2. 用户询问“上传的文件、这份文档、知识库”等内容时，优先调用 knowledge_search。
      3. 使用知识库片段回答时，必须标明文件名和页码；没有页码时标明片段编号。
      4. 普通常识问题不必为了展示工具而强制联网或检索知识库。
      5. 工具不可用或资料不足时必须明确说明，禁止伪造搜索结果和引用。
      6. 优先使用官方文档、政府机构、论文原文或其他一手来源。
      7. 最终回答区分事实、推断和建议，并给出使用过的来源 URL 或本地文件定位。
      8. 不输出隐藏的内部思维过程，只输出简洁的结论、依据和可核对信息。
      9. 使用中文回答，除非用户明确要求其他语言。
      """;
}
