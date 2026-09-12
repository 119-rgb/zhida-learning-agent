package com.zhida.agent.application;

import java.time.OffsetDateTime;

public record AgentEvent(
        long eventId,
        String taskId,
        String type,
        OffsetDateTime timestamp,
        Object data
) {
}
