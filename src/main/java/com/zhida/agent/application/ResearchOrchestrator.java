package com.zhida.agent.application;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.zhida.agent.agent.model.ResearchPlan;
import com.zhida.agent.agent.planner.QuestionPlanner;
import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.common.config.ZhidaProperties;
import com.zhida.agent.observability.ToolTrace;
import com.zhida.agent.observability.ToolTracePublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ResearchOrchestrator {

    private final QuestionPlanner questionPlanner;
    private final ObjectProvider<ReactAgent> agentProvider;
    private final ZhidaProperties properties;
    private final ToolTracePublisher toolTracePublisher;

    public ResearchOrchestrator(
            QuestionPlanner questionPlanner,
            ObjectProvider<ReactAgent> agentProvider,
            ZhidaProperties properties,
            ToolTracePublisher toolTracePublisher
    ) {
        this.questionPlanner = questionPlanner;
        this.agentProvider = agentProvider;
        this.properties = properties;
        this.toolTracePublisher = toolTracePublisher;
    }

    public Flux<AgentEvent> stream(ResearchRequest request) {
        return streamInternal(request, null);
    }

    public Flux<AgentEvent> streamWithContext(ResearchRequest request, List<Message> context) {
        return streamInternal(request, List.copyOf(context));
    }

    public Flux<AgentEvent> streamWithContext(ResearchRequest request, List<Message> context, String taskId) {
        return streamInternal(request, List.copyOf(context), taskId);
    }

    public Flux<AgentEvent> streamWithContext(ResearchRequest request, List<Message> context, String taskId, String owner) {
        return streamInternal(request, List.copyOf(context), taskId, owner);
    }

    private Flux<AgentEvent> streamInternal(ResearchRequest request, List<Message> context) {
        return streamInternal(request, context, UUID.randomUUID().toString());
    }

    private Flux<AgentEvent> streamInternal(ResearchRequest request, List<Message> context, String taskId) {
        return streamInternal(request, context, taskId, "local-user");
    }

    private Flux<AgentEvent> streamInternal(ResearchRequest request, List<Message> context, String taskId, String owner) {
        String conversationId = normalizeConversationId(request.conversationId());
        AtomicLong sequence = new AtomicLong();
        ResearchPlan plan = questionPlanner.plan(request.message());

        Flux<AgentEvent> beginning = Flux.just(
                event(sequence, taskId, "task.started", Map.of(
                        "conversationId", conversationId,
                        "question", request.message(),
                        "mode", properties.getAi().isEnabled() ? "REAL_AGENT" : "DEMO"
                )),
                event(sequence, taskId, "plan.created", plan),
                event(sequence, taskId, "step.started", Map.of(
                        "stepId", "execute",
                        "title", "执行研究计划"
                )),
                event(sequence, taskId, "answer.started", Map.of())
        );

        ReactAgent agent = agentProvider.getIfAvailable();
        if (!properties.getAi().isEnabled() || agent == null) {
            return beginning.concatWith(Flux.just(
                    event(sequence, taskId, "answer.delta", Map.of(
                            "content", demoAnswer(plan),
                            "demo", true
                    )),
                    event(sequence, taskId, "step.completed", Map.of(
                            "stepId", "execute",
                            "title", "演示流程已完成"
                    )),
                    event(sequence, taskId, "task.completed", Map.of(
                            "conversationId", conversationId
                    ))
            ));
        }

        RunnableConfig config = RunnableConfig.builder()
                .threadId(context == null ? conversationId : taskId)
                .addMetadata(ToolTracePublisher.TASK_ID_METADATA_KEY, taskId)
                .addMetadata("zhida.knowledgeBase", com.zhida.agent.auth.KnowledgeScope.key(owner, request.knowledgeBaseId()))
                .build();

        Flux<AgentEvent> agentEvents;
        try {
            Flux<AgentEvent> toolEvents = toolTracePublisher.open(taskId, properties.getExecution().getMaxToolCalls())
                    .map(trace -> toolEvent(sequence, taskId, trace));
            Flux<AgentEvent> answerEvents = (context == null
                    ? agent.streamMessages(request.message(), config)
                    : agent.streamMessages(context, config))
                    .map(message -> message.getText())
                    .filter(text -> text != null && !text.isBlank())
                    .map(text -> event(sequence, taskId, "answer.delta", Map.of("content", text)))
                    .doFinally(signal -> toolTracePublisher.complete(taskId));

            agentEvents = Flux.merge(toolEvents, answerEvents)
                    .takeUntilOther(reactor.core.publisher.Mono.delay(java.time.Duration.ofSeconds(properties.getExecution().getTimeoutSeconds()))
                            .flatMap(ignored -> reactor.core.publisher.Mono.error(new java.util.concurrent.TimeoutException("任务超过总时间限制"))))
                    .concatWith(Flux.defer(() -> Flux.just(
                            event(sequence, taskId, "step.completed", Map.of(
                                    "stepId", "execute",
                                    "title", "研究与回答已完成"
                            )),
                            event(sequence, taskId, "task.completed", Map.of(
                                    "conversationId", conversationId
                            ))
                    )))
                    .onErrorResume(error -> Flux.just(failedEvent(sequence, taskId, error)))
                    .doFinally(signal -> toolTracePublisher.remove(taskId));
        }
        catch (Exception error) {
            toolTracePublisher.remove(taskId);
            agentEvents = Flux.just(failedEvent(sequence, taskId, error));
        }

        return beginning.concatWith(agentEvents);
    }

    private AgentEvent toolEvent(AtomicLong sequence, String taskId, ToolTrace trace) {
        return event(sequence, taskId, trace.type(), Map.of(
                "toolCallId", trace.toolCallId(),
                "toolName", trace.toolName(),
                "arguments", trace.arguments(),
                "resultPreview", trace.resultPreview(),
                "durationMs", trace.durationMs(),
                "error", trace.error()
        ));
    }

    private AgentEvent event(
            AtomicLong sequence,
            String taskId,
            String type,
            Object data
    ) {
        return new AgentEvent(sequence.incrementAndGet(), taskId, type, OffsetDateTime.now(), data);
    }

    private AgentEvent failedEvent(AtomicLong sequence, String taskId, Throwable error) {
        String rawMessage = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String errorCode = "AGENT_EXECUTION_FAILED";
        String message = rawMessage;
        if (error instanceof java.util.concurrent.TimeoutException) {
            errorCode = "TASK_TIMEOUT";
            message = "这次任务用时过长，已停止。可以缩小问题范围后再试。";
        } else if (error instanceof ToolTracePublisher.ToolBudgetExceededException) {
            errorCode = "TOOL_BUDGET_EXCEEDED";
            message = "这次查询已达到工具调用上限，已停止。可以把问题拆成几个小问题。";
        } else if (isAuthenticationFailure(rawMessage)) {
            errorCode = "MODEL_AUTH_FAILED";
            message = "模型服务拒绝了请求（401）：API Key 无效或未配置。"
                    + "请检查 DEEPSEEK_API_KEY 环境变量后重启应用，或确认 Key 是否有效、额度是否充足。";
        }
        return event(sequence, taskId, "task.failed", Map.of(
                "errorCode", errorCode,
                "message", message
        ));
    }

    private boolean isAuthenticationFailure(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("401") || lower.contains("unauthorized")
                || lower.contains("invalid api key") || lower.contains("authentication");
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conversationId.trim();
    }

    private String demoAnswer(ResearchPlan plan) {
        return """
                当前应用运行在演示模式，任务计划已经成功生成，但没有调用真实大模型。

                识别的任务类型：%s
                理解的目标：%s

                如需真实 Agent 回答，请设置 ZHIDA_AI_ENABLED=true 和 DEEPSEEK_API_KEY。
                如果还希望联网搜索，请同时设置 TAVILY_API_KEY。
                """.formatted(plan.taskType(), plan.interpretedGoal());
    }
}
