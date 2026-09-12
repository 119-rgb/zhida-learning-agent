package com.zhida.agent.observability;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;

class ToolBudgetTest {
    @Test void limitsCallsAndRejectsCallsAfterCancellation() {
        var publisher = new ToolTracePublisher();
        var events = publisher.open("task", 2);
        publisher.acquireToolCall("task");
        publisher.acquireToolCall("task");
        assertThatThrownBy(() -> publisher.acquireToolCall("task"))
                .isInstanceOf(ToolTracePublisher.ToolBudgetExceededException.class);
        StepVerifier.create(events).expectError(ToolTracePublisher.ToolBudgetExceededException.class).verify();
        publisher.remove("task");
        assertThatThrownBy(() -> publisher.acquireToolCall("task")).isInstanceOf(java.util.concurrent.CancellationException.class);
    }
}
