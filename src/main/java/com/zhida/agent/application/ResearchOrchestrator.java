package com.zhida.agent.application;

import com.zhida.agent.agent.ResearchAgentConfiguration;
import com.zhida.agent.agent.model.ResearchPlan;
import com.zhida.agent.agent.planner.QuestionPlanner;
import com.zhida.agent.api.dto.ResearchRequest;
import com.zhida.agent.common.config.ZhidaProperties;
import com.zhida.agent.knowledge.KnowledgeBaseAccessService;
import com.zhida.agent.observability.*;
import com.zhida.agent.tool.ResearchTools;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResearchOrchestrator {
  private final QuestionPlanner planner;
  private final ObjectProvider<StreamingChatModel> models;
  private final ZhidaProperties properties;
  private final ToolTracePublisher traces;
  private final KnowledgeBaseAccessService access;
  private final ResearchTools tools;
  private final ObservableToolInterceptor toolInterceptor;
  private final ModelUsageInterceptor usage;
  // Bounded provider/tool work: cancelled remote I/O may take time to unwind.
  private final ExecutorService calls =
      new ThreadPoolExecutor(
          2,
          8,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          r -> {
            var t = new Thread(r, "research-provider");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private final Map<String, List<ChatMessage>> memory =
      Collections.synchronizedMap(
          new LinkedHashMap<>(32, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<ChatMessage>> entry) {
              return size() > 200;
            }
          });

  public ResearchOrchestrator(
      QuestionPlanner planner,
      ObjectProvider<StreamingChatModel> models,
      ZhidaProperties properties,
      ToolTracePublisher traces,
      KnowledgeBaseAccessService access,
      ResearchTools tools,
      ObservableToolInterceptor toolInterceptor,
      ModelUsageInterceptor usage) {
    this.planner = planner;
    this.models = models;
    this.properties = properties;
    this.traces = traces;
    this.access = access;
    this.tools = tools;
    this.toolInterceptor = toolInterceptor;
    this.usage = usage;
  }

  @jakarta.annotation.PreDestroy
  public void close() {
    calls.shutdownNow();
  }

  public ResearchSession prepare(ResearchRequest request, String owner) {
    String id = normalize(request.conversationId());
    String key = owner + ":" + id;
    if (!running.add(key)) throw new ResponseStatusException(HttpStatus.CONFLICT, "这个会话正在回答，请稍后再试");
    try {
      var context = new ArrayList<ChatMessage>(memory.getOrDefault(key, List.of()));
      context.add(UserMessage.from(request.message()));
      return new Session(
          new ResearchRequest(
              id, request.message(), request.requestId(), request.knowledgeBaseId()),
          context,
          request.requestId() == null ? UUID.randomUUID().toString() : request.requestId(),
          owner,
          key);
    } catch (RuntimeException error) {
      running.remove(key);
      throw error;
    }
  }

  public ResearchSession prepareWithContext(
      ResearchRequest request, List<ChatMessage> context, String taskId, String owner) {
    return new Session(request, List.copyOf(context), taskId, owner, null);
  }

  private final class Session implements ResearchSession {
    private final ResearchRequest request;
    private final List<ChatMessage> context;
    private final String taskId, scope, memoryKey;
    private final ResearchPlan plan;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1024);
    private Consumer<AgentEvent> sink;
    private boolean cancelled, finished, started;
    private long sequence;
    private final long deadline;
    private final java.util.concurrent.atomic.AtomicBoolean released =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<StreamingHandle> handle =
        new java.util.concurrent.atomic.AtomicReference<>();
    private Future<?> active;
    private final StringBuilder answer = new StringBuilder();

    private Session(
        ResearchRequest request,
        List<ChatMessage> context,
        String taskId,
        String owner,
        String memoryKey) {
      this.request = request;
      this.context = context;
      this.taskId = taskId;
      this.memoryKey = memoryKey;
      this.scope = access.resolve(owner, request.knowledgeBaseId());
      this.plan = planner.plan(request.message());
      this.deadline =
          System.nanoTime()
              + TimeUnit.SECONDS.toNanos(properties.getExecution().getTimeoutSeconds());
    }

    @Override
    public synchronized boolean cancel() {
      if (cancelled || finished) return false;
      cancelled = true;
      cancelProvider();
      if (active != null) active.cancel(true);
      queue.clear();
      queue.offer(new CancellationException());
      traces.remove(taskId);
      release();
      return true;
    }

    private void release() {
      if (released.compareAndSet(false, true) && memoryKey != null) running.remove(memoryKey);
    }

    private void cancelProvider() {
      var current = handle.getAndSet(null);
      if (current != null) current.cancel();
    }

    private synchronized void register(StreamingHandle current) {
      handle.set(current);
      if (cancelled || finished || System.nanoTime() >= deadline) cancelProvider();
    }

    private synchronized void check() throws TimeoutException {
      if (cancelled) throw new CancellationException();
      if (System.nanoTime() >= deadline) throw new TimeoutException("任务超过总时间限制");
    }

    private long remaining() throws TimeoutException {
      check();
      return Math.max(1, deadline - System.nanoTime());
    }

    private synchronized void emit(String type, Object data) {
      if (cancelled || finished) return;
      sink.accept(new AgentEvent(++sequence, taskId, type, OffsetDateTime.now(), data));
    }

    private synchronized void enqueue(Object value) {
      if (cancelled || finished) return;
      if (!queue.offer(value)) {
        queue.clear();
        queue.offer(new IllegalStateException("模型输出速度超过消费限制"));
      }
    }

    private synchronized void setActive(Future<?> value) {
      active = value;
      if (cancelled) value.cancel(true);
    }

    @Override
    public void execute(Consumer<AgentEvent> sink) {
      synchronized (this) {
        if (started || cancelled || finished) return;
        started = true;
        this.sink = sink;
      }
      try {
        emit(
            "task.started",
            Map.of(
                "conversationId",
                normalize(request.conversationId()),
                "question",
                request.message(),
                "mode",
                properties.getAi().isEnabled() ? "REAL_AGENT" : "DEMO"));
        emit("plan.created", plan);
        emit("step.started", Map.of("stepId", "execute", "title", "执行研究计划"));
        emit("answer.started", Map.of());
        check();
        if (!properties.getAi().isEnabled())
          emit("answer.delta", Map.of("content", demoAnswer(plan), "demo", true));
        else {
          var model = models.getIfAvailable();
          if (model == null) throw new IllegalStateException("真实模型未配置");
          traces.open(taskId, properties.getExecution().getMaxToolCalls(), this::enqueue);
          runModel(model);
        }
        check();
        emit("step.completed", Map.of("stepId", "execute", "title", "研究与回答已完成"));
        synchronized (this) {
          check();
          emit("task.completed", Map.of("conversationId", normalize(request.conversationId())));
          if (memoryKey != null && !answer.isEmpty()) {
            var history = new ArrayList<>(context);
            history.add(AiMessage.from(answer.toString()));
            while (history.size() > 20) {
              history.remove(0);
              if (!history.isEmpty()) history.remove(0);
            }
            int chars = history.toString().length();
            while (chars > 24000 && history.size() > 2) {
              history.remove(0);
              history.remove(0);
              chars = history.toString().length();
            }
            memory.put(memoryKey, List.copyOf(history));
          }
          finished = true;
        }
      } catch (CancellationException ignored) {
      } catch (Throwable error) {
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        try {
          emit("task.failed", failure(error));
        } catch (RuntimeException deliveryFailure) {
          throw deliveryFailure;
        }
      } finally {
        synchronized (this) {
          finished = true;
          cancelProvider();
          if (active != null) active.cancel(true);
        }
        traces.remove(taskId);
        queue.clear();
        release();
      }
    }

    private void runModel(StreamingChatModel model) throws Exception {
      var messages = new ArrayList<ChatMessage>();
      messages.add(SystemMessage.from(ResearchAgentConfiguration.INSTRUCTION));
      messages.addAll(context);
      var specs =
          tools == null
              ? List.<ToolSpecification>of()
              : ToolSpecifications.toolSpecificationsFrom(tools);
      Map<String, DefaultToolExecutor> executors = new HashMap<>();
      if (tools != null) {
        for (var method : ResearchTools.class.getMethods()) {
          if (method.isAnnotationPresent(Tool.class)) {
            executors.put(
                ToolSpecifications.toolSpecificationFrom(method).name(),
                new DefaultToolExecutor(tools, method));
          }
        }
      }
      while (true) {
        check();
        var invocation = usage.begin(taskId);
        ChatResponse response = null;
        String outcome = "onError";
        boolean hadPartial = false;
        try {
          var modelRequest =
              ChatRequest.builder().messages(messages).toolSpecifications(specs).build();
          setActive(
              calls.submit(
                  () -> {
                    try {
                      model.chat(
                          modelRequest,
                          new StreamingChatResponseHandler() {
                            @Override
                            public void onPartialResponse(String text) {
                              if (text != null && !text.isEmpty()) enqueue(text);
                            }

                            @Override
                            public void onPartialResponse(
                                PartialResponse text, PartialResponseContext context) {
                              register(context.streamingHandle());
                              onPartialResponse(text.text());
                            }

                            @Override
                            public void onPartialToolCall(
                                PartialToolCall call, PartialToolCallContext context) {
                              register(context.streamingHandle());
                            }

                            @Override
                            public void onPartialThinking(
                                PartialThinking thinking, PartialThinkingContext context) {
                              register(context.streamingHandle());
                            }

                            @Override
                            public void onCompleteResponse(ChatResponse response) {
                              enqueue(response);
                            }

                            @Override
                            public void onError(Throwable error) {
                              enqueue(error);
                            }
                          });
                    } catch (Throwable error) {
                      enqueue(error);
                    }
                  }));
          while (response == null) {
            Object value = queue.poll(remaining(), TimeUnit.NANOSECONDS);
            check();
            if (value == null) throw new TimeoutException();
            if (value instanceof Throwable error) {
              if (error instanceof Exception exception) throw exception;
              throw new IllegalStateException(error);
            }
            if (value instanceof String text) {
              hadPartial = true;
              answer.append(text);
              emit("answer.delta", Map.of("content", text));
            }
            if (value instanceof ChatResponse complete) response = complete;
          }
          outcome = "onComplete";
        } catch (CancellationException | InterruptedException error) {
          outcome = "cancel";
          throw error;
        } finally {
          invocation.finish(response, outcome);
        }
        check();
        var ai = response.aiMessage();
        if (ai == null) throw new IllegalStateException("模型没有返回内容");
        if (!hadPartial && ai.text() != null && !ai.text().isEmpty()) {
          answer.append(ai.text());
          emit("answer.delta", Map.of("content", ai.text()));
        }
        if (!ai.hasToolExecutionRequests()) return;
        messages.add(ai);
        for (var request : ai.toolExecutionRequests()) {
          check();
          Future<String> result =
              calls.submit(
                  () ->
                      toolInterceptor.execute(
                          taskId,
                          scope,
                          request,
                          () -> {
                            DefaultToolExecutor executor = executors.get(request.name());
                            if (executor == null)
                              throw new IllegalArgumentException("未知工具: " + request.name());
                            return executor.execute(request, taskId);
                          }));
          setActive(result);
          String text;
          try {
            while (!result.isDone()) {
              Object trace =
                  queue.poll(
                      Math.min(remaining(), TimeUnit.MILLISECONDS.toNanos(20)),
                      TimeUnit.NANOSECONDS);
              if (trace instanceof ToolTrace toolTrace) emitTrace(toolTrace);
              if (trace instanceof CancellationException) throw new CancellationException();
              check();
            }
            text = result.get(remaining(), TimeUnit.NANOSECONDS);
          } catch (ExecutionException error) {
            if (error.getCause() instanceof ToolTracePublisher.ToolBudgetExceededException budget)
              throw budget;
            if (error.getCause() instanceof CancellationException cancelled) throw cancelled;
            text = "工具执行失败：" + error.getCause().getMessage();
          }
          Object pending;
          while ((pending = queue.poll()) != null) {
            if (pending instanceof ToolTrace trace) emitTrace(trace);
          }
          check();
          messages.add(ToolExecutionResultMessage.from(request, text));
        }
      }
    }

    private void emitTrace(ToolTrace trace) {
      emit(
          trace.type(),
          Map.of(
              "toolCallId",
              trace.toolCallId(),
              "toolName",
              trace.toolName(),
              "arguments",
              trace.arguments(),
              "resultPreview",
              trace.resultPreview(),
              "durationMs",
              trace.durationMs(),
              "error",
              trace.error()));
    }
  }

  private static String normalize(String id) {
    return id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
  }

  private static Map<String, String> failure(Throwable error) {
    String raw = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    String code = "AGENT_EXECUTION_FAILED", message = "模型执行失败，请稍后重试。";
    if (error instanceof TimeoutException) {
      code = "TASK_TIMEOUT";
      message = "这次任务用时过长，已停止。可以缩小问题范围后再试。";
    } else if (error instanceof ToolTracePublisher.ToolBudgetExceededException) {
      code = "TOOL_BUDGET_EXCEEDED";
      message = "这次查询已达到工具调用上限，已停止。可以把问题拆成几个小问题。";
    } else if (raw.toLowerCase(Locale.ROOT)
        .matches("(?s).*(401|unauthorized|invalid api key|authentication).*")) {
      code = "MODEL_AUTH_FAILED";
      message = "模型服务拒绝了请求：请检查 DEEPSEEK_API_KEY 是否有效。";
    }
    return Map.of("errorCode", code, "message", message);
  }

  private static String demoAnswer(ResearchPlan plan) {
    return "当前应用运行在演示模式，没有调用真实大模型。\n识别的任务类型："
        + plan.taskType()
        + "\n理解的目标："
        + plan.interpretedGoal()
        + "\n如需真实 Agent 回答，请设置 ZHIDA_AI_ENABLED=true 和 DEEPSEEK_API_KEY。";
  }
}
