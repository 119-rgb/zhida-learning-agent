package com.zhida.agent.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ConversationContextTest {
    private ConversationRepository.Message message(String role, String text) {
        return new ConversationRepository.Message("id", role, text, Instant.now());
    }

    @Test void preservesRolesAndOmitsUnansweredQuestions() {
        var result = ConversationContext.restore(List.of(message("user", "失败的请求"),
                message("user", "我叫小周"), message("assistant", "你好小周"),
                message("user", "中断的请求")), "我叫什么？");
        assertThat(result).extracting(Message::getText).containsExactly("我叫小周", "你好小周", "我叫什么？");
        assertThat(result).extracting(m -> m.getMessageType().getValue()).containsExactly("user", "assistant", "user");
    }

    @Test void retainsNewestTenPairsAndCurrentQuestionExactlyOnce() {
        var history = new ArrayList<ConversationRepository.Message>();
        for (int i = 0; i < 15; i++) {
            history.add(message("user", "问题" + i));
            history.add(message("assistant", "回答" + i));
        }
        var result = ConversationContext.restore(history, "新问题");
        assertThat(result).hasSize(21);
        assertThat(result.get(0).getText()).isEqualTo("问题5");
        assertThat(result.get(20).getText()).isEqualTo("新问题");
    }

    @Test void enforcesCharacterBudgetWithoutSplittingRoles() {
        var result = ConversationContext.restore(List.of(message("user", "问题"),
                message("assistant", "字".repeat(24000))), "新问题");
        assertThat(result).extracting(Message::getText).containsExactly("新问题");
    }

    @Test void addsSummaryAsUntrustedUserContext() {
        var result = ConversationContext.withSummary(
                ConversationContext.restore(List.of(), "继续提问"),
                "用户正在学习 Java"
        );
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMessageType().getValue()).isEqualTo("user");
        assertThat(result.get(0).getText()).contains("仅作为上下文资料", "用户正在学习 Java");
        assertThat(result.get(1).getText()).isEqualTo("继续提问");
    }
}
