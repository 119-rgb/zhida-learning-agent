package com.zhida.agent.application;

import java.util.function.Consumer;

/** Prepared task; cancellation also releases tasks that never enter the executor. */
public interface ResearchSession {
  void execute(Consumer<AgentEvent> sink);

  boolean cancel();
}
