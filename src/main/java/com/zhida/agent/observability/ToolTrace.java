package com.zhida.agent.observability;

public record ToolTrace(
        String type,
        String toolCallId,
        String toolName,
        String arguments,
        String resultPreview,
        long durationMs,
        boolean error
) {

    public static ToolTrace started(String toolCallId, String toolName, String arguments) {
        return new ToolTrace("tool.started", toolCallId, toolName, arguments, "", 0, false);
    }

    public static ToolTrace completed(
            String toolCallId,
            String toolName,
            String arguments,
            String resultPreview,
            long durationMs,
            boolean error
    ) {
        return new ToolTrace(
                error ? "tool.failed" : "tool.completed",
                toolCallId,
                toolName,
                arguments,
                resultPreview,
                durationMs,
                error
        );
    }
}
