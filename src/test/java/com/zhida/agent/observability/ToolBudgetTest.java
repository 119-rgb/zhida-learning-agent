package com.zhida.agent.observability;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ToolBudgetTest {
  @Test
  void limitsCallsAndRejectsEndedTask() {
    var publisher = new ToolTracePublisher();
    var traces = new ArrayList<ToolTrace>();
    publisher.open("task", 1, traces::add);
    publisher.acquireToolCall("task");
    assertThatThrownBy(() -> publisher.acquireToolCall("task"))
        .isInstanceOf(ToolTracePublisher.ToolBudgetExceededException.class);
    publisher.remove("task");
    assertThatThrownBy(() -> publisher.acquireToolCall("task"))
        .isInstanceOf(java.util.concurrent.CancellationException.class);
  }
}
