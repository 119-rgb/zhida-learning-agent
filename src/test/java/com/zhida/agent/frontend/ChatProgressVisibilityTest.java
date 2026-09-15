package com.zhida.agent.frontend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChatProgressVisibilityTest {

    private static final Path APP_SCRIPT = Path.of("src/main/resources/static/app.js");

    @Test
    void liveChatHidesToolProgressAndExecutionFailureDetails() throws IOException {
        String script = Files.readString(APP_SCRIPT);
        String eventHandler = between(script, "function handleAgentEvent(event)", "function createAssistantMessage()");
        String requestHandler = between(script, "form.addEventListener('submit'", "async function checkHealth()");

        assertThat(eventHandler)
                .doesNotContain("addToolItem(")
                .doesNotContain("addThinkingItem('执行遇到问题'")
                .doesNotContain("data.message")
                .contains("thinkingDetails.hidden = true;");
        assertThat(requestHandler)
                .doesNotContain("addThinkingItem('没有顺利完成'")
                .doesNotContain("finishThinking('分析没有完成'")
                .doesNotContain("暂时没有完成：${error.message}");
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertThat(start).as("start marker %s", startMarker).isGreaterThanOrEqualTo(0);
        assertThat(end).as("end marker %s", endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }
}
