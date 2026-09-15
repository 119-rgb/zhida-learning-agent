package com.zhida.agent.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationContextTest {
  private ConversationRepository.Message message(String role, String text) {
    return new ConversationRepository.Message("id", role, text, Instant.now());
  }

  @Test
  void preservesRolesAndOmitsUnansweredQuestions() {
    var result =
        ConversationContext.restore(
            List.of(
                message("user", "失败的请求"),
                message("user", "我叫小周"),
                message("assistant", "你好小周"),
                message("user", "中断的请求")),
            "我叫什么？");
    assertThat(result)
        .extracting(
            m ->
                m instanceof dev.langchain4j.data.message.UserMessage u
                    ? u.singleText()
                    : ((dev.langchain4j.data.message.AiMessage) m).text())
        .containsExactly("我叫小周", "你好小周", "我叫什么？");
    assertThat(result)
        .extracting(m -> m.type().name().toLowerCase())
        .containsExactly("user", "ai", "user");
  }

  @Test
  void retainsNewestTenPairsAndCurrentQuestionExactlyOnce() {
    var history = new ArrayList<ConversationRepository.Message>();
    for (int i = 0; i < 15; i++) {
      history.add(message("user", "问题" + i));
      history.add(message("assistant", "回答" + i));
    }
    var result = ConversationContext.restore(history, "新问题");
    assertThat(result).hasSize(21);
    assertThat(((dev.langchain4j.data.message.UserMessage) result.get(0)).singleText())
        .isEqualTo("问题5");
    assertThat(((dev.langchain4j.data.message.UserMessage) result.get(20)).singleText())
        .isEqualTo("新问题");
  }

  @Test
  void enforcesCharacterBudgetWithoutSplittingRoles() {
    var result =
        ConversationContext.restore(
            List.of(message("user", "问题"), message("assistant", "字".repeat(24000))), "新问题");
    assertThat(result)
        .extracting(
            m ->
                m instanceof dev.langchain4j.data.message.UserMessage u
                    ? u.singleText()
                    : ((dev.langchain4j.data.message.AiMessage) m).text())
        .containsExactly("新问题");
  }

  @Test
  void addsSummaryAsUntrustedUserContext() {
    var result =
        ConversationContext.withSummary(
            ConversationContext.restore(List.of(), "继续提问"), "用户正在学习 Java");
    assertThat(result).hasSize(2);
    assertThat(result.get(0).type().name().toLowerCase()).isEqualTo("user");
    assertThat(((dev.langchain4j.data.message.UserMessage) result.get(0)).singleText())
        .contains("仅作为上下文资料", "用户正在学习 Java");
    assertThat(((dev.langchain4j.data.message.UserMessage) result.get(1)).singleText())
        .isEqualTo("继续提问");
  }
}
